import { createHash } from 'node:crypto';

// Preserve Bun's original 64-bit hash path when Bun is available. Node has
// no Bun.hash; use a stable 64-bit digest for opaque per-runtime file baselines
// and prompt fingerprints. This is NOT bit-identical to Bun.hash across runtimes.
export function portableBunHash(value) {
  const nativeHash = globalThis.Bun?.hash;
  if (typeof nativeHash === 'function') return nativeHash(value);
  const bytes = typeof value === 'string'
    ? Buffer.from(value)
    : value instanceof ArrayBuffer
      ? Buffer.from(value)
      : ArrayBuffer.isView(value)
        ? Buffer.from(value.buffer, value.byteOffset, value.byteLength)
        : Buffer.from(String(value));
  return createHash('sha256').update(bytes).digest().readBigUInt64LE(0);
}
