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
// Receipts may disappear between watcher notification and the read; ENOENT is
// expected when the daemon atomically consumes/cleans a transient PTY receipt.
function read(p){
  try{return fs.readFileSync(p,'utf8')}
  catch(error){if(error?.code==='ENOENT')return '';throw error}
}
function captureExitReceipts(directory, receipts){
  let entries;
  try{entries=fs.readdirSync(directory)}
  catch(error){if(error?.code==='ENOENT')return;throw error}
  for(const file of entries.filter(name=>name.endsWith('.exec-exit'))){
    const raw=read(path.join(directory,file));
    if(!raw)continue;
    try{receipts.set(file,JSON.parse(raw))}catch{/* partial receipt: retry on next poll */}
  }
}
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
  let supervisor,watcher;
  try{
    supervisor=await start(home,env);
    const dispatchDir=path.join(home,'.claude/daemon/dispatch');
    fs.mkdirSync(dispatchDir,{recursive:true});
    const bound=read(path.join(home,'supervisor.log')).match(/control socket bound at (\S+\/control\.sock)/);
    assert.ok(bound,'真实 daemon 必须报告已绑定 control socket');
    const ptyDir=path.join(path.dirname(bound[1]),'pty');
    fs.mkdirSync(ptyDir,{recursive:true,mode:0o700});
    const seenReceipts=new Map();
    const observeReceipts=()=>captureExitReceipts(ptyDir,seenReceipts);
    // 在实际 worker 启动前开始观察：原版 PTY 清理可能在回执写入后快速移走文件。
    watcher=fs.watch(ptyDir,()=>observeReceipts());
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
        'process.exitCode='+job.exitCode+';');
      const dispatch={proto:1,short:job.short,sessionId:'e2e-exit-'+job.short,
        createdAt:Date.now(),source:'shell',cwd:home,
        launch:{mode:'exec',cmd:process.execPath,args:[fixture]},env:{},
        isolation:'none',respawnFlags:[]};
      const temp=path.join(dispatchDir,job.short+'.tmp');
      fs.writeFileSync(temp,JSON.stringify(dispatch));
      fs.renameSync(temp,path.join(dispatchDir,job.short));
      await waitFor(()=>fs.existsSync(marker),'actual worker '+job.short+' launched',home,30000);
      const receipts=()=>{observeReceipts();return seenReceipts.get(job.short+'.sock.exec-exit')??null;};
      await waitFor(()=>receipts()?.tail?.includes(job.token+'-stdout'),
        'worker exit receipt '+job.short,home,20000);
      const receipt=receipts();
      assert.equal(receipt.code,job.exitCode,'actual child exit code was lost');
      assert.ok(receipt.tail.includes(job.token+'-stdout'),'stdout tail lost');
      assert.ok(receipt.tail.includes(job.token+'-stderr'),'stderr tail lost');
    }
  }finally{
    watcher?.close();
    if(supervisor?.exitCode===null)supervisor.kill('SIGKILL');
    fs.rmSync(home,{recursive:true,force:true});
  }
});

// Regression: a transient PTY receipt can be removed after readdir and before read.
test('PTY exit receipt observer tolerates concurrent cleanup without hiding other I/O errors',()=>{
  const dir=fs.mkdtempSync(path.join(os.tmpdir(),'111-pty-race-'));
  const receipts=new Map();
  try{
    const file=path.join(dir,'short.sock.exec-exit');
    fs.writeFileSync(file,JSON.stringify({code:7,tail:'stderr'}));
    captureExitReceipts(dir,receipts);
    assert.deepEqual(receipts.get('short.sock.exec-exit'),{code:7,tail:'stderr'});
    fs.unlinkSync(file);
    assert.equal(read(file),'');
    assert.doesNotThrow(()=>captureExitReceipts(dir,receipts));
    fs.writeFileSync(file,'{"partial"');
    assert.doesNotThrow(()=>captureExitReceipts(dir,receipts));
  }finally{fs.rmSync(dir,{recursive:true,force:true})}
  assert.doesNotThrow(()=>captureExitReceipts(dir,receipts));
});

test('真实 Worker 遭 SIGKILL 后留下失败回执，非幂等 exec 不被偷偷重放',{timeout:120000},async()=>{
  const home=fs.mkdtempSync(path.join(os.tmpdir(),'111-daemon-worker-killed-'));
  const env=environment(home),short='f7e6d5c4';
  const marker=path.join(home,'killed-worker.json');
  const count=path.join(home,'side-effect-count.txt');
  const fixture=path.join(home,'killed-worker.cjs');
  let supervisor,watcher,workerPid;
  const receipts=new Map();
  try{
    fs.writeFileSync(fixture,
      'const fs=require("node:fs");'+
      'const file='+JSON.stringify(count)+';'+
      'const n=fs.existsSync(file)?Number(fs.readFileSync(file,"utf8")):0;'+
      'fs.writeFileSync(file,String(n+1));'+
      'fs.writeFileSync('+JSON.stringify(marker)+',JSON.stringify({pid:process.pid}));'+
      'setInterval(()=>{},1000);');
    supervisor=await start(home,env);
    const bound=read(path.join(home,'supervisor.log')).match(/control socket bound at (\S+\/control\.sock)/);
    assert.ok(bound,'真实 supervisor 控制 socket 未启动');
    const ptyDir=path.join(path.dirname(bound[1]),'pty');
    fs.mkdirSync(ptyDir,{recursive:true,mode:0o700});
    const observe=()=>captureExitReceipts(ptyDir,receipts);
    watcher=fs.watch(ptyDir,observe);
    const dispatchDir=path.join(home,'.claude/daemon/dispatch');
    fs.mkdirSync(dispatchDir,{recursive:true});
    const dispatch={proto:1,short,sessionId:'killed-worker-session',
      createdAt:Date.now(),source:'shell',cwd:home,
      launch:{mode:'exec',cmd:process.execPath,args:[fixture]},
      env:{},isolation:'none',respawnFlags:[]};
    fs.writeFileSync(path.join(dispatchDir,short+'.tmp'),JSON.stringify(dispatch));
    fs.renameSync(path.join(dispatchDir,short+'.tmp'),path.join(dispatchDir,short));
    await waitFor(()=>fs.existsSync(marker),'worker created side effect',home,35000);
    workerPid=JSON.parse(read(marker)).pid;
    assert.equal(Number(read(count)),1);
    process.kill(workerPid,'SIGKILL');
    await waitFor(()=>{observe();return receipts.has(short+'.sock.exec-exit');},
      'SIGKILL worker exit receipt',home,30000);
    const receipt=receipts.get(short+'.sock.exec-exit');
    assert.notEqual(receipt.code,0,'SIGKILL 被错误报告为成功');
    await sleep(1800);
    assert.equal(Number(read(count)),1,'不具备幂等保障的 exec 被后台重复执行');
  }finally{
    watcher?.close();
    if(workerPid){try{process.kill(workerPid,'SIGKILL')}catch{}}
    if(supervisor?.exitCode===null)supervisor.kill('SIGKILL');
    fs.rmSync(home,{recursive:true,force:true});
  }
});
