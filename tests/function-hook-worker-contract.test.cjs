'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const root = path.resolve(__dirname, '..');
const worker = readFileSync(path.join(root, 'src/src/plugins/functionHooks/hooks-worker/hooks-worker.js'), 'utf8');
const declarations = readFileSync(path.join(root, 'resources/tools/plugin-api/types/claude-code-bf9d954c.d.ts'), 'utf8');
const source = worker.slice(worker.indexOf('var B='), worker.indexOf('function I(o){'));
assert.ok(source.startsWith('var B=') && source.includes('function W(o)') && source.length > 1500,
  '真实 2.1.310 Worker 协议函数必须能够定位');

function workerProtocol() {
  const notifications = [];
  const api = vm.runInNewContext(source + ';({B,w,G,z,J,Q,W})', {
    He: Error, Ker: error => error, Cer: error => String(error), NKr: error => String(error),
    VE: (signal, { abort }) => {
      if (!signal) return () => {};
      signal.addEventListener('abort', abort, { once: true });
      return () => signal.removeEventListener('abort', abort);
    },
    qzt: () => false,
    Tvt: value => String(value ?? 'aborted'),
    jer: () => ({}),
    XKr: (_name, message) => message,
    r2t: value => value,
    d: value => String(value),
  });
  const bus = { postMessage: message => notifications.push(message) };
  return { ...api, bus, notifications, state: api.B(bus) };
}

test('声明文件包含新版 Hook、权限、流和工具结果契约', () => {
  for (const identifier of ['HookStream', 'StreamNext', 'PermissionRequestDecision', 'PreToolUseResult',
    'ToolCallInput', 'ToolCallResult', 'SessionResume', 'AgentInfo', 'Register', 'On']) {
    assert.match(declarations, new RegExp('\\b(?:export\\s+)?(?:type|interface)\\s+' + identifier + '\\b'));
  }
  assert.match(declarations, /export type PermissionRequestDecision = \{/);
  assert.match(declarations, /behavior: 'allow'/);
  assert.match(declarations, /behavior: 'deny'/);
  assert.match(declarations, /export type ToolCallReserved<T>/);
  assert.match(declarations, /tool_use_id\?: string/);
  assert.match(declarations, /export type HookStream<C, R> = AsyncGenerator<C, R>/);
});

test('Worker next 调用保持消息编号并将返回值交给正确的等待者', async () => {
  const { G, state, notifications } = workerProtocol();
  const signal = new AbortController().signal;
  const pending = G(state, 91)({ prompt: 'hello' }, signal, ['user']);
  assert.equal(notifications.length, 1);
  assert.equal(notifications[0].type, 'next');
  assert.equal(notifications[0].id, 91);
  assert.equal(notifications[0].nextId, 1);
  assert.equal(notifications[0].argument.prompt, 'hello');
  assert.equal(state.pendingNext.size, 1);
  state.pendingNext.get('91:1').resolve({ continued: true });
  assert.equal((await pending).continued, true);
});

test('Worker 工具操作分发、回复及卸载失败不会把成功伪装成已执行', async () => {
  const { z, W, state } = workerProtocol();
  const sent = [];
  const environment = {
    port: { postMessage: message => sent.push(message) },
    pendingOps: new Map(),
    owedOps: new Map(),
  };
  state.ports.set('env', environment);
  const success = z(state, { environmentId: 'env', op: 'tool.call', args: [{ tool: 'Read' }], dispatchId: 7 });
  assert.equal(sent[0].type, 'op');
  assert.equal(sent[0].op, 'tool.call');
  assert.equal(sent[0].dispatchId, 7);
  assert.equal(environment.owedOps.get(1), 7);
  environment.pendingOps.get(1).resolve({ text: 'done' });
  assert.equal((await success).text, 'done');

  const abandoned = z(state, { environmentId: 'env', op: 'tool.call', args: [] });
  W(environment);
  await assert.rejects(abandoned, /environment was unloaded/);
  assert.equal(environment.pendingOps.size, 0);
  await assert.rejects(z(state, { environmentId: 'missing', op: 'tool.call', args: [] }), /environment is unloaded/);
});

test('Worker 流式 next 消费提前结束会发送 next_close', async () => {
  const { J, state, notifications } = workerProtocol();
  const iterator = J(state, 2)({ tool: 'Read' }, new AbortController().signal, ['user']);
  const first = iterator.next();
  assert.equal(notifications[0].type, 'next_pull');
  assert.equal(notifications[0].id, 2);
  assert.ok(notifications[0].open);
  state.pendingSteps.get('2:1').resolve({ done: false, value: 'piece' });
  assert.equal((await first).value, 'piece');
  await iterator.return();
  assert.equal(notifications.at(-1).type, 'next_close');
  assert.equal(notifications.at(-1).nextId, 1);
});

test('Worker 流式工具操作支持 op_pull 和提前关闭 op_close', async () => {
  const { Q, state } = workerProtocol();
  const sent = [];
  const port = { postMessage: message => sent.push(message) };
  const pendingOps = new Map();
  state.ports.set('env', { port, pendingOps, owedOps: new Map() });
  const iterator = Q(state, { environmentId: 'env', op: 'tool.call', args: [], dispatchId: 8 });
  const first = iterator.next();
  assert.equal(sent[0].type, 'op_pull');
  assert.equal(sent[0].open.op, 'tool.call');
  pendingOps.get(1).resolve({ done: false, value: 'partial' });
  assert.equal((await first).value, 'partial');
  await iterator.return();
  assert.equal(sent.at(-1).type, 'op_close');
  assert.equal(sent.at(-1).opId, 1);
});

test('Worker next 取消通知携带正确编号，直到宿主回执前不伪造完成', async () => {
  const { G, state, notifications } = workerProtocol();
  const abort = new AbortController();
  const pending = G(state, 31)({ command: 'read' }, abort.signal);
  abort.abort('cancelled');
  assert.equal(notifications[0].type, 'next');
  assert.equal(notifications[1].type, 'next_abort');
  assert.equal(notifications[1].id, 31);
  assert.equal(notifications[1].nextId, 1);
  state.pendingNext.get('31:1').reject(new Error('cancelled by host'));
  await assert.rejects(pending, /cancelled by host/);
});

test('Worker 工具取消发送 op_abort，并保留等待回执的真实失败语义', async () => {
  const { z, state } = workerProtocol();
  const sent = [];
  const pendingOps = new Map();
  state.ports.set('env', {
    port: { postMessage: message => sent.push(message) },
    pendingOps,
    owedOps: new Map()
  });
  const abort = new AbortController();
  const pending = z(state, { environmentId: 'env', op: 'tool.call', args: [], signal: abort.signal });
  abort.abort('cancelled');
  assert.equal(sent[0].type, 'op');
  assert.equal(sent[1].type, 'op_abort');
  assert.equal(sent[1].opId, 1);
  pendingOps.get(1).reject(new Error('cancelled by host'));
  await assert.rejects(pending, /cancelled by host/);
});
