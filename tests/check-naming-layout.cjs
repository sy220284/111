'use strict';
// 规范化后仍以 2.1.310 原始 BunFS 键索引，确保路径、内容与技能入口保持一致。
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '..');
const layout = JSON.parse(fs.readFileSync(path.join(root, 'resources/asset-layout.json'), 'utf8'));
const targetPaths = new Set();
let markdown = 0;
let skills = 0;
assert.equal(Object.keys(layout.entries).length, 140, '已解压重定位的资源总数');
for (const [bunfsName, entry] of Object.entries(layout.entries)) {
  assert.ok(entry.path.startsWith('resources/'), `无效的目标路径：${bunfsName}`);
  assert.ok(!targetPaths.has(entry.path), `目标路径冲突：${entry.path}`);
  targetPaths.add(entry.path);
  const absolute = path.resolve(root, entry.path);
  assert.ok(absolute.startsWith(root + path.sep), `目标路径越界：${bunfsName}`);
  assert.ok(fs.existsSync(absolute), `资源丢失：${bunfsName} => ${entry.path}`);
  const actual = crypto.createHash('sha256').update(fs.readFileSync(absolute)).digest('hex');
  assert.equal(actual, entry.decodedSha256, `解压后内容不一致：${bunfsName}`);
  if (entry.path.endsWith('.md')) {
    markdown++;
    assert.doesNotMatch(path.basename(entry.path), /-[a-z0-9]{8}\.md$/, `Markdown 仍有临时散列名：${entry.path}`);
    if (entry.path.startsWith('resources/skills/')) {
      skills++;
      assert.equal(path.basename(entry.path), 'SKILL.md', `技能入口命名不规范：${entry.path}`);
      const front = fs.readFileSync(absolute, 'utf8');
      assert.match(front, /^---\r?\nname:\s*\S+/m, `技能 frontmatter 不完整：${entry.path}`);
    }
  }
}
assert.equal(markdown, 83, 'Markdown 资源数量发生变化');
assert.equal(skills, 14, '技能入口数量发生变化');
console.log(JSON.stringify({ result: 'PASS', mappedResources: targetPaths.size, normalizedMarkdown: markdown, skillEntrypoints: skills }));
