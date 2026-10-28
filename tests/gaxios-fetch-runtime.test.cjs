'use strict';
// Executes the actual private Gaxios fetch selector in its original class body,
// without pulling in the unrelated bundled third-party dependencies.
const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');
const source=fs.readFileSync(path.resolve(__dirname,'../chunk-ar74b8kt.js'),'utf8');
function selector({Bun,nativeFetch}){
 const start=source.indexOf('static async#c(){');
 const end=source.indexOf('static mergeHeaders(',start);
 assert.ok(start>=0&&end>start,'Gaxios private fetch selector changed');
 const original=source.slice(start,end);
 const sandbox={process:{versions:{node:'22.16.0'}},globalThis:{fetch:nativeFetch}};
 if(Bun!==undefined)sandbox.Bun=Bun;
 const Gaxios=vm.runInNewContext('(class Gaxios { static #o; '+original+
     'static testSelector(){return this.#c()} })',sandbox);
 return Gaxios;
}
test('Node 22 内置 fetch 可供嵌入式 Gaxios 使用，不动态加载缺失的 node-fetch',async()=>{
 const native=()=>{};
 const gaxios=selector({nativeFetch:native});
 assert.equal(await gaxios.testSelector(),native);
 assert.equal(await gaxios.testSelector(),native,'resolved fetch should remain cached');
});
test('原版 Bun/其他环境的 node-fetch 分支保留，不改动原始加载路径',()=>{
 assert.match(source,/typeof Bun==="undefined"&&typeof globalThis\.fetch==="function"/);
 assert.match(source,/\(await import\("node-fetch"\)\)\.default/);
});
