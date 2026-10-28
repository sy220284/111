/**
 * Optional, shell-free subprocess transport recovered from the independent
 * 2.1.310 high-level runtime project.
 *
 * Bounded output and execution, with process-group termination on Linux.
 * This is not a complete replacement for Bun.spawn or Bun/JSC internals.
 */
import { spawn } from 'node:child_process';

/**
 * @param {string} binary
 * @param {readonly string[]} args
 * @param {Uint8Array|string|undefined} input
 * @param {{timeoutMs?: number, maxStdoutBytes?: number, maxStderrBytes?: number}} limits
 * @returns {Promise<{code:number, stdout:Buffer, stderr:Buffer, signal:string|null}>}
 */
export function runBoundedProcess(binary, args, input, limits = {}) {
  const timeoutMs = limits.timeoutMs ?? 2000;
  const maxStdoutBytes = limits.maxStdoutBytes ?? 16 * 1024 * 1024;
  const maxStderrBytes = limits.maxStderrBytes ?? 64 * 1024;
  if (![timeoutMs, maxStdoutBytes, maxStderrBytes].every(n => Number.isSafeInteger(n) && n > 0)) {
    return Promise.reject(new RangeError('Process limits must be positive integers'));
  }
  return new Promise((resolve, reject) => {
    // A separate process group is essential: children can inherit stdio pipes.
    const detached = process.platform !== 'win32';
    let child;
    try {
      child = spawn(binary, [...args], {stdio: ['pipe', 'pipe', 'pipe'], shell: false, detached});
    } catch (error) {
      reject(error);
      return;
    }
    const stdout = [], stderr = [];
    let outBytes = 0, errBytes = 0, settled = false, timeout;
    function finish(error, output) {
      if (settled) return;
      settled = true;
      if (timeout !== undefined) clearTimeout(timeout);
      if (error) reject(error);
      else resolve(output);
    }
    function terminate(reason) {
      if (child.pid !== undefined) {
        if (detached) {
          try { process.kill(-child.pid, 'SIGKILL'); }
          catch { child.kill('SIGKILL'); }
        } else child.kill('SIGKILL');
      }
      finish(reason);
    }
    child.stdout.on('data', chunk => {
      outBytes += chunk.length;
      if (outBytes > maxStdoutBytes) {
        terminate(new Error('Child stdout exceeded byte limit'));
        return;
      }
      stdout.push(chunk);
    });
    child.stderr.on('data', chunk => {
      errBytes += chunk.length;
      if (errBytes > maxStderrBytes) {
        terminate(new Error('Child stderr exceeded byte limit'));
        return;
      }
      stderr.push(chunk);
    });
    child.on('error', error => finish(error));
    child.on('close', (code, signal) => {
      finish(undefined, {code: code ?? -1, stdout: Buffer.concat(stdout), stderr: Buffer.concat(stderr), signal});
    });
    child.stdin.on('error', error => {
      // Early child exit is determined by exit status, not stdin EPIPE.
      if (error.code !== 'EPIPE') terminate(error);
    });
    timeout = setTimeout(
      () => terminate(new Error('Child process timed out after ' + timeoutMs + 'ms: ' + binary)),
      timeoutMs
    );
    child.stdin.end(input);
  });
}
