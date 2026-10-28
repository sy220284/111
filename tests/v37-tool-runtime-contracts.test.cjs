'use strict';
// Real v37 embedded-tool contracts; all fixtures stay in a temporary directory.
// No browser, network, paid API request or privileged host is started.
const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const os=require('node:os');
const path=require('node:path');
const {spawnSync}=require('node:child_process');
const {createHash}=require('node:crypto');
const root=path.resolve(__dirname,'..');
const hashSource='../tools/bundled/design-sync/lib/sync-hashes.mjs';
const prefixScript=path.join(root,'tools/bundled/evaluation/prefix_diff.py');
const sha=s=>createHash('sha256').update(s).digest('hex').slice(0,16);
function fixture(t){
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'v37-actual-tool-'));
 t.after(()=>fs.rmSync(dir,{recursive:true,force:true}));
 const put=(name,data)=>{
   const dest=path.join(dir,name);fs.mkdirSync(path.dirname(dest),{recursive:true});
   fs.writeFileSync(dest,data);return dest;
 };
 return {dir,put};
}
test('Design Sync sourceKey tracks real user input, not presentation metadata',async t=>{
 const {configSlicesFor,sourceKeyFor,gradeKeyFrom,KEY_RECIPE}=await import(hashSource);
 assert.equal(KEY_RECIPE,7);
 const {dir,put}=fixture(t),design=path.join(dir,'.design-sync');
 const source=put('.design-sync/previews/Card.tsx','export const Card=()=>1');
 const a=configSlicesFor({provider:{a:1,b:2},overrides:{Card:{cardMode:'single',primaryStory:'one'}}},design);
 const b=configSlicesFor({provider:{b:2,a:1},overrides:{Card:{}}},design);
 assert.equal(a.global,b.global,'canonical order must not affect global key');
 assert.equal(a.componentFor('Card'),b.componentFor('Card'),'presentation metadata must be excluded');
 const info={globalSlice:a.global,componentSlice:a.componentFor('Card'),designSyncDir:design,
   stories:[{name:'First',exportKey:'First',emitted:'one'}],srcSha:'origin-1'};
 const k=sourceKeyFor('Card',info);
 assert.match(k,/^[a-f0-9]{16}$/);
 assert.equal(gradeKeyFrom(k),sha(k));
 assert.equal(sourceKeyFor('Card',{...info,stories:[{name:'First',exportKey:'First',emitted:'two'}]}),k,
   'emitted artifact label is not the user story contract');
 assert.notEqual(sourceKeyFor('Card',{...info,srcSha:'origin-2'}),k);
 assert.notEqual(sourceKeyFor('Card',{...info,stories:[{name:'Changed',exportKey:'First'}]}),k);
 put('.design-sync/previews/Card.tsx','export const Card=()=>2');
 assert.notEqual(sourceKeyFor('Card',info),k,'owned preview changes must invalidate grades');
 assert.notEqual(configSlicesFor({provider:{a:1,b:3}},design).global,a.global);
 assert.notEqual(configSlicesFor({overrides:{Card:{viewport:'tablet'}}},design).componentFor('Card'),
   configSlicesFor({},design).componentFor('Card'));
 assert.equal(configSlicesFor({libOverrides:{x:'a'}},design).global,
   configSlicesFor({libOverrides:{x:'b'}},design).global);
 put('.design-sync/overrides/custom.mjs','export default 1');
 const forkA=configSlicesFor({},design).global;
 put('.design-sync/overrides/custom.mjs','export default 2');
 assert.notEqual(configSlicesFor({},design).global,forkA,'fork source bytes must invalidate grades');
 assert.equal(fs.existsSync(source),true);
});
test('Design Sync styling, render, auxiliary and Storybook fingerprints are separate contracts',async t=>{
 const {styleShaFor,renderHashFor,auxShaFor,sbBaseShaFor}=await import(hashSource);
 const {dir,put}=fixture(t);
 const out=path.join(dir,'out');
 put('out/_ds_bundle.js','// @ds-bundle A\nsame bundle body');
 put('out/_ds_bundle.css','body{font:14px serif}');
 put('out/styles.css','body{color:black}');
 put('out/_vendor/react.js','const ReactVersion="a"');
 put('out/guidelines/rules.md','v1');
 put('out/README.md','version 1');
 put('out/_preview/Card.js','console.log("a")');
 put('out/_preview/Card.css','.x{color:red}');
 put('out/components/groupA/Card/Card.html','<!-- @dsCard viewport="desktop" -->\n<div>Body</div>');
 const style=styleShaFor(out,{includeBundleBody:true});
 const noBundle=styleShaFor(out,{includeBundleBody:false});
 assert.notEqual(style,noBundle);
 const aux=auxShaFor(out);
 const render=renderHashFor(out,{group:'groupA',name:'Card'});
 put('out/README.md','version 2');
 assert.notEqual(auxShaFor(out),aux);
 assert.equal(styleShaFor(out,{includeBundleBody:true}),style,'docs do not change styling');
 assert.equal(renderHashFor(out,{group:'groupA',name:'Card'}),render,'docs do not change per-card contract');
 put('out/_ds_bundle.js','// @ds-bundle B\nsame bundle body');
 assert.equal(styleShaFor(out,{includeBundleBody:true}),style,'header-only recompile must not invalidate styling');
 put('out/_ds_bundle.js','// @ds-bundle B\nchanged bundle body');
 assert.notEqual(styleShaFor(out,{includeBundleBody:true}),style);
 assert.equal(styleShaFor(out,{includeBundleBody:false}),noBundle,'storybook styling excludes bundle body');
 put('out/components/groupB/Card/Card.html','<!-- @dsCard viewport="desktop" -->\n<div>Body</div>');
 assert.equal(renderHashFor(out,{group:'groupB',name:'Card'}),render,'group-only move must not regrade');
 put('out/components/groupB/Card/Card.html','<!-- @dsCard viewport="mobile" -->\n<div>Body</div>');
 assert.notEqual(renderHashFor(out,{group:'groupB',name:'Card'}),render,'viewport affects rendered pixels');
 const sb=path.join(dir,'storybook');
 put('storybook/src/Story.js','story v1');
 put('storybook/project.json','{"generatedAt":1}');
 const sb1=sbBaseShaFor(sb);
 put('storybook/project.json','{"generatedAt":2}');
 assert.equal(sbBaseShaFor(sb),sb1,'storybook generated timestamp must not cause churn');
 put('storybook/src/Story.js','story v2');
 assert.notEqual(sbBaseShaFor(sb),sb1);
});
const py=spawnSync('python3',['--version'],{encoding:'utf8',timeout:4000});
test('v37 Python prefix diff: immutable prefix and reordered tools are match', {skip:py.error?'Python3 unavailable':false}, t=>{
 const {put}=fixture(t);
 const tools=[
   {name:'a',description:'tool a',input_schema:{type:'object',properties:{}}},
   {name:'b',description:'tool b',input_schema:{type:'object',properties:{}}}
 ];
 const request=(messages,order=tools,system='Stable instructions')=>({
   model:'claude-sonnet-4-5',system,tools:order,messages
 });
 const user={role:'user',content:'hello'};
 const a=put('a.json',JSON.stringify(request([user])));
 const b=put('b.json',JSON.stringify(request([user,{role:'assistant',content:'hi'},
   {role:'user',content:'next'}],[...tools].reverse())));
 const result=spawnSync('python3',[prefixScript,'--json',a,b],
   {encoding:'utf8',timeout:20000,env:{...process.env,PYTHONDONTWRITEBYTECODE:'1'}});
 assert.equal(result.status,0,result.stderr+'\n'+result.stdout.slice(0,600));
 const records=JSON.parse(result.stdout);
 assert.equal(records.length,1);
 assert.equal(records[0].verdict,'match');
});
test('v37 Python prefix diff: mutated system is a mismatch with machine-readable evidence',
 {skip:py.error?'Python3 unavailable':false}, t=>{
 const {put}=fixture(t);
 const request=system=>({model:'claude-sonnet-4-5',system,messages:[
   {role:'user',content:'hello'}]});
 const a=put('a.json',JSON.stringify(request('Permanent system prompt')));
 const b=put('b.json',JSON.stringify(request('Changed system prompt')));
 const result=spawnSync('python3',[prefixScript,'--json',a,b],
   {encoding:'utf8',timeout:20000,env:{...process.env,PYTHONDONTWRITEBYTECODE:'1'}});
 assert.equal(result.status,1,result.stderr+'\n'+result.stdout.slice(0,600));
 const records=JSON.parse(result.stdout);
 assert.equal(records.length,1);
 assert.equal(records[0].verdict,'mismatch');
 assert.ok(records[0].header||records[0].system||records[0].first_path||
   records[0].notes?.length||records[0].pattern||records[0].kind,
   'the executable report should explain the mismatch rather than only mark failure');
});
