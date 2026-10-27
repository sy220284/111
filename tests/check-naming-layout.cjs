'use strict';
// 以原版 BunFS 键为依据，验证命名迁移的无损性；不要把合法八字母词尾误判成随机散列。
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
  assert.ok(entry.path.startsWith('resources/') || entry.path.startsWith('src/'), `无效的目标路径：${bunfsName}`);
  assert.ok(!targetPaths.has(entry.path), `目标路径冲突：${entry.path}`);
  targetPaths.add(entry.path);
  const absolute = path.resolve(root, entry.path);
  assert.ok(absolute.startsWith(root + path.sep), `目标路径越界：${bunfsName}`);
  assert.ok(fs.existsSync(absolute), `资源丢失：${bunfsName} => ${entry.path}`);
  const data = fs.readFileSync(absolute);
  const actual = crypto.createHash('sha256').update(data).digest('hex');
  assert.equal(actual, entry.decodedSha256, `解压后内容不一致：${bunfsName}`);
  if (entry.path.endsWith('.md')) {
    markdown++;
    const expectedBasename = bunfsName.replace(/\.zst$/, '').replace(/-[a-z0-9]{8}(?=\.md$)/, '');
    assert.equal(path.basename(entry.path), expectedBasename, `Markdown 目标名称与原始键不一致：${bunfsName}`);
    if (entry.path.startsWith('resources/skills/')) {
      skills++;
      assert.equal(path.basename(entry.path), 'SKILL.md', `技能入口命名不规范：${entry.path}`);
      const content = data.toString('utf8');
      assert.ok(content.trim().length > 0, `技能内容为空：${entry.path}`);
      // 原版技能并非全部含 YAML frontmatter；保留原件，不伪造缺失字段。
      if (content.startsWith('---\n') || content.startsWith('---\r\n')) {
        assert.match(content, /^---\r?\n[\s\S]*?description:/, `原版技能元数据缺失：${entry.path}`);
      }
    }
  }
}
// 第二批目标路径逐项按原始 BunFS 键确认（校验有据可查的职责，不推断动态依赖）。
const normalizedBatchTwo = {
  "artifact-plan.html-80a36a32.txt.zst": "resources/templates/artifact/artifact-plan.html",
  "artifact-workshop.html-a79fa64e.txt.zst": "resources/templates/artifact/artifact-workshop.html",
  "component.css-02637b0d.txt.zst": "resources/templates/artifact/component.css",
  "decisions-script.html-e58174c4.txt.zst": "resources/templates/artifact/decisions-script.html",
  "drop_block_probe-bcdfaa4d.py.zst": "resources/tools/evaluation/drop_block_probe.py",
  "permissions_external-df98b90a.txt.zst": "resources/docs/other/permissions-external.txt",
  "prefix_diff-69deb9d3.py.zst": "resources/tools/evaluation/prefix_diff.py",
  "psl-data.json-ffeefa5c.txt.zst": "resources/data/psl-data.json",
  "template.html-101715f9.txt.zst": "resources/templates/artifact/document-editor.html",
  "template.html-268183fd.txt.zst": "resources/templates/artifact/report.html",
  "template.html-cad26093.txt.zst": "resources/templates/artifact/data-table.html",
  "tokens.css-6f9090cf.txt.zst": "resources/templates/artifact/tokens.css",
  "tools-snapshot.json-517c5f68.txt.zst": "resources/data/tools-snapshot.json",
  "validate_palette.py-f97163c4.txt.zst": "resources/tools/design/scripts/validate_palette.py",
  "workshop-page.html-22624ec4.txt.zst": "resources/templates/artifact/workshop-page.html"
};
for (const [key, destination] of Object.entries(normalizedBatchTwo)) {
  assert.equal(layout.entries[key]?.path, destination, `第二批目标路径失配：${key}`);
}

assert.equal(markdown, 83, 'Markdown 资源数量发生变化');
assert.equal(skills, 14, '技能入口数量发生变化');
console.log(JSON.stringify({ result: 'PASS', mappedResources: targetPaths.size, normalizedMarkdown: markdown, skillEntrypoints: skills }));
