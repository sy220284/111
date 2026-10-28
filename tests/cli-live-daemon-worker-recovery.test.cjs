'use strict';
// Real supervisor + filesystem dispatch + exec worker process + crash and new supervisor.
// No mocked VM / no isolated source function extract.
const {test}=require('node:test');
const assert=require('node:assert/strict');
const {spawn,spawnSync}=require('node:child_process');
const fs=require('node:fs');
const os=require('node:os');
const path=require('node:path');
const bin=path.resolve(__dirname,'../bin/claude-310.mjs');
const runtime=process.env.CLAUDE_E2E_RUNTIME||process.execPath;
const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));
const short='a1b2c3d4';
function environment(home){
  return {...process.env,HOME:home,CLAUDE_CONFIG_DIR:path.join(home,'.claude'),
    CI:'true',CLAUDE_CODE_SIMPLE:'1',NO_COLOR:'1',TERM:'dumb',
    DISABLE_TELEMETRY:'1',CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC:'1',
    ANTHROPIC_API_KEY:'',ANTHROPIC_AUTH_TOKEN:'',HTTP_PROXY:'',HTTPS_PROXY:'',ALL_PROXY:'',
    NO_PROXY:'127.0.0.1,localhost'};
}
function cmd(home,env,...args){return spawnSync(runtime,[bin,'daemon',...args],{cwd:home,env,timeout:10000,encoding:'utf8'})}
async function start(home,env){
  const log=path.join(home,'supervisor.log');
  const child=spawn(runtime,[bin,'daemon','run','--origin','foreground','--log-file',log],
    {cwd:home,env,stdio:['ignore','pipe','pipe']});
  let stdout='',stderr='';
  child.stdout.on('data',x=>stdout+=x);
  child.stderr.on('data',x=>stderr+=x);
  for(let i=0;i<70;i++){
    if(child.exitCode!==null)break;
    const check=cmd(home,env,'status');
    if(check.status===0&&check.stdout.includes(String(child.pid)))return child;
    await sleep(200);
  }
  child.kill('SIGKILL');
  throw Error('daemon not running; log='+read(log).slice(-3000)+' stdout='+stdout+' stderr='+stderr);
}
function read(p){return fs.existsSync(p)?fs.readFileSync(p,'utf8'):''}
async function waitFor(fn,description,home,ms=30000){
  const start=Date.now();
  while(Date.now()-start<ms){if(fn())return;await sleep(200)}
  const files=fs.readdirSync(home,{recursive:true}).slice(0,130);
  const details=files.filter(f=>/\.log$|\.json$|stderr|stdout|pty|worker/i.test(f))
    .slice(0,30).map(f=>{
      const full=path.join(home,f);
      try{const st=fs.lstatSync(full);return st.isFile()?
        f+':'+read(full).slice(-1800):f+':'+(st.isSymbolicLink()?'symlink':'directory');}
      catch(e){return f+':'+String(e)}
    }).join('\n');
  throw Error(description+' timeout: '+read(path.join(home,'supervisor.log')).slice(-4500)+
    ' roster='+read(path.join(home,'.claude/daemon/roster.json')).slice(-2200)+
    ' files='+JSON.stringify(files)+' details='+details);
}
test('真实 daemon 分发 exec worker、写 roster、supervisor 崩溃后继续读取接管记录',{timeout:120000},async()=>{
  const home=fs.mkdtempSync(path.join(os.tmpdir(),'111-daemon-worker-'));
  const env=environment(home);
  const marker=path.join(home,'worker-started.json');
  const heartbeat=path.join(home,'worker-heartbeat.txt');
  const fixture=path.join(home,'worker.cjs');
  let first,second,workerPid;
  fs.writeFileSync(fixture,"const fs=require('node:fs');const p=process.argv[2],beat=process.argv[3];fs.writeFileSync(p,JSON.stringify({pid:process.pid,startedAt:Date.now()}));const tick=()=>fs.writeFileSync(beat,String(Date.now()));tick();setInterval(tick,150);");
  try{
    first=await start(home,env);
    const dispatchDir=path.join(home,'.claude/daemon/dispatch');
    fs.mkdirSync(dispatchDir,{recursive:true});
    const d={proto:1,short,sessionId:'e2e-worker-session',createdAt:Date.now(),source:'shell',cwd:home,
      launch:{mode:'exec',cmd:process.execPath,args:[fixture,marker,heartbeat]},env:{},isolation:'none',respawnFlags:[]};
    fs.writeFileSync(path.join(dispatchDir,short+'.tmp'),JSON.stringify(d));
    fs.renameSync(path.join(dispatchDir,short+'.tmp'),path.join(dispatchDir,short));
    await waitFor(()=>fs.existsSync(marker),'worker actual exec spawn',home,35000);
    workerPid=JSON.parse(read(marker)).pid;
    assert.ok(workerPid>0&&workerPid!==first.pid);
    await waitFor(()=>read(path.join(home,'.claude/daemon/roster.json')).includes(short),
      'worker roster actually persisted',home,17000);
    await waitFor(()=>fs.existsSync(heartbeat),'worker heartbeat present',home,2000);
    const beforeCrash=Number(read(heartbeat));
    first.kill('SIGKILL');
    await waitFor(()=>Number(read(heartbeat))>beforeCrash,'orphan exec worker continues after supervisor SIGKILL',home,3500);
    assert.doesNotThrow(()=>process.kill(workerPid,0),'worker process exited when only supervisor was killed');
    // A standalone exec worker may be detached or killed with its PTY;
    // restart must at least reconcile roster rather than lose job identity.
    second=await start(home,env);
    const status=cmd(home,env,'status');
    assert.equal(status.status,0,'restarted daemon unreachable '+status.stderr);
    assert.ok(status.stdout.includes(String(second.pid)));
    const roster=read(path.join(home,'.claude/daemon/roster.json'));
    assert.ok(roster.includes(short),'roster discarded active worker across restart: '+roster);
    const beforeAdopt=Number(read(heartbeat));
    await waitFor(()=>Number(read(heartbeat))>beforeAdopt,'worker continues after new supervisor adopted roster',home,3500);
    assert.doesNotThrow(()=>process.kill(workerPid,0),'worker was replaced or terminated during adopt');
  }finally{
    if(second?.exitCode===null)second.kill('SIGKILL');
    if(first?.exitCode===null)first.kill('SIGKILL');
    if(workerPid){try{process.kill(workerPid,'SIGKILL')}catch{}}
    fs.rmSync(home,{recursive:true,force:true});
  }
});


test('真实 daemon exec worker 正常和异常退出均记录退出码与输出尾部',{timeout:120000},async()=>{
  const home=fs.mkdtempSync(path.join(os.tmpdir(),'111-daemon-worker-exit-'));
  const env=environment(home);
  let supervisor;
  try{
    supervisor=await start(home,env);
    const dispatchDir=path.join(home,'.claude/daemon/dispatch');
    fs.mkdirSync(dispatchDir,{recursive:true});
    const cases=[
      {short:'c1d2e3f4',exitCode:0,token:'completed-worker-111'},
      {short:'d1e2f3a4',exitCode:7,token:'failed-worker-111'}
    ];
    for(const job of cases){
      const fixture=path.join(home,job.short+'.cjs');
      const marker=path.join(home,job.short+'.started');
      fs.writeFileSync(fixture,
        'const fs=require("node:fs");'+
        'fs.writeFileSync('+JSON.stringify(marker)+',"started");'+
        'process.stdout.write('+JSON.stringify(job.token+'-stdout\n')+');'+
        'process.stderr.write('+JSON.stringify(job.token+'-stderr\n')+');'+
        'process.exit('+job.exitCode+');');
      const dispatch={proto:1,short:job.short,sessionId:'e2e-exit-'+job.short,
        createdAt:Date.now(),source:'shell',cwd:home,
        launch:{mode:'exec',cmd:process.execPath,args:[fixture]},env:{},
        isolation:'none',respawnFlags:[]};
      const temp=path.join(dispatchDir,job.short+'.tmp');
      fs.writeFileSync(temp,JSON.stringify(dispatch));
      fs.renameSync(temp,path.join(dispatchDir,job.short));
      await waitFor(()=>fs.existsSync(marker),'actual worker '+job.short+' launched',home,30000);
      const receipts=()=>{
        const bound=read(path.join(home,'supervisor.log')).match(/control socket bound at (\S+\/control\.sock)/);
        const socketRoot=bound?path.dirname(bound[1]):null;
        const files=socketRoot&&fs.existsSync(socketRoot)?
          fs.readdirSync(socketRoot,{recursive:true}).filter(name=>name.endsWith('.exec-exit')):[];
        for(const file of files){
          let record;
          try{record=JSON.parse(read(path.join(socketRoot,file)))}catch{continue}
          if(record.tail?.includes(job.token+'-stdout'))return record;
        }
        return null;
      };
      await waitFor(()=>receipts()!==null,'worker exit receipt '+job.short,home,20000);
      const receipt=receipts();
      assert.equal(receipt.code,job.exitCode,'actual child exit code was lost');
      assert.ok(receipt.tail.includes(job.token+'-stdout'),'stdout tail lost');
      assert.ok(receipt.tail.includes(job.token+'-stderr'),'stderr tail lost');
    }
  }finally{
    if(supervisor?.exitCode===null)supervisor.kill('SIGKILL');
    fs.rmSync(home,{recursive:true,force:true});
  }
});
