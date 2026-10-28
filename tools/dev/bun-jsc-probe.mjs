// Isolated Bun/JSC reference-runtime probes. Not a Node compatibility shim.
// No network, credentials, writable database files, or privileged Bun.ant calls.
if (typeof Bun === 'undefined') throw new Error('Run with Bun, not Node');
const checks = [];
async function check(name, fn) {
  try {
    const detail = await fn();
    checks.push({api:name, status:'passed', detail:detail || 'ok'});
  } catch (error) {
    checks.push({api:name, status:'failed', error:String(error && error.message || error).slice(0,500)});
  }
}
await check('Bun.spawn', async () => {
  const proc = Bun.spawn([process.execPath, '-e', 'process.stdout.write("BUN_SPAWN_OK")'], {
    stdout:'pipe', stderr:'pipe'
  });
  const [output, error, exitCode] = await Promise.all([
    new Response(proc.stdout).text(), new Response(proc.stderr).text(), proc.exited
  ]);
  if (exitCode !== 0 || output !== 'BUN_SPAWN_OK')
    throw Error('stdout / exit mismatch: code=' + exitCode + ', stderr=' + error);
  return 'stdout, exit status';
});
await check('Bun.spawnSync', () => {
  const proc = Bun.spawnSync([process.execPath, '-e', 'process.stdout.write("BUN_SYNC_OK")']);
  if (proc.exitCode !== 0 || new TextDecoder().decode(proc.stdout) !== 'BUN_SYNC_OK')
    throw Error('stdout / exit mismatch');
  return 'stdout, exit status';
});
await check('Bun.Terminal', async () => {
  if (typeof Bun.Terminal !== 'function') throw Error('Bun.Terminal is not exposed');
  let output = '';
  const terminal = new Bun.Terminal({
    cols:80, rows:24, data(_terminal, bytes) { output += new TextDecoder().decode(bytes); }
  });
  try {
    const proc = Bun.spawn([process.execPath, '-e', 'process.stdout.write("BUN_PTY_OK")'], {
      terminal
    });
    const code = await proc.exited;
    if (code !== 0 || !output.includes('BUN_PTY_OK')) throw Error('PTY output or exit mismatch');
    return 'isolated PTY output, exit status';
  } finally { terminal.close(); }
});
await check('Bun.SQL', async () => {
  if (typeof Bun.SQL !== 'function') throw Error('Bun.SQL is not exposed');
  const db = new Bun.SQL(':memory:');
  try {
    const rows = await db.unsafe('SELECT ? AS value', [42]);
    if (!Array.isArray(rows) || Number(rows[0]?.value) !== 42)
      throw Error('SQLite in-memory parameter binding mismatch');
    return 'in-memory SQLite parameter binding';
  } finally { await db.close(); }
});
await check('bun:sqlite', async () => {
  const module = await import('bun:sqlite');
  const db = new module.Database(':memory:');
  try {
    const row = db.query('SELECT ? AS value').get(7);
    if (Number(row?.value) !== 7) throw Error('bun:sqlite prepared statement mismatch');
    return 'in-memory prepared statement';
  } finally { db.close(); }
});
await check('bun:jsc', async () => {
  const mod = await import('bun:jsc');
  const stats = mod.heapStats();
  if (!stats || typeof stats.heapSize !== 'number' || stats.heapSize < 0)
    throw Error('JSC heapStats response shape mismatch');
  return 'JSC heapStats available';
});
checks.push({api:'Bun.ant', status:'not_verified',
  detail:'private/security-sensitive extension; never invoke setDumpable in a probe'});
const failed = checks.filter(x => x.status === 'failed').length;
process.stdout.write(JSON.stringify({
  schema:1, kind:'bun-runtime-probe', runtime:'Bun/JSC', bunVersion:Bun.version,
  platform:process.platform, architecture:process.arch, status:failed ? 'failed' : 'passed',
  checks
}) + '\n');
if (failed) process.exitCode = 1;
