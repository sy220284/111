'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const read = name => fs.readFileSync(path.join(__dirname, '..', 'src', name), 'utf8');
const adoptionSource = read('chunk-bwt94x0y.js');
const daemonSource = read('chunk-t2adf9g4.js');

function originalFragment(source, begin, end) {
  const first = source.indexOf(begin);
  const last = source.indexOf(end, first + begin.length);
  assert.ok(first !== -1 && last > first, '无法定位原版源码函数区间：' + begin);
  return source.slice(first, last);
}

function adoptionFixture() {
  const source = originalFragment(adoptionSource, 'function mcpSettledForAdopt(e)', 'function nIn(e')
    + '\n' + originalFragment(adoptionSource, 'function ngr(e,n)', 'function Vxt(e,n');
  return vm.runInNewContext(source + '\n({tgr,mcpSettledForAdopt,Pno,classifyAdoptReconciliationNotice,ngr,lUe,rgr,rIn,u6e})', {
    pp: row => row.kind === 'shell',
    Tn: row => row.kind === 'agent',
    fLr: row => row.kind === 'workflow',
    SIe: () => true
  });
}

function daemonFixture({ reply, roster, alive }) {
  const source = originalFragment(daemonSource, 'async function probeDaemonJob(e,n)', 'async function Meo(e)');
  const seen = [];
  const api = vm.runInNewContext(source + '\n({probeDaemonJob,Yxn})', {
    bh: async query => { seen.push(query); return reply; },
    Oc: 7,
    JN: async () => ({ workers: roster }),
    $an: async () => alive
  });
  return { ...api, seen };
}

test('MCP 已初始化且不存在 pending 客户端时才满足后台接管条件', () => {
  const { tgr, mcpSettledForAdopt } = adoptionFixture();
  assert.equal(tgr, mcpSettledForAdopt);
  assert.equal(tgr({ mcp: { clientsInitialized: true, clients: [] } }), true);
  assert.equal(tgr({ mcp: { clientsInitialized: false, clients: [] } }), false);
  assert.equal(tgr({ mcp: { clientsInitialized: true, clients: [{ type: 'pending' }] } }), false);
  assert.equal(tgr({ mcp: { clientsInitialized: true, clients: [{ type: 'ready' }] } }), true);
});

test('父代理任务和技能分叉任务采用不同的恢复公告及协调规则', () => {
  const { Pno, classifyAdoptReconciliationNotice } = adoptionFixture();
  assert.equal(Pno, classifyAdoptReconciliationNotice);
  assert.deepEqual({ ...Pno({}) }, { emit: false, excludeFromReconcile: false });
  assert.deepEqual({ ...Pno({ parentAgentId: 'parent' }) }, { emit: true, excludeFromReconcile: false });
  assert.deepEqual({ ...Pno({ forkedSkillName: 'skill' }) }, { emit: true, excludeFromReconcile: true });
  assert.deepEqual({ ...Pno({ parentAgentId: 'parent', forkedSkillName: 'skill' }) }, { emit: true, excludeFromReconcile: false });
});

test('接管资格同时要求任务类型匹配和任务 ID 位于可接管状态集合', () => {
  const { rgr, ngr, u6e } = adoptionFixture();
  const adopted = new Map([['agent', true], ['shell', true]]);
  assert.equal(rgr({ kind: 'agent', id: 'agent' }, adopted), true);
  assert.equal(rgr({ kind: 'shell', id: 'agent' }, adopted), false);
  assert.equal(ngr({ kind: 'shell', id: 'shell' }, adopted), true);
  assert.equal(ngr({ kind: 'agent', id: 'shell' }, adopted), false);
  assert.equal(u6e({ id: 'unknown' }, adopted), false);
});

test('Daemon 探测优先遵从 has 协议的 alive 与 present 独立结果', async () => {
  const f = daemonFixture({ reply: { ok: true, op: 'has', alive: true, present: false }, roster: {}, alive: false });
  assert.equal(f.probeDaemonJob, f.Yxn);
  const result = await f.probeDaemonJob('job-a', {});
  assert.deepEqual({ ...result }, { alive: true, present: false, daemonUp: true });
  assert.equal(f.seen.length, 1);
  assert.equal(f.seen[0].op, 'has');
  assert.equal(f.seen[0].short, 'job-a');
});

test('Daemon 不可连接时依照 roster 及 PID/启动时间的探测结果回退', async () => {
  const roster = { 'job-a': { pid: 1234, procStart: 100 } };
  const dead = daemonFixture({ reply: { ok: false, code: 'ENOCONN' }, roster, alive: false });
  const active = daemonFixture({ reply: { ok: false, code: 'ENOCONN' }, roster, alive: true });
  assert.deepEqual({ ...await dead.probeDaemonJob('job-a', {}) }, { alive: false, present: false, daemonUp: false });
  assert.deepEqual({ ...await active.probeDaemonJob('job-a', {}) }, { alive: true, present: true, daemonUp: false });
});
