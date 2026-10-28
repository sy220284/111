/**
 * Explicit opt-in loader for independently reconstructed Linux N-API modules.
 * This loader never replaces the original resources/bunfs/*.node files and
 * never inserts private Bun globals or modifies the process by itself.
 */
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { resolve, dirname } from 'node:path';
import { existsSync } from 'node:fs';

const require = createRequire(import.meta.url);
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const alternatives = Object.freeze({
  audio: 'audio-capture.node',
  clipboard: 'clipboard-napi.node',
  linuxSystem: 'bun-ant-linux.node',
});
/**
 * Load exactly one compiled alternative. Name allowlist prevents arbitrary
 * file injection via the module-loader boundary.
 * @param {'audio'|'clipboard'|'linuxSystem'} moduleName
 * @returns {object} Node-API exports from the built alternative.
 */
export function loadNativeLinuxAlternative(moduleName) {
  if (process.platform !== 'linux' || process.arch !== 'x64') {
    throw new Error('Native Linux alternatives require linux/x64');
  }
  if (!Object.hasOwn(alternatives, moduleName)) throw new RangeError('Unknown native module name');
  const moduleFile = resolve(root, '.generated/native-linux', alternatives[moduleName]);
  if (!existsSync(moduleFile)) throw new Error('Native alternative not built: ' + moduleName + '; run make -C native/linux');
  return require(moduleFile);
}
export const nativeLinuxAlternativeNames = Object.freeze(Object.keys(alternatives));