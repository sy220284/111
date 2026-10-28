'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const path=require('node:path');
const {analyze,impact,trace,inputFiles}=require('../tools/analysis/code-graph.cjs');
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
  const root=path.resolve(__dirname,'..');
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
