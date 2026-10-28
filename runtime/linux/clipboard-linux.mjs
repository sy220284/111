/**
 * 2.1.310 Linux clipboard backend, readable TypeScript implementation.
 * Sources: chunk-f451a1hs.js (generation ordering / read fallback),
 * 2.1.88 ink/termio/osc.ts (selection and OSC52 behavior).
 * This source is executable standalone. Existing 2.1.310 bundled clipboard
 * remains the production implementation; no downgrade of its behavior.
 */
import { runBoundedProcess } from './process-transport.mjs';
import { createRequire } from 'node:module';
/** Actual bounded process invocation; no shell or clipboard-as-code. */
export const execClipboard = async (program, args, input) => {
    const result = await runBoundedProcess(program, args, input, { timeoutMs: 2000, maxStdoutBytes: 16 * 1024 * 1024 });
    return { code: result.code, stdout: result.stdout.toString('utf8') };
};
export class LinuxClipboard {
    run;
    nativeRead;
    writeGeneration = 0;
    constructor(run = execClipboard, nativeRead) {
        this.run = run;
        this.nativeRead = nativeRead;
    }
    // 2.1.310 does not let an older first-phase copy overwrite a newer
    // request's PRIMARY selection after the newer clipboard copy begins.
    async writeWayland(text) {
        const generation = ++this.writeGeneration;
        const clipboard = await this.run('wl-copy', [], text);
        if (clipboard.code !== 0)
            return false;
        if (generation !== this.writeGeneration)
            return true;
        return (await this.run('wl-copy', ['--primary'], text)).code === 0;
    }
    async read(selection = 'clipboard') {
        const primary = selection === 'primary';
        const candidates = [
            ['wl-paste', primary ? ['--primary', '--no-newline'] : ['--no-newline']],
            ['xclip', ['-selection', selection, '-o']],
            ['xsel', [primary ? '--primary' : '--clipboard', '--output']],
        ];
        for (const [command, args] of candidates) {
            try {
                const result = await this.run(command, args);
                if (result.code === 0)
                    return result.stdout;
            }
            catch { // Command may not be installed; continue to next real backend.
            }
        }
        if (this.nativeRead) {
            try {
                const text = await this.nativeRead(primary, 2000);
                return text?.replace(/\r?\n$|\r$/, '') ?? '';
            }
            catch { }
        }
        return '';
    }
}
/** Optional direct bridge to the recompiled 2.1.310 Linux X11 .node backend.
 * LinuxClipboard retains explicit command precedence: wl-paste > xclip >
 * xsel > N-API. This is the order seen in chunk-f451a1hs.js. */
export function createLinuxClipboardWithNative(moduleFile, run = execClipboard) {
    const load = createRequire(import.meta.url);
    let nativeRead;
    try {
        const addon = load(moduleFile);
        if (typeof addon.getLinuxClipboardText === 'function') {
            nativeRead = (primary, timeoutMs) => addon.getLinuxClipboardText(primary, timeoutMs);
        }
    }
    catch { /* Missing platform addon: the command backends remain usable. */ }
    return new LinuxClipboard(run, nativeRead);
}