'use strict';
// 703 separate TAP assertions over independently audited genuine historical candidate pairs.
// These are source and evidence verification tests, NOT runtime behavior or semantic parity tests.
const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const crypto=require('node:crypto');
const sha=b=>crypto.createHash('sha256').update(b).digest('hex');

const root=path.resolve(__dirname,'../..');
const candidatePath=path.join(root,'.generated/v43-historical-real-full.json');
const reviewPath=path.join(root,'.generated/v44-reviewed-703.json');
if(!fs.existsSync(candidatePath)||!fs.existsSync(reviewPath))
 throw Error('703 real-source audit artifacts missing: run historical CI with fixed 2.1.88 checkout');
const oldRoot=process.env.OLD_SOURCE_ROOT;
if(!oldRoot||!fs.existsSync(path.join(oldRoot,'utils/array.ts')))
 throw Error('real pinned 2.1.88 source tree is required');
const manifest=JSON.parse(fs.readFileSync(candidatePath,'utf8'));
const audited=JSON.parse(fs.readFileSync(reviewPath,'utf8'));
assert.equal(manifest.counts.review,703);
assert.equal(manifest.review.length,703);
assert.equal(audited.items.length,703);
assert.equal(audited.summary.sourceLocationsValidated,703);
assert.equal(audited.summary.semanticEquivalenceConfirmed,0);
assert.equal(audited.summary.runtimeBehaviorVerified,0);
assert.equal(audited.manifestSha256,sha(JSON.stringify(manifest.review)));
const sourceDigests=new Map(), ids=new Set();
function digest(rootDir,file){
 const absolute=path.resolve(rootDir,file);
 assert.ok(absolute.startsWith(path.resolve(rootDir)+path.sep),'path escape '+file);
 if(!sourceDigests.has(absolute))sourceDigests.set(absolute,sha(fs.readFileSync(absolute)));
 return sourceDigests.get(absolute);
}
for(const [i,row] of audited.items.entries()){
 test('703-case #'+String(i+1).padStart(3,'0')+' '+row.id+': '+row.new.file+' -> '+row.old.file,()=>{
  assert.match(row.id,/^[a-f0-9]{16}$/);
  assert.ok(!ids.has(row.id),'duplicate AST identity');
  ids.add(row.id);
  assert.ok(Number.isInteger(row.new.offset)&&row.new.offset>=0);
  assert.ok(Number.isInteger(row.old.offset)&&row.old.offset>=0);
  assert.ok(Number.isInteger(row.new.line)&&row.new.line>=1);
  assert.ok(Number.isInteger(row.old.line)&&row.old.line>=1);
  assert.equal(row.proof.sourceLocated,true);
  assert.equal(row.proof.controlAndMethodProfileCompared,true);
  assert.equal(row.proof.runtimeBehaviorVerified,false);
  assert.equal(row.proof.semanticEquivalenceConfirmed,false);
  assert.equal(row.provenance.historicalFileSha256,digest(oldRoot,row.old.file));
  assert.equal(row.provenance.restoredFileSha256,digest(path.join(root,'src'),row.new.file));
  assert.ok(row.independent.corroboration>=0&&row.independent.corroboration<=1);
  assert.ok(row.independent.controlSimilarity>=0&&row.independent.controlSimilarity<=1);
  assert.ok(row.independent.paramsEqual===true||row.independent.conflicts.includes('parameter-count-changed'));
  if(row.proof.oldReferenceFanout>1)
   assert.ok(row.independent.conflicts.includes('old-function-maps-to-multiple-new-functions'));
  if(row.independent.classification==='conflicting_static_evidence')
   assert.ok(row.independent.conflicts.length>0);
  else assert.ok(['structurally_supported_not_proven','behavior_review_required'].includes(row.independent.classification));
 });
}
