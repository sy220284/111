'use strict';
const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');
const root=path.resolve(__dirname,'..');
const read=name=>fs.readFileSync(path.join(root,'src',name),'utf8');
const fleet=read('chunk-y93r9xd4.js');
const roster=read('chunk-fnsjs1v2.js');
function fragment(src,begin,end){
 const i=src.indexOf(begin),j=src.indexOf(end,i+begin.length);
 assert.ok(i>=0&&j>i,'missing actual function fragment '+begin);
 return src.slice(i,j);
}
function fleetApi(){
 let cleanup,connected=[];
 const source=fragment(fleet,'var n=[];class i','export{Tat,BZr,createFleetViewHost,useAttachFleetOwners};');
 const api=vm.runInNewContext(source+'\n({Tat,BZr,createFleetViewHost,useAttachFleetOwners})',{
   De:()=>({subscribe:fn=>(connected.push(fn),()=>{connected=connected.filter(x=>x!==fn)}),
     emit:()=>connected.forEach(fn=>fn())}),
   Ngr:()=>({kind:'kill-ring'}),b:x=>x,
   P:(factory,deps)=>{assert.equal(deps.length,6);cleanup=factory()}
 });
 return {...api,getCleanup:()=>cleanup};
}
test('Fleet 宿主创建与旧短符号是同一实现，保留会话结果及 ring 状态',()=>{
 const f=fleetApi();
 assert.equal(f.Tat,f.createFleetViewHost);
 const host=f.Tat();
 assert.equal(host.roster,null);
 assert.equal(host.selection,null);
 assert.equal(host.resultSeen.entryChannel,'unknown');
 assert.equal(host.killRing.kind,'kill-ring');
 assert.equal(host.earlier.known,false);
});
test('Fleet 较早条目缓存保持快照、通知和失效加载令牌语义',()=>{
 const h=fleetApi().createFleetViewHost().earlier;
 let notices=0;const un=h.subscribe(()=>notices++);
 const first=h.beginLoad(),next=h.beginLoad();
 assert.equal(h.pending,true);
 h.endLoad(first);assert.equal(h.pending,true,'过期任务不得结束新加载');
 h.setEntries([{id:'a'}]);assert.equal(h.known,true);
 assert.equal(h.getSnapshot().entries[0].id,'a');
 h.endLoad(next);assert.equal(h.pending,false);
 assert.equal(h.getSnapshot().loading,false);
 h.clear();assert.equal(h.known,false);
 assert.equal(h.getSnapshot().entries.length,0);
 assert.ok(notices>=3);
 un();
});
test('Fleet attach/detach 顺序与错误回调完整保留',()=>{
 const f=fleetApi(),joined=[],left=[];
 const make=name=>({attachView:(options)=>{joined.push(name);if(name==='selection') assert.equal(typeof options.onError,'function');return()=>left.push(name)}});
 const attached={selection:make('selection'),view:make('view'),attach:make('attach'),editor:{...make('editor'),setError:()=>{}},deleteConfirm:make('deleteConfirm')};
 assert.equal(f.BZr,f.useAttachFleetOwners);
 f.BZr(attached,{clock:()=>1,groupsEnabled:true});
 assert.equal(joined.join(','),'selection,view,attach,editor,deleteConfirm');
 f.getCleanup()();
 assert.equal(left.join(','),'deleteConfirm,editor,attach,view,selection');
});
test('rosterKey 保留原存储 namespace；bgShort 校验合法任务号',()=>{
 const names=fragment(roster,'function rosterKey()','function Q()');
 const short=fragment(roster,'function bgShort(e)','function Ve(e)');
 const a=vm.runInNewContext(names+'\n'+short+'\n({rosterKey,NFt,bgShort,ax})',{
   We:{daemon:parts=>'daemon:'+parts.join('/')},NP:/^[a-z]+-[0-9]+$/,Hn:s=>'short:'+s,b:s=>'fallback:'+s
 });
 assert.equal(a.NFt,a.rosterKey);assert.equal(a.NFt(),'daemon:roster.json');
 assert.equal(a.ax,a.bgShort);assert.equal(a.ax('task-123'),'short:task-123');
 assert.equal(a.ax('../bad'),'fallback:invalid');
});
test('readRoster 在远端 inspect 失败时标记结果，正常返回相同 roster 对象',async()=>{
 const readSource=fragment(roster,'async function readRoster(e, n)','async function Ye(e, n, r)');
 const expected={workers:{a:{pid:1}}};
 for(const failed of [false,true]){
   const f=vm.runInNewContext(readSource+'\n({readRoster,JN})',{
     Xe:async()=>({roster:expected,inspectFailed:failed}),Q:()=>({workers:{}}),
   });
   assert.equal(f.JN,f.readRoster);
   const got=await f.JN({silent:true},{read:()=>{}});
   assert.equal(got.workers.a.pid,1);
   assert.equal(Boolean(got.inspectFailed),failed);
 }
});
test('updateRoster 序列化修改并重新设置真实进程 owner 和时间戳',async()=>{
 const source=fragment(roster,'function updateRoster(e, n)','async function Dt(e)');
 const saved=[],original={workers:{job:{pid:111}},supervisorPid:-1,updatedAt:0};
 const f=vm.runInNewContext(source+'\n({updateRoster,Hne})',{
   Ft:{run:async(key,fn)=>{assert.equal(key,'roster');return await fn()}},
   Dt:async()=>original,Ct:async(record,ctx)=>saved.push({record,ctx}),process:{pid:456},Date
 });
 assert.equal(f.Hne,f.updateRoster);
 await f.updateRoster(row=>({...row,workers:{...row.workers,newJob:{pid:222}}}),{backend:true});
 assert.equal(saved.length,1);
 assert.equal(saved[0].record.supervisorPid,456);
 assert.ok(saved[0].record.updatedAt>0);
 assert.equal(saved[0].record.workers.newJob.pid,222);
 assert.equal(saved[0].ctx.backend,true);
});
