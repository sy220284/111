'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const path=require('node:path');
const {analyze,impact,trace,inputFiles}=require('../tools/analysis/code-graph.cjs');
const cases={
  'base.js': 'function core(x){return x}; export{core as entry}; export function recur(){ return entry(); }',
  'mid.js': 'import{entry as invoke}from"./base.js"; export function outer(){return invoke(1)};',
  'top.js': 'import * as mid from "./mid.js"; function run(){return mid.outer()};run();',
  'side.js': 'const x=1;'
};
test('inter-file export aliases, imported symbols and namespace invocations are candidates',()=>{
  const g=analyze(cases,[{source:'base.js',symbol:'core',role:'helper',test:'tests/helper.test.cjs',status:'targeted_behavior_test_only'}]);
  assert.deepEqual(g.errors,[]);
  assert.equal(g.summary.modules,4);
  assert.equal(g.summary.moduleDependencies,2);
  assert.equal(g.summary.recordedSemantics,1);
  const direct=trace(g,'base.js','core');
  assert.equal(direct.inbound.length,1);
  assert.equal(direct.inbound[0].kind,'import_binding_candidate');
  assert.equal(direct.semantics[0].role,'helper');
  const other=trace(g,'mid.js','outer');
  assert.equal(other.inbound.length,1);
  assert.equal(other.inbound[0].kind,'namespace_import_candidate');
  assert.equal(other.outbound.length,1);
  assert.equal(other.outbound[0].to.file,'base.js');
});
test('reverse module impact is transitive and does not include unrelated sources',()=>{
  const g=analyze(cases);
  const out=impact(g,'base.js');
  assert.equal(out.affectedModuleCount,2);
  assert.deepEqual(out.reverseDependents,['mid.js','top.js']);
  assert.throws(()=>impact(g,'missing.js'),/unknown source/);
});
test('unresolved dynamic/property calls never become verified call links',()=>{
  const g=analyze({'a.js':'function x(v){return v.fly()+globalThis[v]()+x(1)}'});
  assert.equal(g.summary.callExpressions,3);
  assert.equal(g.summary.candidateResolvedCalls,1);
  assert.equal(g.summary.unresolvedCallExpressions,2);
  assert.equal(g.callEdges[0].kind,'same_module_candidate');
  assert.match(g.summary.warning,/NOT proven runtime/);
});
test('cyclic imports tracked without claiming execution cycles',()=>{
  const g=analyze({
    'a.js':'import "./b.js";',
    'b.js':'export * from "./a.js";'
  });
  assert.equal(g.summary.cyclicGroups,1);
  assert.deepEqual(g.cycles[0],['a.js','b.js']);
});
test('relative missing and syntax errors are not silently accepted',()=>{
  const g=analyze({'a.js':'import {x} from "./missing.js"; function broken( {'});
  assert.ok(g.errors.length>=2);
});
test('repository source selection includes embedded worker and both runtime adapters',()=>{
  const root=path.resolve(__dirname,'..');
  const files=inputFiles(root);
  assert.equal(files.filter(x=>x.startsWith('')).length,2551);
  assert.ok(files.includes('runtime/assets.mjs'));
  assert.ok(files.includes('runtime/string-width.mjs'));
});

test('function positions disambiguate same-line anonymous and named functions',()=>{
  const g=analyze({'a.js':'const a=()=>1,b=()=>2; const c=(v)=>v;'});
  assert.equal(g.summary.functionEntities,3);
  assert.match(g.summary.functionIdentity,/same-line/);
});
test('unexported function is NOT a proven cross-file import target',()=>{
  const g=analyze({'a.js':'function privateFn(){}','b.js':
    'import { privateFn } from "./a.js";export function run(){privateFn();}'});
  assert.equal(g.summary.candidateResolvedCalls,0);
  assert.equal(g.summary.unresolvedReasons.import_without_proven_export,1);
});
test('declared export function / const and default function are recognized',()=>{
  const g=analyze({
    'a.js':'export function named(){};export const f=()=>1;export default function primary(){}',
    'b.js':'import primary,{named,f} from "./a.js";function run(){named();f();primary()}'
  });
  assert.equal(g.summary.candidateResolvedCalls,3);
});
test('local parameters mask same-module globals and import aliases',()=>{
  const g=analyze({
    'a.js':'export function fn(){}',
    'b.js':'import {fn} from "./a.js";function run(fn){fn();}',
    'c.js':'function g(){};function outer(g){g();}'
  });
  assert.equal(g.summary.candidateResolvedCalls,0);
  assert.equal(g.summary.unresolvedReasons.local_binding_or_parameter,2);
});
test('literal dynamic imports add real dependency without inventing a function call',()=>{
  const g=analyze({
    'a.js':'async function run(){return import("./b.js")}',
    'b.js':'export function f(){}'
  });
  assert.equal(g.summary.moduleDependencies,1);
  assert.equal(g.summary.unresolvedReasons.dynamic_import,1);
});

test('lazy dynamic import does not count as an eager/static cycle',()=>{
 const g=analyze({
   'a.js':'import "./b.js"; export async function load(){return import("./c.js")}',
   'b.js':'export const b=1;',
   'c.js':'import "./a.js";export const c=2;'
 });
 assert.equal(g.summary.moduleDependencies,3);
 assert.equal(g.summary.staticModuleDependencies,2);
 assert.equal(g.summary.cyclicGroups,0);
 assert.equal(g.summary.cyclesScope,'static-import-export-only');
});

test('真实恢复函数的const短符号别名在原模块和跨模块调用追踪中指向同一实现',()=>{
 const g=analyze({
  'helper.js':'function restoredPermission(x){return x}; const old=restoredPermission; export{old}',
  'client.js':'import{old as invoke}from"./helper.js"; function run(){return invoke("Bash")}',
  'cli.js':'import{old}from"./helper.js"; old("Task");'
 });
 assert.deepEqual(g.errors,[]);
 assert.equal(g.summary.candidateResolvedCalls,2);
 const evidence=trace(g,'helper.js','restoredPermission');
 assert.equal(evidence.inbound.length,2);
 assert.ok(evidence.inbound.every(x=>x.to.symbol==='restoredPermission'));
 assert.ok(evidence.inbound.every(x=>x.kind==='import_binding_candidate'));
});
test('immutable aliases may chain forward but reassigned let/var must not fabricate callable links',()=>{
 const g=analyze({
  'a.js':'const old=intermediate;const intermediate=impl;function impl(){return 1};old();',
  'b.js':'function impl(){return 1};let old=impl;old();',
  'c.js':'const mutable=()=>1;var rebind=mutable;rebind();'
 });
 const old=trace(g,'a.js','impl');
 assert.equal(old.inbound.length,1);
 assert.equal(old.inbound[0].kind,'same_module_candidate');
 assert.equal(g.summary.candidateResolvedCalls,1);
});

test('函数局部作用域的声明、箭头实现及不可变别名可被精确定位',()=>{
 const g=analyze({'lex.js':
  'function a(){function inner(){};const alias=inner;const cb=()=>{};inner();alias();cb()}'+
  'function b(){function inner(){};inner()}'
 });
 assert.deepEqual(g.errors,[]);
 assert.equal(g.summary.candidateResolvedCalls,4);
 assert.equal(g.summary.unresolvedCallExpressions,0);
 const targets=g.callEdges.filter(x=>x.kind==='lexical_function_candidate');
 assert.equal(targets.reduce((n,e)=>n+e.count,0),4);
 assert.equal(new Set(targets.filter(e=>e.to.symbol==='inner').map(e=>e.to.id)).size,2);
 assert.ok(targets.every(e=>e.to.id.includes('lex.js#')));
});

test('函数参数与块级同名声明不能穿透去错误绑定外层函数',()=>{
 const g=analyze({'shadow.js':
  'function known(){};function outer(known){known()}'+
  'function mixed(){if(true){const known=()=>1}known()}'
 });
 assert.equal(g.summary.candidateResolvedCalls,0);
 assert.equal(g.summary.unresolvedReasons.local_binding_or_parameter,2);
 assert.equal(g.unresolvedCallsites.length,2);
 assert.ok(g.unresolvedCallsites.every(x=>x.line>0 && x.column>0 && x.callee==='known'));
});

test('const 对象字面量的函数成员可建立静态候选关系，参数覆盖仍然不解析',()=>{
 const g=analyze({'obj.js':
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
 const g=analyze({'iife.js':
  '(()=>42)();(function named(){return 1})();'});
 assert.equal(g.summary.candidateResolvedCalls,2);
 assert.equal(g.summary.unresolvedCallExpressions,0);
 assert.ok(g.callEdges.every(e=>e.kind==='immediate_function_candidate'));
 assert.ok(g.callEdges.every(e=>e.to.id.endsWith('@'+e.to.line+':'+e.to.column)));
});

test('所有未解析调用都有原因与独立源码定位，数量等于汇总',()=>{
 const g=analyze({'u.js':
  'function run(x){x.foo(); unknown(); globalThis[x](); import("./dep.js")} ',
  'dep.js':'export const x=1;'});
 assert.equal(g.unresolvedCallsites.length,g.summary.unresolvedCallExpressions);
 assert.equal(Object.values(g.summary.unresolvedReasons).reduce((a,b)=>a+b,0),
   g.summary.unresolvedCallExpressions);
 assert.equal(g.unresolvedCallsites.filter(x=>x.reason==='dynamic_import').length,1);
 assert.ok(g.unresolvedCallsites.every(x=>x.file&&x.owner&&x.callee&&x.reason));
});

test('方括号字面量成员能解析，计算键不会被猜为目标函数',()=>{
 const g=analyze({
  'api.js':'export function fire(){}',
  'a.js':'import * as ns from "./api.js";const obj={foo(){}};'+
    'obj["foo"]();obj[key]();ns["fire"]();ns[key]();'
 });
 assert.deepEqual(g.errors,[]);
 assert.equal(g.summary.candidateResolvedCalls,2);
 assert.equal(g.summary.unresolvedCallExpressions,2);
 assert.equal(g.summary.unresolvedReasons.dynamic_property,2);
 assert.ok(g.callEdges.some(e=>e.kind==='module_object_method_candidate'));
 assert.ok(g.callEdges.some(e=>e.kind==='namespace_import_candidate'));
});

test('多级对象方法与不可变模块别名解析到准确的 AST 方法定义',()=>{
 const g=analyze({'a.js':
  'const first={nested:{work(){return 1}, stop:()=>2}};const second=first;'+
  'function run(){second.nested.work();first.nested.stop()}'});
 assert.deepEqual(g.errors,[]);
 assert.equal(g.summary.candidateResolvedCalls,2);
 assert.equal(g.summary.unresolvedCallExpressions,0);
 assert.ok(g.callEdges.every(e=>e.kind==='module_object_method_candidate'));
 assert.equal(new Set(g.callEdges.map(e=>e.to.id)).size,2);
});

test('命名导入对象、命名空间嵌套对象及再次转导出可以跨文件追溯',()=>{
 const g=analyze({
  'impl.js':'export const client={session:{start(){return 1}}}',
  're-export.js':'export{client as gateway}from"./impl.js"',
  'use.js':'import{client}from"./impl.js";client.session.start()',
  'use2.js':'import * as ns from "./re-export.js";ns.gateway.session.start()',
  'use3.js':'import{gateway as api}from"./re-export.js";api.session.start()'
 });
 assert.deepEqual(g.errors,[]);
 assert.equal(g.summary.candidateResolvedCalls,3);
 assert.equal(g.summary.unresolvedCallExpressions,0);
 assert.equal(g.callEdges.filter(e=>e.kind==='imported_object_method_candidate').length,2);
 assert.equal(g.callEdges.filter(e=>e.kind==='namespace_object_member_candidate').length,1);
 assert.ok(g.callEdges.every(e=>e.to.file==='impl.js'));
 assert.equal(new Set(g.callEdges.map(e=>e.to.id)).size,1);
});

test('函数内对象别名、方法解构和不可变字符串索引可以解析',()=>{
 const g=analyze({'a.js':
  'function f(){const origin={start(){},child:{end(){}}};'+
  'const copy=origin;const {start:invoke}=copy;const key="end";'+
  'copy.child[key]();invoke()}'
 });
 assert.deepEqual(g.errors,[]);
 assert.equal(g.summary.candidateResolvedCalls,2);
 assert.equal(g.summary.unresolvedCallExpressions,0);
 assert.ok(g.callEdges.every(e=>e.kind==='lexical_object_method_candidate'||
  e.kind==='lexical_function_candidate'));
});

test('不信任对象动态键、展开覆盖、参数遮蔽和非 const 别名',()=>{
 const g=analyze({'a.js':
   'const obj={run(){}};const key="run";obj[key]();'+
   'function shadow(key){obj[key]()}'+
   'const extended={run(){},...unknown};extended.run();'+
   'let variable=obj;variable=unknown;variable.run();'+
   'const computed={run(){},[unknown](){}};computed.run()'
 });
 assert.deepEqual(g.errors,[]);
 assert.equal(g.summary.candidateResolvedCalls,1);
 assert.equal(g.summary.unresolvedCallExpressions,4);
 assert.equal(g.unresolvedCallsites.length,4);
 assert.equal(g.summary.unresolvedReasons.dynamic_property,1);
});

test('跨文件对象仅允许真正导出的成员，缺失导出不得猜测',()=>{
 const g=analyze({
   'api.js':'const privateApi={hidden(){}};export const publicApi={visible(){}}',
   'consumer.js':'import{privateApi,publicApi}from"./api.js";'+
     'privateApi.hidden();publicApi.visible();'
 });
 assert.equal(g.summary.candidateResolvedCalls,1);
 assert.equal(g.summary.unresolvedCallExpressions,1);
 assert.equal(g.unresolvedCallsites[0].callee,'privateApi.hidden');
});

test('对象简写函数成员和显式函数引用沿导出绑定定位实现',()=>{
 const g=analyze({
  'source.js':'export function ping(){};export const api={ping,alt:ping}',
  'use.js':'import{api,ping}from"./source.js";const local={go:ping};'+
    'api.ping();api.alt();local.go();'
 });
 assert.deepEqual(g.errors,[]);
 assert.equal(g.summary.candidateResolvedCalls,3);
 assert.equal(g.summary.unresolvedCallExpressions,0);
 assert.ok(g.callEdges.every(e=>e.to.file==='source.js'&&e.to.symbol==='ping'));
});
test('局部参数变量作为对象简写值不得误连同名模块函数',()=>{
 const g=analyze({'foo.js':
  'function cb(){};function run(cb){const api={cb,call:cb};api.cb();api.call()}'
 });
 assert.equal(g.summary.candidateResolvedCalls,0);
 assert.equal(g.summary.unresolvedCallExpressions,2);
});

test('真实压缩源常见 var/let 单写对象及别名可解析，属性写入必须失效',()=>{
 const g=analyze({'minified.js':
  'var a={send(){}};var b=a;let c={recv(){}};'+
  'a.send();b.send();c.recv();'+
  'var mutated={run(){}};mutated.run=()=>0;mutated.run();'+
  'var overwritten={go(){}};overwritten={};overwritten.go();'});
 assert.deepEqual(g.errors,[]);
 assert.equal(g.summary.candidateResolvedCalls,3);
 assert.equal(g.summary.unresolvedCallExpressions,2);
 assert.ok(g.callEdges.some(e=>e.kind==='module_object_method_candidate'));
 assert.equal(g.unresolvedCallsites.length,2);
});
test('函数内未重赋值 var 对象可解析，重赋值的同名 minified 变量不猜测',()=>{
 const g=analyze({'minified.js':
  'function f(){var obj={go(){}};obj.go()}'+
  'function g(){var obj={go(){}};obj={};obj.go()}'});
 // File-wide same-name conservative invalidation: both stay unresolved.
 assert.equal(g.summary.candidateResolvedCalls,0);
 assert.equal(g.summary.unresolvedCallExpressions,2);
});


test('top-level aliases inherit property mutations and never fabricate stale static dispatch',()=>{
 const g=analyze({'alias.js':
  'var original={run(){}}; var alias=original; var c=alias;'+
  'c.run=()=>42;original.run();alias.run();c.run();'+
  'var safe={go(){}};var other=safe;other.go()'});
 assert.deepEqual(g.errors,[]);
 assert.equal(g.summary.candidateResolvedCalls,1);
 assert.equal(g.summary.unresolvedCallExpressions,3);
 assert.equal(g.callEdges[0].to.symbol,'go');
});
test('top-level function declaration reassignment invalidates its static candidate',()=>{
 const g=analyze({'a.js':
   'function changed(){return 1}changed=()=>2;changed();'+
   'function stable(){return 1}stable()'});
 assert.deepEqual(g.errors,[]);
 assert.equal(g.summary.candidateResolvedCalls,1);
 assert.equal(g.summary.unresolvedCallExpressions,1);
 assert.equal(g.callEdges[0].to.symbol,'stable');
});


test('local object alias method rewrite invalidates only its enclosing function body',()=>{
 const g=analyze({'local.js':
   'function damaged(){var origin={go(){}};var b=origin;b.go=()=>0;origin.go();b.go()}'+
   'function untouched(){var origin={go(){}};origin.go()}'});
 assert.deepEqual(g.errors,[]);
 assert.equal(g.summary.candidateResolvedCalls,1);
 assert.equal(g.summary.unresolvedCallExpressions,2);
 assert.equal(g.callEdges[0].kind,'lexical_object_method_candidate');
});


test('uninitialized var declarations remain valid while alias safety analysis scans them',()=>{
 const g=analyze({'uninitialized.js':
  'var placeholder;var original={run(){}};var stable=original;stable.run();'+
  'function inner(){var other;var local={go(){}};local.go()}'});
 assert.deepEqual(g.errors,[]);
 assert.equal(g.summary.candidateResolvedCalls,2);
 assert.equal(g.summary.unresolvedCallExpressions,0);
});


test('reassigned exported function has no stale ESM live-binding candidate',()=>{
 const g=analyze({
  'api.js':'export function changed(){return 1};changed=()=>2;export function stable(){return 3}',
  'client.js':'import {changed,stable} from "./api.js";changed();stable()'
 });
 assert.deepEqual(g.errors,[]);
 assert.equal(g.summary.candidateResolvedCalls,1);
 assert.equal(g.summary.unresolvedCallExpressions,1);
 assert.equal(g.callEdges[0].to.symbol,'stable');
 assert.equal(g.unresolvedCallsites[0].reason,'import_without_proven_export');
});


test('Jelly triage Python contract executes with real unittest assertions',()=>{
 const cp=require('node:child_process');
 const r=cp.spawnSync('python3',['-m','unittest','discover','-s','tests',
   '-p','test_jelly_triage.py','-v'],{cwd:path.resolve(__dirname,'..'),
   encoding:'utf8',timeout:15000,maxBuffer:1024*1024});
 assert.equal(r.status,0,(r.error?.message||'')+'\n'+r.stdout+'\n'+r.stderr);
 assert.match(r.stderr,/Ran [3-9] tests?/);
});
