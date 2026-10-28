'use strict';
const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');
const source=fs.readFileSync(path.resolve(__dirname,'../core/chunk-g936x3jq.js'),'utf8');
function fixture({runner}={}){
 const begin=source.indexOf('function resumeSubagentForNotification(e)');
 const until=source.indexOf('function an(e, k = Db)',begin);
 const reply=source.indexOf('function resumeSubagentForReply(e)',until);
 const after=source.indexOf('async function Qn(',reply);
 const meta=source.indexOf('function addJoinedAgentNoticeMetadata(e, k, h, b)');
 const exported=source.lastIndexOf('export {');
 assert.ok(begin>=0&&until>begin&&reply>until&&after>reply&&meta>after&&exported>meta);
 const inFlight=new Set(),calls=[],finish=[];
 class ResumeBusy extends Error{}
 const Qn=(args,mode,release)=>{
   calls.push({args,mode,release});
   return runner?runner(args,mode,()=>{finish.push(args.agentId);release();}):Promise.resolve(mode);
 };
 const vmSource=source.slice(begin,until)+source.slice(reply,after)+source.slice(meta,exported)+
 ';({nJ,gfo,fwr,it,Yn,resumeSubagentForNotification,resumeSubagentInline,resumeSubagentForReply,runSubagentResumeExclusively,addJoinedAgentNoticeMetadata})';
 const original=vm.runInNewContext(vmSource,{zn:()=>({resumesInFlight:inFlight}),Qn,rte:ResumeBusy});
 return {...original,inFlight,calls,finish,ResumeBusy};
}
function deferred(){let resolve,reject;const promise=new Promise((yes,no)=>{resolve=yes;reject=no;});return{promise,resolve,reject};}
test('三个子代理恢复入口保留不同 delivery 模式，返回原执行器结果',async()=>{
 const f=fixture();
 assert.equal(await f.nJ({agentId:'one'}),'notification');
 assert.equal(await f.gfo({agentId:'two'}),'inline');
 assert.equal(await f.fwr({agentId:'three'}),'reply');
 assert.deepEqual(f.calls.map(x=>x.mode),['notification','inline','reply']);
 assert.equal(f.inFlight.size,0);
});
test('同一 Agent 的并发恢复立即拒绝，不发生重复启动',async()=>{
 const wait=deferred();
 const f=fixture({runner:()=>wait.promise});
 const first=f.nJ({agentId:'same-agent'});
 assert.equal(f.inFlight.has('same-agent'),true);
 await assert.rejects(f.gfo({agentId:'same-agent'}),err=>err instanceof f.ResumeBusy);
 assert.equal(f.calls.length,1);
 wait.resolve('complete');
 assert.equal(await first,'complete');
 assert.equal(f.inFlight.size,0);
});
test('不同 Agent 并发可运行，各自持有并释放恢复占用',async()=>{
 const waiting=new Map();
 const f=fixture({runner:args=>{
  const d=deferred();waiting.set(args.agentId,d);return d.promise;
 }});
 const a=f.nJ({agentId:'A'});
 const b=f.fwr({agentId:'B'});
 assert.deepEqual([...f.inFlight].sort(),['A','B']);
 waiting.get('A').resolve('first');assert.equal(await a,'first');
 assert.deepEqual([...f.inFlight],['B']);
 waiting.get('B').resolve('second');assert.equal(await b,'second');
 assert.equal(f.inFlight.size,0);
});
test('底层恢复失败也释放标记，同一 Agent 可再次恢复',async()=>{
 let times=0;
 const f=fixture({runner:async()=>{if(++times===1)throw Error('resume failed');return 'retry success';}});
 await assert.rejects(f.nJ({agentId:'A'}),/resume failed/);
 assert.equal(f.inFlight.size,0);
 assert.equal(await f.nJ({agentId:'A'}),'retry success');
 assert.equal(f.calls.length,2);
});
test('底层回调主动释放时执行结束后的 finally 不误删新的占用',async()=>{
 const first=deferred();
 const f=fixture({runner:()=>first.promise});
 const pending=f.nJ({agentId:'A'});
 const release=f.calls[0].release;
 release();
 assert.equal(f.inFlight.has('A'),false);
 const next=f.gfo({agentId:'A'});
 assert.equal(f.calls.length,2);
 assert.equal(f.inFlight.has('A'),true);
 // The original callback only owns an idempotent delete, not an ownership epoch.
 first.resolve('old finished');await pending;
 assert.equal(f.inFlight.has('A'),false);
 f.calls[1].release();
 // The second promise also needs to resolve; the shared fixture returns the same settled promise.
 assert.equal(await next,'old finished');
});
test('通知元数据只在传入长度时合并，且不修改原结果对象',()=>{
 const f=fixture();
 const base={agentId:'agent-1',outputFile:'file'};
 assert.equal(f.Yn(undefined,true,[],base),base);
 const out=f.Yn([4,8],false,['run-1'],base);
 assert.equal(out.agentId,'agent-1');
 assert.deepEqual(Array.from(out.joinedNoticeLengths),[4,8]);
 assert.equal(out.joinedNoticeOwn,false);
 assert.deepEqual(Array.from(out.joinedNoticeRunIds),['run-1']);
 assert.equal(base.joinedNoticeLengths,undefined);
});
test('可读入口和旧短符号均映射到相同实际函数',()=>{
 const f=fixture();
 for(const [short,readable] of [
 ['nJ','resumeSubagentForNotification'],['gfo','resumeSubagentInline'],
 ['fwr','resumeSubagentForReply'],['it','runSubagentResumeExclusively'],['Yn','addJoinedAgentNoticeMetadata']
 ])assert.equal(f[short],f[readable]);
});
