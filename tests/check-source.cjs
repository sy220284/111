'use strict';
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createRequire } = require('node:module');
const assert = require('node:assert/strict');

const src = path.resolve(__dirname, '..');
const embeddedWorker = 'plugins/functionHooks/hooks-worker/hooks-worker.js';
const nonModules = new Set(['mermaid.min.js', 'chart.umd.min.js', 'hljsBundle.generated.min.js']);
const files = fs.readdirSync(path.join(src,'core')).filter(n => n.endsWith('.js') && !nonModules.has(n)).map(n=>'core/'+n);
const names = [...files, embeddedWorker];
assert.equal(names.length, 2551, 'embedded JS source module count');

const modules = new Map();
const synthetic = new Map();
const externalRequests = new Set();
let refs = 0;
for (const name of names) {
  const data = fs.readFileSync(path.join(src, name), 'utf8');
  assert.ok(data.length > 0, `empty source: ${name}`);
  const module = new vm.SourceTextModule(data, { identifier: name });
  modules.set(name, module);
  for (const specifier of module.dependencySpecifiers) {
    refs++;
    if (!specifier.startsWith('.')) { assert.ok(!specifier.startsWith('/'), `unresolved absolute import: ${name} -> ${specifier}`); externalRequests.add(specifier); }
    else assert.ok(fs.existsSync(path.resolve(src, path.dirname(name), specifier)), `${name}: missing ${specifier}`);
  }
}

// Runtime adapters participate in the same static link graph. A restored main
// source module may import these files directly; check real named ESM exports.
const runtimeFiles = ['assets.mjs', 'string-width.mjs', 'portable-bun-hash.mjs'];
for (const filename of runtimeFiles) {
  const runtimeId = 'runtime/' + filename;
  const runtimeText = fs.readFileSync(path.resolve(src, runtimeId), 'utf8');
  const runtimeModule = new vm.SourceTextModule(runtimeText, { identifier: runtimeId });
  modules.set(runtimeId, runtimeModule);
  for (const specifier of runtimeModule.dependencySpecifiers) {
    refs++;
    if (!specifier.startsWith('.')) externalRequests.add(specifier);
    else assert.ok(fs.existsSync(path.resolve(src, 'runtime', specifier)),
      `runtime: missing ${specifier}`);
  }
}
const requireFromProject = createRequire(path.join(src, 'core/cli.js'));
for (const specifier of externalRequests) {
  if (specifier === 'ws') {
    // Verifies own ESM names without installing an unrelated runtime package.
    synthetic.set(specifier, new vm.SyntheticModule(['default'], function() {}, { identifier: specifier }));
    continue;
  }
  assert.ok(requireFromProject.resolve(specifier.startsWith('node:') ? specifier : 'node:' + specifier), `missing runtime builtin: ${specifier}`);
  const library = requireFromProject(specifier.startsWith('node:') ? specifier : 'node:' + specifier);
  const exportedNames = [...new Set(['default', ...Object.keys(library)])].filter(n => /^[$A-Z_a-z][$\w]*$/.test(n));
  synthetic.set(specifier, new vm.SyntheticModule(exportedNames, function() {}, { identifier: specifier }));
}

function resolveDependency(request, referencingModule) {
  if (!request.startsWith('.')) {
    const ext = synthetic.get(request);
    assert.ok(ext, `unresolved non-relative dependency: ${request}`);
    return ext;
  }
  const name = path.posix.normalize(path.posix.join(path.posix.dirname(referencingModule.identifier), request));
  const module = modules.get(name);
  assert.ok(module, `missing source module: ${referencingModule.identifier} -> ${request}`);
  return module;
}
(async () => {
  // Link without evaluating: validates circular references, import/export names and all 2551 modules.
  for (const module of modules.values()) if (module.status === 'unlinked') await module.link(resolveDependency);
  const unlinked = [...modules.values()].filter(m => m.status !== 'linked');
  assert.deepEqual(unlinked.map(m => m.identifier), []);
  assert.match(fs.readFileSync(path.join(src, 'core/cli.js'), 'utf8'), /Version: 2\.1\.310/);
  console.log(JSON.stringify({version:'2.1.310', checkedModules: names.length, checkedImports: refs, missingStaticImports: 0, missingNamedExports: 0, externalRuntimeDependencies: [...externalRequests].filter(n => n==='ws'), result:'PASS'}));
})().catch(error => {console.error(error.stack || String(error));process.exitCode=1});
