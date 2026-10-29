'use strict';
// Actual unverified-adoption timer, real filesystem state and a reaped OS child.
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
  const home=fs.mkdtempSync(path.join(os.tmpdir(),'111-unverified-socket-'));
  try{
    const script=`
      import {fpe} from ${JSON.stringify(workerURL)};
      import {_o} from ${JSON.stringify(stateURL)};
      import {spawnSync} from 'node:child_process';
      import {mkdirSync,writeFileSync} from 'node:fs';
      import {join} from 'node:path';
      const mode=process.argv[1],short='c4d5e6f7',dir=_o(short);
      mkdirSync(dir,{recursive:true});
      if(mode==='corrupt')writeFileSync(join(dir,'state.json'),'{broken');
      else writeFileSync(join(dir,'state.json'),JSON.stringify({
        state:mode,tempo:'idle',detail:'persisted-'+mode,
        output:{summary:'result-'+mode},template:'prompt',
        intent:'unverified-socket',sessionId:'unverified-socket-e2e',
        cwd:process.cwd(),createdAt:new Date().toISOString(),
        updatedAt:new Date().toISOString()
      }));
      const child=spawnSync(process.execPath,['-e','process.exit(0)'],{timeout:10000});
      if(child.status!==0||!child.pid)throw Error('real child did not exit normally');
      const dispatch={short,sessionId:'unverified-socket-e2e',createdAt:Date.now(),
        cwd:process.cwd(),source:'shell',launch:{mode:'prompt',args:[]},
        env:{},isolation:'none',respawnFlags:[]};
      const roster={dispatch,pid:child.pid,attempt:1,startedAt:Date.now()-10000,
        ptySock:join(process.cwd(),'missing-worker.sock'),
        rendezvousSock:join(process.cwd(),'missing-rv.sock'),cliVersion:'2.1.310'};
      const worker=fpe.unverified(short,roster,undefined,undefined);
      // The real production poll interval is 5 seconds; allow its async state read to finish.
      await new Promise(resolve=>setTimeout(resolve,5800));
      process.stdout.write(JSON.stringify({
        outcome:worker.record.outcome??null,state:worker.record.state,
        detail:worker.record.detail,output:worker.record.output??null
      })+'\\n');
    `;
    const result=spawnSync(process.execPath,['--input-type=module','-e',script,mode],{
      cwd:home,encoding:'utf8',timeout:20000,
      env:{...process.env,HOME:home,CLAUDE_CONFIG_DIR:path.join(home,'.claude'),
        CI:'true',DISABLE_TELEMETRY:'1',CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC:'1'}
    });
    assert.equal(result.status,0,'unverified worker subprocess failed: '+result.stderr.slice(-3500));
    return JSON.parse(result.stdout.trim().split('\n').at(-1));
  }finally{fs.rmSync(home,{recursive:true,force:true})}
}

for(const [state,outcome] of [['done','done'],['failed','failed'],['stopped','killed']]){
  test('无法核验 PID 的接管 Worker 套接字丢失后恢复磁盘 '+state+' 回执',{timeout:30000},()=>{
    const result=run(state);
    assert.equal(result.outcome,outcome,JSON.stringify(result));
    assert.equal(result.state,state);
    assert.equal(result.detail,'persisted-'+state);
    assert.deepEqual(result.output,{summary:'result-'+state});
  });
}
for(const state of ['working','corrupt']){
  test('无 PID 验证且套接字消失时拒绝未确认状态的成功回执 '+state,{timeout:30000},()=>{
    const result=run(state);
    assert.equal(result.outcome,'crashed',JSON.stringify(result));
    assert.match(result.detail,/without a confirmed terminal receipt/);
  });
}
