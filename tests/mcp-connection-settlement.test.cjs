'use strict';
const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');
const source=fs.readFileSync(path.resolve(__dirname,'../core/chunk-fbc1ty1s.js'),'utf8');
function fixture({original={name:'mcp-a',type:'pending',config:{url:'https://a'}},accountChanged=false,blocked,disable=false,match=true}={}){
 const from=source.indexOf('function applyMcpConnectionSettlement(n, o)');
 const to=source.indexOf('function findRetryableMcpConnections(n, o, e = !0)',from);
 assert.ok(from>0&&to>from,'real 2.1.310 MCP settlement handler missing');
 let state={clients:[original],tools:[],commands:[]};
 const disposed=[],logs=[],dropped=[];
 const external={
  areMcpConfigsEqual:(a,b)=>JSON.stringify(a)===JSON.stringify(b),
  detachAndCloseConnection:async entry=>{disposed.push(['detach',entry.name]);},
  dropDiscoveryEntry:async(name,config)=>{dropped.push(name);}
 };
 const env={
  hft:()=>accountChanged,
  R:()=>external,
  se:(name,message)=>logs.push(message),
  T:entry=>disposed.push(['close',entry.name]),
  cu:()=>blocked??null,
  LBn:()=>false,
  Io:()=>disable,
  wi:()=>false,
  FTt:()=>match,
  E:(current,updated)=>({...current,clients:current.clients.map(c=>c.name===updated.name?updated:c)}),
  jb:()=>({error:'disallowed'}),
  ri:(items,key)=>Array.from(new Map(items.map(item=>[item[key],item])).values())
 };
 const fn=vm.runInNewContext(source.slice(from,to)+';({hfr,applyMcpConnectionSettlement})',env);
 const host={applyMcpUpdate:updater=>{state=updater(state);}};
 return {...fn,host,logs,disposed,dropped,get state(){return state}};
}
function result({type='connected',url='https://a',name='mcp-a'}={}){
 return {client:{name,type,config:{url},client:{id:'connection'}},tools:[{name:'Read'}],commands:[{name:'scan'}],attemptEpoch:1};
}
test('正常 MCP 连接仅在配置一致时入库，工具与命令按名字去重',()=>{
 const f=fixture();
 assert.equal(f.hfr(f.host,result()),'applied');
 assert.equal(f.state.clients[0].type,'connected');
 assert.equal(f.state.tools[0].name,'Read');
 assert.equal(f.state.commands[0].name,'scan');
 assert.equal(f.hfr,f.applyMcpConnectionSettlement);
});
test('过期的服务器配置不能污染当前连接，旧连接被清理',()=>{
 const f=fixture();
 assert.equal(f.hfr(f.host,result({url:'https://stale'})),'dropped');
 assert.equal(f.state.clients[0].type,'pending');
 assert.deepEqual(f.disposed,[['close','mcp-a']]);
 const missing=fixture({original:{name:'different',type:'pending',config:{url:'https://a'}}});
 assert.equal(missing.hfr(missing.host,result()),'dropped');
 assert.equal(missing.state.clients[0].name,'different');
});
test('账户切换后完成的远端连接不得安装，会卸载孤立连接',()=>{
 const f=fixture({accountChanged:true});
 assert.equal(f.hfr(f.host,result()),'superseded');
 assert.equal(f.state.clients[0].type,'pending');
 assert.ok(f.disposed.some(([kind])=>kind==='detach'));
});
test('受策略限制的服务器更新不能安装工具或命令',()=>{
 const f=fixture({blocked:'forbidden'});
 assert.equal(f.hfr(f.host,result()),'refused');
 assert.equal(f.state.clients[0].type,'failed');
 assert.equal(f.state.clients[0].error,'disallowed');
 assert.equal(f.state.tools.length,0);
 assert.deepEqual(f.disposed,[['close','mcp-a']]);
 assert.deepEqual(f.dropped,['mcp-a']);
});
test('已禁用 MCP 槽位不接受迟到的连接成功事件',()=>{
 const f=fixture({original:{name:'mcp-a',type:'disabled',config:{url:'https://a'}},disable:true});
 assert.equal(f.hfr(f.host,result()),'refused');
 assert.equal(f.state.clients[0].type,'disabled');
 assert.equal(f.state.tools.length,0);
});
test('旧客户端匹配标志拒绝时不覆盖当前状态',()=>{
 const f=fixture({match:false});
 assert.equal(f.hfr(f.host,result()),'dropped');
 assert.equal(f.state.clients[0].type,'pending');
 assert.equal(f.state.tools.length,0);
});
