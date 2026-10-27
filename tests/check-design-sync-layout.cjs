'use strict';
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict'),crypto=require('node:crypto'),vm=require('node:vm');
const root=path.resolve(__dirname,'..');
const layout=JSON.parse(fs.readFileSync(path.join(root,'resources/asset-layout.json'),'utf8'));
const source=JSON.parse(fs.readFileSync(path.join(root,'resources/tools/design-sync/source-layout.json'),'utf8'));
const sha=d=>crypto.createHash('sha1').update('blob '+d.length+String.fromCharCode(0)).update(d).digest('hex');
assert.equal(source.migrated.length,12,'expected original source modules');
assert.equal(source.supporting.length,6,'expected source-backed helper modules');
const all=new Set();
let staticImports=0;
for(const entry of [...source.migrated,...source.supporting]){
 assert.ok(entry.path.startsWith('resources/tools/design-sync/'),'unexpected source placement: '+entry.path);
 assert.ok(!all.has(entry.path),'duplicate path '+entry.path);all.add(entry.path);
 const absolute=path.join(root,entry.path),data=fs.readFileSync(absolute);
 assert.equal(sha(data),entry.sha,'Git original content differs: '+entry.path);
 if(entry.key){assert.equal(layout.entries[entry.key]?.path,entry.path,'resource mapping lost: '+entry.key);assert.ok(!fs.existsSync(path.join(root,entry.old)),'old hashed source remains: '+entry.old)}
 else assert.ok(fs.existsSync(path.join(root,entry.source)),'original helper provenance missing: '+entry.source);
 const module=new vm.SourceTextModule(data.toString('utf8'),{identifier:absolute});
 for(const specifier of module.dependencySpecifiers){
  if(!specifier.startsWith('.'))continue;
  staticImports++;
  const dep=path.resolve(path.dirname(absolute),specifier);
  assert.ok(fs.existsSync(dep),'static source dependency unresolved: '+entry.path+' -> '+specifier);
 }
}
for(const [p,name] of [
 ['resources/tools/design-sync/resync.mjs','package-build.mjs'],
 ['resources/tools/design-sync/resync.mjs','package-validate.mjs'],
 ['resources/tools/design-sync/resync.mjs','storybook/compare.mjs'],
 ['resources/tools/design-sync/resync.mjs','lib/remote-diff.mjs'],
 ['resources/tools/design-sync/package-build.mjs','lib/source-kit.mjs'],
 ['resources/tools/design-sync/capture.mjs','storybook/http-serve.mjs']]){
 assert.ok(fs.existsSync(path.join(path.dirname(path.join(root,p)),name)),'driver dependency missing: '+p+' -> '+name);
}
assert.ok(source.knownUnresolved.length>0,'external/dynamic unfinished dependencies must stay explicit');
console.log(JSON.stringify({result:'PASS',tool:'design-sync',mapped:source.migrated.length,helpers:source.supporting.length,staticRelativeImports:staticImports,unresolvedTracked:source.knownUnresolved.length}));
