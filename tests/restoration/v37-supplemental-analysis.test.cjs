'use strict';
const test=require('node:test'),assert=require('node:assert/strict');
const fs=require('node:fs'),path=require('node:path');
const {build,kind,parse,shaBlob}=require('../../scripts/audit-v37-supplemental.cjs');
const root=path.resolve(__dirname,'../..');
const ledger=JSON.parse(fs.readFileSync(path.join(root,'resources/research/v37-supplemental-semantics.json'),'utf8'));
test('scope distinguishes independent bundled sources from main program modules',()=>{
 assert.equal(kind('plugin-testing-kit/index.js'),'plugin_test_kit');
 assert.equal(kind('embedded/runtime/state-library.js'),'embedded_source');
 assert.equal(kind('embedded/browser-tools/plugin.json'),'embedded_non_code_asset');
 assert.throws(()=>kind('src/chunk-example.js'),/out of scope/);
});
test('AST evidence works for exported arrow functions, named functions and TSX',()=>{
 const a=parse('plugin-testing-kit/a.js',Buffer.from('export const v=(x)=>x;export function f(){}'));
 assert.deepEqual(a.functions.map(f=>f.name),['v','f']);
 assert.deepEqual(a.exports,['f','v']);
 const jsx=parse('embedded/components/a.tsx',Buffer.from('export function Card(){return <b>Hello</b>}'));
 assert.equal(jsx.functions[0].name,'Card');
 assert.equal(shaBlob(Buffer.from('test')),'30d74d258442c7c65512eafab474568dd706c430');
});
test('full supplemental source AST and ledger preserve independent functional boundaries',()=>{
 const result=build({root,semantics:ledger.entries,verify:false});
 assert.deepEqual(result.problems,[]);
 assert.equal(result.metrics.kitModules,294);
 assert.equal(result.metrics.embeddedEntities,24);
 assert.equal(result.metrics.totalSourceEntities,318);
 assert.equal(result.metrics.graphModules,295);
 assert.equal(result.metrics.semanticTestRecords,17);
 assert.equal(result.metrics.selfReferentialBarrels,190);
 assert.equal(result.metrics.otherSelfCycles,0);
 assert.equal(result.metrics.multiModuleCycles,0);
 assert.ok(result.metrics.syntaxFunctions>100);
 assert.ok(result.metrics.moduleDependencies>300);
 assert.ok(result.semanticEvidence.every(x=>!x.provesMainCliBehavior));
 assert.ok(result.files.find(x=>x.file==='embedded/runtime/state-library.js')?.functions.length>7);
});
test('invented functions or missing tests cannot count as confirmed supplemental semantic evidence',()=>{
 const fake=[...ledger.entries,{file:'embedded/runtime/state-library.js',symbol:'definitelyDoesNotExist',
  role:'fabricated',behavior:'not tested',test:'tests/not-a-real-test.cjs'}];
 const result=build({root,semantics:fake,verify:false});
 assert.ok(result.problems.some(x=>x.includes('no real function declaration')));
 assert.ok(result.problems.some(x=>x.includes('missing behavior test')));
});
