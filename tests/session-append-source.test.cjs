'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

// Extract and execute the original 2.1.310 functions; do not replace their logic.
const source = fs.readFileSync(path.resolve(__dirname, '../src/chunk-tqjdehqd.js'), 'utf8');
const reference = fs.readFileSync(path.resolve(__dirname, '../resources/docs/plugin-authoring/reference.md'), 'utf8');
const start = source.indexOf('var fp = new Set(["user", "assistant", "attachment", "system"])');
const end = source.indexOf('var Fa = "poll-event"', start);
assert.ok(start > 0 && end > start, 'original session.append and row forwarding implementation located');
const body = source.slice(start, end);
assert.match(reference, /session\.append/);
assert.match(reference, /session\.start/);

function fixture({ active = true, disabled = false, hookEnabled = true, thrown = false, extraRows = [] } = {}) {
  const writes = [], logs = [], disposed = [];
  const taken = { taken: () => extraRows, [Symbol.dispose]: () => disposed.push('disposed') };
  const conversation = {
    rows: () => [], attachLoop: () => taken
  };
  const api = vm.runInNewContext(body + ';({Da,gp,yp})', {
    Symbol,
    Tpt: { set: () => {} }, Ms: () => {},
    As: () => false, eJe: () => hookEnabled,
    Xp: () => ({ conversation: () => conversation }),
    $ge: () => active ? {} : undefined,
    JAo: () => undefined, qmt: { of: () => undefined },
    Nge: props => props,
    J4: async (context, rows, options) => {
      if (thrown) throw new Error('hook append failure');
      writes.push({ rows, options, isHookable: context.isHookable });
    },
    t: message => logs.push(message), d: error => String(error),
  });
  const row = (type, uuid, content = []) => ({ type, uuid, message: { content } });
  const args = { options: { settingsHooksOff: disabled }, agentContext: null };
  return { api, row, args, writes, logs, disposed };
}

test('session.append 接受真实消息类别，拒绝进度等非持久化行', () => {
  const { api, row } = fixture();
  assert.equal(api.gp(row('assistant', 'one')), true);
  assert.equal(api.gp(row('user', 'two')), true);
  assert.equal(api.gp(row('attachment', 'three')), true);
  assert.equal(api.gp(row('system', 'four')), true);
  assert.equal(api.gp(row('progress', 'five')), false);
  assert.equal(api.gp({ type: 'user' }), false);
  assert.equal(api.gp(null), false);
});

test('session.append 精确保存一行一次，转发原对象且无需重新写入历史', async () => {
  const f = fixture();
  const bridge = f.api.Da(f.args, []);
  const one = f.row('user', 'first');
  await bridge.appended(one);
  await bridge.appended(one);
  assert.equal(f.writes.length, 1);
  assert.equal(f.writes[0].rows[0], one);
  assert.equal(f.writes[0].isHookable, true);
  assert.equal(f.writes[0].options.fills, true);
  assert.equal((await bridge.withArrivedRows([one]))[0], one);
  bridge[Symbol.dispose]();
  assert.equal(f.disposed.length, 1);
});

test('session.append 代理 tool_use 时维持原本的 assistant 行并吸收新到达的行', async () => {
  const extra = { type: 'user', uuid: 'incoming', message: { content: [] } };
  const f = fixture({ extraRows: [extra] });
  const bridge = f.api.Da(f.args, []);
  const original = f.row('assistant', 'tool-1', [{ type: 'tool_use', id: 'toolu_1' }]);
  await bridge.appended(original);
  assert.equal(f.writes.length, 1);
  const arrived = await bridge.withArrivedRows([original, extra]);
  assert.equal(arrived.length, 2, 'existing UUID must not be appended twice');
  const appended = await bridge.withArrivedRows([original]);
  assert.deepEqual(Array.from(appended.map(x => x.uuid)), ['tool-1', 'incoming']);
  bridge.answeredAs(f.row('assistant', 'replacement'), original);
  bridge[Symbol.dispose]();
});

test('session.append 被设置禁止、没有运行中会话或 Hook 不可用时保持原响应', async () => {
  for (const opts of [{disabled:true}, {active:false}, {hookEnabled:false}]) {
    const f = fixture(opts);
    const bridge = f.api.Da(f.args, []);
    await bridge.appended(f.row('user', 'one'));
    assert.equal(f.writes.length, 0);
    bridge[Symbol.dispose]();
  }
});

test('session.append 的 Hook 异常不丢弃原行、不会冒充成功写入', async () => {
  const f = fixture({thrown:true});
  const bridge = f.api.Da(f.args, []);
  const one = f.row('user','fail');
  await bridge.appended(one);
  assert.equal(f.writes.length, 0);
  assert.equal(f.logs.length, 1);
  const iterator = bridge.through((async function*(){ yield one; })());
  const values = [];
  for await(const item of iterator) values.push(item);
  assert.equal(values[0], one);
  bridge[Symbol.dispose]();
});

test('session.append 提前停止代理时执行上游迭代器的 return 清理', async () => {
  const f = fixture();
  const bridge = f.api.Da(f.args, []);
  let closed = false;
  async function* upstream() {
    try { yield f.row('user','first'); yield f.row('user','second'); }
    finally { closed = true; }
  }
  for await (const msg of bridge.through(upstream())) {
    assert.equal(msg.uuid, 'first');
    break;
  }
  assert.equal(closed, true);
  bridge[Symbol.dispose]();
});
