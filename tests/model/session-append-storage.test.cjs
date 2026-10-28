'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root=path.resolve(__dirname,'../..');
const source=fs.readFileSync(path.join(root,'src/chunk-w9w4r9w1.js'),'utf8');
const reference=fs.readFileSync(path.join(root,'resources/docs/plugin-authoring/reference.md'),'utf8');
const begin=source.indexOf('async function dispatchStoredSessionRow(e,o,r)');
const middle=source.indexOf('var xe=(e)',begin);
const tail=source.indexOf('var u=function isSessionRowEligibleForAppend(e)',middle);
const end=source.lastIndexOf('export{');
assert.ok(begin>=0 && middle>begin && tail>middle && end>tail);
const original=source.slice(begin,middle)+'\n'+source.slice(tail,end);

function originalAppend({alter=null,fail=false}={}) {
  const stored=[],hooked=[],errors=[];
  const mocks={
    _: (row,options)=>({message:row,door:'model',origin:'query',...options}),
    O: (config,row,complete)=>({config,row,complete}),
    sa: (event,options)=>async input=>{
      hooked.push({event,origin:options.origin,input});
      if(fail)throw new Error('plugin hook failed');
      if(alter!==null)options.core['session.append'].complete(alter);
    },
    t: value=>errors.push(value),
    d: value=>String(value)
  };
  const funcs=vm.runInNewContext(original+';({D,Te,u})',mocks);
  const context={agentId:'agent-1',keep:rows=>stored.push(...rows),rows:()=>[]};
  return {...funcs,context,stored,hooked,errors};
}

test('session.append 原版存储端只记录可保留消息，进度与临时用户消息不进入 Hook',async()=>{
  const x=originalAppend();
  const rows=[
    {type:'user',uuid:'u1'},
    {type:'progress',uuid:'p1'},
    {type:'user',ephemeral:true,uuid:'u2'},
    {type:'assistant',uuid:'a1'},
    {type:'attachment',uuid:'att1'}
  ];
  const result=await x.Te(x.context,rows,{});
  assert.equal(result.length,5);
  assert.deepEqual(x.hooked.map(r=>r.event),['session.append','session.append','session.append']);
  assert.deepEqual(x.hooked.map(r=>r.input.message.uuid),['u1','a1','att1']);
  assert.deepEqual(x.stored.map(r=>r.uuid),['u1','p1','u2','a1','att1']);
  assert.equal(x.u({type:'progress'}),false);
  assert.equal(x.u({type:'user',ephemeral:true}),false);
  assert.equal(x.u({type:'user',ephemeral:false}),true);
});

test('session.append Hook 无替换时按照 fills 区分正常写入与单轮跟踪',async()=>{
  const row={type:'assistant',uuid:'a1'};
  const regular=originalAppend();
  assert.equal(await regular.D(regular.context,{row,listed:[row]},{}),row);
  assert.equal(regular.stored.length,1);
  const fills=originalAppend();
  assert.equal(await fills.D(fills.context,{row,listed:[row]},{fills:true}),row);
  assert.equal(fills.stored.length,0);
});

test('session.append 有 Hook 替换结果时返回 Hook 提交行且不二次兜底存储',async()=>{
  const row={type:'assistant',uuid:'original'};
  const replacement={type:'assistant',uuid:'modified'};
  const x=originalAppend({alter:replacement});
  assert.equal(await x.D(x.context,{row,listed:[row]},{}),replacement);
  assert.equal(x.stored.length,0);
  assert.equal(x.hooked.length,1);
});

test('session.append 的 Hook 抛错仍保留原消息并记录错误',async()=>{
  const row={type:'user',uuid:'user-1'};
  const x=originalAppend({fail:true});
  assert.equal(await x.D(x.context,{row,listed:[row]},{}),row);
  assert.equal(x.stored.length,1);
  assert.equal(x.errors.length,1);
  assert.match(x.errors[0],/session.append/);
});

test('session.append 原始功能文档与仓库可执行入口关联',()=>{
  assert.match(reference,/session\.append/);
  assert.match(source,/appendedRow/);
  assert.match(source,/rowsAppendedTo/);
});
