'use strict';
const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');
const ts=require('typescript');
const legacyMap={yno:'saveUnresumedAdoptTasks',_no:'claimUnresumedAdoptTasksForOwner',egr:'takeUnresumedAdoptTasks',bno:'storeUnresumedFrameEntries',Sno:'claimUnresumedFrameEntriesForOwner',tIn:'takeUnresumedFrameEntries',wno:'appendUnresumedFrameEntries',vno:'removeUnresumedFrameSlug',Eno:'cacheExitRetryFrameEntries',kno:'takeExitRetryFrameEntries'};
const file=path.resolve(__dirname,'../chunk-bwt94x0y.js');
const source=fs.readFileSync(file,'utf8');
const ast=ts.createSourceFile(file,source,ts.ScriptTarget.Latest,true,ts.ScriptKind.JS);
function make(){
 const state={unresumedAdopt:new Map(),unresumedFrameLive:new Map(),exitRetryFrameLive:new Map()};
 const code=[];
 for(const [legacy,readable] of Object.entries(legacyMap)){
  const f=ast.statements.filter(n=>ts.isFunctionDeclaration(n)&&n.name?.text===readable);
  assert.equal(f.length,1,'missing source function '+readable);
  code.push(f[0].getText(ast));
  code.push('const '+legacy+'='+readable+';');
 }
 const vars=[...Object.keys(legacyMap),...Object.values(legacyMap)].join(',');
 const api=vm.runInNewContext(code.join('\n')+';({'+vars+'})',{ole:()=>state});
 return {...api,state};
}
test('十个状态读写入口保留原短符号函数身份',()=>{
 const a=make();for(const [legacy,readable] of Object.entries(legacyMap))assert.equal(a[legacy],a[readable],legacy);
});
test('任务缓存空列表清除记录，非空列表保留所有者身份',()=>{
 const a=make(),v=[{id:'agent'}],w=[{id:'flow'}];
 a.saveUnresumedAdoptTasks('session',v,w,'owner');
 assert.equal(a.state.unresumedAdopt.get('session').owner,'owner');
 assert.deepEqual(a.state.unresumedAdopt.get('session').agents,v);
 a.saveUnresumedAdoptTasks('session',[],[],'owner');
 assert.equal(a.state.unresumedAdopt.has('session'),false);
});
test('只有匹配的任务所有者能够领取并清除缓存',()=>{
 const a=make();a.yno('s',[1],[2],'alice');
 assert.deepEqual(JSON.parse(JSON.stringify(a._no('s','bob'))),{agents:[],workflows:[]});
 assert.equal(a.state.unresumedAdopt.has('s'),true);
 assert.deepEqual(JSON.parse(JSON.stringify(a._no('s','alice'))),{agents:[1],workflows:[2]});
 assert.equal(a.state.unresumedAdopt.has('s'),false);
 assert.deepEqual(JSON.parse(JSON.stringify(a.egr('s'))),{agents:[],workflows:[]});
});
test('Frame 缓存剔除陈旧标记且非所有者不能领取',()=>{
 const a=make(),input=[{slug:'one',stale:true},{slug:'two',title:'t',stale:false}];
 a.bno('s',input,'alice');
 assert.deepEqual(Array.from(a.state.unresumedFrameLive.get('s').entries,x=>({...x})),[{slug:'one'},{slug:'two',title:'t'}]);
 assert.deepEqual([...a.Sno('s','bob')],[]);
 assert.equal(a.state.unresumedFrameLive.has('s'),true);
 assert.deepEqual(Array.from(a.Sno('s','alice'),x=>({...x})),[{slug:'one'},{slug:'two',title:'t'}]);
 assert.equal(a.state.unresumedFrameLive.has('s'),false);
});
test('Frame 追加只允许匹配所有者，新请求复制列表',()=>{
 const a=make(),items=[{slug:'a'}];a.wno('s',items,'alice');items.push({slug:'b'});
 assert.equal(a.state.unresumedFrameLive.get('s').entries.length,1);
 a.wno('s',[{slug:'c'}],'bob');assert.equal(a.state.unresumedFrameLive.get('s').owner,'alice');
 a.wno('s',[],'alice');assert.equal(a.state.unresumedFrameLive.get('s').entries.length,1);
 a.wno('s',[{slug:'d'}],'alice');assert.equal(a.state.unresumedFrameLive.get('s').entries[0].slug,'d');
});
test('Frame 删除必须校验所有者，最后一项删除后清空缓存',()=>{
 const a=make();a.bno('s',[{slug:'a'},{slug:'b'}],'owner');
 a.vno('s','a','other');assert.equal(a.state.unresumedFrameLive.get('s').entries.length,2);
 a.vno('s','a','owner');assert.equal(a.state.unresumedFrameLive.get('s').entries[0].slug,'b');
 a.vno('s','b','owner');assert.equal(a.state.unresumedFrameLive.has('s'),false);
});
test('空 Frame 载荷清除既有状态',()=>{
 const a=make();a.bno('s',[{slug:'a'}],'owner');a.bno('s',[],'other');assert.equal(a.state.unresumedFrameLive.has('s'),false);
});
test('退出重试队列仅存非空快照，领取即消费',()=>{
 const a=make(),items=[{slug:'one'}];a.Eno('s',items);items.push({slug:'two'});
 assert.equal(a.state.exitRetryFrameLive.get('s').length,1);
 assert.deepEqual(Array.from(a.kno('s'),x=>({...x})),[{slug:'one'}]);
 assert.deepEqual([...a.kno('s')],[]);
 assert.deepEqual([...a.kno(undefined)],[]);
 a.Eno('s',[]);assert.equal(a.state.exitRetryFrameLive.has('s'),false);
});
