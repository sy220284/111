'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.resolve(__dirname,'../src/chunk-tqjdehqd.js'),'utf8');
const migration = fs.readFileSync(path.resolve(__dirname,'../resources/docs/claude-api/model-migration.md'),'utf8');
const thinking = fs.readFileSync(path.resolve(__dirname,'../resources/docs/migration/preserved-thinking-migration.md'),'utf8');
function fragment(start, end) {
  const a = source.indexOf(start), b = source.indexOf(end,a);
  assert.ok(a >= 0 && b > a, 'missing actual source fragment ' + start);
  return source.slice(a,b);
}
function recovery(overrides={}) {
  const body=fragment('function Au(e)', 'async function* pN(e)');
  const context={
    Jo: x=>x,
    k:()=>true,
    Xke: x=>x.isServiceEvent===true,
    Yle: x=>x.type==='thinking',
    SVn: x=>x.resumable===true,
    QMe:()=>false,
    $n:()=>32,
    X0s:()=>true,
    WO:usage=>usage,
    ...overrides
  };
  return vm.runInNewContext(body + ';({Au,Uf,Nf,qf})',context);
}
function queueGate(overrides={}) {
  const body=fragment('function Gr(e)', 'export { describeQueryModelFallback,');
  return vm.runInNewContext(body+';({Gr,Pu,Qf})',{N7t:(under, current)=>under===current,...overrides});
}

test('真实 2.1.310 queryLoop 的模型恢复条件可追溯至完整源码',()=>{
  assert.match(source,/async function\* pN\(e\)/);
  assert.match(source,/async function\* Du\(\)/);
  assert.match(source,/resumeIncompleteThinking/);
  assert.match(thinking,/thinking/);
  assert.match(migration,/max_tokens/);
});

test('max_output_tokens 错误须明确属于 assistant，不会被普通用户消息误识别',()=>{
 const {Au}=recovery();
 assert.equal(Au({type:'assistant',apiError:'max_output_tokens'}),true);
 assert.equal(Au({type:'assistant',apiError:'rate_limit'}),false);
 assert.equal(Au({type:'user',apiError:'max_output_tokens'}),false);
 assert.equal(Au(undefined),false);
});

test('Truncated 续跑仅在主线程无交互模式或子代理且开关开启时激活',()=>{
 const {Uf}=recovery();
 const truncated={type:'assistant',isApiErrorMessage:true,truncatedAfterOutput:true};
 assert.equal(Uf(truncated,{options:{isNonInteractiveSession:true}},'main'),true);
 assert.equal(Uf(truncated,{options:{isNonInteractiveSession:false}},'main'),false);
 assert.equal(Uf(truncated,{options:{isNonInteractiveSession:false}},'subagent'),true);
 assert.equal(Uf({...truncated,isApiErrorMessage:false},{options:{isNonInteractiveSession:true}},'main'),false);
 const {Uf:off}=recovery({k:()=>false});
 assert.equal(off(truncated,{options:{isNonInteractiveSession:true}},'main'),false);
});

test('Thinking 未完成恢复只接受唯一可信消息、可续块及 max_tokens，过滤 API 错误',()=>{
 const {Nf}=recovery();
 const good={type:'assistant',message:{content:[{type:'thinking',resumable:true}],stop_reason:'max_tokens',usage:{output_tokens:10}}};
 assert.equal(Nf([good],{}),true);
 assert.equal(Nf([{isApiErrorMessage:true},good],{}),true);
 assert.equal(Nf([good,good],{}),false);
 assert.equal(Nf([{...good,message:{...good.message,stop_reason:'end_turn'}}],{}),false);
 assert.equal(Nf([{...good,message:{...good.message,content:[{type:'text'}]}}],{}),false);
 assert.equal(Nf([{...good,message:{...good.message,content:[{type:'thinking',resumable:true},{type:'text'}]}}],{}),false);
 const {Nf:quota}=recovery({X0s:()=>false});
 assert.equal(quota([good],{}),false);
 const {Nf:already}=recovery({QMe:()=>true});
 assert.equal(already([good],{}),false);
});

test('暂停恢复时的提示语合并尊重顺序并过滤空输入',()=>{
 const {qf}=recovery();
 assert.equal(qf('first','second'),'first\n\nsecond');
 assert.equal(qf(['first','second'],'third'),'first\n\nsecond\n\nthird');
 assert.equal(qf('',undefined),undefined);
 assert.equal(qf(undefined,'last'),'last');
});

test('最后一个交互式用户输入跳过工具结果、元消息与压缩摘要',()=>{
 const {Gr}=queueGate();
 const messages=[
  {type:'user',isMeta:false},
  {type:'assistant'},
  {type:'user',toolUseResult:'done'},
  {type:'user',isCompactSummary:true},
  {type:'user',isMeta:true},
  {type:'user'}
 ];
 assert.equal(Gr(messages),5);
 assert.equal(Gr(messages.slice(0,5)),0);
 assert.equal(Gr([{type:'assistant'}]),0);
});

test('Queued prompt 闩锁阻止重复终止条件，允许其他不冲突命令',()=>{
 const {Pu,Qf}=queueGate();
 const context='session';
 assert.equal(Qf({drainOnly:true},()=>context),true);
 assert.equal(Qf({screeningPending:true},()=>context),true);
 assert.equal(Qf({promptSubmitted:{under:'session'}},()=>context),true);
 assert.equal(Qf({promptSubmitted:{under:'other'}},()=>context),false);
 assert.equal(Qf({promptSubmitted:{under:'other'}},()=>undefined),true);
 const filter=Pu(()=>context);
 assert.equal(filter({mode:'note'}),true);
 assert.equal(filter({mode:'prompt',promptSubmitted:{under:'other'}}),true);
 assert.equal(filter({mode:'prompt',promptSubmitted:{under:'session'}}),false);
 assert.equal(filter({mode:'prompt',promptSubmitted:{under:'other'}}),false);
 assert.equal(filter({mode:'note'}),true);
});

test('回合终止时对未完成 tool_use 生成失败结果，但跳过已完成调用',()=>{
 const fragmentCode=fragment('function* vu(e, o, n, r)', 'function ho(e, o)');
 const {vu}=vm.runInNewContext(fragmentCode+';({vu})',{Oe:x=>x});
 const messages=[{uuid:'assist-1',message:{content:[
  {type:'text',text:'text'},
  {type:'tool_use',id:'toolu_1'},
  {type:'tool_use',id:'toolu_2'}
 ]}}];
 const out=Array.from(vu(messages,'interrupted',{now:()=>0,uuid:()=> 'new'},new Set(['toolu_2'])));
 assert.equal(out.length,1);
 assert.equal(out[0].content[0].type,'tool_result');
 assert.equal(out[0].content[0].tool_use_id,'toolu_1');
 assert.equal(out[0].content[0].is_error,true);
 assert.equal(out[0].sourceToolAssistantUUID,'assist-1');
});
