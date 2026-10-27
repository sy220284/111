import { readFileSync } from 'node:fs';
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
export function isEmbeddedPath(path) {
  return typeof path === 'string' && (relocatedPaths.has(path) ||
    (isAbsolute(path) && path.startsWith(embeddedRoot + sep)));
}

export function loadEmbeddedRequire(path, fallbackRequire) {
  if (!isEmbeddedPath(path)) return fallbackRequire(path);
  if (path.endsWith('.node')) return fallbackRequire(path);
  const bytes = readFileSync(path);
  const zstd = bytes.length >= 4 && bytes[0] === 0x28 && bytes[1] === 0xb5 && bytes[2] === 0x2f && bytes[3] === 0xfd;
  return (zstd ? zstdDecompressSync(bytes) : bytes).toString('utf8');
}
