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
      const scenario=process.argv[1],upgrade=scenario.startsWith('upgrade-'),mode=upgrade?scenario.slice(8):scenario,short='d3a4b5c6',dir=_o(short);
      mkdirSync(dir,{recursive:true});
      if(mode==='corrupt')writeFileSync(join(dir,'state.json'),'{incomplete-json');
      if(mode==='not-regular')mkdirSync(join(dir,'state.json'));
      if(['done','failed','stopped'].includes(mode))writeFileSync(join(dir,'state.json'),JSON.stringify({state:mode,tempo:'idle',detail:'persisted-'+mode,output:{summary:'result-'+mode},template:'prompt',intent:'worker-receipt',sessionId:'worker-guard-e2e',cwd:process.cwd(),createdAt:new Date().toISOString(),updatedAt:new Date().toISOString()}));
      const dispatch={short,sessionId:'worker-guard-e2e',createdAt:Date.now(),
        cwd:process.cwd(),source:'shell',launch:{mode:'prompt',args:[]},
        env:{},isolation:'none',respawnFlags:[]};
      const worker=new fpe(dispatch,()=>{},undefined,'cold',undefined,undefined,undefined);
      let replays=0,spawnArgs=null;
      worker.doSpawn=async(...args)=>{replays++;spawnArgs=args};
      if(upgrade){
        worker.phase={kind:'upgrading'};
        worker.buildBridgeReattachEnvFromState=async()=>({REATTACH_TEST:'retained'});
        // Invoke the real version-upgrade exit branch, not only the ordinary retry helper.
        worker.onExit(0,undefined,undefined);
        await new Promise(resolve=>setTimeout(resolve,350));
      }else await worker.doSpawnUnlessSettledOnDisk();
      process.stdout.write(JSON.stringify({replays,spawnArgs,outcome:worker.record.outcome??null,
        state:worker.record.state,detail:worker.record.detail,output:worker.record.output??null})+'\\n');
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
test('升级 Worker 确认无旧状态时允许重启并保留桥接环境',{timeout:30000},()=>{
  const result=run('upgrade-absent');
  assert.equal(result.replays,1);
  assert.equal(result.outcome,null);
  assert.deepEqual(result.spawnArgs,[{REATTACH_TEST:'retained'},true]);
});
