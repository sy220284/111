'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync, readdirSync, existsSync } = require('node:fs');
const { execFileSync } = require('node:child_process');
const path = require('node:path');
const vm = require('node:vm');

const root = path.resolve(__dirname, '..');
const kit = path.join(root, 'src/plugin-testing-kit');
const scan = dir => readdirSync(dir, { withFileTypes: true }).flatMap(item => {
  const file = path.join(dir, item.name);
  return item.isDirectory() ? scan(file) : [file];
});

test('294 个真实源码片段与嵌入原件逐文件保持一致', () => {
  const result = execFileSync(process.execPath,
    [path.join(root, 'scripts/restore-plugin-testing-kit.cjs'), '--check'],
    { encoding: 'utf8' });
  assert.deepEqual(JSON.parse(result).files, 294);
});

test('294 个模块可解析，628 个导入和再导出均指向存在的源码', () => {
  const files = scan(kit);
  assert.equal(files.length, 294);
  let edges = 0;
  for (const file of files) {
    const source = readFileSync(file, 'utf8');
    const parsed = new vm.SourceTextModule(source, { identifier: file });
    for (const specifier of parsed.dependencySpecifiers) {
      assert.match(specifier, /^\.\.?\//, file + ' references ' + specifier);
      assert.ok(specifier.endsWith('.js'), file + ' unresolved extension ' + specifier);
      assert.ok(existsSync(path.resolve(path.dirname(file), specifier)), file + ' -> ' + specifier);
      edges++;
    }
  }
  assert.equal(edges, 628);
});

test('实际恢复的结构化相等函数处理对象、循环引用与不等值', async () => {
  const { equals } = await import('../src/plugin-testing-kit/equality/equals/equals.js');
  assert.equal(equals({ x: [1, 2] }, { x: [1, 2] }, 'strict'), true);
  assert.equal(equals({ x: [1, 2] }, { x: [1, 3] }, 'strict'), false);
  const left = { name: 'a' }; left.self = left;
  const right = { name: 'a' }; right.self = right;
  assert.equal(equals(left, right, 'strict'), true);
});

test('实际恢复的插件测试收集器执行分组、用例注册和关闭状态机', async () => {
  const { createCollector } = await import('../src/plugin-testing-kit/collector/create-collector/create-collector.js');
  const collector = createCollector();
  assert.equal(collector.fileTier(), 'user');
  collector.describe('hook', () => collector.test('works', () => true));
  assert.equal(collector.cases().length, 1);
  assert.match(collector.cases()[0].title, /works/);
  collector.close();
  assert.throws(() => collector.test('late', () => true), /while the file loads/);
});
