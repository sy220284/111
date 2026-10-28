'use strict';
// Cross-check ALL 250 original BunFS resource names against their current locations,
// source references and both archived-compressed and decoded SHA256 evidence.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '..');
const src = root;
const bunfs = path.join(root, 'resources/bunfs');
const provenance = JSON.parse(fs.readFileSync(path.join(root,'resources/manifest.json'),'utf8'));
const layout = JSON.parse(fs.readFileSync(path.join(root,'resources/asset-layout.json'),'utf8'));
const generated = path.join(root,'.generated');
const detailedOutput = path.join(generated,'resource-consumers.json');
const summaryOutput = path.join(root,'resources/resource-consumer-summary.json');
const sha256 = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
const sources = fs.readdirSync(path.join(src,'core')).filter(f=>f.endsWith('.js')).sort();
const seen = new Map();
const pattern = /\b__cc310AssetPath\(\s*(['"])([^'"\r\n]+)\1\s*\)/g;
for(const file of sources){
 const code=fs.readFileSync(path.join(src,'core',file),'utf8');
 for(const m of code.matchAll(pattern)){
  const consumers=seen.get(m[2]) || new Set();
  consumers.add('core/'+file);
  seen.set(m[2],consumers);
 }
}
const activeNames=[...new Set([...fs.readdirSync(bunfs),...Object.keys(layout.entries)])].sort();
assert.equal(activeNames.length,250,'total original BunFS resource names');
assert.equal(Object.keys(layout.entries).length,140,'all compressed frames relocated');
const results={};
for(const name of activeNames){
 const entry=layout.entries[name];
 const location=entry?path.resolve(root,entry.path):path.resolve(bunfs,name);
 assert.ok(location.startsWith(root+path.sep),'resource path escape: '+name);
 assert.ok(fs.existsSync(location),'resource missing: '+name+' -> '+location);
 const data=fs.readFileSync(location);
 const currentSha=sha256(data);
 if(entry){
  assert.equal(currentSha,entry.decodedSha256,'decoded byte integrity: '+name);
  if(provenance.asset_provenance[name]){
   assert.equal(entry.compressedSha256,provenance.asset_provenance[name].sha256,'original archive provenance: '+name);
  }
 }else if(provenance.asset_provenance[name]){
  assert.equal(currentSha,provenance.asset_provenance[name].sha256,'original raw asset integrity: '+name);
 }
 const direct=[...(seen.get(name)||[])].sort();
 const loaders=entry?.kind==='bundled-runtime-library'
   ?sources.filter(s=>fs.readFileSync(path.join(src,'core',s),'utf8').includes('./'+name)).map(s=>'core/'+s).sort():[];
 assert.ok(direct.length||loaders.length,'missing functional source consumer: '+name);
 results[name]={path:entry?.path??'resources/bunfs/'+name,kind:entry?.kind??'raw-original-resource',sha256:currentSha,literalConsumers:direct,sourceLoaderConsumers:loaders};
}
for(const name of seen.keys())assert.ok(results[name],'source references absent BunFS asset: '+name);
const direct=Object.values(results).filter(x=>x.literalConsumers.length).length;
const indirect=Object.values(results).filter(x=>x.sourceLoaderConsumers.length).length;
assert.equal(direct,provenance.referenced_virtual_assets);
assert.equal(indirect,3);
assert.equal(fs.readdirSync(bunfs).filter(n=>n.endsWith('.zst')).length,0);
const index={version:'2.1.310',total:activeNames.length,direct,indirect,relocated:Object.keys(layout.entries).length,entries:results};
const fixed={
 version:index.version,
 summary:{total:index.total,literalReferenced:direct,sourceLoaderReferenced:indirect,withoutFunctionalConsumer:0,relocatedCompressed:140},
 sourceBundles:Object.fromEntries(Object.entries(results).filter(([,v])=>v.sourceLoaderConsumers.length).map(([name,v])=>[name,{source:v.path,loaders:v.sourceLoaderConsumers,sha256:v.sha256}]))
};
const fixedText=JSON.stringify(fixed,null,2)+'\n';
if(process.argv.includes('--check'))assert.equal(fs.readFileSync(summaryOutput,'utf8'),fixedText,'outdated consumer summary');
else{
 fs.mkdirSync(generated,{recursive:true});
 fs.writeFileSync(detailedOutput,JSON.stringify(index,null,2)+'\n');
 fs.writeFileSync(summaryOutput,fixedText);
}
console.log(JSON.stringify({status:'PASS',count:index.total,direct,indirect,relocated:index.relocated,mode:process.argv.includes('--check')?'check':'generate'}));
