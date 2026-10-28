'use strict';
const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');
const source=fs.readFileSync(path.resolve(__dirname,'../chunk-9xz7emqc.js'),'utf8');
function fixture(active,send){
 const seen=[];
 const at=source.indexOf('async function stopCurrentSession(e,a)');
 const end=source.indexOf('export{',at);
 assert.ok(at>=0&&end>at);
 const api=vm.runInNewContext(source.slice(at,end)+';({stopCurrentSession,keo})',{
  xyt:()=>active,
  KHn:async(...args)=>{seen.push(args);return await send?.(...args)},
  Cie:()=>({origin:'remote'})
 });
 return {...api,seen};
}
test('stop 优先结束本地活动会话，不发送远端停止请求',async()=>{
 let closed=0,cleanup=0;
 const f=fixture({close:()=>{closed++}},async()=>{throw Error('unexpected remote stop')});
 assert.equal(f.stopCurrentSession,f.keo);
 assert.equal(await f.stopCurrentSession(()=>cleanup++,{storageV5:'store'}),null);
 assert.equal(cleanup,1);
 assert.equal(closed,1);
 assert.equal(f.seen.length,0);
});
test('无本地活动会话时用 storageV5 发送停止命令',async()=>{
 const f=fixture(null,async()=>({ok:true}));
 assert.equal(await f.keo(()=>{}, {storageV5:'store'}),null);
 assert.equal(f.seen.length,1);
 assert.equal(f.seen[0][0],'stop_command');
 assert.equal(f.seen[0][1],'store');
 assert.equal(f.seen[0][2].origin,'remote');
});
test('远端停止失败时不吞掉错误',async()=>{
 const f=fixture(null,async()=>{throw Error('remote timeout')});
 await assert.rejects(f.keo(()=>{}, {storageV5:'store'}),/remote timeout/);
});
