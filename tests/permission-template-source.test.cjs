'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.resolve(__dirname, '../core/chunk-eq8vmbm3.js'), 'utf8');
const reference = fs.readFileSync(path.resolve(__dirname, '../resources/docs/other/permissions-external.txt'), 'utf8');

function registryFixture() {
  const from=source.indexOf('let externalTemplates;'), to=source.indexOf('export {',from);
  assert.ok(from>=0&&to>from,'missing original registry module source');
  return vm.runInNewContext(source.slice(from,to)+
    ';({externalTemplateRegistry,listExternalTemplateIds,findExternalTemplateByDigest,findExternalTemplateById,isPreToolUseTemplateEvent,externalTemplateConstantFlag})');
}
test('外部权限资料与原版 Hook 模块均包含 PreToolUse 边界', () => {
  assert.match(reference,/permission|tool|approval/i);
  assert.match(source,/PreToolUse/);
});
test('外部模板仓库初始为空，重复读取复用同一个注册表', () => {
  const api=registryFixture(), list=api.externalTemplateRegistry();
  assert.equal(list,api.externalTemplateRegistry());
  assert.equal(Array.from(api.listExternalTemplateIds()).length,0);
  assert.equal(api.findExternalTemplateById('absent'),undefined);
  assert.equal(api.findExternalTemplateByDigest('missing'),undefined);
});
test('按 ID 和哈希选择原始登记对象及匹配的标签，不复制模板', () => {
  const api=registryFixture();
  const a={id:'template-a',digests:[{sha256:'hash-1',label:'first'},{sha256:'hash-2',label:'second'}]};
  const b={id:'template-b',digests:[{sha256:'hash-1',label:'later'}]};
  api.externalTemplateRegistry().push(a,b);
  assert.deepEqual(Array.from(api.listExternalTemplateIds()),['template-a','template-b']);
  assert.equal(api.findExternalTemplateById('template-a'),a);
  const found=api.findExternalTemplateByDigest('hash-1');
  assert.equal(found.template,a);
  assert.equal(found.label,'first');
  assert.equal(api.findExternalTemplateByDigest('hash-2').label,'second');
  assert.equal(api.findExternalTemplateByDigest('hash-other'),undefined);
});
test('PreToolUse 大小写和其他 Hook 不混淆，原版恒真分支保持原样',()=>{
  const api=registryFixture();
  assert.equal(api.isPreToolUseTemplateEvent({event:'PreToolUse'}),true);
  assert.equal(api.isPreToolUseTemplateEvent({event:'PostToolUse'}),false);
  assert.equal(api.isPreToolUseTemplateEvent({event:'pretooluse'}),false);
  assert.equal(api.externalTemplateConstantFlag(),true);
});
test('新增可读函数与旧短符号导出保持引用同一性',async()=>{
  const api=await import('../core/chunk-eq8vmbm3.js');
  for(const [oldName,newName] of [
    ['Vds','listExternalTemplateIds'],
    ['qds','findExternalTemplateByDigest'],
    ['Oat','findExternalTemplateById'],
    ['Nxn','isPreToolUseTemplateEvent'],
    ['$xn','externalTemplateConstantFlag']
  ])assert.equal(api[oldName],api[newName],oldName);
});
