'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.resolve(__dirname, '../..');
const code = file => fs.readFileSync(path.join(root, file), 'utf8');
const types = code('tools/bundled/plugin-api/types/claude-code.d.ts');
const caching = code('resources/docs/claude-api/prompt-caching.md');
const thinking = code('resources/docs/migration/preserved-thinking-migration.md');
const modelMigration = code('resources/docs/claude-api/model-migration.md');
const main = code('src/chunk-3827ysmv.js');
const stream = code('src/chunk-x3dkgs4r.js');
const query = code('src/chunk-tqjdehqd.js');

function extract(s, from, to) {
  const start = s.indexOf(from), end = s.indexOf(to, start);
  assert.ok(start >= 0 && end > start, 'missing 2.1.310 source: ' + from);
  return s.slice(start, end);
}

test('缓存/Thinking 资料指向的模型用量与停止字段出现在原版源码中', () => {
  for (const keyword of ['cache_read_input_tokens','cache_creation_input_tokens','input_tokens','output_tokens']) {
    assert.ok(main.includes(keyword), keyword);
  }
  assert.match(caching, /prefix match/i);
  assert.match(thinking, /thinking.*signature/i);
  assert.match(modelMigration, /stop_reason/);
  assert.match(types, /SessionMessagesResult/);
});

test('原版聚合器 Ss 精确累计各模型 tokens（含缓存读写及 thinking），不额外重算输入', () => {
  const src = extract(main, 'function Ss() {', 'function so(e)');
  const info = {
    a: { inputTokens: 10, outputTokens: 4, thinkingTokens: 2, cacheReadInputTokens: 8, cacheCreationInputTokens: 3, webSearchRequests: 1 },
    b: { inputTokens: 5, outputTokens: 6, cacheReadInputTokens: 4, cacheCreationInputTokens: 2, webSearchRequests: 2 }
  };
  const { Ss } = vm.runInNewContext(src + ';({Ss})', {
    zH: () => info,
    bm: { input_tokens: 0, server_tool_use: { web_search_requests: 0 } }
  });
  const usage = Ss();
  assert.equal(usage.input_tokens, 15);
  assert.equal(usage.output_tokens, 10);
  assert.equal(usage.cache_read_input_tokens, 12);
  assert.equal(usage.cache_creation_input_tokens, 5);
  assert.equal(usage.output_tokens_details.thinking_tokens, 2);
  assert.equal(usage.server_tool_use.web_search_requests, 3);
});

test('原版停止状态比较严格区分拒绝、正常结束、模型用量变化', () => {
  const src = extract(stream, 'function dr(e, n)', 'var ur =');
  const { dr } = vm.runInNewContext(src + ';({dr})', { L: items => [...new Set(items)] });
  const a = { stopReason: 'end_turn', usage: { input_tokens: 4, output_tokens: 8 } };
  assert.equal(dr(a, { stopReason: 'end_turn', usage: { output_tokens: 8, input_tokens: 4 } }), true);
  assert.equal(dr(a, { stopReason: 'refusal', usage: { input_tokens: 4, output_tokens: 8 } }), false);
  assert.equal(dr(a, { stopReason: 'end_turn', usage: { input_tokens: 4, output_tokens: 9 } }), false);
  assert.equal(dr({ stopReason: 'end_turn', usage: null }, { stopReason: 'end_turn', usage: null }), true);
  assert.equal(dr({ stopReason: 'end_turn', usage: null }, a), false);
});

test('原版拒绝识别 nr 仅匹配 assistant API 错误拒绝，不把用户消息当作拒绝', () => {
  const src = extract(query, 'function nr(e) { return e?.type === "assistant"', 'function td(');
  const { nr } = vm.runInNewContext(src + ';({nr})');
  assert.equal(nr({ type:'assistant', isApiErrorMessage:true, message:{stop_reason:'refusal'} }), true);
  assert.equal(nr({ type:'assistant', isApiErrorMessage:false, message:{stop_reason:'refusal'} }), false);
  assert.equal(nr({ type:'user', isApiErrorMessage:true, message:{stop_reason:'refusal'} }), false);
  assert.equal(nr({ type:'assistant', isApiErrorMessage:true, message:{stop_reason:'end_turn'} }), false);
  assert.equal(nr(undefined), false);
});

test('同模型拒绝重试条件 ed 对辅助请求和禁用开关严格生效', () => {
  const src = extract(query, 'function ed(e, o)', 'function nr(e)');
  const { ed } = vm.runInNewContext(src + ';({ed})', {
    Jo: x => x, i: {CLAUDE_CODE_DISABLE_REFUSAL_RETRY:false}, l: () => {}
  });
  assert.equal(ed('main', false), true);
  assert.equal(ed('auxiliary', false), false);
  assert.equal(ed('main', true), false);
  const { ed: disabled } = vm.runInNewContext(src + ';({ed})', {
    Jo: x => x, i: {CLAUDE_CODE_DISABLE_REFUSAL_RETRY:true}, l: () => {}
  });
  assert.equal(disabled('main', false), false);
});
