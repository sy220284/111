'use strict';
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto'),assert=require('node:assert/strict'),vm=require('node:vm'),{spawnSync}=require('node:child_process');
const root=path.resolve(__dirname,'..');
const manifest=JSON.parse(fs.readFileSync(path.join(root,'tools/bundled/claude-test/source-layout.json'),'utf8'));
const assets=JSON.parse(fs.readFileSync(path.join(root,'resources/asset-layout.json'),'utf8'));
assert.equal(manifest.migrated.length,18);
assert.equal(manifest.supporting.length,6);
const sha=data=>crypto.createHash('sha1').update('blob '+data.length+String.fromCharCode(0)).update(data).digest('hex');
const positions=new Set();
for(const r of [...manifest.migrated,...manifest.supporting]){
 assert.ok(!positions.has(r.path),'duplicate path '+r.path);positions.add(r.path);
 assert.ok(r.path.startsWith('tools/bundled/claude-test/scripts/'));
 const data=fs.readFileSync(path.join(root,r.path));assert.equal(sha(data),r.sha,'original extracted file content changed: '+r.path);
 if(r.key){assert.equal(assets.entries[r.key]?.path,r.path,'BunFS source mapping lost: '+r.key);assert.ok(!fs.existsSync(path.join(root,r.old)),'old hashed source remains: '+r.old);}
 else assert.ok(fs.existsSync(path.join(root,r.source)),'provenance source missing: '+r.source);
 if(r.path.endsWith('.mjs')){const check=spawnSync(process.execPath,['--check',path.join(root,r.path)],{encoding:'utf8'});assert.equal(check.status,0,check.stderr+' : '+r.path);}
 if(r.path.endsWith('.json'))JSON.parse(data.toString('utf8'));
}
let actualStaticImports=0;
for(const entry of [...manifest.migrated,...manifest.supporting]){
 if(!entry.path.endsWith('.mjs'))continue;
 const absolute=path.join(root,entry.path);
 const parsed=new vm.SourceTextModule(fs.readFileSync(absolute,'utf8'),{identifier:absolute});
 for(const specifier of parsed.dependencySpecifiers){
  if(!specifier.startsWith('.'))continue;
  actualStaticImports++;
  const resolved=path.resolve(path.dirname(absolute),specifier);
  assert.ok(fs.existsSync(resolved),'undeclared or missing actual static import: '+entry.path+' -> '+specifier);
 }
}
for(const e of manifest.links){
 const dest=path.resolve(path.dirname(path.join(root,e.from)),e.relative);
 assert.ok(fs.existsSync(dest),'relative filesystem dependency missing: '+e.from+' -> '+e.relative);
}
for(const [p,ref] of [['tools/bundled/claude-test/scripts/lib/hosts.mjs','psl-data.json'],['tools/bundled/claude-test/scripts/lib/tools.mjs','tools-lock.json'],['tools/bundled/claude-test/scripts/playwright-mcp.mjs','tools-snapshot.json']]) {
 assert.ok(fs.existsSync(path.join(path.dirname(path.join(root,p)),ref)), 'support data absent: '+p+' -> '+ref);
}
console.log(JSON.stringify({result:'PASS',tool:'claude-test',mappedModules:manifest.migrated.length,sourceBackedAuxiliaries:manifest.supporting.length,checkedLinks:manifest.links.length,staticRelativeImports:actualStaticImports}));
