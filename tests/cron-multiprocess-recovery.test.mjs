import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdtempSync, mkdirSync, readFileSync, writeFileSync, existsSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { withCronStoreLock, Ecn, c6n } from '../core/chunk-hp7h93my.js';

const lockModule = new URL('../core/chunk-hp7h93my.js', import.meta.url).href;
const cronModule = new URL('../core/chunk-hp7h93my.js', import.meta.url).href;
const wait = ms => new Promise(resolve => setTimeout(resolve, ms));
function tempStore(fn) {
  const dir = mkdtempSync(join(tmpdir(), '111-cron-multiprocess-'));
  mkdirSync(join(dir, '.claude'), { recursive: true });
  const store = join(dir, '.claude', 'scheduled_tasks.json');
  return Promise.resolve().then(() => fn({ dir, store })).finally(() =>
    rmSync(dir, { recursive: true, force: true }));
}
function child(script, args, cwd) {
  const processRef = spawn(process.execPath, ['--input-type=module', '-e', script, ...args],
    { cwd, env: { ...process.env, CI: 'true' }, stdio: ['ignore', 'pipe', 'pipe'] });
  let stderr = '';
  processRef.stderr.on('data', chunk => { stderr += chunk; });
  return { processRef, finished: new Promise((resolve, reject) => {
    processRef.once('error', reject);
    processRef.once('close', (code, signal) => code === 0 ? resolve() :
      reject(Error('child failed: code=' + code + ' signal=' + signal +
        ' stderr=' + stderr.slice(-3000))));
  }) };
}
const task = id => ({ id, cron: '*/5 * * * *', prompt: id, createdAt: 1782000000000, recurring: true });

test('多进程真实并发事务不丢失任一计数器更新', { timeout: 40000 }, async () => {
  await tempStore(async ({ dir, store }) => {
    writeFileSync(store, '0');
    const worker = `import {readFileSync,writeFileSync} from 'node:fs';
      import {withCronStoreLock} from ${JSON.stringify(lockModule)};
      const file=process.argv[1];
      for(let n=0;n<4;n++) await withCronStoreLock(file,async()=>{
        const value=Number(readFileSync(file,'utf8'));
        await new Promise(resolve=>setTimeout(resolve,35));
        writeFileSync(file,String(value+1));
      });`;
    await Promise.all(Array.from({ length: 5 }, () => child(worker, [store], dir).finished));
    assert.equal(Number(readFileSync(store, 'utf8')), 20);
    assert.equal(existsSync(store + '.lock'), false);
  });
});

test('真实 CronDelete 持久化路径在并发进程下不丢失不相干的删除', { timeout: 50000 }, async () => {
  await tempStore(async ({ dir, store }) => {
    const tasks = Array.from({ length: 24 }, (_, i) => task('multi-' + i));
    await c6n(tasks, dir);
    const worker = `import {WTe} from ${JSON.stringify(cronModule)};
      await WTe(process.argv.slice(2),process.argv[1]);`;
    // Each child deletes a distinct subset. The old unlocked read/write
    // sequence could resurrect another child's deleted task.
    const workers = Array.from({ length: 4 }, (_, n) =>
      child(worker,
        [dir, ...tasks.filter((_, i) => i % 4 === n).map(t => t.id)], dir));
    await Promise.all(workers.map(w => w.finished));
    assert.deepEqual(await Ecn(dir), []);
    assert.equal(existsSync(store + '.lock'), false);
  });
});

test('持锁进程遭 SIGKILL 后，下一进程能够回收遗留锁并正常写入', { timeout: 20000 }, async () => {
  await tempStore(async ({ dir, store }) => {
    writeFileSync(store, 'original');
    const script = `import {withCronStoreLock} from ${JSON.stringify(lockModule)};
      await withCronStoreLock(process.argv[1],async()=>{
        process.stdout.write('LOCKED\\n');
        await new Promise(()=>{ setInterval(()=>{},1000); });
      });`;
    const childRef = child(script, [store], dir);
    await new Promise((resolve, reject) => {
      let seen = '';
      const timeout = setTimeout(() => reject(Error('child never acquired lock')), 7000);
      childRef.processRef.stdout.on('data', chunk => {
        seen += String(chunk);
        if (seen.includes('LOCKED')) { clearTimeout(timeout); resolve(); }
      });
      childRef.processRef.once('error', reject);
    });
    assert.equal(existsSync(store + '.lock'), true);
    childRef.processRef.kill('SIGKILL');
    await assert.rejects(childRef.finished, /SIGKILL/);
    await wait(2100);
    await withCronStoreLock(store, async () => writeFileSync(store, 'recovered'));
    assert.equal(readFileSync(store, 'utf8'), 'recovered');
    assert.equal(existsSync(store + '.lock'), false);
  });
});

test('锁定操作失败仍清理归属锁，后续写入不被永久阻塞', async () => {
  await tempStore(async ({ store }) => {
    await assert.rejects(withCronStoreLock(store, async () => {
      throw Error('intentional rollback');
    }), /intentional rollback/);
    assert.equal(existsSync(store + '.lock'), false);
    await withCronStoreLock(store, async () => writeFileSync(store, 'second'));
    assert.equal(readFileSync(store, 'utf8'), 'second');
  });
});
