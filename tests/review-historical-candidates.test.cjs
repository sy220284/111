'use strict';
const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const os=require('node:os');
const path=require('node:path');
const {inspectPair,audit}=require('../scripts/review-historical-candidates.cjs');
const mock=(control,methods,params=1,tokens=80,literals=['persistent-record-key','background-session-failure'])=>({
  features:{params,tokens,literals},
  stats:{control:{if:0,switch:0,case:0,for:0,while:0,try:0,catch:0,throw:0,return:1,await:0,yield:0,conditional:0,call:2,new:0,assignment:0,...control},methods,safety:[]}
});
test('独立AST证据不能自动认证任何函数语义等价',()=>{
 const a=mock({if:1},['startsWith','includes']),b=mock({if:1},['startsWith','includes']);
 const result=inspectPair(a,b);
 assert.equal(result.classification,'structurally_supported_not_proven');
 assert.equal(result.conflicts.length,0);
 assert.match(result.statement,/does NOT certify/);
});
test('参数变更、方法形态和一旧多新必须留下反证',()=>{
 const x=mock({if:3,throw:2},['writeFile','unlink'],1);
 const y=mock({if:0,throw:0},['trim','slice'],2);
 const result=inspectPair(x,y,2);
 assert.equal(result.classification,'conflicting_static_evidence');
 assert.ok(result.conflicts.includes('parameter-count-changed'));
 assert.ok(result.conflicts.includes('control-flow-shape-diverged'));
 assert.ok(result.conflicts.includes('old-function-maps-to-multiple-new-functions'));
});
test('完整候选清单逐项复核、维护独立SHA和可追溯定位',()=>{
 const root=fs.mkdtempSync(path.join(os.tmpdir(),'111-history-review-'));
 const old=path.join(root,'old'),newRoot=path.join(root,'src');
 fs.mkdirSync(old);fs.mkdirSync(newRoot);
 try{
  const oldCode='export function classify(message: string) { if (message.startsWith("persistent-record-key")) return message.includes("background-session-failure"); return false; }';
  const newCode='function q(e) { if (e.startsWith("persistent-record-key")) return e.includes("background-session-failure"); return false; }';
  fs.writeFileSync(path.join(old,'historical.ts'),oldCode);
  fs.writeFileSync(path.join(newRoot,'chunk-test.js'),newCode);
  const c={schema:1,reference:'2.1.88 test fixture',baseline:'2.1.310',scanned:{oldFiles:1,newFiles:1},counts:{review:1,high:0,ambiguous:0},review:[{
   old:{file:'historical.ts',name:'classify',line:1,tokens:40},
   new:{file:'chunk-test.js',name:'q',line:1,tokens:40},score:0.8
  }]};
  const result=audit(c,old,newRoot);
  assert.equal(result.summary.candidates,1);
  assert.equal(result.summary.sourceLocationsValidated,1);
  assert.equal(result.summary.semanticEquivalenceConfirmed,0);
  assert.equal(result.summary.runtimeBehaviorVerified,0);
  assert.equal(result.items[0].provenance.historicalFileSha256.length,64);
  assert.ok(result.items[0].independent.controlSimilarity>=0);
  assert.ok(result.items[0].independent.controlSimilarity<=1);
  assert.equal(result.items[0].proof.semanticEquivalenceConfirmed,false);
  assert.throws(()=>audit({...c,counts:{review:2}},old,newRoot),/truncated/);
  assert.throws(()=>audit({...c,review:[{...c.review[0],old:{...c.review[0].old,line:2}}]},old,newRoot),/candidate no longer/);
 }finally{fs.rmSync(root,{recursive:true,force:true})}
});
