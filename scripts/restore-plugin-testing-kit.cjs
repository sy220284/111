'use strict';
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');

const root = path.resolve(__dirname, '..');
const bundle = fs.readFileSync(path.join(root, 'resources/tools/plugin-api/scripts/kit-modules.js'), 'utf8');
const destination = path.join(root, 'src/plugin-testing-kit');
const prefix = '@/src/plugins/functionHooks/plugin-test/testing';
const headers = [...bundle.matchAll(/^\/\/\/\/ ([^\r\n]+)$/gm)];
const sources = new Map(headers.map((match, index) => [
  match[1],
  bundle.slice(match.index + match[0].length + 1, index + 1 < headers.length ? headers[index + 1].index : bundle.length)
]));
assert.equal(sources.size, headers.length, 'duplicate source path in bundle');
assert.equal(sources.size, 294, '2.1.310 embedded plugin testing source file count');

function resolveSpecifier(from, specifier) {
  const targetBase = specifier.startsWith(prefix)
    ? specifier.slice(prefix.length).replace(/^\//, '')
    : specifier.startsWith('.')
      ? path.posix.join(path.posix.dirname(from), specifier)
      : (() => { throw new Error('unknown import in ' + from + ': ' + specifier); })();
  const resolvedBase = targetBase === '.' ? '' : targetBase;
  const candidates = [...new Set([
    resolvedBase ? resolvedBase + '.js' : 'index.js',
    resolvedBase ? resolvedBase + '/index.js' : 'index.js',
    resolvedBase
  ])].filter(name => sources.has(name));
  assert.equal(candidates.length, 1, 'ambiguous/missing source import: ' + from + ' -> ' + specifier);
  let relative = path.posix.relative(path.posix.dirname(from), candidates[0]);
  if (!relative.startsWith('.')) relative = './' + relative;
  return relative;
}

// Verified behavioral correction: testCaseOf(title, rest) destructures rest as an array.
function repairSource(name, source) {
  if (name === 'collector/create-collector/create-collector.js') {
    const oldSignature = 'test: (name, rest) => {';
    assert.equal(source.split(oldSignature).length, 2, 'unexpected original collector source');
    return source.replace(oldSignature, 'test: (name, ...rest) => {');
  }
  if (name === 'test/test.js') {
    const oldForwarding = 'collector.test(name, rest)';
    assert.equal(source.split(oldForwarding).length, 2, 'unexpected original public test entry');
    return source.replace(oldForwarding, 'collector.test(name, ...rest)');
  }
  return source;
}

const check = process.argv.includes('--check');
const expected = new Set();
let references = 0;
for (const [name, source] of sources) {
  assert.ok(!path.posix.isAbsolute(name) && !name.split('/').includes('..'), 'invalid source path: ' + name);
  const rewritten = repairSource(name, source).replace(/(\bfrom\s*|\bimport\s*|\bimport\(\s*)(['"])([^'"\r\n]+)\2/g,
    (entire, head, quote, specifier) => {
      references++;
      return head + quote + resolveSpecifier(name, specifier) + quote;
    });
  const output = path.join(destination, name);
  expected.add(output);
  if (check) {
    assert.equal(fs.readFileSync(output, 'utf8'), rewritten, 'source diverged from original: ' + name);
  } else {
    fs.mkdirSync(path.dirname(output), { recursive: true });
    fs.writeFileSync(output, rewritten);
  }
}
function allFiles(dir) {
  if (!fs.existsSync(dir)) return [];
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap(entry =>
    entry.isDirectory() ? allFiles(path.join(dir, entry.name)) : [path.join(dir, entry.name)]);
}
if (check) {
  const actual = allFiles(destination);
  assert.equal(actual.length, expected.size, 'unexpected plugin testing kit source file count');
  for (const file of actual) assert.ok(expected.has(file), 'unexpected plugin testing kit source: ' + file);
}
assert.equal(references, 628, 'module references from embedded source');
console.log(JSON.stringify({ result: 'PASS', mode: check ? 'check' : 'restore', files: sources.size, references }));
