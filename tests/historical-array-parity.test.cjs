'use strict';
// Cross-version contract: Claude Code 2.1.88 source/src/utils/array.ts
// compared with the real 2.1.310 chunk, not a mock replacement.
const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');
const ts=require('typescript');

const file=path.resolve(__dirname,'../src/chunk-wrtp8qz8.js');
const ast=ts.createSourceFile(file,fs.readFileSync(file,'utf8'),ts.ScriptTarget.Latest,true,ts.ScriptKind.JS);
const names=['intersperseArray','countMatching','uniqueIterable'];
const legacy=['Hxe','F','L'];
const decls=ast.statements.filter(n=>ts.isFunctionDeclaration(n)&&names.includes(n.name?.text));
assert.equal(decls.length,3,'recoverable functions must remain top-level source declarations');
const recovered=vm.runInNewContext(decls.map(n=>n.getText(ast)).join('\n')+
  ';const Hxe=intersperseArray,F=countMatching,L=uniqueIterable;({intersperseArray,countMatching,uniqueIterable,Hxe,F,L})');

// Reference algorithms are transcribed from 2.1.88 utils/array.ts,
// preserving separator indices, truthiness, and insertion order.
function historicalIntersperse(as,separator){
 return as.flatMap((a,i)=>(i?[separator(i),a]:[a]));
}
function historicalCount(arr,pred){
 let n=0;for(const x of arr)n+=+!!pred(x);return n;
}
function historicalUniq(xs){return [...new Set(xs)];}

test('2.1.310 exports keep the original three function identities',()=>{
 for(let i=0;i<names.length;i++)assert.strictEqual(recovered[legacy[i]],recovered[names[i]],legacy[i]);
});
test('2.1.88 intersperse parity: empty, singleton and separator indices',()=>{
 for(const values of [[],['a'],['a','b'],['a','b','c']]){
  const seenOld=[],seenNew=[];
  const expected=historicalIntersperse(values,i=>(seenOld.push(i),'-'+i));
  const actual=recovered.Hxe(values,i=>(seenNew.push(i),'-'+i));
  assert.deepEqual(Array.from(actual),expected);
  assert.deepEqual(seenNew,seenOld);
 }
});
test('2.1.88 count parity: truthy results, falsey results and predicate calls',()=>{
 const data=[0,1,2,3,4,5],cases=[
  x=>x%2===0,x=>x>2,x=>x===99,x=>x? 'truthy':'',x=>NaN,x=>({value:x})
 ];
 for(const predicate of cases)assert.equal(recovered.F(data,predicate),historicalCount(data,predicate));
 assert.equal(recovered.F([],()=>{throw Error('should not call')}),0);
});
test('2.1.88 uniq parity: insertion order and object reference identity',()=>{
 const a={},b={},lists=[[],[1,2,1,3,2],[NaN,NaN,0,-0],[a,b,a],[undefined,null,undefined]];
 for(const input of lists){
  const old=historicalUniq(input),cur=Array.from(recovered.L(input));
  assert.deepEqual(cur,old);
  for(let i=0;i<cur.length;i++)assert.strictEqual(cur[i],old[i]);
 }
 const fromSet=new Set(['x','y']);
 assert.deepEqual(Array.from(recovered.L(fromSet)),historicalUniq(fromSet));
});
test('2.1.310 retains callback error propagation like 2.1.88',()=>{
 assert.throws(()=>recovered.Hxe(['a','b'],()=>{throw Error('separator failure')}),/separator failure/);
 assert.throws(()=>recovered.F([1],()=>{throw Error('predicate failure')}),/predicate failure/);
});
