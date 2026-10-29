// Exclusive cross-process lock for durable scheduled-task read/modify/write.
// The .lock file is deliberately separate from the atomically staged JSON data.
import { open, readFile, stat, unlink } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';

const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const LOCK_WAIT_MS = 20_000;

function pidIsAlive(pid) {
  if (!Number.isSafeInteger(pid) || pid <= 0) return false;
  try { process.kill(pid, 0); return true; }
  catch (error) { return error?.code !== 'ESRCH'; }
}
async function tryReapAbandonedLock(path) {
  let before;
  try { before = await stat(path); }
  catch (error) { if (error?.code === 'ENOENT') return; throw error; }
  if (Date.now() - before.mtimeMs < 2000) return;
  let owner;
  try { owner = JSON.parse(await readFile(path, 'utf8')); } catch { return; }
  if (!owner || typeof owner !== 'object' || !Number.isSafeInteger(owner.pid) ||
      typeof owner.token !== 'string' || owner.token.length < 10 || pidIsAlive(owner.pid)) return;
  // A separate exclusive reaper lock prevents two survivors from deleting
  // each other's newly acquired writer lock after the abandoned one is removed.
  let reaper;
  try { reaper = await open(path + '.reaper', 'wx', 0o600); }
  catch (error) { if (error?.code === 'EEXIST') return; throw error; }
  try {
    const current = await stat(path).catch(error => {
      if (error?.code === 'ENOENT') return null;
      throw error;
    });
    if (!current || current.dev !== before.dev || current.ino !== before.ino ||
        current.mtimeMs !== before.mtimeMs) return;
    const latest = JSON.parse(await readFile(path, 'utf8'));
    if (latest.token !== owner.token || latest.pid !== owner.pid) return;
    await unlink(path).catch(error => {
      if (error?.code !== 'ENOENT') throw error;
    });
  } finally {
    await reaper.close();
    await unlink(path + '.reaper').catch(error => {
      if (error?.code !== 'ENOENT') throw error;
    });
  }
}

/** Serialize all durable task changes across CLI, daemon and scheduled workers. */
export async function withCronStoreLock(storePath, operation) {
  const path = storePath + '.lock';
  const deadline = Date.now() + LOCK_WAIT_MS;
  while (Date.now() < deadline) {
    let owner;
    try { owner = await open(path, 'wx', 0o600); }
    catch (error) {
      if (error?.code !== 'EEXIST') throw error;
      await tryReapAbandonedLock(path);
      await delay(30);
      continue;
    }
    try {
      await owner.writeFile(JSON.stringify({ pid: process.pid, token: randomUUID() }));
      return await operation();
    } finally {
      await owner.close();
      await unlink(path).catch(error => {
        if (error?.code !== 'ENOENT') throw error;
      });
    }
  }
  throw new Error('Timed out waiting for the scheduled task store lock: ' + storePath);
}
