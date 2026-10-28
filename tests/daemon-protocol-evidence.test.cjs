'use strict';
const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const path=require('node:path');
const ts=require('typescript');
const file=path.resolve(__dirname,'../src/chunk-t2adf9g4.js');
const ast=ts.createSourceFile(file,fs.readFileSync(file,'utf8'),ts.ScriptTarget.Latest,true,ts.ScriptKind.JS);
const entries={at:'daemonLocalRecordPath',Ue:'daemonStorageRecordKey',yt:'daemonStorageJsonKey',Ee:'isDaemonRegistryDirectoryAvailable',Gn:'readDaemonGestureRecord',nn:'parseDaemonGestureRecord',rn:'isDaemonProcessGoneOrReused',qn:'isDaemonRosterRecordStale',tr:'mapDaemonErrorCode',Te:'bufferMatchesAtOffset',Ie:'countBufferPrefixOverlap',y3t:'classifyDaemonSessionTarget'};
function fixture(other={}){
 const needed=new Set([...Object.values(entries),'isDaemonRecordExpired']);
 const parts=ast.statements.filter(n=>ts.isFunctionDeclaration(n)&&needed.has(n.name?.text)).map(n=>n.getText(ast));
 assert.equal(parts.length,needed.size);
 for(const [oldName,newName] of Object.entries(entries))parts.push('const '+oldName+'='+newName+';');
 parts.push('const $e=isDaemonRecordExpired;');
 const dependencies={pe:(a,b)=>a+'/'+b,iye:()=>'/daemon',E3n:'beacon',We:{daemon:p=>p.join('|')},rt:async()=>({isDirectory:()=>true,mtimeMs:100}),E:e=>e.code,bl:async()=>null,ot:999,te:JSON.parse,Xa:()=>true,kd:async()=>123,wt:1000,an:{EAGAIN:'temporary'},Sht:/^cloud:/,...other};
 const names=[...Object.keys(entries),...Object.values(entries)].join(',');
 return vm.runInNewContext(parts.join('\n')+';({'+names+'})',dependencies);
}
test('全部原始符号保持同一个函数引用',()=>{
 const f=fixture();for(const [oldName,newName] of Object.entries(entries))assert.equal(f[oldName],f[newName]);
});
test('记录路径和存储索引的构造保持一致',()=>{
 const f=fixture();assert.equal(f.at('job'),'/daemon/job.json');assert.equal(f.Ue('a'),'beacon|a');assert.equal(f.yt('a'),'beacon|a.json');
});
test('目录缺失仅在明确允许时接受',async()=>{
 const enoent=Object.assign(Error('missing'),{code:'ENOENT'});
 const f=fixture({rt:async()=>{throw enoent}});
 assert.equal(await f.Ee(false),false);assert.equal(await f.Ee(true),true);
 assert.equal(await fixture({rt:async()=>({isDirectory:()=>false})}).Ee(),false);
});
test('手势记录必须符合 ID 与时间契约',()=>{
 const f=fixture(),now=Date.now();
 assert.equal(f.nn(JSON.stringify({gestureId:'g',startedAtEpochMs:now})).gestureId,'g');
 for(const v of ['null','{}','{broken}',JSON.stringify({gestureId:1,startedAtEpochMs:now}),JSON.stringify({gestureId:'g',startedAtEpochMs:now+61000})])assert.equal(f.nn(v),null);
});
test('手势读取失败或非法数据不产生虚假记录',async()=>{
 const now=Date.now();
 assert.equal((await fixture({bl:async()=>JSON.stringify({gestureId:'g',startedAtEpochMs:now})}).Gn('r')).gestureId,'g');
 assert.equal(await fixture({bl:async()=>{throw Error('io')}}).Gn('r'),null);
});
test('进程失效和 PID 重用判断保留原逻辑',async()=>{
 assert.equal(await fixture().rn({pid:0}),false);
 assert.equal(await fixture({Xa:()=>false}).rn({pid:12}),true);
 assert.equal(await fixture({kd:async()=>123}).rn({pid:12,procStart:123}),false);
 assert.equal(await fixture({kd:async()=>124}).rn({pid:12,procStart:123}),true);
});
test('roster 时效按 mtime 判断，读取异常返回 false',async()=>{
 assert.equal(await fixture({rt:async()=>({mtimeMs:0})}).qn('a',2000),true);
 assert.equal(await fixture({rt:async()=>({mtimeMs:1500})}).qn('a',2000),false);
 assert.equal(await fixture({rt:async()=>{throw Error('missing')}}).qn('a',2000),false);
});
test('映射错误代码不允许读取原型链',()=>{
 const f=fixture();assert.equal(f.tr('EAGAIN'),'temporary');assert.equal(f.tr('toString'),'unknown_code');
});
test('字节协议按精确偏移判断前缀并检查尾段长度',()=>{
 const f=fixture(),b=Buffer.from('xyzABCdef'),p=Buffer.from('ABC');
 assert.equal(f.Te(b,3,p),true);assert.equal(f.Te(b,4,p),false);assert.equal(f.Te(b,7,p),false);
});
test('分片拼接计算最长尾前缀重叠',()=>{
 const f=fixture();assert.equal(f.Ie(Buffer.from('01ABC'),Buffer.from('ABCZ')),3);assert.equal(f.Ie(Buffer.from('abc'),Buffer.from('zxy')),0);
});
test('云会话与本地 switch 路由区分',()=>{
 const f=fixture();
 assert.deepEqual({...f.y3t('cloud:abc')},{kind:'cloud',sessionId:'cloud:abc'});
 assert.deepEqual({...f.y3t('abc')},{kind:'switch',short:'abc'});
});
