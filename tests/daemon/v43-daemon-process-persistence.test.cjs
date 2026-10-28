'use strict';
const {test}=require('node:test');
const assert=require('node:assert/strict');
const cp=require('node:child_process');
const fs=require('node:fs');
const os=require('node:os');
const path=require('node:path');
const script=path.resolve(__dirname,'../fixtures/v43-daemon-persistence-worker.cjs');
function execute(mode,dir){
 const out=cp.spawnSync(process.execPath,[script,mode,dir],{
  encoding:'utf8',timeout:15000,env:{...process.env,NODE_ENV:'test'}
 });
 assert.equal(out.status,0,mode+' process failed: '+out.stderr);
 return JSON.parse(out.stdout.trim());
}
test('2.1.310接管源码：真实磁盘跨两个进程保存、重新启动领取、一次性消费',()=>{
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'claude-v43-process-'));
 try{
  const w=execute('write',dir);
  assert.equal(w.wrote,true);assert.equal(w.agent,'process-1');
  assert.equal(fs.existsSync(path.join(dir,'adopt.json')),true);
  const claimed=execute('claim',dir);
  assert.equal(claimed.loaded.agent,'process-1');
  assert.equal(fs.existsSync(path.join(dir,'adopt.json')),false);
  assert.equal(execute('claim',dir).loaded,null);
  assert.deepEqual(fs.readdirSync(dir),[]);
 }finally{fs.rmSync(dir,{recursive:true,force:true})}
});
