'use strict';
const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');
const root=path.resolve(__dirname,'..');
const scriptDir=path.join(root,'resources/tools/design-sync');
const libDir=path.join(scriptDir,'lib');
const source=fs.readFileSync(path.join(scriptDir,'package-build.mjs'),'utf8');
// These are the real constant-name adapters used by package-build, including
// destructured exports in branch-specific paths; no hardcoded copied API list.
const directCalls=[...source.matchAll(/\b(?:const|let)\s*\{([^{}]+)\}\s*=\s*await\s+loadLib\(\s*['"]([^'"]+)['"]\s*\)/gs)];
// Some adapters are conditional destructures: const {name}=shape==='storybook'
//   ? await loadLib('preview-gen-storybook') : { name: fallback };
const conditionalCalls=[...source.matchAll(/\bconst\s*\{([^{}]+)\}\s*=\s*\w+\s*===\s*['"][^'"]+['"]\s*\?\s*await\s+loadLib\(\s*['"]([^'"]+)['"]\s*\)/gs)];
const calls=[...directCalls,...conditionalCalls];
const expected=new Map();
for(const [,bindings,name] of calls){
 const existing=expected.get(name)||new Set();
 for(const token of bindings.split(',')){
  const namePart=token.trim().split(/\s*:\s*/)[0];
  assert.match(namePart,/^[A-Za-z_$][\w$]*$/,'unexpected destructured loadLib binding: '+token);
  existing.add(namePart);
 }
 expected.set(name,existing);
}
const uniqueCallNames=[...source.matchAll(/\bloadLib\(\s*['"]([^'"]+)['"]\s*\)/g)].map(x=>x[1]);
test('Design Sync 14项动态依赖均为真实调用源文件，并保留解构函数契约',async()=>{
 assert.equal(expected.size,14,'adapter declaration set changed');
 assert.equal(directCalls.length,15,'unexpected number of direct named adapter destructures');
 assert.equal(conditionalCalls.length,1,'must detect the conditionally loaded Storybook preview adapter');
 assert.ok(expected.get('preview-gen-storybook')?.has('generatePreviewSource'),'conditional Storybook helper export contract omitted');
 assert.deepEqual([...new Set(uniqueCallNames)].sort(),[...expected.keys()].sort(),'untracked literal adapter name');
 const cache=new Map(),externals=new Map(),visited=new Set();
 const staticImportCounts={relative:0,builtin:0,thirdPartyMock:0};
 const moduleAt=absolute=>{
  const full=path.resolve(absolute);
  if(cache.has(full))return cache.get(full);
  assert.ok(full.startsWith(libDir+path.sep),'unexpected local import outside design-sync lib: '+full);
  assert.ok(fs.existsSync(full),'missing linked design-sync module: '+full);
  const text=fs.readFileSync(full,'utf8');
  const mod=new vm.SourceTextModule(text,{identifier:full});
  cache.set(full,mod);
  return mod;
 };
 for(const name of expected.keys())moduleAt(path.join(libDir,name+'.mjs'));
 const linker=async(specifier,parent)=>{
  if(specifier.startsWith('.')){
   staticImportCounts.relative++;
   return moduleAt(path.resolve(path.dirname(parent.identifier),specifier));
  }
  if(externals.has(specifier))return externals.get(specifier);
  let names,values;
  if(specifier.startsWith('node:')){
   const actual=await import(specifier);
   names=Object.keys(actual);
   values=actual;
   staticImportCounts.builtin++;
  }else{
   // CI doesn't install the optional design-sync build toolchain.
   // Stand-ins validate local graph and local named exports, NOT third-party runtime APIs.
   const optional={'esbuild':['build'],'ts-morph':['Project','Node','ts']};
   assert.ok(Object.hasOwn(optional,specifier),'unexpected external dependency: '+specifier);
   names=optional[specifier];values=Object.fromEntries(names.map(x=>[x,undefined]));
   staticImportCounts.thirdPartyMock++;
  }
  const mod=new vm.SyntheticModule(names,function(){
   for(const name of names)this.setExport(name,values[name]);
  },{identifier:specifier});
  externals.set(specifier,mod);
  return mod;
 };
 for(const mod of cache.values())if(mod.status==='unlinked')await mod.link(linker);
 let expectedSymbols=0;
 for(const [moduleName,symbols] of expected){
  const mod=cache.get(path.join(libDir,moduleName+'.mjs'));
  const exports=new Set(Object.keys(mod.namespace));
  for(const symbol of symbols){
   assert.ok(exports.has(symbol),'Design Sync: package-build expects '+moduleName+'.'+symbol+' but the real lib does not export it');
   expectedSymbols++;
  }
 }
 assert.ok(staticImportCounts.relative>0,'cross-module import linking unexpectedly absent');
 assert.equal(staticImportCounts.thirdPartyMock,2,'the optional esbuild/ts-morph surface changed');
 console.log(JSON.stringify({result:'PASS',adapters:expected.size,demandedNamedExports:expectedSymbols,linkedLocalModules:cache.size,externalBuildPackages:['esbuild','ts-morph'],staticImportCounts}));
});
