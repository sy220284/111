'use strict';
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict'),crypto=require('node:crypto'),vm=require('node:vm');
const root=path.resolve(__dirname,'..');
const layout=JSON.parse(fs.readFileSync(path.join(root,'resources/asset-layout.json'),'utf8'));
const source=JSON.parse(fs.readFileSync(path.join(root,'tools/bundled/design-sync/source-layout.json'),'utf8'));
const sha=d=>crypto.createHash('sha1').update('blob '+d.length+String.fromCharCode(0)).update(d).digest('hex');
assert.equal(source.migrated.length,12,'expected original source modules');
assert.equal(source.supporting.length,6,'expected source-backed helper modules');
const currentPath=p=>p.replace(/^resources\/tools\//,'tools/bundled/');
const all=new Set();
let staticImports=0;
for(const entry of [...source.migrated,...source.supporting]){
 assert.ok(currentPath(entry.path).startsWith('tools/bundled/design-sync/'),'unexpected source placement: '+entry.path);
 assert.ok(!all.has(entry.path),'duplicate path '+entry.path);all.add(entry.path);
 const absolute=path.join(root,currentPath(entry.path)),data=fs.readFileSync(absolute);
 assert.equal(sha(data),entry.sha,'Git original content differs: '+entry.path);
 if(entry.key){assert.equal(layout.entries[entry.key]?.path,currentPath(entry.path),'resource mapping lost: '+entry.key);assert.ok(!fs.existsSync(path.join(root,entry.old)),'old hashed source remains: '+entry.old)}
 else assert.ok(fs.existsSync(path.join(root,currentPath(entry.source))),'original helper provenance missing: '+entry.source);
 const module=new vm.SourceTextModule(data.toString('utf8'),{identifier:absolute});
 for(const specifier of module.dependencySpecifiers){
  if(!specifier.startsWith('.'))continue;
  staticImports++;
  const dep=path.resolve(path.dirname(absolute),specifier);
  assert.ok(fs.existsSync(dep),'static source dependency unresolved: '+entry.path+' -> '+specifier);
 }
}
for(const [p,name] of [
 ['tools/bundled/design-sync/resync.mjs','package-build.mjs'],
 ['tools/bundled/design-sync/resync.mjs','package-validate.mjs'],
 ['tools/bundled/design-sync/resync.mjs','storybook/compare.mjs'],
 ['tools/bundled/design-sync/resync.mjs','lib/remote-diff.mjs'],
 ['tools/bundled/design-sync/package-build.mjs','lib/source-kit.mjs'],
 ['tools/bundled/design-sync/package-capture.mjs','storybook/http-serve.mjs']]){
 assert.ok(fs.existsSync(path.join(path.dirname(path.join(root,p)),name)),'driver dependency missing: '+p+' -> '+name);
}
// The original BunFS entry was named capture.mjs, but its real executable contract is package-capture.mjs.
const captureKey='capture.mjs-9e2f2f8b.txt.zst';
const restoredCapture='tools/bundled/design-sync/package-capture.mjs';
assert.equal(layout.entries[captureKey]?.path,restoredCapture,'original BunFS mapping must point to executable name');
assert.equal(currentPath(source.migrated.find(x=>x.key===captureKey)?.path),restoredCapture,'source provenance must track renamed executable');
assert.ok(!fs.existsSync(path.join(root,'tools/bundled/design-sync/capture.mjs')),'obsolete capture alias must be removed');
const resyncText=fs.readFileSync(path.join(root,'tools/bundled/design-sync/resync.mjs'),'utf8');
const scriptsHashText=fs.readFileSync(path.join(root,'tools/bundled/design-sync/lib/sync-hashes.mjs'),'utf8');
assert.ok(resyncText.includes("runStage('capture', './package-capture.mjs', args)"),'resync capture stage must invoke actual filename');
assert.ok(scriptsHashText.includes("'package-capture.mjs'"),'build recipe fingerprint must include the same file');
const packageBuildText=fs.readFileSync(path.join(root,'tools/bundled/design-sync/package-build.mjs'),'utf8');
const adapters=[...packageBuildText.matchAll(/loadLib\(['"]([^'"]+)['"]\)/g)].map(x=>x[1]);
const distinct=[...new Set(adapters)].sort();
assert.equal(distinct.length,14,'all literal dynamic package-build adapters must be accounted for');
for(const name of distinct){
 const dep=path.join(root,'tools/bundled/design-sync/lib',name+'.mjs');
 assert.ok(fs.existsSync(dep),'runtime-selected adapter file missing: '+name);
 new vm.SourceTextModule(fs.readFileSync(dep,'utf8'),{identifier:dep});
}
// CLI error-path smoke test: runs the real executable, loading its actual imported libraries.
const {spawnSync}=require('node:child_process');
const smoke=spawnSync(process.execPath,[path.join(root,restoredCapture)],{cwd:root,encoding:'utf8',timeout:12000});
assert.equal(smoke.status,2,'missing --out must exit 2, indicating the actual script loaded and validated its arguments: '+smoke.stderr);
assert.match(smoke.stderr,/usage: package-capture\.mjs --out/,'original package-capture CLI error contract');
// scriptsShaFor actual runtime hash smoke: silent missing-file fallback in hashFile
// must not conceal a vanished input to the design upload recipe.
const expectedHashedPaths=[
  'package-build.mjs','package-validate.mjs','package-capture.mjs','resync.mjs',
  'storybook/compare.mjs','storybook/http-serve.mjs','storybook/probe.mjs'
];
for(const item of expectedHashedPaths)
 assert.ok(fs.existsSync(path.join(root,'tools/bundled/design-sync',item)),'recipe input file missing: '+item);
const hashProbe=spawnSync(process.execPath,['--input-type=module','--eval',
  "import {scriptsShaFor} from './tools/bundled/design-sync/lib/sync-hashes.mjs'; process.stdout.write(scriptsShaFor());"
],{cwd:root,encoding:'utf8',timeout:12000});
assert.equal(hashProbe.status,0,'scriptsShaFor original implementation failed to execute: '+hashProbe.stderr);
assert.match(hashProbe.stdout,/^[0-9a-f]{16}$/,'source recipe fingerprint must be stable 16-hex string');

assert.deepEqual(source.knownUnresolved,[],'previous two filename/module-presence blockers are resolved');
assert.equal(source.runtimeNotYetVerified.length,2,'actual Playwright and build execution still require separate validation');

console.log(JSON.stringify({result:'PASS',tool:'design-sync',mapped:source.migrated.length,helpers:source.supporting.length,staticRelativeImports:staticImports,resolvedDynamicAdapters:distinct.length,unresolvedTracked:source.knownUnresolved.length,runtimeLimits:source.runtimeNotYetVerified.length}));
