import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { resolveEmbeddedPath } from '../runtime/assets.mjs';
import { loadChartBundleJs } from '../core/chunk-nxfv8sbm.js';
import { loadHljsBundleJs } from '../core/chunk-crqz6t7m.js';
import { loadMermaidBundleJs } from '../core/chunk-axeech6e.js';

for (const [name, loader] of [
  ['core/chart.umd.min.js',loadChartBundleJs],
  ['core/hljsBundle.generated.min.js',loadHljsBundleJs],
  ['core/mermaid.min.js',loadMermaidBundleJs],
]) {
  test('资源源码、压缩原件及实际加载行为一致：'+name,async()=>{
    const actualPath=resolveEmbeddedPath(name);
    const source=readFileSync(new URL('../'+name,import.meta.url));
    const current=readFileSync(actualPath);
    assert.deepEqual(current,source);
    const text=await loader();
    if(name==='core/mermaid.min.js') assert.ok(text.endsWith(source.toString('utf8')));
    else assert.equal(text,source.toString('utf8'));
  });
}
