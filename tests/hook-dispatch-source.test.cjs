'use strict';
const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');
const code=fs.readFileSync(path.resolve(__dirname,'../core/chunk-q85nvv4x.js'),'utf8');

async function load(modules) {
 const context=vm.createContext({});
 const mod=new vm.SourceTextModule(code,{context,identifier:'core/chunk-q85nvv4x.js'});
 await mod.link(async name=>{
   const bindings=name.includes('chunk-g2mvjkdk.js')
     ? {Ie:()=>({loadedModules:modules})}
     : name.includes('chunk-y609bddh.js')
       ? {DS:candidate=>candidate.isPolicy===true}
       : name.includes('chunk-5rw2zfa0.js')
         ? {GNe:'managed'}
         : null;
   assert.ok(bindings,'unexpected original module dependency '+name);
   return new vm.SyntheticModule(Object.keys(bindings),function(){
     for(const [key,value] of Object.entries(bindings))this.setExport(key,value);
   },{context,identifier:name});
 });
 await mod.evaluate();
 return mod.namespace;
}

test('原版敏感 Hook 事件不会分发给被保留的受控策略模块',async()=>{
 const managed={name:'managed',isPolicy:true};
 const nonPrivileged={name:'managed',isPolicy:false};
 const plugin={name:'plugin',isPolicy:true};
 const m=await load([managed,nonPrivileged,plugin]);
 assert.equal(m.uun,m.isReservedPolicyHookModule);
 assert.equal(m.HE,m.listModulesForHookDispatch);
 assert.equal(m.isReservedPolicyHookModule(managed),true);
 assert.equal(m.isReservedPolicyHookModule(nonPrivileged),false);
 for(const event of ['classic.PreToolUse','classic.PermissionRequest','tool.describe','tool.call','tool.check']){
   assert.deepEqual(Array.from(m.HE(event)),[nonPrivileged,plugin],event);
 }
 for(const event of ['session.append','turn.step','PreToolUse','model.complete']){
   assert.deepEqual(Array.from(m.HE(event)),[managed,nonPrivileged,plugin],event);
 }
});

test('敏感 Hook 列表无候选时返回空数组，不引入假的默认权限',async()=>{
 const m=await load([]);
 assert.deepEqual(Array.from(m.HE('tool.call')),[]);
 assert.deepEqual(Array.from(m.HE('session.append')),[]);
});
