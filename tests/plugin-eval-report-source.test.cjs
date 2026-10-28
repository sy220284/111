'use strict';
const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.resolve(__dirname,'..');
const text = p => fs.readFileSync(path.join(root,p),'utf8');
const source = text('chunk-9da5g4tn.js');
const docs = text('resources/docs/plugin-authoring/plugin-eval.md');
const quickref = text('resources/docs/plugin-authoring/plugin-eval-quickref.md');
const begin = source.indexOf('function fe(e){');
const end = source.indexOf('function ze(e,s)',begin);
assert.ok(begin>=0 && end>begin,'2.1.310 plugin-eval aggregate audit function missing');
const checkAggregate = vm.runInNewContext(source.slice(begin,end) + ';fe', {
  XCt: values => values.length === 0 ? 0 : values.reduce((a,b)=>a+b,0)/values.length
});

test('插件 Eval 的用例格式、Mock、退出码和部分完成原因有具体证据', () => {
  for(const item of ['schema_version','grader','mocks/','cost_ceiling','auth_failed']) {
    assert.ok(docs.includes(item) || quickref.includes(item),item);
  }
  for(const flag of ['partialReason','scoreWithout','skippedPaidGraders','aggregates']) {
    assert.ok(source.includes(flag),'missing real report field: '+flag);
  }
});

function entry(score,runs=[score]) {
  return { arms:{ with:runs.map(x=>({score:x})),without:[] }, aggregates:{ score }};
}
function fixture(cases,overallScore) {
  return {cases,aggregates:{overallScore}};
}
test('原版 Eval 聚合校验器接受一致的每用例与全套平均结果',()=>{
  assert.equal(checkAggregate(fixture([entry(0.5,[0,1]),entry(1,[1,1])],0.75)), '');
  assert.equal(checkAggregate(fixture([],0)), '');
});
test('原版 Eval 校验器标记用例级和全套结果的数值不一致',()=>{
  assert.match(checkAggregate(fixture([entry(0.4,[0,1])],0.4)),/aggregates.*do not match/);
  assert.match(checkAggregate(fixture([entry(0.5,[0,1]),entry(1,[1,1])],0.9)),/aggregates.*do not match/);
});
test('原版 Eval 校验器保留精度阈值，避免微小误差误报',()=>{
  assert.equal(checkAggregate(fixture([entry(0.504,[0,1])],0.504)), '');
  assert.match(checkAggregate(fixture([entry(0.506,[0,1])],0.506)),/do not match/);
});

test('插件 Eval 报告并非完整执行器：仅认可已证实的函数入口与导出',()=>{
  assert.match(source,/export\{U as evalReportTitle,Ce as renderEvalReportFragment,ze as wrapReportDocument\}/);
  assert.ok(!source.includes('export{parseEvalCase'));
  assert.match(docs,/Exit codes/);
});
