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
      const mode=process.argv[1],short='d3a4b5c6',dir=_o(short);
      mkdirSync(dir,{recursive:true});
      if(mode==='corrupt')writeFileSync(join(dir,'state.json'),'{incomplete-json');
      if(mode==='not-regular')mkdirSync(join(dir,'state.json'));
      const dispatch={short,sessionId:'worker-guard-e2e',createdAt:Date.now(),
        cwd:process.cwd(),source:'shell',launch:{mode:'prompt',args:[]},
        env:{},isolation:'none',respawnFlags:[]};
      const worker=new fpe(dispatch,()=>{},undefined,'cold',undefined,undefined,undefined);
      let replays=0;
      worker.doSpawn=async()=>{replays++};
      await worker.doSpawnUnlessSettledOnDisk();
      process.stdout.write(JSON.stringify({replays,outcome:worker.record.outcome??null,
        state:worker.record.state,detail:worker.record.detail})+'\\n');
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
