'use strict';
// Daemon supervisor integration: real CLI processes, real OS PIDs and isolated filesystem.
// Not proof of worker job adoption or cloud business task recovery.
const {test}=require('node:test');
const assert=require('node:assert/strict');
const {spawn,spawnSync}=require('node:child_process');
const fs=require('node:fs');
const os=require('node:os');
const path=require('node:path');
const bin=path.resolve(__dirname,'../bin/claude-310.mjs');
const runtime=process.env.CLAUDE_E2E_RUNTIME||process.execPath;
function sleep(ms){return new Promise(resolve=>setTimeout(resolve,ms))}
function envFor(home){
  return {...process.env,HOME:home,CLAUDE_CONFIG_DIR:path.join(home,'.claude'),
    CI:'true',TERM:'dumb',NO_COLOR:'1',CLAUDE_CODE_SIMPLE:'1',
    DISABLE_TELEMETRY:'1',CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC:'1',
    ANTHROPIC_API_KEY:'',ANTHROPIC_AUTH_TOKEN:'',HTTP_PROXY:'',HTTPS_PROXY:'',ALL_PROXY:'',
    NO_PROXY:'127.0.0.1,localhost'};
}
function status(home,env){
  return spawnSync(runtime,[bin,'daemon','status'],{
    cwd:home,env,encoding:'utf8',timeout:13000,maxBuffer:2*1024*1024
  });
}
async function startAndAssert(home,env){
  const child=spawn(runtime,[bin,'daemon','run','--origin','foreground','--log-file',path.join(home,'daemon.log')],{
    cwd:home,env,stdio:['ignore','pipe','pipe']
  });
  let stdout='',stderr='';
  child.stdout.on('data',x=>stdout+=String(x));
  child.stderr.on('data',x=>stderr+=String(x));
  let last;
  const started=Date.now();
  while(Date.now()-started<18000){
    if(child.exitCode!==null)break;
    const st=status(home,env);
    last=st;
    if(st.status===0 && st.stdout.includes('pid:') &&
       new RegExp('pid:\\s*'+child.pid+'\\b').test(st.stdout))return {child,stdout:()=>stdout,stderr:()=>stderr};
    await sleep(250);
  }
  child.kill('SIGKILL');
  const logfile=path.join(home,'daemon.log');
  const logfileText=fs.existsSync(logfile)?fs.readFileSync(logfile,'utf8').slice(-5000):'(no explicit daemon log)';
  const tree=fs.readdirSync(home,{recursive:true}).slice(0,80).join(',');
  throw Error('Real daemon not healthy; log='+logfileText+' files='+tree+' pid='+child.pid+' childExit='+child.exitCode+
    ' stdout='+stdout.slice(-2000)+' stderr='+stderr.slice(-4000)+
    ' status='+JSON.stringify({code:last?.status,stdout:last?.stdout?.slice(-2400),stderr:last?.stderr?.slice(-2400)}));
}
async function stop(child){
  if(child.exitCode===null)child.kill('SIGTERM');
  const ended=await Promise.race([
    new Promise(resolve=>child.once('close',(code,signal)=>resolve({code,signal}))),
    new Promise(resolve=>setTimeout(()=>resolve(null),13000))
  ]);
  if(!ended){child.kill('SIGKILL');await sleep(300);}
  return ended;
}
test('真实 Daemon supervisor 启动、status PID、一致性退出及跨进程重启',{timeout:100000},async()=>{
  const home=fs.mkdtempSync(path.join(os.tmpdir(),'111-daemon-live-'));
  const env=envFor(home);
  let first,second;
  try{
    first=await startAndAssert(home,env);
    assert.equal(first.child.exitCode,null,'daemon unexpectedly exited');
    const alive=status(home,env);
    assert.equal(alive.status,0,alive.stderr);
    assert.match(alive.stdout,/version:\s*2\.1\.310/);
    await stop(first.child);
    let stale;
    for(let i=0;i<8;i++){
      stale=status(home,env);
      if(/not running/.test(stale.stdout))break;
      await sleep(300);
    }
    assert.match(stale.stdout,/not running/,'supervisor lock not released after SIGTERM; '+stale.stdout.slice(-1500));
    second=await startAndAssert(home,env);
    assert.notEqual(second.child.pid,first.child.pid,'restart reused previous daemon PID');
    const restarted=status(home,env);
    assert.ok(restarted.stdout.includes(String(second.child.pid)),
      'new supervisor not discoverable: '+restarted.stdout);
  }finally{
    if(second)await stop(second.child);
    if(first&&first.child.exitCode===null)first.child.kill('SIGKILL');
    fs.rmSync(home,{recursive:true,force:true});
  }
});
