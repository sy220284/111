'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = readFileSync(path.resolve(__dirname, '../core/chunk-x3dkgs4r.js'), 'utf8');
const declarations = readFileSync(path.resolve(__dirname, '../tools/bundled/plugin-api/types/claude-code.d.ts'), 'utf8');
const docs = readFileSync(path.resolve(__dirname, '../resources/docs/claude-api/typescript/streaming.md'), 'utf8');
const toolDocs = readFileSync(path.resolve(__dirname, '../resources/docs/claude-api/tool-use-concepts.md'), 'utf8');

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
  const { QMn, ZMn, jhs, Whs } = await import('../core/chunk-1nnn2mdy.js');
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

test('真实模型流工具调用块保持 tool_use id、名称及分块参数的关联', () => {
  const decoder = source.slice(source.indexOf('function _r(e, n, s)'), source.indexOf('var hr = (e, n, s)'));
  const block = source.slice(source.indexOf('var hr = (e, n, s)'), source.indexOf('function bwr(e, n)'));
  assert.ok(block.startsWith('var hr') && block.includes('function Tr(e)'));
  const { Tr } = vm.runInNewContext(decoder + block + ';({Tr})', {
    fo: () => undefined,
    xn: value => value,
    On: () => ({ output_tokens: 6 })
  });
  const blocks = new Map([[4, { kind: 'tool' }]]);
  const seen = [];
  const tracker = {
    latest: () => ({ blocks }),
    hold: chunk => chunk,
    filed: (ref, part) => seen.push(part)
  };
  const reader = Tr(tracker);
  const started = reader.chunkOf({ type: 'stream_event', event: {
    type: 'content_block_start', index: 4,
    content_block: { type: 'tool_use', id: 'toolu_1', name: 'Read', input: {} }
  } });
  assert.equal(started.kind, 'tool');
  assert.equal(started.id, 'toolu_1');
  assert.equal(started.name, 'Read');
  const delta = reader.chunkOf({ type: 'stream_event', event: {
    type: 'content_block_delta', index: 4, delta: { type: 'input_json_delta', partial_json: '{"file_path":' }
  } });
  assert.equal(delta.kind, 'input');
  assert.equal(delta.index, 4);
  assert.equal(delta.json, '{"file_path":');
  assert.equal(seen.length, 2);
});

test('真实工具结果映射器保留调用 ID，失败结果显式标记 is_error', () => {
  const toolSource = readFileSync(path.resolve(__dirname, '../core/chunk-g936x3jq.js'), 'utf8');
  const begin = toolSource.indexOf('mapToolResultToToolResultBlockParam(e, k)');
  const end = toolSource.indexOf(', renderToolUseMessage', begin);
  assert.ok(begin > 0 && end > begin);
  const method = toolSource.slice(begin, end);
  const mapper = vm.runInNewContext('({' + method + '})').mapToolResultToToolResultBlockParam;
  const ok = mapper({ success: true, message: 'queued' }, 'toolu_1');
  const fail = mapper({ success: false, message: 'denied' }, 'toolu_2');
  assert.equal(ok.tool_use_id, 'toolu_1');
  assert.equal(ok.type, 'tool_result');
  assert.equal(ok.is_error, false);
  assert.equal(ok.content, 'queued');
  assert.equal(fail.tool_use_id, 'toolu_2');
  assert.equal(fail.is_error, true);
  assert.equal(fail.content, 'denied');
});
