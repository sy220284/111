'use strict';
const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');
const source=fs.readFileSync(path.resolve(__dirname,'../chunk-t2adf9g4.js'),'utf8');
function fragment(start,end){const a=source.indexOf(start),b=source.indexOf(end,a+start.length);assert.ok(a>=0&&b>a);return source.slice(a,b)}
function absent(reply){const code=fragment('async function isDaemonJobConfirmedAbsent(e)','var Et=');return vm.runInNewContext(code+';({Vxn,isDaemonJobConfirmedAbsent})',{bh:async()=>reply,Oc:7})}
function format(){const code=fragment('function formatUnpushedCommitCount(e)','async function isExpectedGitHead(e,n)');return vm.runInNewContext(code+';({Oeo,_cs,formatUnpushedCommitCount,formatUnpushedCommitSummary})',{wn:()=>'',I:(n,w)=>n===1?w:w+'s'})}
function disconnected(){const code=fragment('function isDaemonConnectionFailureMessage(e)','var At=');return vm.runInNewContext(code+';({Deo,isDaemonConnectionFailureMessage})',{vn:()=>"Couldn't reach the Daemon"})}
test('只有 has 协议明确回应 present=false 才能确认作业不存在',async()=>{
 for(const [reply,expected] of [
 [{ok:true,op:'has',present:false},true],
 [{ok:true,op:'has',alive:false},false],
 [{ok:true,op:'has',present:true},false],
 [{ok:false,code:'ENOCONN',present:false},false],
 [{ok:true,op:'list',present:false},false]
 ]){
  const api=absent(reply);
  assert.equal(api.Vxn,api.isDaemonJobConfirmedAbsent);
  assert.equal(await api.Vxn('job'),expected);
 }
});
test('未推送提交的数量和分支摘要保持原版格式',()=>{
 const f=format();assert.equal(f.Oeo,f.formatUnpushedCommitCount);assert.equal(f._cs,f.formatUnpushedCommitSummary);
 assert.equal(f.Oeo({count:1,branch:'feat'}),'1 unpushed commit on feat');
 assert.equal(f.Oeo({count:2}),'2 unpushed commits on detached HEAD');
 assert.equal(f._cs({count:2,branch:'feat',shortSha:'abc',subject:'Fix'}),'2 unpushed commits on feat: abc Fix and 1 more');
});
test('Daemon 连接错误只匹配约定的前缀',()=>{
 const f=disconnected();assert.equal(f.Deo,f.isDaemonConnectionFailureMessage);
 assert.equal(f.Deo("Couldn't reach the Daemon — restart"),true);
 assert.equal(f.Deo('Unrelated error'),false);
});
