'use strict';
const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');
const source=fs.readFileSync(path.resolve(__dirname,'../chunk-t2adf9g4.js'),'utf8');
function between(start,end){
 const a=source.indexOf(start),b=source.indexOf(end,a+start.length);
 assert.ok(a>=0&&b>a,'source anchor missing: '+start);
 return source.slice(a,b);
}
function listFixture(response,workers={},isAlive=()=>false){
 const calls=[];
 const code=between('async function listAliveDaemonJobs(e)','async function probeDaemonJob(e,n)');
 const api=vm.runInNewContext(code+';({listAliveDaemonJobs,b3t})',{
  bh:async q=>{calls.push(q);return response},Oc:7,
  JN:async()=>({workers}),$an:async(pid,start)=>isAlive(pid,start)
 });
 return {...api,calls};
}
function presentFixture(response){
 const code=between('async function isDaemonJobPresent(e)','function refreshDaemonJobRecord(e,n)');
 return vm.runInNewContext(code+';({isDaemonJobPresent,Meo})',{
  bh:async()=>response,Oc:7
 });
}
function recordFixture(){
 const code=between('function matchesBackgroundJobReference(e,n)','\nexport{');
 return vm.runInNewContext(code+';({matchesBackgroundJobReference,Bn})');
}
function refreshFixture(keepBlocked){
 const code=between('function refreshDaemonJobRecord(e,n)','var jFe=');
 return vm.runInNewContext(code+';({refreshDaemonJobRecord,zat})',{
  fLs:()=>keepBlocked,xht:()=>false,Xu:v=>v,Ms:v=>v,sg:999
 });
}
test('Daemon 列表优先使用协议响应，已结束作业不进入活动记录',async()=>{
 const f=listFixture({ok:true,op:'list',jobs:[{short:'a',outcome:null},{short:'b',outcome:'done'}]});
 assert.equal(f.listAliveDaemonJobs,f.b3t);
 const r=await f.listAliveDaemonJobs({});
 assert.deepEqual([...r.shorts].sort(),['a','b']);
 assert.deepEqual(Array.from(r.records,x=>x.short),['a']);
 assert.equal(f.calls[0].op,'list');
});
test('Daemon 列表协议失败时仅把 PID/启动时间匹配的工作进程视为活动',async()=>{
 const f=listFixture({ok:false}, {a:{pid:10,procStart:1},b:{pid:11,procStart:2}},pid=>pid===11);
 const r=await f.b3t({});
 assert.deepEqual([...r.shorts],['b']);
 assert.deepEqual([...r.records],[]);
});
test('Daemon 单作业存在性遵从 present 并在字段缺失时回退 alive',async()=>{
 assert.equal(typeof presentFixture({ok:true,op:'has',alive:true,present:false}).Meo('a').then,'function');
 assert.equal(await presentFixture({ok:true,op:'has',alive:true,present:false}).isDaemonJobPresent('a'),false);
 assert.equal(await presentFixture({ok:true,op:'has',alive:true}).Meo('a'),true);
 assert.equal(await presentFixture({ok:false,code:'ENOCONN'}).Meo('a'),false);
});
test('清理背景任务仅匹配明确关联的 jobId 或 sessionId 前缀',()=>{
 const {matchesBackgroundJobReference,Bn}=recordFixture();
 assert.equal(matchesBackgroundJobReference,Bn);
 assert.equal(Bn({kind:'bg',jobId:'a'},'a'),true);
 assert.equal(Bn({kind:'bg',sessionId:'abc-def'},'abc'),true);
 assert.equal(Bn({kind:'fg',jobId:'a'},'a'),false);
 assert.equal(Bn({kind:'bg',sessionId:'abc-def'},'zzz'),false);
});
test('作业刷新清理旧回复输出、收敛空白并更新时戳',()=>{
 const {refreshDaemonJobRecord,zat}=refreshFixture(false);
 assert.equal(refreshDaemonJobRecord,zat);
 const r=zat({tempo:'blocked',state:'waiting',needs:['x'],suggestedReply:'old',output:'old'},'  new    update  ');
 assert.equal(r.detail,'new update');
 assert.equal(r.tempo,'active');
 assert.equal(r.needs,undefined);
 assert.equal(r.suggestedReply,undefined);
 assert.equal(r.output,null);
 assert.ok(!Number.isNaN(Date.parse(r.updatedAt)));
});
test('仍有未满足依赖的阻塞任务刷新时保留阻塞节奏',()=>{
 const {zat}=refreshFixture(true);
 const r=zat({tempo:'blocked',state:'waiting',needs:['dep']},'report');
 assert.equal(r.tempo,'blocked');
 assert.deepEqual(Array.from(r.needs),['dep']);
});
