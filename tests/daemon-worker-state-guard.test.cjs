'use strict';
// Execute the production daemon worker class in a fresh Node process against
// a real on-disk job directory. No VM source extraction or mocked state reader.
const {test}=require('node:test');
const assert=require('node:assert/strict');
const {spawnSync}=require('node:child_process');
const fs=require('node:fs');
const os=require('node:os');
const path=require('node:path');
const {pathToFileURL}=require('node:url');
const moduleURL=pathToFileURL(path.resolve(__dirname,'../core/chunk-kj2kfe1q.js')).href;
const stateURL=pathToFileURL(path.resolve(__dirname,'../core/chunk-fnsjs1v2.js')).href;

function run(mode){
  const home=fs.mkdtempSync(path.join(os.tmpdir(),'111-worker-state-'));
  try{
    const script=`
      import {fpe} from ${JSON.stringify(moduleURL)};
      import {_o} from ${JSON.stringify(stateURL)};
      import {mkdirSync,writeFileSync} from 'node:fs';
      import {join} from 'node:path';
      const scenario=process.argv[1],staticUpgrade=scenario.startsWith('static-upgrade-'),upgrade=scenario.startsWith('upgrade-'),retry=scenario.startsWith('retry-'),exitZero=scenario.startsWith('exit-zero-'),exitNonzero=scenario.startsWith('exit-nonzero-'),mode=staticUpgrade?scenario.slice(15):upgrade?scenario.slice(8):retry?scenario.slice(6):exitZero?scenario.slice(10):exitNonzero?scenario.slice(13):scenario,short='d3a4b5c6',dir=_o(short);
      mkdirSync(dir,{recursive:true});
      if(mode==='corrupt')writeFileSync(join(dir,'state.json'),'{incomplete-json');
      if(mode==='not-regular')mkdirSync(join(dir,'state.json'));
      if(mode==='working'||mode==='flush')writeFileSync(join(dir,'state.json'),JSON.stringify({state:'working',tempo:'idle',detail:'unfinished-job',template:'prompt',intent:'worker-receipt',sessionId:'worker-guard-e2e',cwd:process.cwd(),createdAt:new Date().toISOString(),updatedAt:new Date().toISOString()}));
      if(mode==='queued')writeFileSync(join(dir,'state.json'),JSON.stringify({state:'done',tempo:'idle',queuedPrompt:'continue the pending work',detail:'queued-follow-up',template:'prompt',intent:'worker-receipt',sessionId:'worker-guard-e2e',cwd:process.cwd(),createdAt:new Date().toISOString(),updatedAt:new Date().toISOString()}));
      if(['done','failed','stopped'].includes(mode))writeFileSync(join(dir,'state.json'),JSON.stringify({state:mode,tempo:'idle',detail:'persisted-'+mode,output:{summary:'result-'+mode},template:'prompt',intent:'worker-receipt',sessionId:'worker-guard-e2e',cwd:process.cwd(),createdAt:new Date().toISOString(),updatedAt:new Date().toISOString()}));
      const dispatch={short,sessionId:'worker-guard-e2e',createdAt:Date.now(),
        cwd:process.cwd(),source:'shell',launch:{mode:'prompt',args:[]},
        env:{},isolation:'none',respawnFlags:[]};
      let worker=new fpe(dispatch,()=>{},undefined,'cold',undefined,undefined,undefined);
      let replays=0,spawnArgs=null;
      worker.doSpawn=async(...args)=>{replays++;spawnArgs=args};
      if(staticUpgrade){
        // Exercise the production static spawn(..., { afterUpgrade: true }) branch.
        fpe.prototype.doSpawn=async function(...args){replays++;spawnArgs=args};
        fpe.prototype.buildBridgeReattachEnvFromState=async()=>({REATTACH_TEST:'retained'});
        worker=fpe.spawn(dispatch,()=>{},undefined,{afterUpgrade:true},undefined,undefined);
        await new Promise(resolve=>setTimeout(resolve,350));
      }else if(upgrade){
        worker.phase={kind:'upgrading'};
        worker.buildBridgeReattachEnvFromState=async()=>({REATTACH_TEST:'retained'});
        // Invoke the real version-upgrade exit branch, not only the ordinary retry helper.
        worker.onExit(0,undefined,undefined);
        await new Promise(resolve=>setTimeout(resolve,350));
      }else if(exitZero||exitNonzero){
        worker.phase={kind:'running'};
        worker.workerReady=true;
        worker.attempt=1;
        worker.lastSpawnAt=Date.now()-10000;
        if(mode==='flush')setTimeout(()=>writeFileSync(join(dir,'state.json'),JSON.stringify({state:'done',tempo:'idle',detail:'persisted-after-flush',output:{summary:'flushed'},template:'prompt',intent:'worker-receipt',sessionId:'worker-guard-e2e',cwd:process.cwd(),createdAt:new Date().toISOString(),updatedAt:new Date().toISOString()})),15);
        worker.onExit(exitNonzero?137:0,exitNonzero?'SIGKILL':undefined,undefined);
        await new Promise(resolve=>setTimeout(resolve,350));
      }else {
        if(retry)worker.attempt=1; // Simulate a prior launch after the state file disappeared.
        await worker.doSpawnUnlessSettledOnDisk();
      }
      process.stdout.write(JSON.stringify({replays,spawnArgs,outcome:worker.record.outcome??null,
        phase:worker.phase.kind,state:worker.record.state,detail:worker.record.detail,output:worker.record.output??null})+'\\n');
    `;
    const result=spawnSync(process.execPath,['--input-type=module','-e',script,mode],{
      cwd:home,encoding:'utf8',timeout:25000,
      env:{...process.env,HOME:home,CLAUDE_CONFIG_DIR:path.join(home,'.claude'),
        CI:'true',DISABLE_TELEMETRY:'1',CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC:'1'}
    });
    assert.equal(result.status,0,'production worker process failed: '+result.stderr.slice(-3500));
    return JSON.parse(result.stdout.trim().split('\n').at(-1));
  }finally{fs.rmSync(home,{recursive:true,force:true})}
}

test('Worker 状态 JSON 损坏时真实生产恢复链拒绝盲目重放',{timeout:30000},()=>{
  const result=run('corrupt');
  assert.equal(result.replays,0);
  assert.equal(result.outcome,'crashed');
  assert.match(result.detail,/cannot verify persisted job state/);
});

test('Worker 状态文件被替换为目录时不得重复执行任务',{timeout:30000},()=>{
  const result=run('not-regular');
  assert.equal(result.replays,0);
  assert.equal(result.outcome,'crashed');
});

test('Worker 确认没有历史状态时仍允许正常启动',{timeout:30000},()=>{
  const result=run('absent');
  assert.equal(result.replays,1);
  assert.equal(result.outcome,null);
});

for(const [state,outcome] of [['done','done'],['failed','failed'],['stopped','killed']]){
  test('Worker 重启后从真实持久化状态恢复 '+state+' 成果回执且不重放',{timeout:30000},()=>{
    const result=run(state);
    assert.equal(result.replays,0,'已结束任务不得重复执行');
    assert.equal(result.outcome,outcome,JSON.stringify(result));
    assert.equal(result.state,state);
    assert.equal(result.detail,'persisted-'+state,'必须保留真实业务结果描述');
    assert.deepEqual(result.output,{summary:'result-'+state},'必须保留最终输出');
  });
}

for(const [state,outcome] of [['done','done'],['failed','failed'],['stopped','killed']]){
  test('升级 Worker 前读取实际磁盘 '+state+' 回执并防止重复启动',{timeout:30000},()=>{
    const result=run('upgrade-'+state);
    assert.equal(result.replays,0,'升级过程不能绕过已完成任务的磁盘确认');
    assert.equal(result.outcome,outcome,JSON.stringify(result));
    assert.equal(result.state,state);
    assert.equal(result.detail,'persisted-'+state);
    assert.deepEqual(result.output,{summary:'result-'+state});
  });
}
test('升级 Worker 遇到损坏状态不能盲目重新执行',{timeout:30000},()=>{
  const result=run('upgrade-corrupt');
  assert.equal(result.replays,0);
  assert.equal(result.outcome,'crashed');
  assert.match(result.detail,/cannot verify persisted job state/);
});
test('升级 Worker 找不到既有状态时拒绝潜在重复副作用',{timeout:30000},()=>{
  const result=run('upgrade-absent');
  assert.equal(result.replays,0);
  assert.equal(result.outcome,'crashed');
  assert.match(result.detail,/missing after a prior worker launch/);
});
for(const scenario of ['upgrade-working','static-upgrade-working']){
  test('升级任务明确未完成时保持桥接环境并继续启动 '+scenario,{timeout:30000},()=>{
    const result=run(scenario);
    assert.equal(result.replays,1,JSON.stringify(result));
    assert.equal(result.outcome,null);
    assert.deepEqual(result.spawnArgs,[{REATTACH_TEST:'retained'},true]);
  });
}

for(const [state,outcome] of [['done','done'],['failed','failed'],['stopped','killed']]){
  test('静态 spawn(afterUpgrade) 从磁盘恢复 '+state+' 真实结果而不二次执行',{timeout:30000},()=>{
    const result=run('static-upgrade-'+state);
    assert.equal(result.replays,0,'构造器升级恢复不得直接执行已结束任务');
    assert.equal(result.outcome,outcome);
    assert.equal(result.detail,'persisted-'+state);
    assert.deepEqual(result.output,{summary:'result-'+state});
  });
}
test('静态升级入口遇到损坏 state.json 拒绝重放',{timeout:30000},()=>{
  const result=run('static-upgrade-corrupt');
  assert.equal(result.replays,0);
  assert.equal(result.outcome,'crashed');
  assert.match(result.detail,/cannot verify persisted job state/);
});
test('重试任务丢失 state.json 必须阻止潜在重复工具副作用',{timeout:30000},()=>{
  const result=run('retry-absent');
  assert.equal(result.replays,0,'没有证据表明已执行副作用是否完成时不得重放');
  assert.equal(result.outcome,'crashed');
  assert.match(result.detail,/missing after a prior worker launch/);
});
test('静态升级任务丢失状态时不应重复执行',{timeout:30000},()=>{
  const result=run('static-upgrade-absent');
  assert.equal(result.replays,0);
  assert.equal(result.outcome,'crashed');
  assert.match(result.detail,/missing after a prior worker launch/);
});

for(const [state,outcome] of [['done','done'],['failed','failed'],['stopped','killed']]){
  test('Worker 退出码 0 必须以磁盘 '+state+' 终态作为真实成果回执',{timeout:30000},()=>{
    const result=run('exit-zero-'+state);
    assert.equal(result.replays,0);
    assert.equal(result.outcome,outcome,JSON.stringify(result));
    assert.equal(result.state,state);
    assert.equal(result.detail,'persisted-'+state);
    assert.deepEqual(result.output,{summary:'result-'+state});
  });
}
for(const mode of ['corrupt','not-regular','working','absent']){
  test('Worker 退出码 0 但持久化结果不可核验时不得伪报成功 '+mode,{timeout:30000},()=>{
    const result=run('exit-zero-'+mode);
    assert.equal(result.replays,0);
    assert.equal(result.outcome,'crashed',JSON.stringify(result));
    assert.match(result.detail,/without a verified terminal job state/);
  });
}
test('Worker 退出码 0 但仍有排队工作时不得丢弃续跑',{timeout:30000},()=>{
  const result=run('exit-zero-queued');
  assert.equal(result.replays,0,'续跑必须走统一的持久化校验与退避调度');
  assert.equal(result.outcome,null,JSON.stringify(result));
  assert.equal(result.phase,'spawning');
  assert.match(result.detail,/respawning/);
});

test('Worker 退出码 0 等待短暂持久化写入并恢复真正完成回执',{timeout:30000},()=>{
  const result=run('exit-zero-flush');
  assert.equal(result.replays,0);
  assert.equal(result.outcome,'done',JSON.stringify(result));
  assert.equal(result.detail,'persisted-after-flush');
  assert.deepEqual(result.output,{summary:'flushed'});
});

for(const [state,outcome] of [['done','done'],['failed','failed'],['stopped','killed']]){
  test('Worker 被强制异常退出但已落盘 '+state+' 时仍交付真实成果',{timeout:30000},()=>{
    const result=run('exit-nonzero-'+state);
    assert.equal(result.replays,0,'已结束任务不能被重新执行');
    assert.equal(result.outcome,outcome,JSON.stringify(result));
    assert.equal(result.state,state);
    assert.equal(result.detail,'persisted-'+state);
    assert.deepEqual(result.output,{summary:'result-'+state});
  });
}
test('Worker 非零退出后持久化稍晚落盘仍应读取最终回执',{timeout:30000},()=>{
  const result=run('exit-nonzero-flush');
  assert.equal(result.replays,0);
  assert.equal(result.outcome,'done',JSON.stringify(result));
  assert.equal(result.detail,'persisted-after-flush');
  assert.deepEqual(result.output,{summary:'flushed'});
});
test('Worker 异常退出且存在已确认排队任务时继续安排恢复',{timeout:30000},()=>{
  const result=run('exit-nonzero-queued');
  assert.equal(result.replays,0);
  assert.equal(result.outcome,null,JSON.stringify(result));
  assert.equal(result.phase,'spawning');
  assert.match(result.detail,/respawning/);
});
for(const mode of ['working','corrupt','absent']){
  test('Worker 异常退出且未落盘可信终态时不能伪报成功 '+mode,{timeout:30000},()=>{
    const result=run('exit-nonzero-'+mode);
    assert.equal(result.replays,0);
    assert.notEqual(result.outcome,'done',JSON.stringify(result));
    assert.notEqual(result.outcome,'failed',JSON.stringify(result));
  });
}
