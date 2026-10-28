'use strict';
const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const {analyzeQueryCallgraph}=require('../scripts/trace-query-loop-callchain.cjs');
const original=fs.readFileSync(path.resolve(__dirname,'../core/chunk-tqjdehqd.js'),'utf8');
test('真实 AST 证明 pN → Ou → Fu → Du，而非错误省略 Fu',()=>{
  const graph=analyzeQueryCallgraph(original);
  assert.deepEqual(graph.links.map(e=>e.from+'->'+e.to),['pN->Da','pN->Ou','Ou->Fu','Fu->Du']);
  for(const edge of graph.links)assert.ok(edge.callLines.length>0);
  assert.equal(graph.semanticOrEndToEndParity,false);
});
test('删掉 Fu 到 Du 的真实调用会失败，不能只依赖文字锚点',()=>{
  const changed=original.replace('let S = yield* Du();','let S = yield* missingRound();');
  assert.notEqual(changed,original);
  assert.throws(()=>analyzeQueryCallgraph(changed),/Fu -> Du missing AST call/);
});
test('取消分支、thinking 恢复与会话桥位置仍由 AST 检查',()=>{
  const result=analyzeQueryCallgraph(original);
  assert.ok(result.scopes.pN<result.scopes.Ou);
  assert.ok(result.scopes.Ou<result.scopes.Du);
  assert.ok(result.scopes.Du<result.scopes.Fu);
});
