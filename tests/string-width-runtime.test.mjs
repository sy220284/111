import { test } from 'node:test';
import assert from 'node:assert/strict';
import { nodeTerminalStringWidth } from '../runtime/string-width.mjs';
import { le as getTerminalWidth } from '../src/chunk-vw9e882a.js';

test('Node 终端文本宽度：英文、中日韩、组合字符及双宽符号', () => {
  for (const [text, width] of [
    ['', 0], ['Hello', 5], ['A中B', 4], ['テスト', 6],
    ['e\u0301', 1], ['가', 2], ['１', 2], ['\n', 0],
    ['👩🏽', 2], ['👨‍👩‍👧‍👦', 2], ['🇨🇳', 2],
    ['1️⃣', 2], ['\u001b[31m红\u001b[0m', 2],
  ]) {
    assert.equal(nodeTerminalStringWidth(text), width, JSON.stringify(text));
    if (typeof Bun === 'undefined') assert.equal(getTerminalWidth(text), width, JSON.stringify(text));
  }
});
test('原生 Bun.stringWidth 可用时保留原始接口与 ambiguousIsNarrow 选项', () => {
  const original = globalThis.Bun;
  const received = [];
  try {
    globalThis.Bun = { stringWidth: (text, options) => {
      received.push([text, options]);
      return 42;
    } };
    assert.equal(getTerminalWidth('测试'), 42);
    assert.deepEqual(received, [['测试', { ambiguousIsNarrow: true }]]);
  } finally {
    if (original === undefined) delete globalThis.Bun;
    else globalThis.Bun = original;
  }
});
