'use strict';
const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');
const {fragmentToModuleExports}=require('../helpers/source-extraction.cjs');
const root=path.resolve(__dirname,'../..');
const policy=fs.readFileSync(path.join(root,'src/chunk-2af27mew.js'),'utf8');
const paths=fs.readFileSync(path.join(root,'src/chunk-yhfqc2mr.js'),'utf8');

function policyFixture(){
 const from=policy.indexOf('function parseDaemonAttemptCount(e)');

 assert.ok(from>=0,'original 2.1.310 daemon retry decisions missing');
 class PassingFailure extends Error{}
 const api=vm.runInNewContext(fragmentToModuleExports(policy,'function parseDaemonAttemptCount(e)')+';({parseDaemonAttemptCount,classifyDaemonWorkerFailure,chooseDaemonWorkerExit,_gi,xyr,bgi})',{
  uVt:PassingFailure,
  d:e=>String(e),
  Kj:'fatal', $Ne:'retry'
 });
 return {...api,PassingFailure};
}
function pathFixture(){
 const from=paths.indexOf('function getDaemonStateFilePath()');

 assert.ok(from>=0,'daemon path helpers missing');
 return vm.runInNewContext(fragmentToModuleExports(paths,'function getDaemonStateFilePath()')+';({aD,Xpe,getDaemonStateFilePath,getDaemonLogFilePath})',{
  Le:()=>'/tmp/claude-state',
  o:path.join
 });
}

test('Daemon 重试次数严格限定为正的安全整数',()=>{
 const f=policyFixture();
 assert.equal(f._gi('2'),2);
 assert.equal(f._gi(4),4);
 assert.equal(f._gi(0),0);
 assert.equal(f._gi(-1),0);
 assert.equal(f._gi('invalid'),0);
 assert.equal(f._gi(Number.MAX_SAFE_INTEGER+1),0);
 assert.equal(f._gi,f.parseDaemonAttemptCount);
});
test('Daemon 瞬时故障和持久故障产生不同的原版分类',()=>{
 const f=policyFixture();
 const transient=f.xyr(new f.PassingFailure('busy'));
 const lasting=f.xyr(new Error('unrecoverable'));
 assert.equal(transient.kind,'passing');
 assert.equal(lasting.kind,'lasting');
 assert.match(transient.reason,/Error:.*busy/);
 assert.equal(f.xyr,f.classifyDaemonWorkerFailure);
});
test('Daemon 瞬时故障未达到五次上限时重试，耗尽后终止',()=>{
 const f=policyFixture(),error={kind:'passing',reason:'Error: held'};
 for(const count of [0,1,2,3]){
  const decision=f.bgi(error,count);
  assert.equal(decision.code,'retry',String(count));
  assert.equal(decision.said,'Error: held\n');
 }
 const final=f.bgi(error,4);
 assert.equal(final.code,'fatal');
 assert.match(final.said,/did not pass in 5 tries/);
 assert.equal(f.bgi,f.chooseDaemonWorkerExit);
});
test('Daemon 永久错误直接返回终止码和修复后重启提示',()=>{
 const f=policyFixture();
 const result=f.bgi({kind:'lasting',reason:'Error: broken'},0);
 assert.equal(result.code,'fatal');
 assert.match(result.said,/Start the daemon again/);
 assert.doesNotMatch(result.said,/did not pass in 5 tries/);
});
test('Daemon 状态和日志文件继续使用原始数据目录与固定文件名',()=>{
 const f=pathFixture();
 assert.equal(f.aD(),path.join('/tmp/claude-state','daemon.json'));
 assert.equal(f.Xpe(),path.join('/tmp/claude-state','daemon.log'));
 assert.equal(f.aD,f.getDaemonStateFilePath);
 assert.equal(f.Xpe,f.getDaemonLogFilePath);
});
