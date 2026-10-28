'use strict';
// Execute the *actual* v37 function bodies and their current counterparts in isolated
// VM fixtures. This is targeted differential evidence, not whole-program equivalence.
const {test}=require('node:test');
const assert=require('node:assert/strict');
const cp=require('node:child_process');
const path=require('node:path');
const vm=require('node:vm');
const ts=require('typescript');
const root=path.resolve(__dirname,'..');
const pin='3c64d1669174e825fb0fbae088c5ec95edaa7339';
function old(file){return cp.execFileSync('git',['show',pin+':src/'+file.replace(/^core\//,'')],
  {cwd:root,encoding:'utf8',maxBuffer:16*1024*1024});}
function latest(file){return require('node:fs').readFileSync(path.join(root,file),'utf8');}
function fn(source,name){
 const ast=ts.createSourceFile(name+'.js',source,ts.ScriptTarget.Latest,true,ts.ScriptKind.JS);
 const node=ast.statements.find(x=>ts.isFunctionDeclaration(x)&&x.name?.text===name);
 assert.ok(node,'cannot locate original function '+name);
 return node.getText(ast);
}
function load(source,name,context){return vm.runInNewContext('('+fn(source,name)+')',context);}
test('v37 stop command and current readable stop implementation have matching branch behavior',async()=>{
 const file='core/chunk-9xz7emqc.js';
 for(const active of [true,false]){
  async function run(src,name){
   let closed=0,cleanup=0;
   const sends=[];
   const ctx={xyt:()=>active?{close(){closed++}}:null,
     KHn:async(...args)=>{sends.push([args[0],args[1],args[2]]);return {ok:true}},
     Cie:()=>({origin:'ref'})};
   const f=load(src,name,ctx);
   const result=await f(()=>cleanup++,{storageV5:'store'});
   return {result,cleanup,closed,sends};
  }
  assert.deepEqual(await run(old(file),'keo'),
    await run(latest(file),'stopCurrentSession'));
 }
});
test('v37 Bun.stringWidth route preserved when real Bun API is available',()=>{
 const file='core/chunk-vw9e882a.js';
 function run(src){
  let called=0,options;
  const ctx={Bun:{stringWidth:(s,o)=>{called++;options={...o};assert.equal(s,'全角');return 2}},
    o:{ambiguousIsNarrow:true}};
  const result=load(src,'le',ctx)('全角');
  return {result,called,options};
 }
 assert.deepEqual(run(old(file)),run(latest(file)));
});
test('v37 roster key keeps the same actual daemon path',()=>{
 const file='core/chunk-fnsjs1v2.js';
 const ctx={We:{daemon:parts=>'/state/'+parts.join('/')}};
 assert.equal(load(old(file),'NFt',ctx)(),load(latest(file),'rosterKey',ctx)());
});

// Extend existing pinned v37 parity test entrypoint without editing protected workflows.
require('./v37-original-daemon-adoption-parity.test.cjs');
