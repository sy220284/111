'use strict';
// Offline, pinned-v37 provenance plus AST-level semantic evidence for the *non-main*
// recovered source. A Git byte match is not proof of execution or product equivalence.
const fs=require('node:fs');
const path=require('node:path');
const cp=require('node:child_process');
const crypto=require('node:crypto');
const ts=require('typescript');
const {analyze}=require('../tools/analysis/code-graph.cjs');
const ROOT=path.resolve(__dirname,'..');
const PIN='3c64d1669174e825fb0fbae088c5ec95edaa7339';
function relative(p){return path.relative(ROOT,p).split(path.sep).join('/');}
function walk(folder){
 const all=[];
 for(const item of fs.readdirSync(folder,{withFileTypes:true}).sort((a,b)=>a.name.localeCompare(b.name))){
  const abs=path.join(folder,item.name);
  if(item.isDirectory())all.push(...walk(abs));
  else if(item.isFile())all.push(relative(abs));
  else throw Error('unsafe non-file source entry '+relative(abs));
 }
 return all;
}
function gitObjects(root,ref){
 const raw=cp.execFileSync('git',['ls-tree','-r','-z','--full-tree',ref],
  {cwd:root,encoding:'utf8',maxBuffer:64*1024*1024});
 const out=new Map();
 for(const line of raw.split('\0')){
  const match=/^[0-7]{6} blob ([a-f0-9]{40})\t(.+)$/.exec(line);
  if(match)out.set(match[2],match[1]);
 }
 return out;
}
function shaBlob(data){
 return crypto.createHash('sha1').update('blob '+data.length+'\0').update(data).digest('hex');
}
function kind(p){
 if(p.startsWith('src/plugin-testing-kit/')&&p.endsWith('.js'))return 'plugin_test_kit';
 if(p.startsWith('src/embedded/')&&/\.(mjs|js|ts|tsx|d\.ts)$/.test(p))return 'embedded_source';
 if(p.startsWith('src/embedded/'))return 'embedded_non_code_asset';
 throw Error('out of scope '+p);
}
function identifiers(ast){
 const found=[],exported=new Set(),imports=[];
 for(const stmt of ast.statements){
  if(ts.isImportDeclaration(stmt)&&ts.isStringLiteralLike(stmt.moduleSpecifier))
   imports.push(stmt.moduleSpecifier.text);
  if(ts.isExportDeclaration(stmt)){
   if(stmt.moduleSpecifier&&ts.isStringLiteralLike(stmt.moduleSpecifier))
    imports.push(stmt.moduleSpecifier.text);
   if(stmt.exportClause&&ts.isNamedExports(stmt.exportClause))
    for(const e of stmt.exportClause.elements)exported.add(e.name.text);
  }
  if(stmt.modifiers?.some(m=>m.kind===ts.SyntaxKind.ExportKeyword)){
   if(stmt.name&&ts.isIdentifier(stmt.name))exported.add(stmt.name.text);
   if(ts.isVariableStatement(stmt))for(const dec of stmt.declarationList.declarations)
    if(ts.isIdentifier(dec.name))exported.add(dec.name.text);
  }
 }
 function visit(n){
  if(ts.isFunctionLike(n)&&n.body){
   let name=n.name&&ts.isIdentifier(n.name)?n.name.text:null;
   if(!name&&ts.isVariableDeclaration(n.parent)&&ts.isIdentifier(n.parent.name))
    name=n.parent.name.text;
   if(!name&&ts.isPropertyAssignment(n.parent)&&ts.isIdentifier(n.parent.name))
    name=n.parent.name.text;
   const pos=ast.getLineAndCharacterOfPosition(n.getStart(ast));
   found.push({name:name||null,start:n.getStart(ast),end:n.getEnd(),
    line:pos.line+1,column:pos.character+1,parameters:n.parameters?.length||0});
  }
  ts.forEachChild(n,visit);
 }
 visit(ast);
 return {functions:found,exports:[...exported].sort(),imports:[...new Set(imports)].sort()};
}
function parse(p,data){
 if(!/\.(?:mjs|js|tsx|ts)$/.test(p))return null;
 const source=data.toString('utf8');
 const k=p.endsWith('.tsx')?ts.ScriptKind.TSX:p.endsWith('.ts')?ts.ScriptKind.TS:ts.ScriptKind.JS;
 const ast=ts.createSourceFile(p,source,ts.ScriptTarget.Latest,true,k);
 if(ast.parseDiagnostics.length)
  throw Error('unexpected AST syntax for '+p+': '+ts.flattenDiagnosticMessageText(ast.parseDiagnostics[0].messageText,' '));
 return identifiers(ast);
}
function build({root=ROOT,baseline=PIN,semantics,verify=true}={}){
 root=path.resolve(root);
 const refObjects=verify?gitObjects(root,baseline):null;
 const kit=walk(path.join(root,'src/plugin-testing-kit'));
 const embedded=walk(path.join(root,'src/embedded'));
 const files=[...kit,...embedded].sort();
 const problems=[];
 const entries=[];
 const graphSources={};
 let functionEntities=0,exportSymbols=0;
 for(const file of files){
  const bytes=fs.readFileSync(path.join(root,file));
  const hash=shaBlob(bytes),reference=refObjects?.get(file)||null;
  const classification=kind(file);
  if(verify&&reference!==hash)problems.push(file+': v37 Git blob differs or unavailable');
  const ast=parse(file,bytes);
  functionEntities+=ast?.functions.length||0;
  exportSymbols+=ast?.exports.length||0;
  entries.push({file,kind:classification,bytes:bytes.length,
   currentBlob:hash,v37Blob:reference,byteIdenticalToV37:reference===hash,
   ...ast});
  if(classification==='plugin_test_kit'||file==='src/embedded/runtime/state-library.js')
   graphSources[file]=bytes.toString('utf8');
 }
 if(kit.length!==294)problems.push('expected 294 plugin test kit source files, got '+kit.length);
 if(embedded.length!==24)problems.push('expected 24 embedded source entities, got '+embedded.length);
 const graph=analyze(graphSources);
 if(graph.errors.length)problems.push('supplemental graph errors: '+JSON.stringify(graph.errors.slice(0,6)));
 const catalog=new Map(entries.map(e=>[e.file,e]));
 const evidence=[];
 for(const row of semantics){
  const entry=catalog.get(row.file);
  if(!entry){problems.push('unknown evidence file '+row.file);continue}
  const f=entry.functions?.filter(f=>f.name===row.symbol)||[];
  if(!f.length)problems.push('no real function declaration '+row.file+'#'+row.symbol);
  if(!row.test||!fs.existsSync(path.join(root,row.test)))
   problems.push('missing behavior test '+row.file+' '+row.test);
  if(!row.role||!row.behavior)problems.push('missing semantic description '+row.file+' '+row.symbol);
  evidence.push({...row,locations:f.map(f=>({line:f.line,column:f.column,start:f.start,end:f.end})),
   status:'source_definition_with_targeted_behavior_test_only',
   provesMainCliBehavior:false});
 }
 const mismatch=entries.filter(e=>verify&&!e.byteIdenticalToV37);
 // Self-reference from index.js `export * as default from './index.js'` is a
 // barrel re-export; never report that as a multi-module initialization cycle.
 const single=graph.cycles.filter(c=>c.length===1);
 const multi=graph.cycles.filter(c=>c.length>1);
 const selfReexportBarrels=single.filter(c=>c[0].endsWith('/index.js') &&
   catalog.get(c[0])?.imports.includes('./index.js'));
 return {schema:1,kind:'111-v37-nonmain-source-semantic-evidence',
  reference:{commit:baseline,source:'Git v37 pinned commit, NOT unprovided tar.zst'},
  metrics:{kitModules:kit.length,embeddedEntities:embedded.length,totalSourceEntities:entries.length,
   v37ByteIdentical:verify?entries.length-mismatch.length:null,
   byteMismatches:verify?mismatch.length:null,syntaxFunctions:functionEntities,
   sourceExportSymbols:exportSymbols,semanticTestRecords:evidence.length,
   graphModules:graph.summary.modules,moduleDependencies:graph.summary.moduleDependencies,
   staticModuleDependencies:graph.summary.staticModuleDependencies,
   candidateCallExpressions:graph.summary.candidateResolvedCalls,
   unresolvedCallExpressions:graph.summary.unresolvedCallExpressions,
   circularDependencyGroups:graph.summary.cyclicGroups,
   selfReferentialBarrels:selfReexportBarrels.length,
   otherSelfCycles:single.length-selfReexportBarrels.length,
   multiModuleCycles:multi.length},
  classification:{plugin_test_kit:'bundled independent plugin testing engine, not proven main CLI implementation',
   embedded_source:'bundled tool/plugin/component source, not automatically the main CLI',
   embedded_non_code_asset:'embedded markup, metadata, and configuration'},
  semanticEvidence:evidence,files:entries,
  candidateGraph:{summary:graph.summary,cycles:graph.cycles,callEdges:graph.callEdges},
  problems,
  limitations:['Git identity proves no loss of v37 reference bytes for this source; not an ELF function match.',
   'These sources are separate from the 2551 main program modules.',
   'Self-referential barrel exports do not imply multi-module runtime cycles.',
   'Semantic descriptions cover only tested functions; not all syntax function entities.',
   'No complete v37 tar.zst has been byte-verified.']};
}
function main(args){
 let output=null,baseline=PIN,root=ROOT;
 for(let i=0;i<args.length;i++){
  const k=args[i],v=args[++i];if(!v)throw Error('missing '+k);
  if(k==='--output')output=v;
  else if(k==='--baseline')baseline=v;
  else if(k==='--root')root=v;
  else throw Error('unknown argument '+k);
 }
 const json=JSON.parse(fs.readFileSync(path.join(root,'resources/research/v37-supplemental-semantics.json'),'utf8'));
 if(baseline===PIN&&json.baseline!==PIN)throw Error('v37 reference mismatch');
 const result=build({root,baseline,semantics:json.entries,verify:true});
 if(output){
  fs.mkdirSync(path.dirname(path.resolve(output)),{recursive:true});
  fs.writeFileSync(output,JSON.stringify(result,null,2)+'\n');
 }
 process.stdout.write(JSON.stringify({metrics:result.metrics,problems:result.problems,report:output},null,2)+'\n');
 if(result.problems.length)process.exitCode=1;
}
if(require.main===module){try{main(process.argv.slice(2))}catch(e){console.error(e.stack||e);process.exitCode=1}}
module.exports={build,kind,walk,parse,identifiers,shaBlob};
