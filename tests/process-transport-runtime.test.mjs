import { test } from 'node:test';
import assert from 'node:assert/strict';
import { runBoundedProcess } from '../runtime/process-transport.mjs';

const node = process.execPath;

test('transfers UTF-8 stdin/stdout without a shell', async () => {
  const input = '你好 🐈';
  const result = await runBoundedProcess(node, ['-e', 'process.stdin.pipe(process.stdout)'], input);
  assert.equal(result.code, 0);
  assert.equal(result.stdout.toString(), input);
  assert.equal(result.signal, null);
});

test('times out and kills an unresponsive isolated subprocess', async () => {
  const start = Date.now();
  await assert.rejects(
    runBoundedProcess(node, ['-e', 'setInterval(()=>{},1000)'], undefined, {timeoutMs: 150}),
    /timed out after 150ms/
  );
  assert.ok(Date.now() - start < 5000);
});

test('applies independent stdout and stderr byte budgets', async () => {
  await assert.rejects(
    runBoundedProcess(node, ['-e', 'process.stdout.write("a".repeat(200000))'], undefined, {maxStdoutBytes: 1024}),
    /stdout exceeded byte limit/
  );
  await assert.rejects(
    runBoundedProcess(node, ['-e', 'process.stderr.write("b".repeat(200000))'], undefined, {maxStderrBytes: 1024}),
    /stderr exceeded byte limit/
  );
});

test('returns nonzero child exit status', async () => {
  const result = await runBoundedProcess(node, ['-e', 'process.exit(17)']);
  assert.equal(result.code, 17);
});

test('rejects invalid limits before running a command', async () => {
  await assert.rejects(runBoundedProcess(node, [], undefined, {timeoutMs: -1}), RangeError);
  await assert.rejects(runBoundedProcess(node, [], undefined, {maxStderrBytes: 0}), RangeError);
});

test('remains usable after a timed-out process', async () => {
  await assert.rejects(runBoundedProcess(node, ['-e', 'setInterval(()=>{},1000)'], undefined, {timeoutMs: 100}));
  const result = await runBoundedProcess(node, ['-e', 'process.stdout.write("ok")']);
  assert.equal(result.stdout.toString(), 'ok');
});

test('passes shell metacharacters as literal argv, not commands', async () => {
  const literal = '$(printf unexpected); & | >';
  const result = await runBoundedProcess(node, ['-e', 'process.stdout.write(process.argv[1])', literal]);
  assert.equal(result.code, 0);
  assert.equal(result.stdout.toString(), literal);
});
