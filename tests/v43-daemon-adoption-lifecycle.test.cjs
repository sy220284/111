'use strict';
// Runs exact 2.1.310 function declarations with shared state and explicit boundary stubs.
// Not an end-to-end live Daemon, network, or cloud equivalence test.
const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');
const crypto=require('node:crypto');
const ts=require('typescript');

const specs={
  'core/chunk-bwt94x0y.js':{
    Jmr:'serializeAdoptAgent',Qmr:'serializeAdoptWorkflow',
    Zmr:'dedupFrameLiveNewest',FX:'computeAdoptability',
    f6e:'countLiveWorkflowAgents',aUe:'noticeFieldOf',
    xlt:'noticeDescriptionOf',eIn:'serializeAdoptable',
    Rlt:'writeAdoptJson',hno:'readAndConsumeAdoptJson',
    Xmr:'detachAndSerializeShell',ZPn:'collectFrameLiveConsent',
    Ino:'emitAdoptAgentDenied',nIn:'emitAdoptAgentFailed',E8t:'emitAdoptWorkflowFailed',
    Tno:'linkAdoptedAgentTranscript',Rno:'linkAdoptedWorkflowDir',
    Ano:'relinkAdoptedAgentSymlinks',xno:'resolveAdoptedScriptPath',Ono:'adoptCron'
  },
  'core/chunk-t2adf9g4.js':{yPe:'killJob',wpr:'killOrphanedWorker',Hee:'deleteJob',Kat:'replyToJob'}
};
function sourceFunctions(){
  const fragments=[],aliases=[];
  for(const [file,mapping] of Object.entries(specs)){
    const filepath=path.join(__dirname,'..',file);
    const ast=ts.createSourceFile(filepath,fs.readFileSync(filepath,'utf8'),ts.ScriptTarget.Latest,true,ts.ScriptKind.JS);
    assert.equal(ast.parseDiagnostics.length,0,'must parse '+file);
    const found=new Set();
    for(const s of ast.statements){
      if(!ts.isFunctionDeclaration(s)||!s.name)continue;
      const old=Object.keys(mapping).find(key=>mapping[key]===s.name.text);
      if(old===undefined)continue;
      found.add(old);
      fragments.push(s.getText(ast));
      aliases.push('const '+old+'='+s.name.text+';');
    }
    assert.equal(found.size,Object.keys(mapping).length,'missing restored declarations: '+file);
  }
  return fragments.join('\n')+'\n'+aliases.join('\n')+
    '\n({'+Object.values(specs).flatMap(m=>Object.keys(m).concat(Object.values(m))).join(',')+'})';
}
const exact=sourceFunctions();
function makeFixture(){
  const disk=new Map(),events=[],removed=[],aborted=[],jobCalls=[],roster=new Map();
  let daemonReply={ok:true,op:'kill'},storageEnabled=true;
  const taskPath=id=>'/jobs/'+id;
  const state={
    process:{pid:4321,umask:()=>0o022},
    Date,
    SIe:()=>true,Tn:x=>x.kind==='agent',pp:x=>x.kind==='shell',fLr:x=>x.kind==='workflow',
    rgr:(x,map)=>x.kind==='agent'&&map.get(x.id)===true,
    ngr:(x,map)=>x.kind==='shell'&&map.get(x.id)===true,
    rIn:(x,map)=>x.kind==='workflow'&&map.get(x.id)===true,
    lUe:()=>false,Om:()=>[],ZPn:()=>[],F6:()=>false,UYt:()=>0,tR:()=>'/output',
    Xmr:async()=>null,M3e:()=>{},AQt:()=>{},ZB:()=>false,ES:()=>{},TX:()=>{},Nl:()=>Error('checkpoint'),
    Wg:async()=>{},Ru:async()=>{},vIe:()=>{},f:()=>{},t:()=>{},a:(...e)=>events.push(e),
    J:()=>{},_:(x)=>JSON.stringify(x),Ue:crypto.createHash,
    D:async x=>x,$m:id=>'/transcripts/'+id,Br:id=>id,BW:id=>'/workflow/'+id,
    j:path.join,Y:x=>x,z:()=>false,_r:()=>false,_o:taskPath,
    Fe:()=>({safeParse:x=>x&&Array.isArray(x.shells)?{success:true,data:x}:{success:false,error:{message:'missing shells'}}}),
    Oe:()=>({safeParse:x=>x&&Array.isArray(x.shells)?{success:true,data:x}:{success:false,error:{message:'missing shells'}}}),
    He:async p=>{if(!disk.has(p))throw Object.assign(Error('ENOENT'),{code:'ENOENT'});return disk.get(p);},
    te:JSON.parse,Dn:async(p,data)=>disk.set(p,data),et:(a,b)=>b,
    Os:async(from,to)=>{if(!disk.has(from))throw Object.assign(Error('ENOENT'),{code:'ENOENT'});disk.set(to,disk.get(from));disk.delete(from);return false;},
    zK:async p=>disk.has(p)?{kind:'ok',content:disk.get(p)}:{kind:'missing'},
    N:async p=>{disk.delete(p)},E:e=>e.code,X:async()=>{},Ke:1,Clt:86400000,rgi:60000,vq:new Set(['EBUSY']),b:x=>x,yh:e=>String(e),Ge:()=>{},
    F:(values,p)=>values.filter(p).length,
    Z:()=>({live:{supervisors:new Map([['s1',{autoReactWiring:{title:'first'}}]]),bootingWiredArms:new Map()}}),
    sBe:()=>new Set(),xwe:()=>['s1'],
    ae:()=>({safeParse:p=>p.startsWith('/safe/')?{success:true,data:p}:{success:false,error:{issues:[{message:'path not allowed'}]}}}),
    Np:()=>{},R:class extends Error{},
    c:x=>x,ce:x=>x,tw:()=>undefined,vpr:'peer socket missing',
    lfn:async()=>{},
    jct:10,Bk:100,pCe:50,
    sE:s=>s,pW:s=>s,Et:/[\r\n]/,Wt:s=>s,xq:s=>s.length,_t:/^[\w.\/\- ]+$/,It:200,oe:/^[\w.\/\- ]+$/,Rt:'description omitted',
    Oc:1,bh:async p=>{jobCalls.push(p);return daemonReply},X:async()=>{},
    U5e:async()=>false,GT:id=>id,_re:async()=>[],Bn:()=>false,$an:async()=>false,
    lo:async p=>roster.get(p)||{state:'finished',fan:[]},
    gn:async()=>{},Cr:async p=>{removed.push(p);disk.delete(p)},Or:async()=>{},ZTe:x=>x,
    Dp:()=>{},g:()=>{},m:()=>{},d:x=>String(x),_o:taskPath
  };
  const f=vm.runInNewContext(exact,state);
  return {f,disk,events,removed,aborted,jobCalls,roster,taskPath,
    setReply:v=>daemonReply=v,setStorageEnabled:v=>storageEnabled=v};
}
test('24个原短符号保留与实际具名函数严格相同的引用',()=>{
  const {f}=makeFixture();
  for(const mapping of Object.values(specs))
    for(const [legacy,readable] of Object.entries(mapping))
      assert.strictEqual(f[legacy],f[readable],legacy);
});
test('接管Agent与Workflow序列化保持ID、父任务、脚本哈希和转储路径',async()=>{
  const {f}=makeFixture();
  const agent=await f.serializeAdoptAgent({agentId:'a1',agentType:'worker',description:'task',parentAgentId:'parent',forkedSkillName:'skill',spawnDepth:1});
  assert.equal(agent.transcriptPath,'/transcripts/a1');
  assert.equal(agent.parentAgentId,'parent');
  const wf=await f.serializeAdoptWorkflow({id:'task-2',workflowRunId:'run-2',script:'echo hi',args:{x:1},description:'workflow'});
  assert.equal(wf.workflowRunId,'run-2');
  assert.equal(wf.transcriptDir,'/workflow/run-2');
  assert.equal(wf.scriptSha256,crypto.createHash('sha256').update('echo hi').digest('hex'));
  assert.deepEqual(JSON.parse(wf.argsJson),{x:1});
});
test('资格判定不把父子任务中不可后台化的Agent当成可接管',()=>{
  const {f}=makeFixture();
  const good={id:'parent',kind:'agent',agentType:'worker',status:'running',isBackgrounded:true,abortController:{}};
  const child={id:'child',parentAgentId:'parent',kind:'agent',agentType:'worker',status:'running',isBackgrounded:true,abortController:{}};
  let rights=f.computeAdoptability({parent:good,child});
  assert.equal(rights.get('parent'),true);assert.equal(rights.get('child'),true);
  rights=f.computeAdoptability({parent:good,child:{...child,isBackgrounded:false}});
  assert.equal(rights.get('parent'),false);assert.equal(rights.get('child'),false);
});
test('FrameLive归集依照时间取最新并累计受限的未读回复数',()=>{
  const {f}=makeFixture();
  const values=f.dedupFrameLiveNewest([{fallbackBasis:100,entries:[{slug:'s1',unattendedReplies:3},{slug:'s2'}]},
    {fallbackBasis:200,entries:[{slug:'s1',unattendedReplies:9}]}]);
  assert.equal(values[0].slug,'s1');assert.equal(values[0].writtenAtMs,200);assert.equal(values[0].unattendedReplies,10);
  assert.equal(values.length,2);
});
test('实际接管流程：Agent选择→序列化→持久化→原子领取→二次领取为空→后台检查点',async()=>{
  const c=makeFixture(),{f}=c;
  let aborts=0;
  const parent={id:'a1',agentId:'a1',kind:'agent',agentType:'worker',status:'running',isBackgrounded:true,description:'resume me',abortController:{abort:()=>aborts++}};
  const pending=await f.serializeAdoptable({a1:parent},null);
  assert.ok(pending);assert.equal(pending.payload.agents.length,1);
  assert.equal(pending.payload.agents[0].agentId,'a1');
  await f.writeAdoptJson('/jobs/j1',pending.payload);
  assert.equal(c.disk.has('/jobs/j1/adopt.json'),true);
  const found=await f.readAndConsumeAdoptJson('/jobs/j1');
  assert.equal(found.agents[0].agentId,'a1');
  assert.equal(c.disk.has('/jobs/j1/adopt.json'),false);
  assert.equal(await f.readAndConsumeAdoptJson('/jobs/j1'),null);
  const removed=[];
  await pending.checkpointAgents({remove:id=>removed.push(id)});
  assert.equal(aborts,1);
  pending.disown({remove:id=>removed.push(id),all:()=>[]});
  assert.deepEqual(removed,['a1']);
});
test('Daemon kill确认后才允许清理作业目录；拒绝时不触碰目录',async()=>{
  const c=makeFixture(),{f}=c;
  c.disk.set('/jobs/job1/state.json','{}');
  const result=await f.deleteJob('job1',{internal:true});
  assert.equal(result.removed,true);
  assert.deepEqual(c.removed,['/jobs/job1']);
  assert.equal(c.jobCalls[0].op,'kill');
  const d=makeFixture();
  d.disk.set('/jobs/job2/state.json','{}');
  d.setReply({ok:false,code:'EPERM',error:'denied'});
  const denied=await d.f.deleteJob('job2',{internal:true});
  assert.equal(denied.removed,false);assert.equal(denied.errorCode,'kill_unconfirmed');
  assert.deepEqual(d.removed,[]);
  assert.equal(d.disk.has('/jobs/job2/state.json'),true);
});
test('停止远程/守护协议失败时不把未确认kill伪装为成功',async()=>{
  const c=makeFixture();c.setReply({ok:false,code:'EUNVERIFIED',error:'unknown worker'});
  const out=await c.f.killJob('job1',{state:'running'},{evict:true});
  assert.equal(out.confirmed,false);
  const d=makeFixture();d.setReply({ok:false,code:'ENOJOB',error:'no job'});
  const safe=await d.f.killJob('jobX',{state:'finished'},{}); 
  assert.equal(safe.confirmed,true);
});

test('v40恢复的FrameLive实时同意采集只带真实存在的slug和合法标题',()=>{
 const c=makeFixture();const a=c.f.collectFrameLiveConsent({});
 assert.equal(a.length,1);assert.equal(a[0].slug,'s1');assert.equal(a[0].title,'first');
 assert.equal(typeof a[0].writtenAtMs,'number');
});
test('v40恢复的脚本路径解析拒绝非法路径，不吞掉安全拒绝分支',()=>{
 const c=makeFixture();
 assert.equal(c.f.resolveAdoptedScriptPath('/safe/task.js'),'/safe/task.js');
 assert.throws(()=>c.f.resolveAdoptedScriptPath('../invalid'),/path not allowed/);
});
test('Daemon回复真实函数处理peer socket缺失和正常peer发送',async()=>{
 const c=makeFixture();
 const missing=await c.f.replyToJob('j','hello',{backend:'peer'},undefined,undefined,undefined);
 assert.equal(missing.err,'peer socket missing');
 const success=await c.f.replyToJob('j','hello',{backend:'peer',sock:'local-socket'},undefined,undefined,undefined);
 assert.equal(success,null);
});
