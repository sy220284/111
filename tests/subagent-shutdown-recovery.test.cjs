'use strict';
const {test}=require('node:test');
const assert=require('node:assert/strict');
const vm=require('node:vm');
const fs=require('node:fs');
const path=require('node:path');
const source=fs.readFileSync(path.resolve(__dirname,'../src/chunk-g936x3jq.js'),'utf8');
function dispatch(){
 const start=source.indexOf('function isSubagentDispatchToolUse(e)');
 const end=source.indexOf('async function resumeSubagentWithInheritedHookOrigin(',start);
 assert.ok(start>=0&&end>start,'subagent dispatch functions missing');
 return vm.runInNewContext(source.slice(start,end)+
 ';({mfo,en,isSubagentDispatchToolUse,hasMatchedSubagentResult})',{
   ht:'Task',cf:'Agent',
   ddn:type=>typeof type==='string'&&['Explore','general-purpose'].includes(type)
 });
}
function interrupted(){
 const start=source.indexOf('function normalizeShutdownInterruptedAgentResult(e, k)');
 const end=source.indexOf('function addJoinedAgentNoticeMetadata(e, k, h, b)',start);
 assert.ok(start>=0&&end>start,'shutdown normalization function missing');
 return vm.runInNewContext(source.slice(start,end)+
 ';({Un,normalizeShutdownInterruptedAgentResult})',{
   Xue:row=>row.hasToolError===true,
   vm:row=>row.hasPlainError===true,
   Gx:'Interrupted before tool completion',
   a3o:'Agent stopped while processing your request'
 });
}
test('Task/Agent 子代理调用必须具有受支持的 subagent_type',()=>{
 const api=dispatch();
 for(const name of ['Task','Agent'])
  assert.equal(api.mfo({name,input:{subagent_type:'Explore'}}),true);
 for(const request of [
   {name:'Bash',input:{subagent_type:'Explore'}},
   {name:'Task',input:{subagent_type:'NotFound'}},
   {name:'Task',input:null},
   {name:'Task',input:'Explore'},
   {name:'Task',input:{}}
 ])assert.equal(api.mfo(request),false);
 assert.equal(api.mfo,api.isSubagentDispatchToolUse);
});
test('子代理 tool_result 仅在 ID 与 Agent 身份均关联时认定已完成',()=>{
 const api=dispatch();
 const messages=[
  {type:'assistant',message:{content:[{type:'tool_use',id:'toolu_1',name:'Task',input:{subagent_type:'Explore'}}]}},
  {type:'user',toolUseResult:{agentId:'agent-7'},message:{content:[{type:'tool_result',tool_use_id:'toolu_1',content:'done'}]}}
 ];
 assert.equal(api.en(messages,'agent-7'),true);
 assert.equal(api.en(messages,'agent-8'),false);
 assert.equal(api.en(messages.slice(0,1),'agent-7'),false);
 const other=messages.slice(0,1).concat([{type:'user',toolUseResult:{agentId:'agent-7'},message:{content:[{type:'tool_result',tool_use_id:'toolu_9'}]}}]);
 assert.equal(api.en(other,'agent-7'),false);
 assert.equal(api.en,api.hasMatchedSubagentResult);
});
test('普通 user 文本或没有工具返回标识的行不得冒充已完成 Agent',()=>{
 const api=dispatch();
 const messages=[
  {type:'assistant',message:{content:[{type:'tool_use',id:'toolu_1',name:'Task',input:{subagent_type:'Explore'}}]}},
  {type:'user',message:{content:[{type:'tool_result',tool_use_id:'toolu_1'}]}},
  {type:'user',toolUseResult:{agentId:'agent-7'},message:{content:'plain string'}}
 ];
 assert.equal(api.en(messages,'agent-7'),false);
});
test('Agent ID、行类型和中断标记不匹配时保留原消息身份',()=>{
 const api=interrupted();
 const row={type:'user',agentId:'agent-7',interruptedByShutdown:true,message:{content:[{type:'tool_result',tool_use_id:'id1',content:'old'}]}};
 assert.equal(api.Un(row,'agent-8'),row);
 assert.equal(api.Un({...row,interruptedByShutdown:false},'agent-7').interruptedByShutdown,false);
 assert.equal(api.Un({...row,type:'assistant'},'agent-7').type,'assistant');
 assert.equal(api.Un({...row,message:{content:'text'}},'agent-7').message.content,'text');
 assert.equal(api.Un(row,'agent-7'),row);
});
test('关机中断的普通错误消息恢复成明确文本行，不扩大到其他 Agent',()=>{
 const api=interrupted();
 const row={type:'user',agentId:'agent-7',interruptedByShutdown:true,
   hasPlainError:true,message:{content:[{type:'tool_result',tool_use_id:'id1'}]}};
 const restored=api.Un(row,'agent-7');
 assert.deepEqual(Array.from(restored.message.content).map(x=>({type:x.type,text:x.text})),
 [{type:'text',text:'Agent stopped while processing your request'}]);
 assert.equal(restored.agentId,'agent-7');
 assert.equal(row.message.content[0].type,'tool_result');
});
test('关机中断的工具失败保留 tool_use_id 并替换结果内容，清理拒绝状态',()=>{
 const api=interrupted();
 const row={type:'user',agentId:'agent-7',interruptedByShutdown:true,hasToolError:true,
 toolDenialKind:'unknown',message:{content:[
 {type:'tool_result',tool_use_id:'toolu_1',content:'old'},
 {type:'text',text:'context'},
 {type:'tool_result',tool_use_id:'toolu_2',content:'old2'}
 ]}};
 const restored=api.Un(row,'agent-7');
 assert.equal(restored.toolUseResult,'Interrupted before tool completion');
 assert.equal(restored.toolDenialKind,undefined);
 assert.deepEqual(Array.from(restored.message.content).filter(x=>x.type==='tool_result').map(x=>[x.tool_use_id,x.content]),
 [['toolu_1','Interrupted before tool completion'],['toolu_2','Interrupted before tool completion']]);
 assert.equal(restored.message.content[1].text,'context');
 assert.equal(row.message.content[0].content,'old');
 assert.equal(api.Un,api.normalizeShutdownInterruptedAgentResult);
});
test('Agent 恢复业务命名与原版短符号均保留同一实际函数',()=>{
 for(const alias of ['const mfo=isSubagentDispatchToolUse',
 'const en=hasMatchedSubagentResult','const Un=normalizeShutdownInterruptedAgentResult'])
 assert.ok(source.includes(alias),alias);
 const exported=source.slice(source.lastIndexOf('export {'));
 for(const name of ['isSubagentDispatchToolUse','hasMatchedSubagentResult','normalizeShutdownInterruptedAgentResult'])
  assert.ok(exported.includes(name),name+' must remain exported');
});
