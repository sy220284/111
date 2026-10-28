// @bun @bytecode
// Claude Code is a Beta product per Anthropic's Commercial Terms of Service.
// By using Claude Code, you agree that all code acceptance or rejection decisions you make,
// and the associated conversations in context, constitute Feedback under Anthropic's Commercial Terms,
// and may be used to improve Anthropic's products, including training models.
// You are responsible for reviewing any code suggestions before use.
// (c) Anthropic PBC. All rights reserved. Use is subject to the Legal Agreements outlined here: https://code.claude.com/docs/en/legal-and-compliance.
// Version: 2.1.310

import { readFileSync } from 'node:fs';
import { readFile } from 'node:fs/promises';
import { isAbsolute, join } from 'node:path';
import { zstdDecompressSync, zstdDecompress } from 'node:zlib';
import { promisify } from 'node:util';

const ZSTD_MAGIC = [0x28, 0xb5, 0x2f, 0xfd];
const decompressAsync = promisify(zstdDecompress);

/** Resolve a file-backed resource with the same absolute/relative rules as the 2.1.310 loader. */
function resolveAssetPath(resourcePath, baseDirectory) {
  return isAbsolute(resourcePath) ? resourcePath : join(baseDirectory, resourcePath);
}

/** Match the Zstandard frame magic; uncompressed files are left untouched. */
function isZstdAsset(bytes) {
  return bytes.length >= ZSTD_MAGIC.length && ZSTD_MAGIC.every((byte, index) => bytes[index] === byte);
}

/** Read the binary payload of an embedded resource, decompressing Zstandard when present. */
function readEmbeddedAssetBytes(resourcePath, baseDirectory) {
  const path = resolveAssetPath(resourcePath, baseDirectory);
  try {
    const bytes = readFileSync(path);
    return isZstdAsset(bytes) ? zstdDecompressSync(bytes) : bytes;
  } catch (cause) {
    throw Object.assign(new Error('embedded asset is missing or corrupt', { cause }), { path });
  }
}

/** Decode the embedded resource as UTF-8 text, preserving the original synchronous behavior. */
function readEmbeddedAssetText(resourcePath, baseDirectory) {
  return readEmbeddedAssetBytes(resourcePath, baseDirectory).toString('utf8');
}

/** Async counterpart; like the original, filesystem/decompression errors propagate directly. */
async function readEmbeddedAssetTextAsync(resourcePath, baseDirectory) {
  const bytes = await readFile(resolveAssetPath(resourcePath, baseDirectory));
  return (isZstdAsset(bytes) ? await decompressAsync(bytes) : bytes).toString('utf8');
}

// Preserve the original exported names for all existing compiled-module consumers.
export {
  readEmbeddedAssetBytes,
  readEmbeddedAssetText,
  readEmbeddedAssetTextAsync,
  readEmbeddedAssetBytes as Nwo,
  readEmbeddedAssetText as dt,
  readEmbeddedAssetTextAsync as Xft,
};
