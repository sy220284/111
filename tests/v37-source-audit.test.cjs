'use strict';
const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),os=require('node:os'),path=require('node:path'),cp=require('node:child_process');
const {isModule,functionBodies,preserve,audit}=require('../scripts/audit-v37-source.cjs');
test('only real 2551 source modules are selected',()=>{
 assert.equal(isModule('chunk-abc.js'),true);
 assert.equal(isModule('audio-capture.js'),true);
 assert.equal(isModule('plugins/functionHooks/hooks-worker/hooks-worker.js'),true);
 assert.equal(isModule('mermaid.min.js'),false);
 assert.equal(isModule('plugin-testing-kit/collector/index.js'),false);
});
test('renamed functions share exact body but changed body is not marked preserved',()=>{
 const old=functionBodies('function a(){return 1}function b(){return 2}','a.js');
 const next=functionBodies('function renamed(){return 1}function b(){return 3}','a.js');
 assert.deepEqual([preserve(old,next).identicalBodies,preserve(old,next).changedOrMissingBodies],[1,1]);
});
test('actual git source comparison flags deletion and unreviewed changes',()=>{
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'v37-source-'));
 const g=(...args)=>cp.execFileSync('git',args,{cwd:dir,encoding:'utf8'});
 try{
  g('init','-q');g('config','user.name','test');g('config','user.email','test@example.invalid');
  fs.mkdirSync(path.join(dir,'src'));
  fs.writeFileSync(path.join(dir,'x.js'),'function a(){return 1}\n');
  g('add','.');g('commit','-qm','baseline');const base=g('rev-parse','HEAD').trim();
  fs.writeFileSync(path.join(dir,'x.js'),'function b(){return 1}\n');
  g('add','.');g('commit','-qm','head');
  const a=audit({root:dir,base,expected:1});
  assert.equal(a.metrics.identical,0);assert.equal(a.metrics.changed,1);
  assert.equal(a.changes[0].identicalBodies,1);assert.ok(a.failures.includes('unreviewed changed files'));
  fs.mkdirSync(path.join(dir,'tests'));fs.writeFileSync(path.join(dir,'tests/a.test.cjs'),'test');
  const b=audit({root:dir,base,expected:1,review:{'x.js':{tests:['tests/a.test.cjs'],classification:'tested'}}});
  assert.deepEqual(b.failures,[]);
  fs.unlinkSync(path.join(dir,'x.js'));g('add','-A');g('commit','-qm','delete');
  assert.ok(audit({root:dir,base,expected:1}).failures.includes('source modules deleted or added'));
 }finally{fs.rmSync(dir,{recursive:true,force:true})}
});
