import { readFileSync, realpathSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { join, resolve, sep, isAbsolute } from 'node:path';
import { zstdDecompressSync } from 'node:zlib';

// The original 2.1.310 module imports retain their BunFS resource names.
// The relocated files are now readable source/doc/template files on disk.
export const projectRoot = resolve(fileURLToPath(new URL('../', import.meta.url)));
export const embeddedRoot = resolve(projectRoot, 'resources/bunfs');
const layout = JSON.parse(readFileSync(new URL('../resources/asset-layout.json', import.meta.url), 'utf8'));
const mappedPaths = new Map();
const relocatedPaths = new Set();
for (const [name, entry] of Object.entries(layout.entries)) {
  if (!/^[^/\\]+$/.test(name) || name === '.' || name === '..') throw new Error('invalid embedded resource key');
  const resolved = resolve(projectRoot, entry.path);
  if (!resolved.startsWith(projectRoot + sep)) throw new Error('invalid relocated path for ' + name);
  mappedPaths.set(name, resolved);
  relocatedPaths.add(resolved);
}

/** Resolve the original BunFS name to its new categorized location. */
export function resolveEmbeddedPath(name) {
  if (!/^[^/\\]+$/.test(name) || name === '.' || name === '..') throw new Error(`invalid embedded resource: ${name}`);
  return mappedPaths.get(name) ?? join(embeddedRoot, name);
}

/** Only known embedded files may bypass normal Node require semantics. */
// Only classify canonical, existing assets as embedded. Raw prefix matching would
// trust "resources/bunfs/../sensitive-file" and symlinks pointing outside BunFS.
export function isEmbeddedPath(path) {
  if (typeof path !== 'string' || !isAbsolute(path) || resolve(path) !== path) return false;
  const raw = path.startsWith(embeddedRoot + sep);
  if (!raw && !relocatedPaths.has(path)) return false;
  try {
    const actual = realpathSync(path);
    const trustedRoot = realpathSync(raw ? embeddedRoot : projectRoot);
    if (!actual.startsWith(trustedRoot + sep)) return false;
    return raw || relocatedPaths.has(path);
  } catch {
    return false;
  }
}

export function loadEmbeddedRequire(path, fallbackRequire) {
  if (!isEmbeddedPath(path)) return fallbackRequire(path);
  if (path.endsWith('.node')) return fallbackRequire(path);
  const bytes = readFileSync(path);
  const zstd = bytes.length >= 4 && bytes[0] === 0x28 && bytes[1] === 0xb5 && bytes[2] === 0x2f && bytes[3] === 0xfd;
  return (zstd ? zstdDecompressSync(bytes) : bytes).toString('utf8');
}
