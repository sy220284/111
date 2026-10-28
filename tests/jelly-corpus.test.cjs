'use strict';
const test=require('node:test'),assert=require('node:assert/strict');
const {args,decode,normalizeGraph}=require('../tools/analysis/jelly-corpus.cjs');
const fixture={ignoreDependencies:true,files:['src/a.js','src/b.js'],
  functions:{'0':'0:1:1:1:22','1':'1:1:1:1:24','2':'0:?:?:?:?'},
  calls:{'3':'0:2:3:2:15','4':'1:3:1:3:12'},
  fun2fun:[[0,1]],call2fun:[[3,1],[3,1],[4,2]]};
test('Jelly v0.13.0 call graph location decoder validates paths and 1-based coordinates',()=>{
 assert.deepEqual(decode('0:2:3:2:15',['src/a.js']),
   {file:'src/a.js',line:2,column:3,endLine:2,endColumn:15});
 assert.equal(decode('0:?:?:?:?',['src/a.js']),null);
 assert.equal(decode('0:1:1:1:8',['../bad.js']),null);
 assert.equal(decode('0:1:1:1:8',['/root/bad.js']),null);
 assert.equal(decode('3:1:1:1:8',['src/a.js']),null);
});
test('candidate rows deduplicate within graph and ignore unresolved module pseudo-targets',()=>{
 const a=normalizeGraph(fixture,['src/a.js','src/b.js']);
 assert.deepEqual(a.rows,['src/a.js\t2\t3\t2\t15\tsrc/b.js\t1\t1\t1\t24']);
 assert.equal(a.call2fun,3);
 assert.equal(a.malformed,1);
 assert.equal(a.calls,2);
 assert.equal(a.functions,3);
});
test('reject incomplete or dependency-expanded analysis graph instead of claiming coverage',()=>{
 assert.throws(()=>normalizeGraph({...fixture,ignoreDependencies:false},fixture.files),/schema/);
 assert.throws(()=>normalizeGraph(fixture,['src/a.js']),/Unexpected/);
 assert.throws(()=>normalizeGraph({...fixture,files:['src/a.js']},fixture.files),/Missing/);
});
test('invalid scan limits rejected before starting Jelly',()=>{
 assert.throws(()=>args(['--jelly','a','--out','b','--shards','8','--shard','9']),/Invalid/);
 assert.throws(()=>args(['--jelly','a','--out','b','--timeout','3']),/Invalid/);
 assert.equal(args(['--jelly','a','--out','b','--shards','8','--shard','3']).shard,3);
});
