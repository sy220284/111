'use strict';
const {test}=require('node:test');
const assert=require('node:assert/strict');
const vm=require('node:vm');
const fs=require('node:fs');
const path=require('node:path');
const source=fs.readFileSync(path.resolve(__dirname,'../src/chunk-23vy5pjb.js'),'utf8');
function fixture({allowed=true,blocked,policyPending=false,now=7200000}={}){
 const start=source.indexOf('function safeTaskFileId(r)');
 const end=source.indexOf('function rTi(r,e,n)',start);
 assert.ok(start>=0&&end>start);
 const host={client:true}, calls=[];
 const api=vm.runInNewContext(source.slice(start,end)+';({safeTaskFileId,removeTaskUploadReferences,diagnosePreparedTaskUpload,de,tTi,nTi})',{
  o9e:/^file_[A-Za-z0-9]+$/,
  U:()=>allowed?{host}:undefined,
  X:async(ids,target)=>calls.push({ids,target}),
  mn:x=>x!==null&&typeof x==='object',
  D:{start_task:{words:{nothingDone:'No task was started.'}}},
  K:3600,se:300000,Date:{now:()=>now},
  iB:()=>policyPending,fJ:()=>blocked,
  v:()=>({error:'egress blocked'}),C:()=>({error:'essential traffic'}),M:()=>({error:'policy pending'})
 });
 return {...api,host,calls,now};
}
test('task file id 检查符合原版格式与原短符号身份',()=>{
 const f=fixture();
 assert.equal(f.safeTaskFileId('file_abc123'),'file_abc123');
 assert.equal(f.safeTaskFileId('bad-file'),'?');
 assert.equal(f.safeTaskFileId(undefined),'?');
 assert.equal(f.de,f.safeTaskFileId);
});
test('仅删除有效上传标识，忽略本地文件与无效描述',async()=>{
 const f=fixture();
 await f.removeTaskUploadReferences({},'context',[{file_id:'file_1'},{path:'/tmp/test'},{file_id:'file_2'},{file_id:42},null]);
 assert.equal(f.calls.length,1);
 assert.deepEqual(Array.from(f.calls[0].ids),['file_1','file_2']);
 assert.equal(f.calls[0].target,f.host);
 assert.equal(f.tTi,f.removeTaskUploadReferences);
});
test('没有远端宿主和非数组输入不执行删除',async()=>{
 const f=fixture({allowed:false});
 await f.removeTaskUploadReferences({},'context',[{file_id:'file_1'}]);
 assert.equal(f.calls.length,0);
 const g=fixture();
 await g.removeTaskUploadReferences({},'context','file_1');
 assert.equal(g.calls.length,0);
});
test('上传过期阈值与审批前余量沿用 2.1.310 原判断',()=>{
 const f=fixture();
 const boundary=f.now-(3600*1000-300000);
 assert.equal(f.diagnosePreparedTaskUpload(boundary,'start_task'),undefined);
 assert.match(f.diagnosePreparedTaskUpload(boundary-1,'start_task'),/expired or are about to/);
 assert.equal(f.nTi,f.diagnosePreparedTaskUpload);
});
test('远端禁用、必需流量限制和未加载策略给出不同失败',()=>{
 assert.equal(fixture({blocked:'egress_blocked'}).diagnosePreparedTaskUpload(7200000,'start_task'),'egress blocked');
 assert.equal(fixture({blocked:'essential_traffic'}).diagnosePreparedTaskUpload(7200000,'start_task'),'essential traffic');
 assert.equal(fixture({policyPending:true}).diagnosePreparedTaskUpload(7200000,'start_task'),'policy pending');
});
test('具名函数是实际模块导出的可访问接口，旧短符号仍在原版调用位置',()=>{
 assert.match(source,/const de=safeTaskFileId/);
 assert.match(source,/const tTi=removeTaskUploadReferences/);
 assert.match(source,/const nTi=diagnosePreparedTaskUpload/);
 assert.match(source,/export\{safeTaskFileId,removeTaskUploadReferences,diagnosePreparedTaskUpload,/);
});
