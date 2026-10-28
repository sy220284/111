'use strict';
// v37 PIN -> current: actually execute both REAL function bodies with equivalent local boundary fixtures.
// This is behavior parity for the exercised cases, not a substitute for a full production Daemon.
const {test}=require('node:test');
const assert=require('node:assert/strict');
const childProcess=require('node:child_process');
const fs=require('node:fs');
const path=require('node:path');
const crypto=require('node:crypto');
const vm=require('node:vm');
const ts=require('typescript');

const root=path.resolve(__dirname,'..');
const PIN='3c64d1669174e825fb0fbae088c5ec95edaa7339';
const oldCache=new Map(),nowCache=new Map();
function historical(file) {
 if(!oldCache.has(file))oldCache.set(file,childProcess.execFileSync('git',['show',PIN+':src/'+file],{cwd:root,encoding:'utf8',maxBuffer:25*1024*1024}));
 return oldCache.get(file);
}
function current(file) {
 if(!nowCache.has(file))nowCache.set(file,fs.readFileSync(path.join(root,'src',file),'utf8'));
 return nowCache.get(file);
}
function findFunction(source,location,name) {
 const ast=ts.createSourceFile(location,source,ts.ScriptTarget.Latest,true,ts.ScriptKind.JS);
 assert.equal(ast.parseDiagnostics.length,0,'parse '+location);
 const nodes=ast.statements.filter(n=>ts.isFunctionDeclaration(n)&&n.name?.text===name);
 assert.equal(nodes.length,1,'unique '+name+' in '+location);
 return nodes[0].getText(ast);
}
function load(source,file,name,context) {
 return vm.runInNewContext('('+findFunction(source,file,name)+')',context,{timeout:1500});
}
function ordinary(value) {
 if(Object.prototype.toString.call(value)==='[object Map]')return [...value.entries()].sort((a,b)=>String(a[0]).localeCompare(String(b[0])));
 if(value===undefined)return {value:'undefined'};
 return JSON.parse(JSON.stringify(value));
}
async function compare(file,legacy,readable,execute) {
 const first=await execute(historical(file),legacy);
 const second=await execute(current(file),readable);
 assert.deepEqual(ordinary(second),ordinary(first),file+': '+legacy+' -> '+readable);
}
const beacon='core/chunk-t2adf9g4.js',adopt='core/chunk-bwt94x0y.js';

test('v37原始过期计算与现行具名函数：24h临界和未来时钟漂移',async()=>{
 const run=(source,name)=>{
  const fn=load(source,beacon,name,{wt:86400000});
  const now=1000000000;
  return [now-86400000,now-86399999,now+60000,now+60001,now].map(time=>fn(time,now));
 };
 await compare(beacon,'$e','isDaemonRecordExpired',run);
});

function beaconHarness(source,name) {
 const ownedBeacons=new Map(),queued=[],disk=[],v5=[];
 let allowed=true;
 const ctx={
  wt:86400000,Number,
  yF:()=>({ownedBeacons}),
  st:(id,task)=>queued.push({id,task}),
  vb:async()=>9001,
  Ee:async()=>allowed,
  kt:async(store,id,state)=>v5.push({id,state:JSON.parse(JSON.stringify(state))}),
  Dn:async(file,content,mode)=>disk.push({file,content,mode}),
  at:id=>'/jobs/'+id+'.json',
  _:JSON.stringify
 };
 const fn=load(source,beacon,name,ctx);
 return {fn,ownedBeacons,queued,disk,v5,setAllowed:x=>allowed=x,flush:async()=>{
  while(queued.length)await queued.shift().task();
 }};
}
test('v37原始重试次数函数与当前：单调递增、失去所有权、存储双路由',async()=>{
 const run=async(source,name)=>{
  const h=beaconHarness(source,name),record={attempt:1};
  h.ownedBeacons.set('task-a',record);
  for(const n of [0,1,1.25,2,2])h.fn('task-a',n);
  await h.flush();
  h.fn('task-a',3);
  h.ownedBeacons.set('task-a',{attempt:99});
  await h.flush();
  h.fn('task-a',100,{useV5:true});
  await h.flush();
  return {disk:h.disk,v5:h.v5,lastOriginal:record,active:h.ownedBeacons.get('task-a')};
 };
 await compare(beacon,'tn','updateOwnedDaemonBeaconAttempt',run);
});
test('v37原始Daemon交互进度与当前：标志粘性、首次字段、所有权保护',async()=>{
 const run=async(source,name)=>{
  const h=beaconHarness(source,name),record={attempt:0};
  h.ownedBeacons.set('task-a',record);
  h.fn('task-a',{marksExpected:true,daemonBooted:true,interactiveReached:false,attachMs:21,via:'ssh'});
  h.fn('task-a',{marksExpected:false,daemonBooted:false,interactiveReached:true,attachMs:99,via:'tty',msgsLoaded:0});
  await h.flush();
  h.fn('task-a',{msgsInJsonl:3,msgsRenderedAtFirstPaint:2});
  h.ownedBeacons.set('task-a',{attempt:5});
  await h.flush();
  return {record,disk:h.disk,v5:h.v5,active:h.ownedBeacons.get('task-a')};
 };
 await compare(beacon,'oxt','mergeOwnedDaemonBeaconProgress',run);
});
test('v37原始Agent序列化与当前：父子、路径、缺省元数据一致',async()=>{
 const run=async(source,name)=>{
  const ctx={Br:id=>id,$m:id=>'/transcripts/'+id,D:async p=>p};
  const fn=load(source,adopt,name,ctx);
  return [await fn({agentId:'agent-1',agentType:'worker',description:'resume',parentAgentId:'leader',spawnDepth:2}),
   await fn({agentId:'agent-2',agentType:'worker',description:'other'},{derivedTranscriptPath:'/custom/out.jsonl'})];
 };
 await compare(adopt,'Jmr','serializeAdoptAgent',run);
});
test('v37原始Workflow序列化与当前：脚本SHA、参数、路径与缺省一致',async()=>{
 const run=async(source,name)=>{
  const fn=load(source,adopt,name,{BW:id=>'/workflow/'+id,D:async p=>p,Ue:crypto.createHash,_:JSON.stringify});
  return [
   await fn({id:'w1',workflowRunId:'r1',script:'echo test',args:{x:1},description:'hello'}),
   await fn({id:'w2',workflowRunId:'r2',description:'no script'},{derivedTranscriptDir:'/custom/w2'})
  ];
 };
 await compare(adopt,'Qmr','serializeAdoptWorkflow',run);
});
test('v37原始FrameLive合并与当前：时间排序、同slug未读计数上限',async()=>{
 const run=(source,name)=>{
  const logs=[];
  const fn=load(source,adopt,name,{jct:10,Bk:100,pCe:50,f:(...args)=>logs.push(args)});
  return {items:fn([
   {fallbackBasis:10,entries:[{slug:'s1',unattendedReplies:7},{slug:'s2',unattendedReplies:1}]},
   {fallbackBasis:20,entries:[{slug:'s1',unattendedReplies:8},{slug:'s3'}]}
  ]),logs};
 };
 await compare(adopt,'Zmr','dedupFrameLiveNewest',run);
});
test('v37原始接管资格判断与当前：父子后台任务不可随意提升权限',async()=>{
 const run=(source,name)=>{
  const ctx={
   SIe:()=>true,Tn:x=>x.kind==='agent',pp:x=>x.kind==='shell',
   fLr:x=>x.kind==='workflow',rgr:(x,map)=>map.get(x.id)===true,
   ngr:(x,map)=>map.get(x.id)===true,rIn:(x,map)=>map.get(x.id)===true
  };
  const fn=load(source,adopt,name,ctx);
  const parent={id:'parent',kind:'agent',agentType:'worker',status:'running',isBackgrounded:true,abortController:{}};
  const child={id:'child',parentAgentId:'parent',kind:'agent',agentType:'worker',status:'running',isBackgrounded:true,abortController:{}};
  const permitted=ordinary(fn({parent,child}));
  const rejected=ordinary(fn({parent,child:{...child,isBackgrounded:false}}));
  assert.ok(permitted.some(([id,allowed])=>id==='parent'&&allowed===true));
  assert.ok(permitted.some(([id,allowed])=>id==='child'&&allowed===true));
  assert.ok(rejected.some(([id,allowed])=>id==='child'&&allowed===false));
  return [permitted,rejected,ordinary(fn({}))];
 };
 await compare(adopt,'FX','computeAdoptability',run);
});
test('v37原始工作流运行计数与当前：error之后的done记为rerun',async()=>{
 const run=(source,name)=>{
  const ctx={rIn:(task,allowed)=>allowed.get(task.id)===true};
  const fn=load(source,adopt,name,ctx);
  const wf={
    id:'wf',kind:'workflow',workflowProgress:[
      {type:'workflow_agent',index:0,state:'done'},
      {type:'workflow_agent',index:1,state:'error'},
      {type:'workflow_agent',index:2,state:'done'},
      {type:'workflow_agent',index:3,state:'progress'}
    ]
  };
  return fn({wf},new Map([['wf',true]]));
 };
 await compare(adopt,'f6e','countLiveWorkflowAgents',run);
});
test('v37原始通知字段验证与当前：长度、换行和危险字符边界一致',async()=>{
 const run=(source,name)=>{
  const ctx={sE:s=>s,pW:s=>s,Et:/[\r\n]/,Wt:s=>s,xq:s=>s.length,_t:/^[a-z0-9._\/-]+$/i};
  const fn=load(source,adopt,name,ctx);
  return [
   fn('job-one',20),fn('job-one',2),
   fn('bad\nvalue',20),fn('bad;command',20),
   fn('task-1',20,/^task-\d$/)
  ].map(ordinary);
 };
 await compare(adopt,'aUe','noticeFieldOf',run);
});
test('v37原始Daemon kill与当前：peer、确认成功、拒绝边界保持一致',async()=>{
 const run=async(source,name)=>{
  const sent=[];
  const ctx={bh:async p=>{sent.push({...p});return{ok:true}},Oc:1,X:async()=>{}};
  const fn=load(source,beacon,name,ctx);
  const peer=await fn('peer-1',{backend:'peer'},{});
  const local=await fn('job-1',{backend:'daemon'},{evict:true});
  return {peer,local,sent};
 };
 await compare(beacon,'yPe','killJob',run);
});
