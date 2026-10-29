'use strict';
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');
const assert=require('node:assert/strict');

// Traverse only real static relative imports; every discovered adapter is linked
// as a VM SourceTextModule, so missing files and wrong ESM export names fail.
function discoverStaticRuntimeModules({root,modules,externalRequests,onImport=()=>{}}){
  const pending=[];
  function enqueue(importer,request){
    if(!request.startsWith('.'))return;
    const name=path.posix.normalize(path.posix.join(path.posix.dirname(importer),request));
    if(!modules.has(name))pending.push(name);
  }
  for(const [name,mod] of modules)for(const dep of mod.dependencySpecifiers)enqueue(name,dep);
  const discovered=[];
  for(let i=0;i<pending.length;i++){
    const name=pending[i];
    if(modules.has(name))continue;
    assert.ok(name.startsWith('runtime/'),'unexpected static dependency outside source/runtime: '+name);
    const file=path.resolve(root,name);
    const text=fs.readFileSync(file,'utf8');
    assert.ok(text.length>0,'empty runtime module: '+name);
    const mod=new vm.SourceTextModule(text,{identifier:name});
    modules.set(name,mod);
    discovered.push(name);
    for(const dep of mod.dependencySpecifiers){
      onImport();
      if(dep.startsWith('.')){
        const resolved=path.posix.normalize(path.posix.join(path.posix.dirname(name),dep));
        assert.ok(fs.existsSync(path.resolve(root,resolved)),name+': missing '+dep);
        enqueue(name,dep);
      }else externalRequests.add(dep);
    }
  }
  return discovered;
}
module.exports={discoverStaticRuntimeModules};
