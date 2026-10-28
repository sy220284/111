import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createLinuxClipboardWithNative } from '../runtime/linux/clipboard-linux.mjs';
import { loadNativeLinuxAlternative } from '../runtime/native-linux.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const nativePath = resolve(root, '.generated/native-linux/clipboard-napi.node');
const display = process.platform === 'linux' && !!process.env.DISPLAY;
const compiled = existsSync(nativePath);

test('Linux high-level fallback really invokes recompiled X11 N-API for both selections',
  { skip: !display || !compiled ? 'Requires built native addon and isolated Xvfb DISPLAY' : false, timeout: 12000 },
  async () => {
    const text = '111_MAINLINE_原生桥接_验证';
    const native = loadNativeLinuxAlternative('clipboard');
    assert.equal(native.setLinuxClipboardText(text), true);
    const invoked = [];
    const clipboard = createLinuxClipboardWithNative(nativePath, async (program, args) => {
      invoked.push({program,args});
      return {code: 127, stdout: ''};
    });
    assert.equal(await clipboard.read('clipboard'), text);
    assert.equal(await clipboard.read('primary'), text);
    assert.deepEqual(invoked.map(x => x.program),
      ['wl-paste','xclip','xsel','wl-paste','xclip','xsel']);
  });
