'use strict';
const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.resolve(__dirname,'..');
const code = p => fs.readFileSync(path.join(root,p),'utf8');
const query=code('core/chunk-tqjdehqd.js');
const refs=code('resources/docs/claude-api/error-codes.md');
const model=code('resources/docs/claude-api/model-migration.md');
function recoveredRefusal() {
  const from=query.indexOf('function td({ refused: e, kept: o, answered: n, started: r');
  const to=query.indexOf('function od({ sentOn: e',from);
  assert.ok(from>0 && to>from, '2.1.310 refusal cleanup function present');
  return vm.runInNewContext(query.slice(from,to)+';td', {
    a:()=>{},wt:v=>v,_e:v=>v,ce:v=>v,Tye:v=>v,gs:v=>v,l:()=>{},
    kk:(message)=>message,Qo:m=>m,u3o:'interrupted',GYr:'not-run',Fm:'not run',Lm:'interrupted'
  });
}
test('模型错误文档支持 refusal、请求校验及可重试服务错误的分类',()=>{
  for(const code of ['400','401','403','404','429','500','529'])assert.ok(refs.includes(code),code);
  assert.match(model,/refusal/);
});
test('真实拒绝恢复函数为未执行与中断中工具分别补上失败结果，而不重复已回答的工具',()=>{
  const td=recoveredRefusal();
  const toMessage=payload=>payload;
  const response=td({
    refused:{requestId:'request-1',refusedMessageId:'msg-1',message:{stop_details:{category:'safety'}}},
    kept:[{uuid:'assistant-1',message:{content:[
      {type:'tool_use',id:'toolu_started'},
      {type:'tool_use',id:'toolu_unstarted'},
      {type:'tool_use',id:'toolu_answered'}
    ]}}],
    answered:[{type:'user',message:{content:[{type:'tool_result',tool_use_id:'toolu_answered'}]}}],
    started:['toolu_started'],
    model:'test-model',querySource:'query',queryChainId:'chain',queryDepth:0,
    deps:{now:()=>0,uuid:()=> 'uuid'},makeUserMessage:toMessage
  });
  assert.equal(response.notRun.length,2);
  const started=response.notRun.find(x=>x.content[0].tool_use_id==='toolu_started');
  const notStarted=response.notRun.find(x=>x.content[0].tool_use_id==='toolu_unstarted');
  assert.equal(started.toolDenialKind,'interrupted');
  assert.equal(notStarted.toolDenialKind,'cancelled');
  assert.equal(started.content[0].is_error,true);
  assert.equal(notStarted.content[0].is_error,true);
  assert.equal(response.notRun.some(x=>x.content[0].tool_use_id==='toolu_answered'),false);
  assert.equal(response.nudge.refusedMessageId,'msg-1');
  assert.equal(response.nudge.turnCompanion,true);
});
test('没有工具需要补录时拒绝恢复函数不额外捏造 tool_result',()=>{
 const td=recoveredRefusal();
 const result=td({
   refused:{requestId:'request-2',message:{}},
   kept:[{uuid:'a',message:{content:[{type:'text',text:'refused'}]}}],
   answered:[],started:[],model:'model',querySource:'main',
   queryChainId:'id',queryDepth:1,
   deps:{now:()=>0,uuid:()=>''},makeUserMessage:p=>p
 });
 assert.equal(result.notRun.length,0);
 assert.equal(result.nudge.turnCompanion,true);
});
