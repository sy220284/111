import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { portableBunHash } from '../core/chunk-q1myka9a.js';

test('Node 文件指纹在无 Bun 时稳定且能区分文件变化', () => {
  if (typeof Bun !== 'undefined') return;
  const a=portableBunHash('BEFORE_EDIT_111\n');
  const b=portableBunHash('BEFORE_EDIT_111\n');
  const c=portableBunHash('AFTER_EDIT_111\n');
  assert.equal(typeof a,'bigint');
  assert.equal(a,b);
  assert.notEqual(a,c);
  assert.ok(a>=0n&&a<=(1n<<64n)-1n);
  assert.equal(portableBunHash(Buffer.from('hello')),portableBunHash('hello'));
});

test('Node 文件指纹在独立进程间结果一致', () => {
  if (typeof Bun !== 'undefined') return;
  const source=`import {portableBunHash} from ${JSON.stringify(new URL('../core/chunk-q1myka9a.js',import.meta.url).href)};process.stdout.write(portableBunHash('cross-process').toString())`;
  const child=spawnSync(process.execPath,['--input-type=module','-e',source],{encoding:'utf8',timeout:8000});
  assert.equal(child.status,0,child.stderr);
  assert.equal(child.stdout,portableBunHash('cross-process').toString());
});

test('Bun 运行时保留原始 native Bun.hash 值与类型', () => {
  if (typeof Bun === 'undefined') return;
  assert.equal(portableBunHash('Bun-native-hash'),Bun.hash('Bun-native-hash'));
});
