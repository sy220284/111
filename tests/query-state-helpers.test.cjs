'use strict';
const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');
const source=fs.readFileSync(path.resolve(__dirname,'../chunk-tqjdehqd.js'),'utf8');

function fallbackFixture({reasonHasHint=false}={}){
 const from=source.indexOf('function describeQueryModelFallback(e, o, n, r)');
 const to=source.indexOf('async function Wf(e, o)',from);
 assert.ok(from>0&&to>from,'main query fallback functions missing');
 return vm.runInNewContext(source.slice(from,to)+';({Gf,Iu,describeQueryModelFallback,abbreviateModelFallbackReason})',{
  xNe:()=>reasonHasHint,
  d:value=>String(value),
  re:(value,max)=>value.slice(0,max)
 });
}
function cancellationFixture(){
 const from=source.indexOf('function getCancelledMainTurnMessageId(e)');
 const to=source.indexOf('async function* Lf(',from);
 assert.ok(from>0&&to>from,'main query interruption helpers missing');
 return vm.runInNewContext(source.slice(from,to)+';({sr,Ff,getCancelledMainTurnMessageId,findLatestTrailingUserMessageUuid})',{
  Fi:reason=>reason,
  hFe:()=>'remote-user-message',
  nQ:row=>row.type==='user'
 });
}

test('fallback 高负载、不可用与最后回退的文案符合原版分支',()=>{
 const f=fallbackFixture();
 assert.match(f.Gf('overloaded','a','b'),/high demand for a/);
 assert.match(f.Gf('server_error','a','b'),/high demand for a/);
 assert.match(f.Gf('model_not_found','a','b','404'),/not available \(404\)/);
 assert.match(f.Gf('permission_denied','a','b','private'),/not available/);
 assert.match(f.Gf('model_blocked','a','b','blocked'),/not available/);
 assert.match(f.Gf('last_resort','a','b','offline'),/could not be retried \(offline\)/);
 assert.equal(f.Gf('end_turn','a','b'),undefined);
});
test('model_not_found 已包含提示时避免附加错误明细',()=>{
 const f=fallbackFixture({reasonHasHint:true});
 assert.equal(f.Gf('model_not_found','old','new','hidden'),'Switched to new because old is not available');
});
test('错误明细截断到300字符并保留省略号、空值不生成括号',()=>{
 const f=fallbackFixture();
 assert.equal(f.Iu(undefined),'');
 assert.equal(f.Iu(''),'');
 assert.equal(f.Iu('short'),' (short)');
 const long=f.Iu('z'.repeat(401));
 assert.equal(long,' ('+'z'.repeat(300)+'…)');
 assert.equal(f.Iu,f.abbreviateModelFallbackReason);
 assert.equal(f.Gf,f.describeQueryModelFallback);
});
test('取消回合仅接受主线程 user-cancel 和 remote-cancel，不传播给子代理',()=>{
 const f=cancellationFixture();
 assert.equal(f.sr({agentId:'child',abortController:{signal:{reason:'user-cancel'}}}),undefined);
 assert.equal(f.sr({abortController:{signal:{reason:'user-cancel'}}}),'remote-user-message');
 assert.equal(f.sr({abortController:{signal:{reason:'remote-cancel'}}}),'remote-user-message');
 assert.equal(f.sr({abortController:{signal:{reason:'shutdown'}}}),undefined);
 assert.equal(f.sr({abortController:{signal:{reason:undefined}}}),undefined);
 assert.equal(f.sr,f.getCancelledMainTurnMessageId);
});
test('最近用户消息只从最后 assistant 行之后检索，缺失返回 null',()=>{
 const f=cancellationFixture();
 const rows=[
 {type:'user',uuid:'early'},
 {type:'assistant',uuid:'assistant'},
 {type:'user',uuid:'new'},
 {type:'user',uuid:'newest'}
 ];
 assert.equal(f.Ff(rows),'newest');
 assert.equal(f.Ff(rows.slice(0,2)),null);
 assert.equal(f.Ff([{type:'user',uuid:'single'}]),'single');
 assert.equal(f.Ff([]),null);
 assert.equal(f.Ff,f.findLatestTrailingUserMessageUuid);
});
test('完整主查询模块保留原短名别名及可读导出',()=>{
 for(const item of ['const sr=getCancelledMainTurnMessageId','const Ff=findLatestTrailingUserMessageUuid',
 'const Gf=describeQueryModelFallback','const Iu=abbreviateModelFallbackReason',
 'export { describeQueryModelFallback,abbreviateModelFallbackReason'])
 assert.ok(source.includes(item),item);
});
