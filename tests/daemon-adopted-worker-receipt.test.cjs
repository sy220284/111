'use strict';
// Real Node child processes, production daemon worker class and on-disk job state.
// A synthetic EPERM fault is injected only for the OS permission-denied branch.
const {test}=require('node:test');
const assert=require('node:assert/strict');
const {spawnSync}=require('node:child_process');
const fs=require('node:fs');
const os=require('node:os');
const path=require('node:path');
const {pathToFileURL}=require('node:url');
const workerURL=pathToFileURL(path.resolve(__dirname,'../core/chunk-kj2kfe1q.js')).href;
const stateURL=pathToFileURL(path.resolve(__dirname,'../core/chunk-fnsjs1v2.js')).href;

function run(mode){
  const home=fs.mkdtempSync(path.join(os.tmpdir(),'111-adopted-receipt-'));
  try{
    const script=`
      import {fpe} from ${JSON.stringify(workerURL)};
      import {_o} from ${JSON.stringify(stateURL)};
      import {spawnSync} from 'node:child_process';
      import {mkdirSync,writeFileSync} from 'node:fs';
      import {join} from 'node:path';
      const mode=process.argv[1],short='b3d4e5f6',dir=_o(short);
      mkdirSync(dir,{recursive:true});
      if(mode==='corrupt')writeFileSync(join(dir,'state.json'),'{invalid-json');
      if(['done','failed','stopped','queued','working'].includes(mode)){
        const state=mode==='queued'?'done':mode;
        writeFileSync(join(dir,'state.json'),JSON.stringify({
          state,tempo:'idle',detail:'persisted-'+mode,output:{summary:'result-'+mode},
          ...(mode==='queued'?{queuedPrompt:'finish pending response'}:{}),
          template:'prompt',intent:'adopted-receipt',sessionId:'adopted-receipt-test',
          cwd:process.cwd(),createdAt:new Date().toISOString(),updatedAt:new Date().toISOString()
        }));
      }
      // Reaped real child: its PID is no longer alive, exactly as after a lost supervisor.
      const child=spawnSync(process.execPath,['-e','process.exit(0)'],{timeout:10000});
      if(child.status!==0||!child.pid)throw Error('cannot create reaped worker process');
      const dispatch={short,sessionId:'adopted-receipt-test',createdAt:Date.now(),
        cwd:process.cwd(),source:'shell',launch:{mode:mode==='exec'?'exec':'prompt',args:[]},
        env:{},isolation:'none',respawnFlags:[]};
      const worker=new fpe(dispatch,()=>{},undefined,'adopted',undefined,undefined,undefined);
      worker.phase={kind:'running'};
      worker.workerReady=true;
      worker.attempt=1;
      worker.record.pid=child.pid;
      if(mode==='permission'){
        const original=process.kill;
        process.kill=(pid,signal)=>{
          if(pid===child.pid&&signal===0){const error=new Error('operation not permitted');error.code='EPERM';throw error}
          return original(pid,signal);
        };
        try{await worker.checkPid()}finally{process.kill=original}
      }else{
        await worker.checkPid();
      }
      process.stdout.write(JSON.stringify({
        outcome:worker.record.outcome??null,state:worker.record.state,
        detail:worker.record.detail,output:worker.record.output??null,
        phase:worker.phase.kind,pid:worker.record.pid
      })+'\\n');
    `;
    // The test child is a real independent process importing the production module.
    const result=spawnSync(process.execPath,['--input-type=module','-e',
      script,mode],{
      cwd:home,encoding:'utf8',timeout:25000,
      env:{...process.env,HOME:home,CLAUDE_CONFIG_DIR:path.join(home,'.claude'),
        CI:'true',DISABLE_TELEMETRY:'1',CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC:'1'}
    });
    assert.equal(result.status,0,'real adopted worker subprocess failed: '+result.stderr.slice(-3500));
    return JSON.parse(result.stdout.trim().split('\n').at(-1));
  }finally{fs.rmSync(home,{recursive:true,force:true})}
}

for(const [state,outcome] of [['done','done'],['failed','failed'],['stopped','killed']]){
  test('接管 Worker 消失后读取真实磁盘 '+state+' 成果回执',{timeout:30000},()=>{
    const result=run(state);
    assert.equal(result.outcome,outcome,JSON.stringify(result));
    assert.equal(result.state,state);
    assert.equal(result.detail,'persisted-'+state);
    assert.deepEqual(result.output,{summary:'result-'+state});
  });
}
test('接管 Worker 消失但仍有 queuedPrompt 时保留待续任务',{timeout:30000},()=>{
  const result=run('queued');
  assert.equal(result.outcome,null,JSON.stringify(result));
  assert.equal(result.phase,'spawning');
  assert.match(result.detail,/respawning/);
});
for(const mode of ['working','corrupt','absent']){
  test('接管 Worker 状态不可信时不盲目重放 '+mode,{timeout:30000},()=>{
    const result=run(mode);
    assert.equal(result.outcome,'crashed',JSON.stringify(result));
    assert.match(result.detail,/without a verified terminal job state/);
  });
}
test('进程存在性权限拒绝 EPERM 不应误报 Worker 已崩溃',{timeout:30000},()=>{
  const result=run('permission');
  assert.equal(result.outcome,null,JSON.stringify(result));
  assert.equal(result.phase,'running');
});
test('独立 exec Worker 消失仍按进程失败语义处理',{timeout:30000},()=>{
  const result=run('exec');
  assert.equal(result.outcome,'crashed');
});
