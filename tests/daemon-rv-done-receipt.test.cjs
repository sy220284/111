'use strict';
// Real Unix-domain rendezvous socket, production BackgroundWorker and durable job state.
// Each scenario runs in its own Node process to avoid shared configuration caches.
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
  const home=fs.mkdtempSync(path.join(os.tmpdir(),'111-rv-done-receipt-'));
  try{
    const script=`
      import {fpe} from ${JSON.stringify(workerURL)};
      import {_o} from ${JSON.stringify(stateURL)};
      import {mkdirSync,writeFileSync} from 'node:fs';
      import {createServer} from 'node:net';
      import {join} from 'node:path';
      const mode=process.argv[1],short='e5f6a7b8',dir=_o(short);
      mkdirSync(dir,{recursive:true});
      const receipt=(state,extra={})=>JSON.stringify({
        state,tempo:'idle',detail:'durable-'+state,output:{summary:'persisted-'+state},
        template:'prompt',intent:'rv-done-receipt',sessionId:'rv-done-session',
        cwd:process.cwd(),createdAt:new Date().toISOString(),
        updatedAt:new Date().toISOString(),...extra
      });
      const stateFile=join(dir,'state.json');
      if(mode!=='absent')writeFileSync(stateFile,receipt(mode==='queued'?'done':mode==='flush'?'working':mode,
        mode==='queued'?{queuedPrompt:'continue the next turn'}:{}));
      const sock=join(process.cwd(),'rv.sock');
      let delivered=0;
      const server=createServer(socket=>{
        let data='';
        socket.on('data',chunk=>{
          data+=chunk.toString();
          if(delivered===0&&data.includes('\\n')){
            delivered++;
            socket.write(JSON.stringify({type:'done',outcome:'done'})+'\\n');
            if(mode==='flush')setTimeout(()=>writeFileSync(stateFile,receipt('done')),15);
          }
        });
      });
      await new Promise((resolve,reject)=>{server.once('error',reject);server.listen(sock,resolve)});
      const dispatch={short,sessionId:'rv-done-session',createdAt:Date.now(),
        cwd:process.cwd(),source:'shell',launch:{mode:'prompt',args:[]},
        env:{},isolation:'none',respawnFlags:[]};
      const worker=new fpe(dispatch,()=>{},undefined,'cold',undefined,undefined,undefined);
      worker.rvSockPath=sock;
      worker.phase={kind:'running'};
      worker.workerReady=true;
      worker.attempt=1;
      worker.lastSpawnAt=Date.now()-10000;
      worker.connectRv();
      const started=Date.now();
      while(Date.now()-started<3500){
        if(delivered&&((mode==='queued'&&!worker.record.outcome)||
          (mode!=='queued'&&worker.record.outcome)))break;
        await new Promise(resolve=>setTimeout(resolve,20));
      }
      // A queued follow-up must remain available to the real process-exit path.
      if(mode==='queued'){
        await new Promise(resolve=>setTimeout(resolve,100));
        await worker.onExit(0,undefined,undefined);
      }
      const result={delivered,outcome:worker.record.outcome??null,
        state:worker.record.state,detail:worker.record.detail,
        output:worker.record.output??null,phase:worker.phase.kind};
      worker.clearLiveness();
      if(worker.backoffTimer)clearTimeout(worker.backoffTimer);
      await new Promise(resolve=>server.close(resolve));
      process.stdout.write(JSON.stringify(result)+'\\n');
    `;
    const result=spawnSync(process.execPath,['--input-type=module','-e',script,mode],{
      cwd:home,encoding:'utf8',timeout:15000,
      env:{...process.env,HOME:home,CLAUDE_CONFIG_DIR:path.join(home,'.claude'),
        CI:'true',DISABLE_TELEMETRY:'1',CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC:'1'}
    });
    assert.equal(result.status,0,'rendezvous subprocess failed: '+result.stderr.slice(-3500));
    return JSON.parse(result.stdout.trim().split('\n').at(-1));
  }finally{fs.rmSync(home,{recursive:true,force:true})}
}

for(const [state,outcome] of [['done','done'],['failed','failed'],['stopped','killed']]){
  test('真实 rendezvous 完成信号必须交付磁盘 '+state+' 状态与成果',{timeout:25000},()=>{
    const result=run(state);
    assert.equal(result.delivered,1);
    assert.equal(result.outcome,outcome,JSON.stringify(result));
    assert.equal(result.state,state);
    assert.equal(result.detail,'durable-'+state);
    assert.deepEqual(result.output,{summary:'persisted-'+state});
  });
}
test('rendezvous 信号先到、持久化稍晚写完时仍恢复真实结果',{timeout:25000},()=>{
  const result=run('flush');
  assert.equal(result.outcome,'done',JSON.stringify(result));
  assert.equal(result.detail,'durable-done');
  assert.deepEqual(result.output,{summary:'persisted-done'});
});
test('rendezvous 完成信号不得吞掉磁盘 queuedPrompt，退出后走统一续跑路径',{timeout:25000},()=>{
  const result=run('queued');
  assert.equal(result.delivered,1);
  assert.equal(result.outcome,null,JSON.stringify(result));
  assert.equal(result.phase,'spawning');
  assert.match(result.detail,/respawning/);
});
test('没有持久化状态时保留显式 rendezvous 完成回执的兼容行为',{timeout:25000},()=>{
  const result=run('absent');
  assert.equal(result.outcome,'done',JSON.stringify(result));
});
