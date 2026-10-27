'use strict';

// Rebuild the source-to-resource relationship from actual 2.1.310 call sites.
// This index records *literal* calls to __cc310AssetPath; dynamic callers
// are not inferred and must be checked against their enclosing feature code.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const assert = require('node:assert/strict');

const project = path.resolve(__dirname, '..');
const source = path.join(project, 'src');
const resourceDirectory = path.join(project, 'resources/bunfs');
const provenance = JSON.parse(fs.readFileSync(path.join(project, 'resources/manifest.json'), 'utf8'));
const output = path.join(project, 'resources/consumers.json');
const callsite = /\b__cc310AssetPath\(\s*(['"])([^'"\r\n]+)\1\s*\)/g;
const consumers = new Map();

for (const file of fs.readdirSync(source).filter(name => name.endsWith('.js')).sort()) {
  const text = fs.readFileSync(path.join(source, file), 'utf8');
  let match;
  while ((match = callsite.exec(text)) !== null) {
    const name = match[2];
    const callers = consumers.get(name) || new Set();
    callers.add('src/' + file);
    consumers.set(name, callers);
  }
}

function classify(name) {
  if (name.endsWith('.node')) return 'native-addon';
  if (name.endsWith('.woff2.asset')) return 'font';
  if (/\.tsx?-[a-z0-9]+\.txt(?:\.zst)?$/.test(name)) return 'typescript-source';
  if (/\.mjs-[a-z0-9]+\.txt(?:\.zst)?$/.test(name) || name.endsWith('.mjs')) return 'javascript-module';
  if (/\.js-[a-z0-9]+\.txt(?:\.zst)?$/.test(name) || name.endsWith('.js')) return 'javascript-source';
  if (/\.html-[a-z0-9]+\.txt(?:\.zst)?$/.test(name)) return 'html-template';
  if (/\.json-[a-z0-9]+\.txt(?:\.zst)?$/.test(name)) return 'json-data';
  if (/\.py(?:-[a-z0-9]+\.txt)?(?:\.zst)?$/.test(name)) return 'python-source';
  if (/\.md(?:\.zst)?$/.test(name)) return 'markdown';
  return 'text';
}

const entries = {};
for (const name of fs.readdirSync(resourceDirectory).sort()) {
  const data = fs.readFileSync(path.join(resourceDirectory, name));
  const original = provenance.asset_provenance[name];
  const sha256 = crypto.createHash('sha256').update(data).digest('hex');
  if (original) assert.equal(sha256, original.sha256, 'provenance mismatch: ' + name);
  const magic = data.length >= 4 && data[0] === 0x28 && data[1] === 0xb5 && data[2] === 0x2f && data[3] === 0xfd;
  entries[name] = {
    kind: classify(name),
    encoding: magic ? 'zstd' : 'raw',
    bytes: data.length,
    sha256,
    evidence: original?.source ?? '2.1.310/v35/additional-bunfs',
    literalConsumers: [...(consumers.get(name) || [])].sort(),
  };
}

for (const name of consumers.keys()) assert.ok(entries[name], 'source references missing asset ' + name);
const referenced = Object.values(entries).filter(item => item.literalConsumers.length > 0).length;
const orphanNames = Object.entries(entries).filter(([, item]) => !item.literalConsumers.length).map(([name]) => name);
assert.equal(Object.keys(entries).length, 250, 'all 2.1.310 embedded resources');
assert.equal(referenced, provenance.referenced_virtual_assets, 'literal resource consumer coverage');
assert.deepEqual(orphanNames, [...provenance.additional_unreferenced_v35_assets].sort());

const index = {
  version: '2.1.310',
  detection: 'literal __cc310AssetPath callsites; dynamic code paths not counted',
  summary: { total: Object.keys(entries).length, literalReferenced: referenced, withoutLiteralReference: orphanNames.length },
  resources: entries,
};
const content = JSON.stringify(index, null, 2) + '\n';
if (process.argv.includes('--check')) {
  console.log(JSON.stringify({ ...index.summary, index: 'verified' }));
} else {
  fs.writeFileSync(output, content);
  console.log(JSON.stringify({ ...index.summary, index: 'generated' }));
}
