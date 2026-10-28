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
test('src 原有 2554 个文件归类，2553 个放入 core，独立插件继续在 plugins',()=>{
 assert.equal(manifest.rootFiles,2554);
 assert.equal(manifest.files.length,2554);
 assert.equal(manifest.importAdjusted,0);
 assert.equal(fs.existsSync(path.join(ROOT,'src')),false,'src 旧目录仍存在');
 assert.equal(fs.readdirSync(ROOT).filter(x=>x.endsWith('.js')).length,0,'根目录散落 JavaScript');
 const actual=fs.readdirSync(path.join(ROOT,'core')).filter(x=>x.endsWith('.js')).sort();
 assert.equal(actual.length,2553);
 const seen=new Set();
 for(const item of manifest.files){
  assert.ok(item.old.startsWith('src/'),'非法源路径 '+item.old);
  assert.ok(item.current.startsWith('core/')||item.current.startsWith('plugins/'),'归类错误 '+item.current);
  assert.ok(!seen.has(item.current),'重复目标 '+item.current);
  seen.add(item.current);
  assert.equal(fs.existsSync(path.join(ROOT,item.old)),false,'旧文件残留 '+item.old);
  const target=path.join(ROOT,item.current);
  assert.ok(fs.existsSync(target),'目标缺失 '+item.current);
  assert.equal(blob(fs.readFileSync(target)),item.originalGitBlob,'原件字节被更改 '+item.current);
  assert.equal(item.currentGitBlob,item.originalGitBlob,'迁移清单字节不一致 '+item.current);
 }
 assert.equal([...seen].filter(x=>x.startsWith('core/')).length,2553);
});
test('core 的运行时相对导入恢复为 ../runtime 并能定位到真实文件',()=>{
 const modules=manifest.files.filter(x=>x.current.startsWith('core/'));
 const runtime=modules.filter(x=>fs.readFileSync(path.join(ROOT,x.current),'utf8').includes('../runtime/'));
 assert.equal(runtime.length,38);
 for(const item of runtime){
  const code=fs.readFileSync(path.join(ROOT,item.current),'utf8');
  assert.ok(!code.includes("from './runtime/"),item.current+' 存在错误的平级适配导入');
  for(const match of code.matchAll(/['"]\.\.\/runtime\/([^'"]+)['"]/g)){
   assert.ok(fs.existsSync(path.resolve(ROOT,'core','../runtime',match[1])),item.current+' -> '+match[1]);
  }
 }
 assert.ok(fs.existsSync(path.join(ROOT,'core/cli.js')));
});
