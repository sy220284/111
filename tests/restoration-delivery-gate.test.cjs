'use strict';
const {test} = require('node:test');
const assert = require('node:assert/strict');
const {
  field, businessPath, classifyCodeChange, auditDelivery
} = require('../scripts/check-restoration-delivery.cjs');

const functionalBody = [
  '交付类别：功能恢复',
  '业务问题：实际 CLI 调用失败',
  '实际代码改动：src/chunk-demo.js 修复请求处理',
  '调用链：CLI -> 模型传输 -> 响应解析',
  '验证命令：node --test tests/model-transport.test.cjs',
  '验证结果：在真实源码实现上通过正常和取消分支',
  '验收级别：局部行为已验证',
  '未验收范围：线上 OAuth 尚未验证'
].join('\n');

const runtime = {path:'src/chunk-demo.js',
  before:'export function run() { return 1; }',
  after:'export function run() { return 2; }'};
const tests = ['tests/model-transport.test.cjs'];
const files = ['src/chunk-demo.js', ...tests];

function review(overrides={}) {
  return auditDelivery({
    title:'修复模型请求返回值',
    body:functionalBody,
    changed:files,
    sourceChanges:[runtime],
    ...overrides
  });
}
function hasError(report, part) {
  assert.equal(report.ok, false);
  assert.ok(report.errors.some(e => e.includes(part)), report.errors.join('\n'));
}

test('有效的业务修复必须包含运行态代码、对应测试及完整验收范围',()=>{
  const r=review();
  assert.equal(r.ok,true,r.errors.join('\n'));
  assert.equal(r.implementation.length,1);
  assert.equal(r.tests.length,1);
});

test('仅提交文档、函数台账与静态证据不能算恢复',()=>{
  const r=review({changed:['docs/research.md','resources/function-verification-ledger.json'],
    sourceChanges:[]});
  hasError(r,'缺少实际业务源码');
});

test('仅补测试与测试工具不构成业务恢复',()=>{
  const r=review({changed:['tests/model-transport.test.cjs','tools/analysis/code-graph.cjs'],
    sourceChanges:[]});
  hasError(r,'缺少实际业务源码');
});

test('真实代码没有对应测试的提交被拒绝',()=>{
  const r=review({changed:['src/chunk-demo.js']});
  hasError(r,'缺少对应测试');
});

test('注释与格式改动不构成业务修复',()=>{
  const r=review({sourceChanges:[{path:runtime.path,
    before:'function f(){return 1;}',
    after:'/* recovered */ function f() { return 1; }'}]});
  hasError(r,'只有注释/格式/标识符');
});

test('纯函数名/短符号改名不能冒充恢复',()=>{
  const r=review({sourceChanges:[{path:runtime.path,
    before:'function QMn(){return 1;}',
    after:'function readHookOrigin(){return 1;}'}]});
  hasError(r,'没有可识别的实际行为变更');
  assert.equal(classifyCodeChange({path:runtime.path,before:'let abc=1;',after:'let def=1;'}),'identifier_only');
});

test('改变实际执行逻辑与修复业务行为可识别',()=>{
  assert.equal(classifyCodeChange(runtime),'effective');
  assert.equal(classifyCodeChange({path:'src/chunk-demo.js',before:'function f(){return 1}',after:'function f(){return 2}'}),'effective');
  assert.equal(classifyCodeChange({path:'src/chunk-demo.js',before:'function f(){return 1}',after:'function f() { return 1; }'}),'cosmetic');
});

test('仅修改台账、证据、历史候选仍属非业务文件',()=>{
  for(const path of ['docs/a.md','resources/function-summary.json',
    'resources/function-verification-ledger.json',
    'tools/analysis/code-graph.cjs','.github/workflows/check.yml']) {
    assert.equal(businessPath(path),false,path);
  }
  assert.equal(businessPath('runtime/assets.mjs'),true);
  assert.equal(businessPath('src/plugin-testing-kit/index.js'),true);
  assert.equal(businessPath('resources/bunfs/asset.json'),true);
});

test('伪造的未检视代码差异不得略过',()=>{
  const r=review({sourceChanges:[]});
  hasError(r,'未完成实际差异分析');
});

test('缺少必填验收字段被拒绝',()=>{
  const r=review({body:functionalBody.replace('验证结果：在真实源码实现上通过正常和取消分支','验证结果：待填写')});
  hasError(r,'验证结果');
});

test('独立测试可提交，但必须明确不计入功能恢复',()=>{
  const report=auditDelivery({
    title:'补齐现有 MCP 业务链的测试',
    body:'交付类别：独立测试\n不计入功能恢复',
    changed:['tests/mcp-e2e.test.cjs'],sourceChanges:[]
  });
  assert.equal(report.ok,true,report.errors.join('\n'));
  hasError(auditDelivery({
    title:'补齐现有 MCP 业务链的测试',
    body:'交付类别：独立测试',
    changed:['tests/mcp-e2e.test.cjs'],sourceChanges:[]
  }),'不计入功能恢复');
});

test('治理配置可以单独提交，但不能假称完成恢复',()=>{
  const r=auditDelivery({
    title:'完善源码恢复提交门禁',
    body:'交付类别：治理配置\n不计入功能恢复',
    changed:['AGENTS.md','.github/workflows/restoration-delivery-gate.yml',
      'scripts/check-restoration-delivery.cjs','tests/restoration-delivery-gate.test.cjs'],
    sourceChanges:[]
  });
  assert.equal(r.ok,true,r.errors.join('\n'));
});

test('非恢复类别不能偷偷修改业务实现',()=>{
  const r=review({body:'交付类别：文档维护\n不计入功能恢复'});
  hasError(r,'实际修改业务源码');
});

test('字段缺少值或重复声明不视为有效证据',()=>{
  assert.equal(field('业务问题：第一\n业务问题：第二','业务问题'),'');
  assert.equal(field('业务问题：','业务问题'),'');
  assert.equal(field('- 业务问题：请求失败','业务问题'),'请求失败');
});

test('验收级别不得伪造或用无意义状态替代',()=>{
  const r=review({body:functionalBody.replace('验收级别：局部行为已验证','验收级别：全功能等价')});
  hasError(r,'验收级别只能为');
});
