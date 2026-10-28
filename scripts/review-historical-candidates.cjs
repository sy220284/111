'use strict';
// Independent second-pass audit of EVERY historical candidate, not a proof of functional equivalence.
const fs=require('node:fs');
const path=require('node:path');
const ts=require('typescript');
const crypto=require('node:crypto');
const {features}=require('./batch-restoration-candidates.cjs');
const sha=x=>crypto.createHash('sha256').update(x).digest('hex');
const fnName=n=>{
 if(n.name&&ts.isIdentifier(n.name))return n.name.text;
 const parent=n.parent;
 if(ts.isVariableDeclaration(parent)&&ts.isIdentifier(parent.name))return parent.name.text;
 if(ts.isPropertyAssignment(parent)&&ts.isIdentifier(parent.name))return parent.name.text;
 return null;
};
const statNames=['if','switch','case','for','while','try','catch','throw','return','await','yield','conditional','call','new','assignment'];
function stats(root){
 const control=Object.fromEntries(statNames.map(k=>[k,0]));
 const methods=new Set(),safety=new Set();
 function visit(n){
  if(ts.isIfStatement(n))control.if++;
  if(ts.isSwitchStatement(n))control.switch++;
  if(ts.isCaseClause(n))control.case++;
  if(ts.isForStatement(n)||ts.isForInStatement(n)||ts.isForOfStatement(n))control.for++;
  if(ts.isWhileStatement(n)||ts.isDoStatement(n))control.while++;
  if(ts.isTryStatement(n))control.try++;
  if(ts.isCatchClause(n))control.catch++;
  if(ts.isThrowStatement(n))control.throw++;
  if(ts.isReturnStatement(n))control.return++;
  if(ts.isAwaitExpression(n))control.await++;
  if(ts.isYieldExpression(n))control.yield++;
  if(ts.isConditionalExpression(n))control.conditional++;
  if(ts.isCallExpression(n))control.call++;
  if(ts.isNewExpression(n))control.new++;
  if(ts.isBinaryExpression(n)&&ts.isAssignmentOperator(n.operatorToken.kind))control.assignment++;
  if(ts.isPropertyAccessExpression(n))methods.add(n.name.text);
  if(ts.isIdentifier(n)&&['process','Bun','fetch','spawn','exec','writeFile','unlink','rmSync','kill','fs'].includes(n.text))safety.add(n.text);
  ts.forEachChild(n,visit);
 }
 visit(root);
 return {control,methods:[...methods].sort(),safety:[...safety].sort()};
}
const setOverlap=(a,b)=>{
 const x=new Set(a),y=new Set(b);
 const both=[...x].filter(i=>y.has(i)).length;
 const union=x.size+y.size-both;
 return {both,ratio:union?both/union:1};
};
const controlSimilarity=(a,b)=>{
 let delta=0,total=0;
 for(const k of statNames){delta+=Math.abs(a[k]-b[k]);total+=Math.max(a[k],b[k]);}
 return total===0?1:Math.max(0,1-delta/total);
};
function inspectPair(old,newer,others=1){
 const control=controlSimilarity(old.stats.control,newer.stats.control);
 const method=setOverlap(old.stats.methods,newer.stats.methods);
 const literal=setOverlap(old.features.literals,newer.features.literals);
 const tokenRatio=Math.min(old.features.tokens,newer.features.tokens)/Math.max(old.features.tokens,newer.features.tokens,1);
 const paramsEqual=old.features.params===newer.features.params;
 const corroboration=0.35*control+0.25*method.ratio+0.20*literal.ratio+0.20*tokenRatio;
 const conflicts=[];
 if(!paramsEqual)conflicts.push('parameter-count-changed');
 if(control<0.55)conflicts.push('control-flow-shape-diverged');
 if(method.ratio<0.15&&Math.min(old.stats.methods.length,newer.stats.methods.length)>=3)conflicts.push('method-call-evidence-diverged');
 if(others>1)conflicts.push('old-function-maps-to-multiple-new-functions');
 const strong=paramsEqual&&others===1&&control>=0.87&&method.ratio>=0.70&&tokenRatio>=0.80&&literal.ratio>=0.7;
 const classification=conflicts.length?'conflicting_static_evidence':strong?'structurally_supported_not_proven':'behavior_review_required';
 return {classification,conflicts,corroboration:+corroboration.toFixed(4),
  controlSimilarity:+control.toFixed(4),methodSimilarity:+method.ratio.toFixed(4),
  literalSimilarity:+literal.ratio.toFixed(4),sharedLongLiterals:literal.both,
  sizeSimilarity:+tokenRatio.toFixed(4),paramsEqual,oldParams:old.features.params,newParams:newer.features.params,
  controls:{old:old.stats.control,new:newer.stats.control},
  riskFlags:{old:old.stats.safety,new:newer.stats.safety},
  statement:'Static AST corroboration only; does NOT certify runtime/semantic equivalence.'};
}
function readTargets(root,prefix,targets){
 const byFile=new Map();
 for(const row of targets){
  const loc=row[prefix];
  if(!loc||!loc.file||!Number.isInteger(loc.line)||!Number.isInteger(loc.offset))throw Error('candidate location missing');
  if(!byFile.has(loc.file))byFile.set(loc.file,new Set());
  byFile.get(loc.file).add(loc.offset+'::'+loc.name);
 }
 const found=new Map(),hashes={};
 for(const [file,wanted] of [...byFile].sort(([a],[b])=>a.localeCompare(b))){
  const resolved=path.resolve(root,file);
  if(!resolved.startsWith(path.resolve(root)+path.sep))throw Error('candidate path escape: '+file);
  const buf=fs.readFileSync(resolved),source=buf.toString('utf8');
  hashes[file]=sha(buf);
  const ast=ts.createSourceFile(file,source,ts.ScriptTarget.Latest,true,
   /\.tsx$|\.jsx$/.test(file)?ts.ScriptKind.TSX:/\.tsx?$/.test(file)?ts.ScriptKind.TS:ts.ScriptKind.JS);
  if(ast.parseDiagnostics.length)throw Error('unparseable historical candidate source: '+file);
  const got=new Set();
  function visit(n){
   if(ts.isFunctionLike(n)&&n.body){
    const offset=n.getStart(ast),line=ast.getLineAndCharacterOfPosition(offset).line+1;
    const name=fnName(n),key=offset+'::'+name;
    if(wanted.has(key)){
      if(got.has(key))throw Error('duplicate source location '+file+':'+key);
      found.set(file+'::'+key,{features:features(n,ast,file),stats:stats(n.body)});
      got.add(key);
    }
   }
   ts.forEachChild(n,visit);
  }
  visit(ast);
  for(const key of wanted)if(!got.has(key))throw Error('candidate no longer at source '+file+':'+key);
 }
 return {found,hashes};
}
function audit(plan,oldRoot,newRoot){
 if(plan.schema!==1)throw Error('unknown candidate manifest schema');
 if(!Array.isArray(plan.review)||plan.review.length!==plan.counts.review)
  throw Error('review candidates truncated; run matcher --max 5000 to audit ALL cases');
 const wanted=plan.review,ids=new Set(),olderCounts=new Map();
 for(const row of wanted){
  const newid=row.new.file+':'+row.new.offset+':'+row.new.name,oldid=row.old.file+':'+row.old.offset+':'+row.old.name;
  if(ids.has(newid))throw Error('duplicate new candidate '+newid);
  ids.add(newid);
  olderCounts.set(oldid,(olderCounts.get(oldid)||0)+1);
 }
 const old=readTargets(oldRoot,'old',wanted),now=readTargets(newRoot,'new',wanted);
 const results=wanted.map(row=>{
  const oldKey=row.old.file+'::'+row.old.offset+'::'+row.old.name,
        newKey=row.new.file+'::'+row.new.offset+'::'+row.new.name,
        oldEntry=old.found.get(oldKey),newEntry=now.found.get(newKey);
  const oldId=row.old.file+':'+row.old.offset+':'+row.old.name;
  if(oldEntry.features.line!==row.old.line||newEntry.features.line!==row.new.line)
   throw Error('candidate source line changed: '+oldKey+' => '+newKey);
  const r=inspectPair(oldEntry,newEntry,olderCounts.get(oldId));
  return {
    id:sha(newKey+' => '+oldKey).slice(0,16),
    old:row.old,new:row.new,matcherScore:row.score,
    independent:r,
    provenance:{historicalFileSha256:old.hashes[row.old.file],restoredFileSha256:now.hashes[row.new.file]},
    proof:{sourceLocated:true,controlAndMethodProfileCompared:true,
     candidateUniqueNewTarget:true,oldReferenceFanout:olderCounts.get(oldId),
     runtimeBehaviorVerified:false,semanticEquivalenceConfirmed:false}
  };
 });
 results.sort((a,b)=>a.new.file.localeCompare(b.new.file)||a.new.line-b.new.line||a.new.offset-b.new.offset||a.id.localeCompare(b.id));
 const classes={};
 for(const r of results)classes[r.independent.classification]=(classes[r.independent.classification]||0)+1;
 return {
  schema:'review-703-v1',historicalReference:plan.reference,
  baseline:plan.baseline,
  manifestSha256:sha(JSON.stringify(wanted)),
  sourceInventory:plan.scanned,
  summary:{candidates:results.length,reviewedWithIndependentStaticEvidence:results.length,
   sourceLocationsValidated:results.length,semanticEquivalenceConfirmed:0,
   runtimeBehaviorVerified:0,classification:classes,
   multiConsumerOldCandidates:results.filter(x=>x.proof.oldReferenceFanout>1).length},
  interpretation:'Review completed here means evidence and counter-evidence evaluated for each pair, NOT equivalence certified.',
  items:results
 };
}
function main(argv){
 const arg=k=>{const i=argv.indexOf(k);return i<0?null:argv[i+1]};
 const input=arg('--candidates'),old=arg('--old'),newRoot=arg('--new')||path.resolve(__dirname,'../core'),
  output=arg('--out')||'.generated/historical-703-reviewed.json';
 if(!input||!old)throw Error('need --candidates <full-manifest> --old <2.1.88/src>');
 const manifest=JSON.parse(fs.readFileSync(input,'utf8'));
 const expected=arg('--expected'),result=audit(manifest,path.resolve(old),path.resolve(newRoot));
 if(expected!==null&&result.summary.candidates!==Number(expected))throw Error('historical candidate cardinality changed; expected '+expected+', got '+result.summary.candidates);
 fs.mkdirSync(path.dirname(output),{recursive:true});
 fs.writeFileSync(output,JSON.stringify(result,null,2)+'\n');
 console.log(JSON.stringify({out:output,...result.summary,manifestSha256:result.manifestSha256}));
}
if(require.main===module){try{main(process.argv.slice(2))}catch(e){console.error(e.stack||e);process.exitCode=1;}}
module.exports={stats,inspectPair,audit};
