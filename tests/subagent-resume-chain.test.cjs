'use strict';
const { test }=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');
const source=fs.readFileSync(path.resolve(__dirname,'../src/chunk-g936x3jq.js'),'utf8');

function originalResumeHarness({storedOrigin,interrupt=false,starting,fail=false}={}){
 const from=source.indexOf('async function resumeSubagentWithInheritedHookOrigin(');
 const until=source.indexOf('var qn =',from);
 assert.ok(from>=0&&until>from,'subagent resume and transcript validation source missing');
 const launches=[],disposals=[],inert=Symbol('no-active-resource');
 class AgentResumeInterrupted extends Error{}
 const funcs=vm.runInNewContext(source.slice(from,until)+
 ';({dwr,cwr,sn,resumeSubagentWithInheritedHookOrigin,formatPluginResumeDenial,hasCoherentResumedSubagentTranscript})',{
  kzn:()=>storedOrigin===undefined?undefined:{hookOrigin:storedOrigin},
  gwr:async args=>{
    launches.push(args);
    if(fail)throw Error('agent launch rejected');
    return {starting:starting===undefined?{[Symbol.dispose]:()=>disposals.push('disposed')}:starting,deny:undefined};
  },
  bfo:inert,
  Hxr:()=>interrupt,
  Ye:AgentResumeInterrupted,
  H:value=>typeof value==='object'&&value!==null&&!Array.isArray(value),
  Symbol
 });
 return {...funcs,launches,disposals,inert,AgentResumeInterrupted};
}

test('恢复子代理时保留 hook 来源、不可变身份和原始调用选项',async()=>{
 const f=originalResumeHarness();
 const result=await f.dwr('agent-42',{origin:['local'],signal:'cancel-signal',background:false,
   agent:{agentType:'Explore'},permissionMode:'default'});
 assert.equal(f.launches.length,1);
 const opts=f.launches[0];
 assert.deepEqual(Array.from(opts.origin),['local']);
 assert.equal(opts.signal,'cancel-signal');
 assert.equal(opts.background,false);
 assert.equal(opts.agent.agentType,'Explore');
 assert.deepEqual(Array.from(opts.agents),[]);
 assert.equal(opts.resume.agentId,'agent-42');
 assert.equal(Object.isFrozen(opts.resume),true);
 assert.equal(result.starting!==undefined,true);
 assert.equal(f.dwr,f.resumeSubagentWithInheritedHookOrigin);
});
test('持久化 hookOrigin 在子代理恢复时置于新调用来源前，不修改原数组',async()=>{
 const parent=['root-plugin','previous'];
 const current=['new-origin'];
 const f=originalResumeHarness({storedOrigin:parent});
 await f.dwr('agent-1',{origin:current,background:true});
 assert.deepEqual(Array.from(f.launches[0].origin),['root-plugin','previous','new-origin']);
 assert.deepEqual(parent,['root-plugin','previous']);
 assert.deepEqual(current,['new-origin']);
 const absent=originalResumeHarness({storedOrigin:['persistent']});
 await absent.dwr('agent-2',{background:false});
 assert.deepEqual(Array.from(absent.launches[0].origin),['persistent']);
});
test('恢复启动受到中断门禁阻止时释放真实启动句柄并抛出原异常',async()=>{
 const f=originalResumeHarness({interrupt:true});
 await assert.rejects(f.dwr('agent-1',{origin:[],signal:{aborted:true}}),e=>e instanceof f.AgentResumeInterrupted);
 assert.deepEqual(f.disposals,['disposed']);
});
test('无启动句柄和原版空资源哨兵可在中断条件下安全处理',async()=>{
 const nullStart=originalResumeHarness({interrupt:true,starting:null});
 await assert.rejects(nullStart.dwr('agent-1',{}),e=>e instanceof nullStart.AgentResumeInterrupted);
 assert.equal(nullStart.disposals.length,0);
 const f=originalResumeHarness({interrupt:true});
 // The original guard does not interrupt if the launch uses the inert sentinel.
 const from=source.indexOf('async function resumeSubagentWithInheritedHookOrigin(');
 const until=source.indexOf('var qn =',from);
 let called=0;
 const marker=Symbol('no-launch');
 const mod=vm.runInNewContext(source.slice(from,until)+';({dwr})',{
   kzn:()=>undefined,gwr:async()=>({starting:marker}),
   bfo:marker,Hxr:()=>{called++;return true;},Ye:f.AgentResumeInterrupted,
   H:x=>!!x,Symbol
 });
 assert.equal((await mod.dwr('agent-1',{})).starting,marker);
 assert.equal(called,0);
});
test('原版 Agent 恢复启动错误原样传播，不会虚报已完成的恢复',async()=>{
 const f=originalResumeHarness({fail:true});
 await assert.rejects(f.dwr('agent-2',{origin:[]}),/agent launch rejected/);
 assert.equal(f.disposals.length,0);
});
test('插件拒绝恢复原因严格保留原字符串并兼容短符号',()=>{
 const f=originalResumeHarness();
 assert.equal(f.cwr('denied by policy'),'a plugin declined the resume: denied by policy');
 assert.equal(f.cwr,f.formatPluginResumeDenial);
});
test('子代理历史根节点及所有父节点有证据时允许继续恢复',()=>{
 const f=originalResumeHarness();
 const history=[
  {uuid:'root',parentUuid:null},
  {uuid:'second',parentUuid:'root'},
  {uuid:'third',parentUuid:'second'}
 ];
 assert.equal(f.sn(history,['root']),true);
 assert.equal(f.sn(history,['root','second']),true);
 assert.equal(f.sn,f.hasCoherentResumedSubagentTranscript);
});
test('子代理历史必须从请求中的根 UUID 开始，且不可缺失父节点',()=>{
 const f=originalResumeHarness();
 assert.equal(f.sn([] ,[]),false);
 assert.equal(f.sn([{uuid:'child',parentUuid:'absent'}],['child']),false);
 assert.equal(f.sn([{uuid:'root'},{uuid:'child',parentUuid:'missing'}],['root']),false);
 // A parent already represented in the kept conversation UUIDs need not be repeated in the fetched transcript.
 assert.equal(f.sn([{uuid:'child',parentUuid:'root'}],['root']),true);
});
test('上下文压缩 preserving UUID 仅在可追溯于历史/已知行时通过',()=>{
 const f=originalResumeHarness();
 const accepted=[
  {uuid:'root'},
  {uuid:'middle',parentUuid:'root'},
  {uuid:'tail',parentUuid:'middle',compactMetadata:{preservedMessages:{uuids:['middle']}}}
 ];
 assert.equal(f.sn(accepted,['root']),true);
 const unknown=accepted.slice(0,2).concat([
  {uuid:'tail',parentUuid:'middle',compactMetadata:{preservedMessages:{uuids:['invisible']}}}
 ]);
 assert.equal(f.sn(unknown,['root']),false);
});
test('恢复辅助函数继续暴露原短符号供旧调用方引用',()=>{
 for(const [oldName,readable] of [
  ['dwr','resumeSubagentWithInheritedHookOrigin'],
  ['cwr','formatPluginResumeDenial'],
  ['sn','hasCoherentResumedSubagentTranscript']
 ])assert.ok(source.includes('const '+oldName+'='+readable));
 assert.match(source,/export \{ resumeSubagentWithInheritedHookOrigin,formatPluginResumeDenial,hasCoherentResumedSubagentTranscript,/);
});
