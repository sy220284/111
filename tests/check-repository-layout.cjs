'use strict';
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const crypto=require('node:crypto');
const root=path.resolve(__dirname,'..');
const r=JSON.parse(fs.readFileSync(path.join(root,'resources/layout/relocations.json'),'utf8'));
assert.equal(r.files.length,453,'迁移文件总数必须保持');
assert.equal(r.files.filter(x=>x.kind==='test').length,68);
assert.equal(r.files.filter(x=>x.kind==='document').length,11);
for(const [kind,count] of Object.entries({'embedded-source':24,'plugin-source':294,'bundled-tool-source':56})) assert.equal(r.files.filter(x=>x.kind===kind).length,count);
const seen=new Set();
for(const x of r.files){
 assert.ok(!seen.has(x.current),'重复目标 '+x.current);seen.add(x.current);
 assert.match(x.originalGitBlob,/^[a-f0-9]{40}$/);
 assert.equal(fs.existsSync(path.join(root,x.old)),false,'旧目录残留 '+x.old);
 assert.equal(fs.existsSync(path.join(root,x.current)),true,'归档缺失 '+x.current);
 if(['embedded-source','plugin-source','bundled-tool-source'].includes(x.kind) && !x.current.endsWith('/source-layout.json')){
  const bytes=fs.readFileSync(path.join(root,x.current));
  const actual=crypto.createHash('sha1').update('blob '+bytes.length+'\0').update(bytes).digest('hex');
  assert.equal(actual,x.originalGitBlob,'独立源码字节发生非预期改变 '+x.current);
 }
}
for(const x of r.files.filter(x=>x.kind==='document')){
 const doc=fs.readFileSync(path.join(root,x.current),'utf8');
 for(const match of doc.matchAll(/\]\((\.?\.?\/[^)#]+)(?:#[^)]*)?\)/g)){
  const dest=path.resolve(root,path.dirname(x.current),decodeURI(match[1]));
  assert.ok(fs.existsSync(dest),x.current+' 链接失效 '+match[1]);
 }
}
const pkg=JSON.parse(fs.readFileSync(path.join(root,'package.json'),'utf8'));
const scripts=Object.values(pkg.scripts).join('\n');
for(const x of r.files.filter(x=>x.kind==='test')){
 assert.ok(!scripts.includes(x.old),'npm仍引用旧测试 '+x.old);
}
console.log('按用途归档校验通过：68 个业务测试、11 份工程文档、374 份独立源码；全部目标存在，旧路径及文档内部链接已核实');
