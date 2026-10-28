'use strict';
const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');
// Execute the real extracted 2.1.310 hook functions with controlled imports.
// This does not claim full plugin/worker runtime coverage.
const source=fs.readFileSync(path.resolve(__dirname,'../core/chunk-y5vkfxm0.js'),'utf8');
const start=source.indexOf('function createHookEventMetadata(');
const end=source.indexOf('\nexport{',start);
assert.ok(start>0 && end>start,'real hook function bodies must be present');
const body=source.slice(start,end);
function load(deps={}) {
 return vm.runInNewContext(body+';({createHookEventMetadata,groupHooksByEventAndMatcher,listEventMatchersSorted,getHooksForMatcher})',{
 Odi:['permission_prompt','idle_prompt','auth_success','elicitation_dialog'],
 Ud:['PreToolUse','PostToolUse','Notification','StopFailure'],
 Ddi:['auto','manual'],c4t:()=>false,
 Tjs:()=>[],CC:()=>null,Ajs:(a)=>a.sort(),
 ...deps,
 });
}
test('新版 Hook 事件与安全/权限元数据',()=>{
 const {createHookEventMetadata}=load();
 const meta=createHookEventMetadata(['Read','Bash']);
 for(const kind of ['PreToolUse','PostToolUse','PostToolUseFailure','PostToolBatch','PermissionDenied','Notification','StopFailure']){
 assert.ok(meta[kind], 'missing event '+kind);
 assert.ok(meta[kind].description.length>20);
 }
 assert.equal(meta.PreToolUse.matcherMetadata.fieldToMatch,'tool_name');
 assert.deepEqual(Array.from(meta.PreToolUse.matcherMetadata.values),['Read','Bash']);
 assert.ok(meta.PermissionDenied.description.includes('retry'));
 assert.ok(meta.Notification.matcherMetadata.values.includes('elicitation_response'));
});
test('用户与插件 Hook 的合并、分组与查询',()=>{
 const local={event:'PreToolUse',matcher:'Bash',config:{type:'command',command:'local'}};
 const plugin={type:'command',command:'plugin'};
 const {groupHooksByEventAndMatcher,listEventMatchersSorted,getHooksForMatcher}=load({
 Tjs:()=>[local],
 CC:()=>({PreToolUse:[{pluginRoot:'/test',pluginId:'example',matcher:'Bash',hooks:[plugin]}]})
 });
 const grouped=groupHooksByEventAndMatcher({},['Read','Bash']);
 const result=getHooksForMatcher(grouped,'PreToolUse','Bash');
 assert.equal(result.length,2);
 assert.equal(result[0],local);
 assert.equal(result[1].source,'pluginHook');
 assert.equal(result[1].pluginName,'example');
 assert.equal(result[1].config,plugin);
 assert.deepEqual(Array.from(listEventMatchersSorted(grouped,'PreToolUse')),['Bash']);
 assert.equal(getHooksForMatcher(grouped,'PreToolUse','Read').length,0);
});
test('缺失 Hook 事件匹配返回空数组',()=>{
 const {getHooksForMatcher}=load();
 assert.deepEqual(Array.from(getHooksForMatcher({},'Missing')),[]);
 assert.deepEqual(Array.from(getHooksForMatcher({StopFailure:{'':[1]}},'StopFailure')), [1]);
});
