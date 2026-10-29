import { test } from 'node:test';
import assert from 'node:assert/strict';
import { YTt, _Qo } from '../core/chunk-8r2m7vfp.js';

// Keep the original Bun JSONL fast path. Under Node, use the existing
// line-oriented parser without referencing a missing Bun global.
test('Node 无 Bun 全局对象时 JSONL 逐行解析仍可读取历史消息', () => {
  if (typeof Bun !== 'undefined') return;
  assert.equal(_Qo(), undefined);
  const rows = [
    { type: 'user', uuid: 'user-111' },
    { type: 'assistant', uuid: 'reply-111' }
  ];
  assert.deepEqual(YTt(rows.map(x => JSON.stringify(x)).join('\n') + '\n'), rows);
  assert.deepEqual(YTt(Buffer.from(rows.map(x => JSON.stringify(x)).join('\n'))), rows);
});

test('Node JSONL 遇到 BOM、空行、无效单行时仍保留其余合法历史记录', () => {
  if (typeof Bun !== 'undefined') return;
  const text = '\ufeff{"type":"user","uuid":"first"}\n\nnot-json\n{"type":"assistant","uuid":"second"}\n';
  assert.deepEqual(YTt(Buffer.from(text)), [
    { type: 'user', uuid: 'first' },
    { type: 'assistant', uuid: 'second' }
  ]);
  assert.deepEqual(YTt(text), [
    { type: 'user', uuid: 'first' },
    { type: 'assistant', uuid: 'second' }
  ]);
});
