import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, existsSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { join, dirname, sep } from 'node:path';
import { createHash as hashForLayout } from 'node:crypto';
import { createRequire } from 'node:module';
import { resolveEmbeddedPath, isEmbeddedPath, embeddedRoot, loadEmbeddedRequire } from '../runtime/assets.mjs';
import { Nwo, Xft, dt, readEmbeddedAssetBytes, readEmbeddedAssetText, readEmbeddedAssetTextAsync } from '../chunk-6gaf2snv.js';
import { Fe, loadEmbeddedModule } from '../chunk-a2y39866.js';

const require = createRequire(import.meta.url);
const manifest = JSON.parse(readFileSync(new URL('../resources/manifest.json', import.meta.url), 'utf8'));
const layout = JSON.parse(readFileSync(new URL('../resources/asset-layout.json', import.meta.url), 'utf8'));
const digest = bytes => createHash('sha256').update(bytes).digest('hex');

test('原资源清单、文件哈希和来源标注一致', () => {
  assert.equal(manifest.referenced_virtual_assets, 247);
  assert.equal(manifest.resolved_assets, 247);
  assert.equal(manifest.missing_assets.length, 0);
  assert.equal(Object.keys(manifest.asset_provenance).length, 247);
  const count = Object.values(manifest.asset_provenance).reduce((a, v) => (a[v.source] = (a[v.source] || 0) + 1, a), {});
  assert.equal(count['2.1.310/v35'], 169);
  assert.equal(count['2.1.310/ELF/UTF16LE-to-UTF8-equivalent'], 12);
  assert.equal(count['2.1.310/ELF/bytes-equal-to-2.1.300'], 55);
  assert.equal(count['2.1.310/ELF/exact-recovered'], 11);
  for (const [name, entry] of Object.entries(manifest.asset_provenance)) {
    const file = resolveEmbeddedPath(name);
    assert.ok(existsSync(file), `资源缺失: ${name}`);
    const compressed = layout.entries[name];
    if (compressed) {
      assert.equal(compressed.compressedSha256, entry.sha256, `压缩来源校验失配: ${name}`);
      assert.equal(digest(readFileSync(file)), compressed.decodedSha256, `解压内容变更: ${name}`);
    } else {
      assert.equal(digest(readFileSync(file)), entry.sha256, `原始资源变更: ${name}`);
    }
  }
});

test('从源码直接读取未压缩的真实 2.1.310 内嵌资源', () => {
  const path = resolveEmbeddedPath('NOTICE-chm3rwx9.txt');
  const expected = readFileSync(path, 'utf8');
  assert.equal(dt(path, process.cwd()), expected);
  assert.equal(Fe(path), expected);
  assert.equal(loadEmbeddedRequire(path, require), expected);
});

test('从源码同步及异步读取 Zstandard 压缩资源', async () => {
  const path = resolveEmbeddedPath('README-29cff9e7.md.zst');
  assert.match(path, /resources\/docs\/managed-agents\/php/);
  const bytes = Nwo(path, process.cwd());
  assert.ok(bytes.length > 100);
  assert.equal(bytes.toString('utf8'), dt(path, process.cwd()));
  assert.equal(await Xft(path, process.cwd()), dt(path, process.cwd()));
  assert.equal(Fe(path), dt(path, process.cwd()));
});

test('从源码加载 2.1.310 的原生插件（仅验证导出，不触碰硬件）', () => {
  const clipboard = Fe(resolveEmbeddedPath('clipboard-napi.node'));
  const audio = Fe(resolveEmbeddedPath('audio-capture.node'));
  assert.equal(typeof clipboard.getLinuxClipboardText, 'function');
  assert.equal(typeof clipboard.setLinuxClipboardText, 'function');
  assert.equal(typeof audio.microphoneAuthorizationStatus, 'function');
});

// In v2 these 11 real content files were absent, causing eager skill-module imports
// to fail. Loading the exact 2.1.310 resource-bearing module is a functional test.
test('恢复的 11 份原版内嵌资源实际参与技能加载，105 份技能文档齐全', async () => {
  const { SKILL_FILES } = await import('../chunk-7kf9j3c4.js');
  assert.equal(Object.keys(SKILL_FILES).length, 105);
  for (const [path, source] of Object.entries(SKILL_FILES)) {
    assert.equal(typeof source, 'string', path);
    assert.ok(source.length > 50, `${path}: 文档内容为空`);
  }
  const examples = {
    'csharp/claude-api/opentelemetry.md': '# OpenTelemetry Tracing - C#',
    'csharp/claude-api/tool-use.md': '# Tool Use - C#',
    'python/claude-api/opentelemetry.md': '# OpenTelemetry Tracing - Python',
    'typescript/claude-api/opentelemetry.md': '# OpenTelemetry Tracing - TypeScript',
  };
  for (const [path, heading] of Object.entries(examples)) {
    assert.ok(SKILL_FILES[path]?.startsWith(heading), path);
  }
  for (const name of manifest.recovered_elf_assets) {
    const data = readFileSync(resolveEmbeddedPath(name), 'utf8');
    assert.ok(data.length > 100, `${name}: 原件恢复失败`);
  }
});

test('全部 12 份从原版 UTF-16LE 规范化的文档能按 UTF-8 读取', () => {
  assert.equal(manifest.utf16le_normalized_assets.length, 12);
  for (const name of manifest.utf16le_normalized_assets) {
    const path = resolveEmbeddedPath(name);
    const expected = readFileSync(path, 'utf8');
    assert.ok(expected.length > 100, name);
    assert.equal(loadEmbeddedRequire(path, require), expected);
  }
});


test('恢复的资源读取函数与 2.1.310 原始短符号完全兼容', async () => {
  assert.equal(readEmbeddedAssetBytes, Nwo);
  assert.equal(readEmbeddedAssetText, dt);
  assert.equal(readEmbeddedAssetTextAsync, Xft);
  assert.equal(loadEmbeddedModule, Fe);
  const path = resolveEmbeddedPath('README-29cff9e7.md.zst');
  assert.match(path, /resources\/docs\/managed-agents\/php/);
  assert.deepEqual(readEmbeddedAssetBytes(path, process.cwd()), Nwo(path, process.cwd()));
  assert.equal(readEmbeddedAssetText(path, process.cwd()), dt(path, process.cwd()));
  assert.equal(await readEmbeddedAssetTextAsync(path, process.cwd()), await Xft(path, process.cwd()));
  assert.equal(loadEmbeddedModule(path), Fe(path));
});

test('资源缺失与损坏的同步读取异常保留原始路径与原因', () => {
  const missing = resolveEmbeddedPath('definitely-missing-resource.md.zst');
  assert.throws(() => readEmbeddedAssetBytes(missing, process.cwd()), error =>
    error.message === 'embedded asset is missing or corrupt' &&
    error.path === missing &&
    error.cause?.code === 'ENOENT');
});

test('内嵌资源路径只接受规范化且实际仍位于受信任目录的文件', () => {
  const validOriginal=resolveEmbeddedPath('NOTICE-chm3rwx9.txt');
  const validMapped=resolveEmbeddedPath('README-29cff9e7.md.zst');
  assert.equal(isEmbeddedPath(validOriginal),true);
  assert.equal(isEmbeddedPath(validMapped),true);
  // A raw prefix-only check would treat this path as a BunFS asset and read a file outside it.
  const escaped=embeddedRoot+sep+'..'+sep+'asset-layout.json';
  assert.equal(isEmbeddedPath(escaped),false);
  assert.equal(isEmbeddedPath(embeddedRoot+sep+'..'+sep+'..'+sep+'README.md'),false);
  assert.equal(isEmbeddedPath(validOriginal+sep+'..'),false);
  assert.equal(isEmbeddedPath('resources/bunfs/NOTICE-chm3rwx9.txt'),false);
  assert.equal(isEmbeddedPath(undefined),false);
  let fallback=0;
  const sentinel={from:'fallback'};
  assert.equal(loadEmbeddedRequire(escaped,()=>{fallback++;return sentinel;}),sentinel);
  assert.equal(fallback,1,'traversal path must not bypass ordinary require handling');
});
