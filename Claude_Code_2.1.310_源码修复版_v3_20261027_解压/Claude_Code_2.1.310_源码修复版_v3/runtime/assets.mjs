import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { join, resolve, sep } from 'node:path';
import { zstdDecompressSync } from 'node:zlib';

// Portable replacement for Bun's embedded /$bunfs/root virtual filesystem.
export const embeddedRoot = resolve(fileURLToPath(new URL('../resources/bunfs/', import.meta.url)));
export function resolveEmbeddedPath(name) {
  if (!/^[^/\\]+$/.test(name) || name === '.' || name === '..') throw new Error(`invalid embedded resource: ${name}`);
  return join(embeddedRoot, name);
}
export function isEmbeddedPath(path) {
  return typeof path === 'string' && path.startsWith(embeddedRoot + sep);
}
export function loadEmbeddedRequire(path, fallbackRequire) {
  if (!isEmbeddedPath(path)) return fallbackRequire(path);
  if (path.endsWith('.node')) return fallbackRequire(path);
  const bytes = readFileSync(path);
  const zstd = bytes.length >= 4 && bytes[0] === 0x28 && bytes[1] === 0xb5 && bytes[2] === 0x2f && bytes[3] === 0xfd;
  return (zstd ? zstdDecompressSync(bytes) : bytes).toString('utf8');
}
