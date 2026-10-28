'use strict';
const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const crypto=require('node:crypto');
const cp=require('node:child_process');
const ROOT=path.resolve(__dirname,'..');
const manifest=JSON.parse(fs.readFileSync(path.join(ROOT,'resources/layout/root-source-relocations.json'),'utf8'));
const blob=b=>crypto.createHash('sha1').update('blob '+b.length+'\0').update(b).digest('hex');
test('2,554 项源码平移无丢失、无重复、新旧路径按原件校验',()=>{
 assert.equal(manifest.rootFiles,2554);
 assert.equal(manifest.importAdjusted,38);
 assert.equal(manifest.files.length,2554);
 assert.equal(fs.existsSync(path.join(ROOT,'src')),false,'src/ 仍然存在');
 const seen=new Set(),old=new Set();
 for(const item of manifest.files){
  assert.ok(item.old.startsWith('src/'),'非法历史路径 '+item.old);
  assert.ok(!item.current.startsWith('src/'),'未展平 '+item.current);
  assert.ok(!seen.has(item.current),'目标冲突 '+item.current);
  seen.add(item.current);old.add(item.old);
  assert.equal(fs.existsSync(path.join(ROOT,item.old)),false,'旧路径残留 '+item.old);
  assert.ok(fs.existsSync(path.join(ROOT,item.current)),'目标缺失 '+item.current);
  const bytes=fs.readFileSync(path.join(ROOT,item.current));
  assert.equal(blob(bytes),item.currentGitBlob,'当前 Blob 不一致 '+item.current);
  if(!item.importAdjusted)assert.equal(item.currentGitBlob,item.originalGitBlob,'非导入文件变化 '+item.current);
 }
 assert.equal(old.size,2554);
});
test('38 个运行时导入路径调整均为可复现的唯一字符串迁移',()=>{
 const patched=manifest.files.filter(x=>x.importAdjusted);
 assert.equal(patched.length,38);
 for(const item of patched){
  const origin=cp.execFileSync('git',['show',manifest.baseCommit+':'+item.old],{cwd:ROOT,maxBuffer:24*1024*1024});
  assert.equal(blob(origin),item.originalGitBlob,'原始对象不匹配 '+item.old);
  const original=origin.toString('utf8'),after=fs.readFileSync(path.join(ROOT,item.current),'utf8');
  assert.ok(original.includes('../runtime/'),'非运行时导入被修改 '+item.old);
  assert.equal(after,original.replaceAll('../runtime/','./runtime/'),'源码改动超出导入路径 '+item.old);
 }
});
