'use strict';
const {test}=require('node:test');
const assert=require('node:assert/strict');

test('四个可读 Hook 函数与原版旧导出是同一函数',async()=>{
 const api=await import('../src/chunk-1nnn2mdy.js');
 for(const [oldName,newName] of [['QMn','parseHookSourceDescriptor'],['ZMn','parsePluginHookIdentity'],['jhs','resolvePreToolUseRegistration'],['Whs','parseToolPermissionEvent']]){
   assert.equal(api[oldName],api[newName]);
 }
});

test('Hook 来源、插件标识保留原版格式与边界',async()=>{
 const {parseHookSourceDescriptor:source,parsePluginHookIdentity:plugin}=await import('../src/chunk-1nnn2mdy.js');
 for(const value of ['settings','plugin','skill','plugin:example','skill:foo-1_2'])assert.equal(source(value),value);
 for(const value of [null,22,'settings:x','plugin:','skill:bad/path',' plugin','plugin:bad name','plugin:'+'a'.repeat(65)])assert.equal(source(value),undefined);
 assert.equal(source('plugin:'+'a'.repeat(64)),'plugin:'+'a'.repeat(64));
 assert.equal(plugin('one@two'),'one@two');
 for(const value of [null,[],undefined,'one@','@two','one@bad value','a'.repeat(65)+'@ok'])assert.equal(plugin(value),undefined);
});
test('PreToolUse 权限匹配不发生工具串扰',async()=>{
 const {resolvePreToolUseRegistration:resolve,parseToolPermissionEvent:parse}=await import('../src/chunk-1nnn2mdy.js');
 assert.equal(resolve('PreToolUse:Bash','Bash'),'PreToolUse');
 assert.equal(resolve('PreToolUse:Read','Bash'),undefined);
 assert.equal(resolve('tool.check','Bash'),'tool.check');
 assert.equal(resolve('PostToolUse:Bash','Bash'),undefined);
 assert.equal(parse('PreToolUse'),'PreToolUse');
 assert.equal(parse('tool.check'),'tool.check');
 assert.equal(parse('PermissionRequest'),undefined);
 assert.equal(parse(null),undefined);
});
