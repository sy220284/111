'use strict';
const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const {collect,indexOld,matchOne,plan} = require('../scripts/batch-restoration-candidates.cjs');
const tmp=()=>fs.mkdtempSync(path.join(os.tmpdir(),'cc310-restore-'));
const oldFunction=`export function classifyRemoteRequest(request: string) {
  const output = [];
  if (request.startsWith("remote-session-authorized")) {
    output.push("start remote session");
    if (request.includes("authorization-expired")) throw new Error("authorization-expired-error");
  } else if (request.startsWith("remote-session-refused")) {
    output.push("permission denied");
  }
  for (const part of output) { if (part.length > 2) output.push(part.toUpperCase()); }
  return output.join(";");
}`;
const newFunction=`function q(e) {
  const z = [];
  if (e.startsWith("remote-session-authorized")) {
    z.push("start remote session");
    if (e.includes("authorization-expired")) throw new Error("authorization-expired-error");
  } else if (e.startsWith("remote-session-refused")) {
    z.push("permission denied");
  }
  for (const a of z) { if (a.length > 2) z.push(a.toUpperCase()); }
  return z.join(";");
}`;
test('相同结构的跨版本函数恢复唯一候选，不更改源码',()=>{
 const base=tmp(),old=path.join(base,'old'),src=path.join(base,'src');
 fs.mkdirSync(old);fs.mkdirSync(src);
 fs.writeFileSync(path.join(old,'remote.ts'),oldFunction);
 fs.writeFileSync(path.join(src,'chunk-test.js'),newFunction);
 const before=fs.readFileSync(path.join(src,'chunk-test.js'));
 const result=plan({oldRoot:old,newRoot:src,focus:['chunk-test.js']});
 assert.equal(result.scanned.oldFiles,1);assert.equal(result.scanned.newFiles,1);
 assert.equal(result.counts.high,1);assert.equal(result.high[0].old.name,'classifyRemoteRequest');
 assert.equal(result.mode,'只读候选，未执行源码替换');
 assert.deepEqual(fs.readFileSync(path.join(src,'chunk-test.js')),before);
 fs.rmSync(base,{recursive:true,force:true});
});
test('重复结构不擅自定名，已恢复旧符号会被跳过',()=>{
 const base=tmp(),old=path.join(base,'old'),src=path.join(base,'src');
 fs.mkdirSync(old);fs.mkdirSync(src);
 fs.writeFileSync(path.join(old,'a.ts'),oldFunction);
 fs.writeFileSync(path.join(old,'b.ts'),oldFunction.replace('classifyRemoteRequest','classifyOtherRequest'));
 fs.writeFileSync(path.join(src,'chunk-test.js'),newFunction);
 const a=plan({oldRoot:old,newRoot:src,focus:['chunk-test.js']});
 assert.equal(a.counts.high,0);assert.equal(a.counts.ambiguous,0);assert.equal(a.counts.review,0);
 const aliases=path.join(base,'aliases.json');
 fs.writeFileSync(aliases,JSON.stringify({entries:[{source:'src/chunk-test.js',legacy:'q',readable:'existing'}]}));
 const b=plan({oldRoot:old,newRoot:src,focus:['chunk-test.js'],aliasPath:aliases});
 assert.equal(b.scanned.alreadyRestored,1);
 fs.rmSync(base,{recursive:true,force:true});
});
test('源码数量、匹配样本与输出顺序可重复',()=>{
 const base=tmp(),old=path.join(base,'old'),src=path.join(base,'src');
 fs.mkdirSync(old);fs.mkdirSync(src);
 fs.writeFileSync(path.join(old,'a.ts'),oldFunction);
 fs.writeFileSync(path.join(src,'chunk-test.js'),newFunction);
 assert.deepEqual(plan({oldRoot:old,newRoot:src,focus:['chunk-test.js']}),plan({oldRoot:old,newRoot:src,focus:['chunk-test.js']}));
 fs.rmSync(base,{recursive:true,force:true});
});
