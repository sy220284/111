'use strict';
const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const os=require('node:os');
const path=require('node:path');
const {analyze}=require('../tools/analysis/code-graph.cjs');
const {evaluate,sourceDefinitions,shortestModulePath}=require('../scripts/audit-historical-business-chains.cjs');
const makeAudit=(file,def)=>({
 schema:'review-703-v1',baseline:'2.1.310',
 summary:{candidates:1},manifestSha256:'test',
 items:[{id:'aabbccdd00112233',new:{file,line:def.line,offset:def.offset,name:def.name},
  independent:{classification:'behavior_review_required',conflicts:[]},
  proof:{runtimeBehaviorVerified:false,semanticEquivalenceConfirmed:false}}]
});
test('调用依赖闭环：跨模块消费者、真实函数AST、CLI导入路径具有明确证据',()=>{
 const home=fs.mkdtempSync(path.join(os.tmpdir(),'111-chain-'));
 const src=path.join(home,'src');
 fs.mkdirSync(src);
 try{
  const program='export function core(v){return v+1}';
  fs.writeFileSync(path.join(src,'chunk-a.js'),program);
  const def=sourceDefinitions(program,'chunk-a.js').defs[0];
  const graph=analyze({
   'cli.js':'import{core}from"./chunk-a.js";function start(){return core(1)}',
   'chunk-a.js':program,'disconnected.js':'function f(){return 1}'
  });
  const result=evaluate(makeAudit('chunk-a.js',def),graph,src);
  assert.equal(result.summary.examined,1);
  assert.equal(result.summary.directStaticCallerWitnesses,1);
  assert.equal(result.summary.moduleRouteFromCLI,1);
  assert.deepEqual(result.items[0].dependencies.cliModuleImportRoute,['cli.js','chunk-a.js']);
  assert.equal(result.items[0].dependencies.candidateCallSiteCount,1);
  assert.equal(result.items[0].verification.validatedBusinessChain,false);
  assert.equal(result.summary.semanticallyConfirmed,0);
 }finally{fs.rmSync(home,{recursive:true,force:true})}
});
test('同名定义在一行上时不能把候选调用边错误绑定到某一个函数',()=>{
 const home=fs.mkdtempSync(path.join(os.tmpdir(),'111-chain-')),src=path.join(home,'src');
 fs.mkdirSync(src);
 try {
  const program='function f(a){return a+1};function f(a){return a+2}';
  fs.writeFileSync(path.join(src,'chunk-a.js'),program);
  const target=sourceDefinitions(program,'chunk-a.js').defs[0];
  const graph=analyze({'cli.js':'import "./chunk-a.js";',
   'chunk-a.js':program+';f(1)'});
  const result=evaluate(makeAudit('chunk-a.js',target),graph,src);
  assert.equal(result.summary.duplicateNameAmbiguous,1);
  assert.equal(result.items[0].dependencies.candidateCallSiteCount,0);
  assert.equal(result.items[0].dependencies.ambiguousTargetDueToDuplicateName,true);
  assert.equal(result.items[0].verification.witnessedRuntimeCall,false);
 }finally{fs.rmSync(home,{recursive:true,force:true})}
});
test('候选偏移与源码不一致或者缺失模块时拒绝出具通过报告',()=>{
 const home=fs.mkdtempSync(path.join(os.tmpdir(),'111-chain-')),src=path.join(home,'src');
 fs.mkdirSync(src);
 try{
  const content='function g(x){return x}';
  fs.writeFileSync(path.join(src,'chunk-a.js'),content);
  const ast=sourceDefinitions(content,'chunk-a.js').defs[0];
  const graph=analyze({'cli.js':'','chunk-a.js':content});
  const audited=makeAudit('chunk-a.js',ast);
  audited.items[0].new.offset++;
  assert.throws(()=>evaluate(audited,graph,src),/source function drift/);
  assert.throws(()=>evaluate(makeAudit('chunk-a.js',ast),
    analyze({'cli.js':''}),src),/not in source dependency graph/);
 }finally{fs.rmSync(home,{recursive:true,force:true})}
});
