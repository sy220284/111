'use strict';
// All 703 candidate functions are checked against the *actual* 2.1.310 AST and
// repository module/candidate-call graph, not only name or string similarity.
// Graph reachability, call edges and lexical mentions are NOT runtime call proofs.
const fs=require('node:fs');
const path=require('node:path');
const crypto=require('node:crypto');
const ts=require('typescript');
const sha=s=>crypto.createHash('sha256').update(s).digest('hex');
function nameOf(n){
 if(n.name&&ts.isIdentifier(n.name))return n.name.text;
 const p=n.parent;
 if(ts.isVariableDeclaration(p)&&ts.isIdentifier(p.name))return p.name.text;
 if(ts.isPropertyAssignment(p)&&ts.isIdentifier(p.name))return p.name.text;
 return null;
}
function sourceDefinitions(content,file) {
 const ast=ts.createSourceFile(file,content,ts.ScriptTarget.Latest,true,ts.ScriptKind.JS);
 if(ast.parseDiagnostics.length)throw Error('source invalid '+file);
 const defs=[],lexicalRefs=new Map();
 function visit(n){
  if(ts.isFunctionLike(n)&&n.body){
   const id=nameOf(n);
   if(id)defs.push({offset:n.getStart(ast),end:n.getEnd(),name:id,
    line:ast.getLineAndCharacterOfPosition(n.getStart(ast)).line+1,
    column:ast.getLineAndCharacterOfPosition(n.getStart(ast)).character+1,
    kind:ts.SyntaxKind[n.kind]});
  }
  if(ts.isIdentifier(n)&&!ts.isPropertyAccessExpression(n.parent)){
   const arr=lexicalRefs.get(n.text)||[];
   arr.push(n.getStart(ast));lexicalRefs.set(n.text,arr);
  }
  ts.forEachChild(n,visit);
 }
 visit(ast);
 return {defs,lexicalRefs};
}
function shortestModulePath(graph,root){
 const byFile=new Map(graph.modules.map(m=>[m.file,m.dependsOn]));
 const queue=[root],visited=new Map([[root,null]]);
 for(let i=0;i<queue.length;i++)for(const to of byFile.get(queue[i])||[]){
  if(!visited.has(to)){visited.set(to,queue[i]);queue.push(to);}
 }
 return file=>{
  if(!visited.has(file))return null;
  const arr=[];
  for(let p=file;p!==null;p=visited.get(p))arr.unshift(p);
  return arr;
 };
}
function evaluate(items,graph,newRoot){
 if(!items||items.schema!=='review-703-v1'||!Array.isArray(items.items))
  throw Error('must use real complete v44 audited manifest');
 if(items.items.length!==items.summary.candidates)throw Error('truncated v44 audit');
 if(graph.errors?.length)throw Error('code graph contains unresolved imports/parse errors');
 const modules=new Set(graph.modules.map(x=>x.file));
 const sourceCache=new Map();
 const targetMap=new Map();
 for(const row of items.items){
  const file='core/'+row.new.file;
  if(!modules.has(file))throw Error('not in source dependency graph '+file);
  if(!sourceCache.has(file)){
   const full=path.resolve(newRoot,row.new.file);
   if(!full.startsWith(path.resolve(newRoot)+path.sep))throw Error('path traversal');
   sourceCache.set(file,sourceDefinitions(fs.readFileSync(full,'utf8'),file));
  }
  const found=sourceCache.get(file).defs.find(x=>x.offset===row.new.offset&&x.name===row.new.name);
  if(!found||found.line!==row.new.line)throw Error('current source function drift: '+file+':'+row.new.offset);
  targetMap.set(row.id,{file,def:found});
 }
 const fromCLI=shortestModulePath(graph,'core/cli.js');
 const fromWorker=shortestModulePath(graph,'plugins/functionHooks/hooks-worker/hooks-worker.js');
 const moduleReverse=new Map();
 for(const m of graph.modules)for(const dep of m.dependsOn){
  if(!moduleReverse.has(dep))moduleReverse.set(dep,new Set());
  moduleReverse.get(dep).add(m.file);
 }
 const edgesByTarget=new Map();
 for(const edge of graph.callEdges){
  if(!edge.to?.file||!edge.to.symbol)continue;
  const key=edge.to.file+'#'+edge.to.symbol;
  if(!edgesByTarget.has(key))edgesByTarget.set(key,[]);
  edgesByTarget.get(key).push(edge);
 }
 const results=items.items.map(row=>{
  const {file,def}=targetMap.get(row.id),data=sourceCache.get(file);
  const duplicates=data.defs.filter(x=>x.name===def.name).length;
  const directEdges=edgesByTarget.get(file+'#'+def.name)||[];
  const resolvedUnique=duplicates===1;
  const references=(data.lexicalRefs.get(def.name)||[]).filter(pos=>pos<def.offset||pos>def.end);
  const cli=fromCLI(file),worker=fromWorker(file);
  const back=[...(moduleReverse.get(file)||[])].sort();
  // A graph edge names only file+symbol; it cannot locate a duplicate binding.
  const counted=resolvedUnique?directEdges:[];
  return {
   id:row.id,location:{file,offset:def.offset,line:def.line,column:def.column,
    symbol:def.name,kind:def.kind,definitionNameCollisions:duplicates},
   crossVersion:{classification:row.independent.classification,conflicts:row.independent.conflicts,
    runtimeBehaviorVerified:row.proof.runtimeBehaviorVerified,
    semanticEquivalenceConfirmed:row.proof.semanticEquivalenceConfirmed},
   dependencies:{
    cliModuleImportRoute:cli,hookModuleImportRoute:worker,
    directModuleImporters:back,
    lexicalIdentifierReferencesOutsideDefinition:references.length,
    lexicalReferenceOffsets:references.slice(0,8),
    candidateCallSiteCount:counted.reduce((a,x)=>a+x.count,0),
    candidateCallers:counted.slice(0,8).map(x=>({
      caller:x.from,kind:x.kind,count:x.count,evidence:x.evidence})),
    ambiguousTargetDueToDuplicateName:!resolvedUnique&&directEdges.length>0
   },
   verification:{
    actualCurrentAstLocated:true,
    fullModuleGraphJoined:true,
    witnessedRuntimeCall:false,
    validatedBusinessChain:false,
    note:'Import route and candidate caller DO NOT establish real execution or behavior parity.'
   }
  };
 });
 const categories={};
 for(const row of results)categories[row.crossVersion.classification]=(categories[row.crossVersion.classification]||0)+1;
 return {
  schema:'703-business-chain-audit-v1',
  baseline:items.baseline,
  provenance:items.manifestSha256,
  summary:{
   examined:results.length,sourceDefinitionsLocated:results.length,
   directStaticCallerWitnesses:results.filter(x=>x.dependencies.candidateCallSiteCount>0).length,
   lexicalConsumerWitnesses:results.filter(x=>x.dependencies.lexicalIdentifierReferencesOutsideDefinition>0).length,
   moduleRouteFromCLI:results.filter(x=>!!x.dependencies.cliModuleImportRoute).length,
   moduleRouteFromHookWorker:results.filter(x=>!!x.dependencies.hookModuleImportRoute).length,
   duplicateNameAmbiguous:results.filter(x=>x.location.definitionNameCollisions>1).length,
   runtimeBusinessChainsProved:0,semanticallyConfirmed:0,
   crossVersionClassification:categories
  },items:results
 };
}
function main(argv){
 const arg=k=>{const at=argv.indexOf(k);return at<0?null:argv[at+1]};
 const audited=arg('--audited'),graphPath=arg('--graph'),root=arg('--src')||path.resolve(__dirname,'../core'),
 out=arg('--out')||'.generated/v45-703-business-chains.json';
 if(!audited||!graphPath)throw Error('required --audited <703 manifest> --graph <full graph JSON>');
 const result=evaluate(JSON.parse(fs.readFileSync(audited,'utf8')),
 JSON.parse(fs.readFileSync(graphPath,'utf8')),path.resolve(root));
 if(result.summary.examined!==703)throw Error('expected 703 candidates, found '+result.summary.examined);
 fs.mkdirSync(path.dirname(out),{recursive:true});
 fs.writeFileSync(out,JSON.stringify(result,null,2)+'\n');
 console.log(JSON.stringify({file:out,summary:result.summary,sha256:sha(JSON.stringify(result.items))}));
}
if(require.main===module){try{main(process.argv.slice(2))}catch(e){console.error(e.stack||e);process.exitCode=1}}
module.exports={sourceDefinitions,shortestModulePath,evaluate};
