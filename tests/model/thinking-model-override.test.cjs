'use strict';
const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');
const source=fs.readFileSync(path.resolve(__dirname,'../../src/chunk-x3dkgs4r.js'),'utf8');
const reference=fs.readFileSync(path.resolve(__dirname,'../../resources/docs/migration/preserved-thinking-migration.md'),'utf8');
function fixture({allowSwitch=false}={}){
  const start=source.indexOf('function Sr(e, n)');
  const end=source.indexOf('var kr =',start);
  assert.ok(start>0&&end>start,'2.1.310 original thinking/model override function missing');
  const advisories=[];
  const {Sr}=vm.runInNewContext(source.slice(start,end)+';({Sr})',{
    bwr:()=>allowSwitch,
    tr:m=>advisories.push(m),
    Js:(requested,current)=>requested??current
  });
  const context={
    args:{options:{model:'claude-model-A',resumeIncompleteThinking:true,otherOption:42},messages:[]},
    sent:{effort:'medium'},
    state:{isResumeKeptTold:false}
  };
  return {Sr,context,advisories};
}
test('Preserved Thinking 文档要求保持之前的推理状态与请求约束一致',()=>{
  assert.match(reference,/thinking/i);
  assert.match(reference,/signature|invalid|model/i);
  assert.match(source,/resumeIncompleteThinking/);
});
test('原版模型切换在尚未安全续接 Thinking 时保持现有模型',()=>{
  const f=fixture();
  const result=f.Sr(f.context,{model:'claude-model-B',effort:'medium'});
  assert.equal(result.model,'claude-model-A');
  assert.equal(result.otherOption,42);
  assert.equal(result.resumeIncompleteThinking,true);
  assert.deepEqual(f.advisories,['claude-model-A']);
  assert.equal(f.context.state.isResumeKeptTold,true);
});
test('多次模型切换建议只通知一次，不重复强制丢弃 Thinking',()=>{
  const f=fixture();
  f.Sr(f.context,{model:'claude-model-B'});
  const second=f.Sr(f.context,{model:'claude-model-C'});
  assert.equal(second.model,'claude-model-A');
  assert.equal(f.advisories.length,1);
});
test('经原版消息检查允许切换时更新模型且不发 Thinking 保护提示',()=>{
  const f=fixture({allowSwitch:true});
  const result=f.Sr(f.context,{model:'claude-model-B',effort:'high'});
  assert.equal(result.model,'claude-model-B');
  assert.equal(result.hookEffortValue,'high');
  assert.equal(f.advisories.length,0);
  assert.equal(f.context.state.isResumeKeptTold,false);
});
test('没有续接要求或模型未变化时维持正常设置逻辑',()=>{
  const f=fixture();
  f.context.args.options.resumeIncompleteThinking=false;
  assert.equal(f.Sr(f.context,{model:'claude-model-B'}).model,'claude-model-B');
  assert.deepEqual(f.advisories,[]);
  f.context.args.options.resumeIncompleteThinking=true;
  assert.equal(f.Sr(f.context,{model:'claude-model-A'}).model,'claude-model-A');
  assert.equal(f.advisories.length,0);
});
test('Hook 修改 effort 才覆盖设置，省略 effort 不额外创建字段',()=>{
  const f=fixture({allowSwitch:true});
  const same=f.Sr(f.context,{model:'claude-model-A',effort:'medium'});
  assert.equal(Object.hasOwn(same,'hookEffortValue'),false);
  const missing=f.Sr(f.context,{model:'claude-model-A'});
  assert.equal(Object.hasOwn(missing,'hookEffortValue'),false);
  const changed=f.Sr(f.context,{model:'claude-model-A',effort:'low'});
  assert.equal(changed.hookEffortValue,'low');
});
