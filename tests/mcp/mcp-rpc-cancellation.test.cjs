'use strict';
const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');
const source=fs.readFileSync(path.resolve(__dirname,'../src/chunk-fmf16p94.js'),'utf8');
function makeProtocol({rejectSend=false}={}){
  const start=source.indexOf('request(e,t,r){');
  const end=source.indexOf('async getTask(e,t){',start);
  assert.ok(start>0&&end>start,'original request protocol implementation missing');
  const requestMethod=source.slice(start,end);
  class RpcError extends Error {
    constructor(code,message){super(message);this.code=code;}
    static fromError(code,message){return new RpcError(code,message);}
  }
  const cls=vm.runInNewContext('(class Protocol { '+requestMethod+' })',{
    Mi:RpcError,ea:{RequestTimeout:-32000},ye:30000,eEe:'relatedTask',
    JX:(schema,result)=>({success:true,data:result})
  });
  const frames=[];
  const instance=new cls();
  instance._requestMessageId=1;
  instance._responseHandlers=new Map();
  instance._progressHandlers=new Map();
  instance._requestResolvers=new Map();
  instance._timeoutInfo=new Map();
  instance._options={};
  instance._transport={send:frame=>{frames.push(frame);return rejectSend?Promise.reject(Error('offline')):Promise.resolve();}};
  instance._cleanupTimeout=id=>instance._timeoutInfo.delete(id);
  instance._setupTimeout=(id,ms,total,callback)=>instance._timeoutInfo.set(id,callback);
  instance._onerror=error=>{throw error;};
  return {instance,frames};
}
const flush=()=>new Promise(resolve=>setImmediate(resolve));
test('MCP RPC 响应完成后取消相同信号，不再向服务器发送迟到取消通知',async()=>{
  const {instance,frames}=makeProtocol();
  const signal=new AbortController();
  const promise=instance.request({method:'tools/call',params:{name:'demo'}},{},{signal:signal.signal});
  assert.equal(frames.length,1);
  instance._responseHandlers.get(1)({result:{ok:true}});
  assert.equal((await promise).ok,true);
  signal.abort('too-late');
  await flush();
  assert.equal(frames.length,1,'completed request must not be cancelled afterward');
});
test('MCP RPC 进行中 abort 发出准确 requestId 和 reason，并释放资源',async()=>{
  const {instance,frames}=makeProtocol();
  const controller=new AbortController();
  const pending=instance.request({method:'tools/call'}, {}, {signal:controller.signal});
  controller.abort('user-cancel');
  await assert.rejects(pending,/user-cancel/);
  await flush();
  assert.equal(frames.length,2);
  assert.equal(frames[1].method,'notifications/cancelled');
  assert.equal(frames[1].params.requestId,1);
  assert.equal(frames[1].params.reason,'user-cancel');
  assert.equal(instance._responseHandlers.size,0);
  assert.equal(instance._timeoutInfo.size,0);
});
test('MCP 请求超时后迟到的 abort 不产生第二次取消通知',async()=>{
  const {instance,frames}=makeProtocol();
  const controller=new AbortController();
  const pending=instance.request({method:'tools/call'}, {}, {signal:controller.signal});
  instance._timeoutInfo.get(1)();
  await assert.rejects(pending,/Request timed out/);
  controller.abort('late-cancel');
  await flush();
  assert.equal(frames.filter(f=>f.method==='notifications/cancelled').length,1);
});
test('发送失败时清理挂起句柄，后续中止不会通知一个未发送成功的请求',async()=>{
  const {instance,frames}=makeProtocol({rejectSend:true});
  const controller=new AbortController();
  await assert.rejects(instance.request({method:'tools/call'}, {}, {signal:controller.signal}),/offline/);
  controller.abort('late');
  await flush();
  assert.equal(frames.length,1);
  assert.equal(instance._responseHandlers.size,0);
  assert.equal(instance._timeoutInfo.size,0);
});
test('真实MCP relatedTask队列入列失败后必须释放侧信道请求解析器，禁止孤儿请求',async()=>{
  const {instance,frames}=makeProtocol();
  instance._enqueueTaskMessage=()=>Promise.reject(Error('queue-offline'));
  const controller=new AbortController();
  await assert.rejects(
    instance.request({method:'tools/call',params:{name:'demo'}},{},{signal:controller.signal,relatedTask:{taskId:'task-a'}}),
    /queue-offline/
  );
  assert.equal(instance._requestResolvers.size,0);
  assert.equal(instance._responseHandlers.size,0);
  assert.equal(instance._timeoutInfo.size,0);
  controller.abort('too-late');
  await flush();
  assert.equal(frames.length,0,'enqueue failure must not send a phantom network request');
});
test('真实MCP relatedTask取消或超时后侧信道回调必须删除，避免内存泄漏',async()=>{
  for(const ending of ['abort','timeout']){
    const {instance,frames}=makeProtocol();
    instance._enqueueTaskMessage=async()=>{};
    const controller=new AbortController();
    const pending=instance.request({method:'tools/call',params:{name:'demo'}},{},{
      signal:controller.signal,relatedTask:{taskId:'task-b'}
    });
    assert.equal(instance._requestResolvers.size,1);
    if(ending==='abort')controller.abort('user-stop');
    else instance._timeoutInfo.get(1)();
    await assert.rejects(pending,ending==='abort'?/user-stop/:/Request timed out/);
    assert.equal(instance._requestResolvers.size,0);
    assert.equal(instance._responseHandlers.size,0);
    assert.equal(instance._timeoutInfo.size,0);
    assert.equal(frames.filter(x=>x.method==='notifications/cancelled').length,1);
  }
});
