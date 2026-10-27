'use strict';
const { test }=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const path=require('node:path');
const source=fs.readFileSync(path.resolve(__dirname,'../src/chunk-fbc1ty1s.js'),'utf8');

function fixture({clients=[],transient=new Map()}={}){
 const start=source.indexOf('function findRetryableMcpConnections(n, o, e = !0)');
 const end=source.indexOf('async function R6t(n, o, e, u = (s) => hfr(o, s), d)',start);
 assert.ok(start>=0&&end>start,'real restored MCP retry source is missing');
 const module={getClients:()=>clients};
 const env={
   WLt:item=>item.type==='failed',
   bXe:item=>item.type==='connecting',
   R:()=>({areMcpConfigsEqual:(left,right)=>JSON.stringify(left)===JSON.stringify(right)}),
   xt:()=>({transientRetryPasses:transient}),
   Symbol
 };
 const api=vm.runInNewContext(source.slice(start,end)+
 ';({QRn,aZr,findRetryableMcpConnections,trackTransientMcpRetries})',env);
 return {api,module,transient};
}
const original={
 a:{url:'https://a'},b:{url:'https://b'},c:{url:'https://c'},d:{url:'https://d'}
};
test('MCP retry 仅发现当前仍在失败状态且配置未变化的服务器',()=>{
 const clients=[
   {name:'a',type:'failed',config:{url:'https://a'}},
   {name:'b',type:'failed',config:{url:'https://new-b'}},
   {name:'c',type:'connected',config:{url:'https://c'}},
   {name:'d',type:'disabled',config:{url:'https://d'}}
 ];
 const f=fixture({clients});
 const matched=Array.from(f.api.QRn(original,f.module));
 assert.deepEqual(matched.map(([name])=>name),['a']);
 assert.equal(f.api.QRn,f.api.findRetryableMcpConnections);
});
test('最后一次 MCP retry 允许原版标记为连接中的行，早期尝试不允许',()=>{
 const clients=[
   {name:'a',type:'failed',config:{url:'https://a'}},
   {name:'b',type:'connecting',config:{url:'https://b'}}
 ];
 const f=fixture({clients});
 assert.deepEqual(Array.from(f.api.QRn(original,f.module,false)).map(([name])=>name),['a']);
 assert.deepEqual(Array.from(f.api.QRn(original,f.module,true)).map(([name])=>name),['a','b']);
});
test('retry 当前槽已删除或配置漂移时不得复用历史服务器引用',()=>{
 const f=fixture({clients:[
   {name:'a',type:'failed',config:{url:'https://different'}},
   {name:'z',type:'failed',config:{url:'https://z'}}
 ]});
 assert.equal(Array.from(f.api.QRn(original,f.module)).length,0);
});
test('Transient retry 作用域为每个服务器按引用计数，允许嵌套且离开归零',()=>{
 const f=fixture();
 const outer=f.api.aZr(['a','b']);
 assert.equal(f.transient.get('a'),1);
 assert.equal(f.transient.get('b'),1);
 const inner=f.api.aZr(['a']);
 assert.equal(f.transient.get('a'),2);
 inner[Symbol.dispose]();
 assert.equal(f.transient.get('a'),1);
 outer[Symbol.dispose]();
 assert.equal(f.transient.has('a'),false);
 assert.equal(f.transient.has('b'),false);
 assert.equal(f.api.aZr,f.api.trackTransientMcpRetries);
});
test('Transient retry 对初始已有计数的服务器保持既有引用，不误删',()=>{
 const counts=new Map([['a',2],['other',3]]);
 const f=fixture({transient:counts});
 const scope=f.api.aZr(['a','a','b']);
 assert.equal(counts.get('a'),4);
 assert.equal(counts.get('b'),1);
 scope[Symbol.dispose]();
 assert.equal(counts.get('a'),2);
 assert.equal(counts.get('other'),3);
 assert.equal(counts.has('b'),false);
});
test('原版名称仍保持单一别名与源码级兼容',()=>{
 assert.match(source,/const QRn=findRetryableMcpConnections/);
 assert.match(source,/const aZr=trackTransientMcpRetries/);
 assert.match(source,/export\{findRetryableMcpConnections,trackTransientMcpRetries,/);
});
