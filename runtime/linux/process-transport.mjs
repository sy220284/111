/**
 * Linux subprocess transport shared by the reconstructed clipboard and image
 * backends. No shell, bounded output, bounded execution time, and process-group
 * termination on failure so inherited pipes cannot hang our event loop.
 * This is a high-level replacement for the bounded Bun subprocess call sites
 * in 2.1.310; it is not a Bun/JSC engine reimplementation.
 */
import { spawn } from 'node:child_process';
export function runBoundedProcess(binary, args, input, limits = {}) {
    const timeoutMs = limits.timeoutMs ?? 2000;
    const maxStdoutBytes = limits.maxStdoutBytes ?? 16 * 1024 * 1024;
    const maxStderrBytes = limits.maxStderrBytes ?? 64 * 1024;
    if (!Number.isSafeInteger(timeoutMs) || timeoutMs <= 0 ||
        !Number.isSafeInteger(maxStdoutBytes) || maxStdoutBytes <= 0 ||
        !Number.isSafeInteger(maxStderrBytes) || maxStderrBytes <= 0)
        return Promise.reject(new RangeError('Process limits must be positive integers'));
    return new Promise((resolve, reject) => {
        // detach on Linux makes the process its own process-group leader; only our
        // own newly-created group is killed, never the parent's process group.
        const detached = process.platform !== 'win32';
        let child;
        try {
            child = spawn(binary, [...args], { stdio: ['pipe', 'pipe', 'pipe'], shell: false, detached });
        }
        catch (error) {
            reject(error);
            return;
        }
        const stdout = [], stderr = [];
        let outBytes = 0, errBytes = 0, settled = false;
        let timeout;
        function finish(error, output) {
            if (settled)
                return;
            settled = true;
            if (timeout !== undefined)
                clearTimeout(timeout);
            if (error)
                reject(error);
            else
                resolve(output);
        }
        function terminate(reason) {
            // Stop the entire group before rejecting so inherited stdout and stderr
            // descriptors do not keep 'close' pending indefinitely.
            if (child.pid !== undefined) {
                if (detached) {
                    try {
                        process.kill(-child.pid, 'SIGKILL');
                    }
                    catch {
                        child.kill('SIGKILL');
                    }
                }
                else
                    child.kill('SIGKILL');
            }
            finish(reason);
        }
        child.stdout.on('data', (chunk) => {
            outBytes += chunk.length;
            if (outBytes > maxStdoutBytes) {
                terminate(new Error('Child stdout exceeded byte limit'));
                return;
            }
            stdout.push(chunk);
        });
        child.stderr.on('data', (chunk) => {
            errBytes += chunk.length;
            if (errBytes > maxStderrBytes) {
                terminate(new Error('Child stderr exceeded byte limit'));
                return;
            }
            stderr.push(chunk);
        });
        child.on('error', (error) => finish(error));
        child.on('close', (code, signal) => {
            finish(undefined, { code: code ?? -1, stdout: Buffer.concat(stdout), stderr: Buffer.concat(stderr), signal });
        });
        child.stdin.on('error', (error) => {
            // A command may exit before reading stdin; its exit code, not EPIPE,
            // determines whether it succeeded (as with `wl-copy` probes).
            if (error.code !== 'EPIPE')
                terminate(error);
        });
        timeout = setTimeout(() => terminate(new Error(`Child process timed out after ${timeoutMs}ms: ${binary}`)), timeoutMs);
        child.stdin.end(input);
    });
}