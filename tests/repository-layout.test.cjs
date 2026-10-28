'use strict';
const { test }=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const crypto=require('node:crypto');
const ROOT=path.resolve(__dirname,'..');
const manifest=JSON.parse(fs.readFileSync(path.join(ROOT,'resources/layout/relocations.json'),'utf8'));
const rows=manifest.files;
const byOld=new Map(rows.map(x=>[x.old,x]));
const byNew=new Set();
const blob=b=>crypto.createHash('sha1').update('blob '+b.length+String.fromCharCode(0)).update(b).digest('hex');
const current=p=>byOld.get(p)?.current||p;
test('所有原件归位后仍有唯一目标、无旧路径、原 Git Blob 与现行文件字节一致',()=>{
 const kinds=Object.groupBy(rows,x=>x.kind);
 assert.equal(kinds['embedded-source'].length,24);
 assert.equal(kinds['plugin-source'].length,294);
 assert.equal(kinds['bundled-tool'].length,54);
 assert.equal(kinds.document.length,16);
 for(const x of rows){
  assert.ok(!byNew.has(x.current),'duplicate: '+x.current);byNew.add(x.current);
  assert.equal(fs.existsSync(path.join(ROOT,x.old)),false,'old file remains: '+x.old);
  const file=path.join(ROOT,x.current);
  assert.ok(fs.existsSync(file),'missing: '+x.current);
  const bytes=fs.readFileSync(file);
  assert.equal(blob(bytes),x.originalGitBlob,'original Git blob changed: '+x.current);
 }
});
test('原始 BunFS 键、新源码及资源路径和证据清单一致',()=>{
 const asset=JSON.parse(fs.readFileSync(path.join(ROOT,'resources/asset-layout.json'),'utf8'));
 const emb=JSON.parse(fs.readFileSync(path.join(ROOT,'resources/embedded-source-layout.json'),'utf8'));
 assert.equal(Object.keys(asset.entries).length,140);
 assert.equal(emb.entries.length,24);
 for(const r of emb.entries){
  assert.ok(r.old.startsWith('src/embedded/'),'pinned old path must remain historical');
  assert.ok(r.path.startsWith('embedded/'));
  assert.ok(fs.existsSync(path.join(ROOT,r.path)));
  const f=rows.find(x=>x.current===r.path);
  assert.equal(f?.originalGitBlob,r.gitBlobSha1,'embedded source byte identity '+r.path);
 }
 for(const item of Object.values(asset.entries)){
  assert.ok(fs.existsSync(path.join(ROOT,item.path)),item.path);
  assert.ok(!item.path.startsWith('resources/tools/'),'tool executable not moved: '+item.path);
 }
});
test('历史工具恢复记录仍然可映射到新位置，辅助资源与相对导入结构保持完整',()=>{
 for(const tool of ['claude-test','design-sync']){
  const index=JSON.parse(fs.readFileSync(path.join(ROOT,'resources/tools',tool,'source-layout.json'),'utf8'));
  for(const item of [...index.migrated,...index.supporting]){
   const dest=current(item.path);
   assert.ok(dest.startsWith('tools/bundled/'),dest);
   const bytes=fs.readFileSync(path.join(ROOT,dest));
   assert.equal(blob(bytes),item.sha,'tool hash drift '+dest);
  }
 }
});
