'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');

test('real plugin equality code handles nested objects, subsets, cyclic references and typed containers',async()=>{
 const {equals}=await import('../plugin-testing-kit/equality/equals/equals.js');
 assert.equal(equals({a:{b:1}}, {a:{b:1}},'strict'),true);
 assert.equal(equals({a:{b:1}}, {a:{b:2}},'strict'),false);
 assert.equal(equals({a:1,extra:3},{a:1},'subset'),true);
 assert.equal(equals({a:1,extra:3},{a:1},'strict'),false);
 assert.equal(equals(new Map([['key',{v:3}]]),new Map([['key',{v:3}]]),'strict'),true);
 assert.equal(equals(new Map([['key',1]]),new Map([['key',2]]),'strict'),false);
 assert.equal(equals(new Set([1,2]),new Set([2,1]),'strict'),true);
 assert.equal(equals(new Date(123),new Date(124),'strict'),false);
 const a={id:'a'},b={id:'a'};a.self=a;b.self=b;
 assert.equal(equals(a,b,'strict'),true);
});
test('actual mockStore dispatches get/set/delete/keys and keeps independent held state',async()=>{
 const {mockStore}=await import('../plugin-testing-kit/mock/mock-store/mock-store.js');
 const handlers=new Map();
 mockStore((event,handler)=>handlers.set(event,handler),{alpha:3});
 assert.equal(handlers.get('store.get')(null,{key:'alpha'}).value,3);
 assert.deepEqual(handlers.get('store.keys')().value,['alpha']);
 handlers.get('store.set')(null,{key:'beta',value:8});
 assert.equal(handlers.get('store.get')(null,{key:'beta'}).value,8);
 handlers.get('store.delete')(null,{key:'alpha'});
 assert.equal(handlers.get('store.get')(null,{key:'alpha'}).value,undefined);
 assert.deepEqual(handlers.get('store.keys')().value,['beta']);
 assert.throws(()=>mockStore(()=>{},null),TypeError);
});
test('actual mockSession only journals successful transformed session append messages',async()=>{
 const {mockSession}=await import('../plugin-testing-kit/mock/mock-session/mock-session.js');
 let hook=null;
 const journal=mockSession((event,handler)=>{assert.equal(event,'session.append');hook=handler});
 const accepted=await hook({}, {message:{content:'pre'}},async e=>({...e,message:{content:'normalized'}}));
 assert.equal(accepted.message.content,'normalized');
 assert.equal(journal.appended().length,1);
 assert.equal(journal.appended()[0].message.content,'normalized');
 await hook({}, {message:{content:'denied'}},async()=>({deny:'policy'}));
 assert.equal(journal.appended().length,1);
});
test('embedded state atom freezes initial nested object, request membership and preserves source atom',async()=>{
 const {atom,memberOf}=await import('../embedded/runtime/state-library.js');
 const initial={a:{b:1}};
 const a=atom({plugin:'demo',key:'value'},initial);
 assert.equal(Object.isFrozen(a),true);
 assert.equal(Object.isFrozen(a.initial.a),true);
 assert.equal(Object.isFrozen(a.ref),true);
 const member=memberOf(a,{requestId:'R-1'});
 assert.equal(member.ref.id,'R-1');
 assert.equal(a.ref.id,undefined);
 assert.equal(member.initial,a.initial);
});
function stateFixture(initial={}){
 const map=new Map(Object.entries(initial)),versions=new Map();
 const key=ref=>[ref.plugin,ref.key,ref.id||''].join(':');
 return {
  map,versions,key,
  state:{
   async get(ref){const id=key(ref);return{value:map.get(id),version:versions.get(id)||0}},
   async set(ref,value,{ifVersion}){
    const id=key(ref),version=versions.get(id)||0;
    if(ifVersion!==version)return{isSet:false};
    map.set(id,value);versions.set(id,version+1);return{isSet:true};
   }
  }
 };
}
test('embedded state read/derive memoizes by versions, update uses CAS, and shaped atom detects stale data',async()=>{
 const {atom,derive,read,update}=await import('../embedded/runtime/state-library.js');
 const fixture=stateFixture();const $={state:fixture.state};
 const a=atom({plugin:'demo',key:'a'},2);
 const b=atom({plugin:'demo',key:'b'},5);
 let computed=0;
 const total=derive([a,b],(x,y)=>{computed++;return x+y});
 assert.equal(await read($,total),7);
 assert.equal(await read($,total),7);
 assert.equal(computed,1);
 assert.equal(await update($,a,x=>x+3),5);
 assert.equal(await read($,total),10);
 assert.equal(computed,2);
 const shaped=atom({plugin:'demo',key:'shaped'},11,{shape:'s1'});
 fixture.map.set(fixture.key(shaped.ref),{shape:'obsolete',value:999});
 assert.equal(await read($,shaped),11);
 assert.equal(await update($,shaped,x=>x+1),12);
 assert.deepEqual(fixture.map.get(fixture.key(shaped.ref)),{shape:'s1',value:12});
});
test('embedded state optimistic update retries version conflicts but stops after 64 failures',async()=>{
 const {atom,update}=await import('../embedded/runtime/state-library.js');
 const a=atom({plugin:'demo',key:'retry'},0);
 let reads=0,writes=0;
 const state={
  async get(){reads++;return{value:reads===1?2:4,version:reads===1?0:1}},
  async set(ref,val,{ifVersion}){
   writes++;return{isSet:ifVersion===1};
  }
 };
 assert.equal(await update({state},a,n=>n+1),5);
 assert.equal(reads,2);assert.equal(writes,2);
 let attempts=0;
 const never={
  async get(){return{value:0,version:0}},
  async set(){attempts++;return{isSet:false}}
 };
 await assert.rejects(()=>update({state:never},a,n=>n+1),/bound on tries/);
 assert.equal(attempts,64);
});
