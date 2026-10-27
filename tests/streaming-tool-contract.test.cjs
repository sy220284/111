'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = readFileSync(path.resolve(__dirname, '../src/chunk-x3dkgs4r.js'), 'utf8');
const declarations = readFileSync(path.resolve(__dirname, '../resources/tools/plugin-api/types/claude-code-bf9d954c.d.ts'), 'utf8');
const docs = readFileSync(path.resolve(__dirname, '../resources/docs/claude-api/streaming-e0e3c46f.md'), 'utf8');
const toolDocs = readFileSync(path.resolve(__dirname, '../resources/docs/claude-api/tool-use-concepts-c2460f49.md'), 'utf8');

// Execute exact extracted 2.1.310 wire-stream handlers, not recreated equivalents.
function streamFunctions() {
  const first = source.slice(source.indexOf('function lr(e, n)'), source.indexOf('function dr(e, n)'));
  const second = source.slice(source.indexOf('var ct = (e)'), source.indexOf('function La(e, n)'));
  const third = source.slice(source.indexOf('function _r(e, n, s)'), source.indexOf('var hr = (e, n, s)'));
  assert.ok(first.startsWith('function lr') && second.startsWith('var ct') && third.startsWith('function _r'));
  return vm.runInNewContext(first + second + third + ';({lr,Da,pr,Na,Fa,_r})');
}

test('文档中的流事件和工具参数增量与实际 2.1.310 源码契约相符', () => {
  for (const kind of ['message_start', 'content_block_start', 'content_block_delta',
    'content_block_stop', 'message_delta', 'message_stop', 'input_json_delta', 'text_delta', 'thinking_delta']) {
    assert.ok(docs.includes(kind), 'missing streaming documentation: ' + kind);
    assert.ok(source.includes(kind), '2.1.310 missing streaming event: ' + kind);
  }
  assert.match(toolDocs, /tool_result/);
  assert.match(toolDocs, /tool_use_id/);
  assert.match(declarations, /export type ToolCallInput = ToolCallEnvelope/);
  assert.match(declarations, /export type ToolCallResult</);
});

test('真实 SSE 增量处理保留文本、思考和工具 JSON 三类数据与索引', () => {
  const { Da, pr, Na, Fa, _r } = streamFunctions();
  const variants = [
    { kind: 'text', index: 1, text: 'Hello', deltaType: 'text_delta' },
    { kind: 'thinking', index: 2, text: 'reason', deltaType: 'thinking_delta' },
    { kind: 'input', index: 3, json: '{"path":"', deltaType: 'input_json_delta' }
  ];
  for (const part of variants) {
    const wire = Fa(part);
    assert.equal(wire.type, 'stream_event');
    assert.equal(wire.event.type, 'content_block_delta');
    assert.equal(wire.event.index, part.index);
    assert.equal(wire.event.delta.type, part.deltaType);
    const decoded = _r(wire.event.index, wire.event.delta, 'from-model');
    assert.equal(decoded.kind, part.kind);
    assert.equal(decoded.ref, 'from-model');
    assert.equal(part.kind === 'input' ? decoded.json : decoded.text, part.kind === 'input' ? part.json : part.text);
  }
  assert.equal(Da({ index: 7, json: '}' }).event.delta.partial_json, '}');
  assert.equal(pr({ index: 8, text: 'x' }).event.delta.text, 'x');
  assert.equal(Na({ index: 9, text: 't' }).event.delta.thinking, 't');
});

test('真实流比较函数拒绝错误索引、错误增量类型及不一致内容', () => {
  const { lr, Fa } = streamFunctions();
  const text = { kind: 'text', index: 1, text: 'abc' };
  const input = { kind: 'input', index: 2, json: '{"key":' };
  assert.equal(lr(text, Fa(text).event), true);
  assert.equal(lr(input, Fa(input).event), true);
  assert.equal(lr(text, { ...Fa(text).event, index: 99 }), false);
  assert.equal(lr(input, Fa(text).event), false);
  assert.equal(lr(input, { ...Fa(input).event, delta: { type: 'input_json_delta', partial_json: 'wrong' } }), false);
  assert.equal(lr(text, { type: 'message_stop' }), false);
});

test('实际权限/Hook 来源规则保留显式来源和 PreToolUse 匹配边界', async () => {
  const { QMn, ZMn, jhs, Whs } = await import('../src/chunk-1nnn2mdy.js');
  for (const source of ['settings','plugin','skill','plugin:example','skill:module_1']) {
    assert.equal(QMn(source), source);
  }
  for (const source of ['', 'plugin:', 'external', 'plugin:bad/path', 'skill:invalid name']) {
    assert.equal(QMn(source), undefined);
  }
  assert.equal(ZMn('sample@plugin'), 'sample@plugin');
  assert.equal(ZMn('sample@bad space'), undefined);
  assert.equal(jhs('PreToolUse:Read', 'Read'), 'PreToolUse');
  assert.equal(jhs('PreToolUse:Bash', 'Read'), undefined);
  assert.equal(jhs('tool.check', 'Read'), 'tool.check');
  assert.equal(Whs('PreToolUse'), 'PreToolUse');
  assert.equal(Whs('tool.check'), 'tool.check');
  assert.equal(Whs('PostToolUse'), undefined);
});
