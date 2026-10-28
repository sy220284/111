'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const path=require('node:path');
const {analyze,impact,trace,inputFiles}=require('../../tools/analysis/code-graph.cjs');
const cases={
  'src/base.js': 'function core(x){return x}; export{core as entry}; export function recur(){ return entry(); }',
  'src/mid.js': 'import{entry as invoke}from"./base.js"; export function outer(){return invoke(1)};',
  'src/top.js': 'import * as mid from "./mid.js"; function run(){return mid.outer()};run();',
  'src/side.js': 'const x=1;'
};
test('inter-file export aliases, imported symbols and namespace invocations are candidates',()=>{
  const g=analyze(cases,[{source:'src/base.js',symbol:'core',role:'helper',test:'tests/helper.test.cjs',status:'targeted_behavior_test_only'}]);
  assert.deepEqual(g.errors,[]);
  assert.equal(g.summary.modules,4);
  assert.equal(g.summary.moduleDependencies,2);
  assert.equal(g.summary.recordedSemantics,1);
  const direct=trace(g,'src/base.js','core');
  assert.equal(direct.inbound.length,1);
  assert.equal(direct.inbound[0].kind,'import_binding_candidate');
  assert.equal(direct.semantics[0].role,'helper');
  const other=trace(g,'src/mid.js','outer');
  assert.equal(other.inbound.length,1);
  assert.equal(other.inbound[0].kind,'namespace_import_candidate');
  assert.equal(other.outbound.length,1);
  assert.equal(other.outbound[0].to.file,'src/base.js');
});
test('reverse module impact is transitive and does not include unrelated sources',()=>{
  const g=analyze(cases);
  const out=impact(g,'src/base.js');
  assert.equal(out.affectedModuleCount,2);
  assert.deepEqual(out.reverseDependents,['src/mid.js','src/top.js']);
  assert.throws(()=>impact(g,'src/missing.js'),/unknown source/);
});
test('unresolved dynamic/property calls never become verified call links',()=>{
  const g=analyze({'src/a.js':'function x(v){return v.fly()+globalThis[v]()+x(1)}'});
  assert.equal(g.summary.callExpressions,3);
  assert.equal(g.summary.candidateResolvedCalls,1);
  assert.equal(g.summary.unresolvedCallExpressions,2);
  assert.equal(g.callEdges[0].kind,'same_module_candidate');
  assert.match(g.summary.warning,/NOT proven runtime/);
});
test('cyclic imports tracked without claiming execution cycles',()=>{
  const g=analyze({
    'src/a.js':'import "./b.js";',
    'src/b.js':'export * from "./a.js";'
  });
  assert.equal(g.summary.cyclicGroups,1);
  assert.deepEqual(g.cycles[0],['src/a.js','src/b.js']);
});
test('relative missing and syntax errors are not silently accepted',()=>{
  const g=analyze({'src/a.js':'import {x} from "./missing.js"; function broken( {'});
  assert.ok(g.errors.length>=2);
});
test('repository source selection includes embedded worker and both runtime adapters',()=>{
  const root=path.resolve(__dirname,'../..');
  const files=inputFiles(root);
  assert.equal(files.filter(x=>x.startsWith('src/')).length,2551);
  assert.ok(files.includes('runtime/assets.mjs'));
  assert.ok(files.includes('runtime/string-width.mjs'));
});

test('function positions disambiguate same-line anonymous and named functions',()=>{
  const g=analyze({'src/a.js':'const a=()=>1,b=()=>2; const c=(v)=>v;'});
  assert.equal(g.summary.functionEntities,3);
  assert.match(g.summary.functionIdentity,/same-line/);
});
test('unexported function is NOT a proven cross-file import target',()=>{
  const g=analyze({'src/a.js':'function privateFn(){}','src/b.js':
    'import { privateFn } from "./a.js";export function run(){privateFn();}'});
  assert.equal(g.summary.candidateResolvedCalls,0);
  assert.equal(g.summary.unresolvedReasons.import_without_proven_export,1);
});
test('declared export function / const and default function are recognized',()=>{
  const g=analyze({
    'src/a.js':'export function named(){};export const f=()=>1;export default function primary(){}',
    'src/b.js':'import primary,{named,f} from "./a.js";function run(){named();f();primary()}'
  });
  assert.equal(g.summary.candidateResolvedCalls,3);
});
test('local parameters mask same-module globals and import aliases',()=>{
  const g=analyze({
    'src/a.js':'export function fn(){}',
    'src/b.js':'import {fn} from "./a.js";function run(fn){fn();}',
    'src/c.js':'function g(){};function outer(g){g();}'
  });
  assert.equal(g.summary.candidateResolvedCalls,0);
  assert.equal(g.summary.unresolvedReasons.local_binding_or_parameter,2);
});
test('literal dynamic imports add real dependency without inventing a function call',()=>{
  const g=analyze({
    'src/a.js':'async function run(){return import("./b.js")}',
    'src/b.js':'export function f(){}'
  });
  assert.equal(g.summary.moduleDependencies,1);
  assert.equal(g.summary.unresolvedReasons.dynamic_import,1);
});

test('lazy dynamic import does not count as an eager/static cycle',()=>{
 const g=analyze({
   'src/a.js':'import "./b.js"; export async function load(){return import("./c.js")}',
   'src/b.js':'export const b=1;',
   'src/c.js':'import "./a.js";export const c=2;'
 });
 assert.equal(g.summary.moduleDependencies,3);
 assert.equal(g.summary.staticModuleDependencies,2);
 assert.equal(g.summary.cyclicGroups,0);
 assert.equal(g.summary.cyclesScope,'static-import-export-only');
});

test('真实恢复函数的const短符号别名在原模块和跨模块调用追踪中指向同一实现',()=>{
 const g=analyze({
  'src/helper.js':'function restoredPermission(x){return x}; const old=restoredPermission; export{old}',
  'src/client.js':'import{old as invoke}from"./helper.js"; function run(){return invoke("Bash")}',
  'src/cli.js':'import{old}from"./helper.js"; old("Task");'
 });
 assert.deepEqual(g.errors,[]);
 assert.equal(g.summary.candidateResolvedCalls,2);
 const evidence=trace(g,'src/helper.js','restoredPermission');
 assert.equal(evidence.inbound.length,2);
 assert.ok(evidence.inbound.every(x=>x.to.symbol==='restoredPermission'));
 assert.ok(evidence.inbound.every(x=>x.kind==='import_binding_candidate'));
});
test('immutable aliases may chain forward but reassigned let/var must not fabricate callable links',()=>{
 const g=analyze({
  'src/a.js':'const old=intermediate;const intermediate=impl;function impl(){return 1};old();',
  'src/b.js':'function impl(){return 1};let old=impl;old();',
  'src/c.js':'const mutable=()=>1;var rebind=mutable;rebind();'
 });
 const old=trace(g,'src/a.js','impl');
 assert.equal(old.inbound.length,1);
 assert.equal(old.inbound[0].kind,'same_module_candidate');
 assert.equal(g.summary.candidateResolvedCalls,1);
});

test('函数局部作用域的声明、箭头实现及不可变别名可被精确定位',()=>{
 const g=analyze({'src/lex.js':
  'function a(){function inner(){};const alias=inner;const cb=()=>{};inner();alias();cb()}'+
  'function b(){function inner(){};inner()}'
 });
 assert.deepEqual(g.errors,[]);
 assert.equal(g.summary.candidateResolvedCalls,4);
 assert.equal(g.summary.unresolvedCallExpressions,0);
 const targets=g.callEdges.filter(x=>x.kind==='lexical_function_candidate');
 assert.equal(targets.reduce((n,e)=>n+e.count,0),4);
 assert.equal(new Set(targets.filter(e=>e.to.symbol==='inner').map(e=>e.to.id)).size,2);
 assert.ok(targets.every(e=>e.to.id.includes('src/lex.js#')));
});

test('函数参数与块级同名声明不能穿透去错误绑定外层函数',()=>{
 const g=analyze({'src/shadow.js':
  'function known(){};function outer(known){known()}'+
  'function mixed(){if(true){const known=()=>1}known()}'
 });
 assert.equal(g.summary.candidateResolvedCalls,0);
 assert.equal(g.summary.unresolvedReasons.local_binding_or_parameter,2);
 assert.equal(g.unresolvedCallsites.length,2);
 assert.ok(g.unresolvedCallsites.every(x=>x.line>0 && x.column>0 && x.callee==='known'));
});

test('const 对象字面量的函数成员可建立静态候选关系，参数覆盖仍然不解析',()=>{
 const g=analyze({'src/obj.js':
  'const api={run(){}, stop:()=>{}};function run(){api.run();api.stop()}'+
  'function shadow(api){api.run()}'+
  'function local(){const worker={execute(){}};worker.execute()}'
 });
 assert.equal(g.summary.candidateResolvedCalls,3);
 assert.equal(g.summary.unresolvedCallExpressions,1);
 assert.equal(g.summary.unresolvedReasons.property_or_namespace_unresolved,1);
 assert.ok(g.callEdges.some(e=>e.kind==='module_object_method_candidate'));
 assert.ok(g.callEdges.some(e=>e.kind==='lexical_object_method_candidate'));
 assert.equal(g.unresolvedCallsites[0].callee,'api.run');
});

test('立即执行的箭头函数和函数表达式指向实际AST函数位置',()=>{
 const g=analyze({'src/iife.js':
  '(()=>42)();(function named(){return 1})();'});
 assert.equal(g.summary.candidateResolvedCalls,2);
 assert.equal(g.summary.unresolvedCallExpressions,0);
 assert.ok(g.callEdges.every(e=>e.kind==='immediate_function_candidate'));
 assert.ok(g.callEdges.every(e=>e.to.id.endsWith('@'+e.to.line+':'+e.to.column)));
});

test('所有未解析调用都有原因与独立源码定位，数量等于汇总',()=>{
 const g=analyze({'src/u.js':
  'function run(x){x.foo(); unknown(); globalThis[x](); import("./dep.js")} ',
  'src/dep.js':'export const x=1;'});
 assert.equal(g.unresolvedCallsites.length,g.summary.unresolvedCallExpressions);
 assert.equal(Object.values(g.summary.unresolvedReasons).reduce((a,b)=>a+b,0),
   g.summary.unresolvedCallExpressions);
 assert.equal(g.unresolvedCallsites.filter(x=>x.reason==='dynamic_import').length,1);
 assert.ok(g.unresolvedCallsites.every(x=>x.file&&x.owner&&x.callee&&x.reason));
});

test('方括号字面量成员能解析，计算键不会被猜为目标函数',()=>{
 const g=analyze({
  'src/api.js':'export function fire(){}',
  'src/a.js':'import * as ns from "./api.js";const obj={foo(){}};'+
    'obj["foo"]();obj[key]();ns["fire"]();ns[key]();'
 });
 assert.deepEqual(g.errors,[]);
 assert.equal(g.summary.candidateResolvedCalls,2);
 assert.equal(g.summary.unresolvedCallExpressions,2);
 assert.equal(g.summary.unresolvedReasons.dynamic_property,2);
 assert.ok(g.callEdges.some(e=>e.kind==='module_object_method_candidate'));
 assert.ok(g.callEdges.some(e=>e.kind==='namespace_import_candidate'));
});
