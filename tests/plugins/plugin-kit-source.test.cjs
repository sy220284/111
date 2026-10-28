'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync, readdirSync, existsSync } = require('node:fs');
const { execFileSync } = require('node:child_process');
const path = require('node:path');
const vm = require('node:vm');

const root = path.resolve(__dirname, '../..');
const kit = path.join(root, 'plugin-testing-kit');
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

test('294 个插件测试模块完整链接，所有导入名称都由对应模块真实导出', async () => {
  const files = scan(kit);
  const modules = new Map(files.map(file => [file, new vm.SourceTextModule(
    readFileSync(file, 'utf8'), { identifier: file }
  )]));
  const resolve = (specifier, from) => {
    const file = path.resolve(path.dirname(from.identifier), specifier);
    assert.ok(modules.has(file), from.identifier + ' -> ' + specifier);
    return modules.get(file);
  };
  for (const module of modules.values()) {
    if (module.status === 'unlinked') await module.link(resolve);
  }
  assert.deepEqual([...modules.values()].filter(module => module.status !== 'linked').map(module => module.identifier), []);
});

test('插件测试包根入口可实际加载公开导出', async () => {
  const api = await import('../../plugin-testing-kit/index.js');
  assert.equal(typeof api.createCollector, 'function');
  assert.equal(typeof api.equals, 'function');
  assert.equal(typeof api.run, 'function');
});

test('插件测试包公开 test 入口正确转发单回调与带选项回调', async () => {
  const { test: register, collector } = await import('../../plugin-testing-kit/index.js');
  const count = collector.cases().length;
  register('public single callback', () => true);
  register('public options callback', { timeoutMs: 35 }, () => true);
  assert.equal(collector.cases().length, count + 2);
  assert.equal(typeof collector.cases()[count].body, 'function');
  assert.equal(collector.cases()[count + 1].timeoutMs, 35);
  assert.equal(typeof collector.cases()[count + 1].body, 'function');
});

test('实际恢复的结构化相等函数处理对象、循环引用与不等值', async () => {
  const { equals } = await import('../../plugin-testing-kit/equality/equals/equals.js');
  assert.equal(equals({ x: [1, 2] }, { x: [1, 2] }, 'strict'), true);
  assert.equal(equals({ x: [1, 2] }, { x: [1, 3] }, 'strict'), false);
  const left = { name: 'a' }; left.self = left;
  const right = { name: 'a' }; right.self = right;
  assert.equal(equals(left, right, 'strict'), true);
});

test('实际恢复的插件测试收集器执行分组、用例注册和关闭状态机', async () => {
  const { createCollector } = await import('../../plugin-testing-kit/collector/create-collector/create-collector.js');
  const collector = createCollector();
  assert.equal(collector.fileTier(), 'user');
  collector.describe('hook', () => collector.test('works', () => true));
  collector.test('with options', { timeoutMs: 42 }, () => true);
  assert.equal(collector.cases().length, 2);
  assert.equal(collector.cases()[1].timeoutMs, 42);
  assert.match(collector.cases()[0].title, /works/);
  collector.close();
  assert.throws(() => collector.test('late', () => true), /while the file loads/);
});

test('插件测试入口到运行器：注册、宿主初始化、执行及失败回收', async () => {
  const { install, test: register, run } = await import('../../plugin-testing-kit/index.js');
  const opened = [];
  const closed = [];
  const host = {
    now: () => Date.now(),
    open: payload => {
      const id = 'seat-' + (opened.length + 1);
      opened.push({ id, request: JSON.parse(payload) });
      return id;
    },
    close: async id => {
      closed.push(id);
      return { told: '' };
    },
  };
  install(host, '[]');
  register('full-chain success', () => true);
  register('full-chain failure', () => { throw new Error('plugin body failed'); });
  const result = JSON.parse(await run());
  assert.ok(result.length >= 2);
  const pass = result.find(row => row.title === 'full-chain success');
  const fail = result.find(row => row.title === 'full-chain failure');
  assert.equal(pass?.failure, null);
  assert.match(fail?.failure, /plugin body failed/);
  assert.equal(opened.length, result.length);
  assert.equal(closed.length, result.length);
  assert.ok(opened.every(item => item.request.tier === 'user'));
  assert.deepEqual(closed.sort(), opened.map(x => x.id).sort());
});
