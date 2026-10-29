import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, readFileSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Ecn, c6n } from '../core/chunk-hp7h93my.js';

const task = { id: 'abcdef01', cron: '*/5 * * * *', prompt: 'carry out the user task', createdAt: 1782000000000, recurring: true };

function withStore(fn) {
  const home = mkdtempSync(join(tmpdir(), '111-cron-integrity-'));
  const dir = join(home, '.claude');
  mkdirSync(dir);
  const path = join(dir, 'scheduled_tasks.json');
  return Promise.resolve().then(() => fn({ home, path })).finally(() =>
    rmSync(home, { recursive: true, force: true }));
}

test('持久化任务文件 JSON 损坏时，真实写入函数拒绝覆盖原数据', async () => {
  await withStore(async ({ home, path }) => {
    const raw = '{"tasks":[{"id":"partial"';
    writeFileSync(path, raw);
    assert.deepEqual(await Ecn(home), [], 'ordinary read keeps original tolerant behavior');
    await assert.rejects(c6n([task], home), /refusing to overwrite malformed/);
    assert.equal(readFileSync(path, 'utf8'), raw, 'corrupt file must remain untouched');
  });
});

test('持久化任务中存在无效记录时，不能因宽松过滤而丢失记录', async () => {
  await withStore(async ({ home, path }) => {
    const raw = JSON.stringify({ tasks: [task, { id: 'other', cron: 'bad', prompt: 'keep', createdAt: 1782000000000 }] });
    writeFileSync(path, raw);
    assert.equal((await Ecn(home)).length, 1, 'legacy read path may skip invalid record');
    await assert.rejects(c6n([task], home), /refusing to overwrite task/);
    assert.equal(readFileSync(path, 'utf8'), raw);
  });
});

test('读取任务文件失败时，不得将真实错误当成空任务文件重新保存', async () => {
  await withStore(async ({ home, path }) => {
    // The scheduled_tasks.json path itself is a directory: real filesystem EISDIR.
    mkdirSync(path);
    await assert.rejects(c6n([task], home), error => error?.code === 'EISDIR');
    assert.deepEqual(await Ecn(home), [], 'legacy tolerant read remains unchanged');
  });
});

test('合法任务文件仍通过真实文件写入并可再次读取', async () => {
  await withStore(async ({ home, path }) => {
    const other = { ...task, id: 'abcdef02', prompt: 'second prompt' };
    await c6n([task], home);
    assert.equal((await Ecn(home)).length, 1);
    await c6n([task, other], home);
    assert.equal((await Ecn(home)).length, 2);
    const saved = JSON.parse(readFileSync(path, 'utf8'));
    assert.equal(saved.tasks[0].id, task.id);
    assert.equal(saved.tasks[1].id, other.id);
  });
});
