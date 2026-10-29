'use strict';
const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const os=require('node:os');
const path=require('node:path');
const vm=require('node:vm');
const {discoverStaticRuntimeModules}=require('../scripts/discover-static-runtime-modules.cjs');

test('真实 VM 静态链接自动发现嵌套 runtime 模块与命名导出',async()=>{
  const root=fs.mkdtempSync(path.join(os.tmpdir(),'111-runtime-discovery-'));
  try{
    fs.mkdirSync(path.join(root,'runtime'),{recursive:true});
    fs.writeFileSync(path.join(root,'runtime','nested.mjs'),'export const value=42;\n');
    fs.writeFileSync(path.join(root,'runtime','adapter.mjs'),'import {value} from "./nested.mjs"; export const result=value;\n');
    const main=new vm.SourceTextModule('import {result} from "../runtime/adapter.mjs"; export const answer=result;',{identifier:'core/main.js'});
    const modules=new Map([['core/main.js',main]]),externalRequests=new Set();
    let imports=0;
    const discovered=discoverStaticRuntimeModules({root,modules,externalRequests,onImport:()=>{imports++}});
    assert.deepEqual(discovered,['runtime/adapter.mjs','runtime/nested.mjs']);
    assert.equal(imports,1);
    await main.link((request,ref)=>modules.get(path.posix.normalize(path.posix.join(path.posix.dirname(ref.identifier),request))));
    await main.evaluate();
    assert.equal(main.namespace.answer,42);
    assert.equal(externalRequests.size,0);
  }finally{fs.rmSync(root,{recursive:true,force:true})}
});

test('不存在的 runtime 静态依赖必须失败而不是跳过',()=>{
  const root=fs.mkdtempSync(path.join(os.tmpdir(),'111-runtime-missing-'));
  try{
    const main=new vm.SourceTextModule('import "../runtime/missing.mjs"',{identifier:'core/main.js'});
    assert.throws(()=>discoverStaticRuntimeModules({root,modules:new Map([['core/main.js',main]]),externalRequests:new Set()}),/ENOENT/);
  }finally{fs.rmSync(root,{recursive:true,force:true})}
});
