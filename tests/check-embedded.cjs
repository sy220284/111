'use strict';
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { spawnSync } = require('node:child_process');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '..');
const records = JSON.parse(fs.readFileSync(path.join(root, 'resources/embedded-source-layout.json'), 'utf8')).entries;
const sourceRoot = path.join(root, 'embedded');
const actual = [];
function visit(directory) {
 for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
  const p = path.join(directory, entry.name);
  if (entry.isDirectory()) visit(p); else actual.push(path.relative(root,p).split(path.sep).join('/'));
 }
}
visit(sourceRoot);
assert.equal(records.length, 24, '原始嵌入源码实体数');
assert.equal(new Set(records.map(x => x.path)).size, 24, '迁移目标重名');
assert.deepEqual(actual.sort(), records.map(x=>x.path).sort(), '内嵌源码路径与清单一致');
let checkedJavaScript=0, checkedJSON=0;
for (const entry of records) {
 assert.ok(entry.old.startsWith('src/embedded/'), '无效原始路径');
 assert.ok(entry.path.startsWith('embedded/'), '无效新路径');
 assert.ok(entry.old!==entry.path && !fs.existsSync(path.join(root,entry.old)), '原始路径未清除：'+entry.old);
 const absolute=path.join(root,entry.path), data=fs.readFileSync(absolute);
 assert.ok(data.length>0 && !data.includes(0), '空文件或非法字节：'+entry.path);
 const blobSha1=crypto.createHash('sha1').update('blob '+data.length+String.fromCharCode(0)).update(data).digest('hex');
 assert.equal(blobSha1,entry.gitBlobSha1,'提取源码字节与原始 Git blob 不一致：'+entry.path);
 if (entry.path.endsWith('.mjs') || entry.path.endsWith('.js')) {
   const result=spawnSync(process.execPath,['--check',absolute],{encoding:'utf8'});
   assert.equal(result.status,0,entry.path+': '+result.stderr);
   checkedJavaScript++;
 }
 if (entry.path.endsWith('.json')) {JSON.parse(data.toString('utf8'));checkedJSON++;}
}
console.log(JSON.stringify({ result:'PASS', embeddedSources:records.length, checkedJavaScript,checkedJSON,bytesEqualOriginal:true }));
