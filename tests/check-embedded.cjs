'use strict';
const fs=require('node:fs');const path=require('node:path');const {spawnSync}=require('node:child_process');const assert=require('node:assert/strict');
const root=path.resolve(__dirname,'../src/embedded');let total=0,js=0,json=0;
function walk(dir){for(const file of fs.readdirSync(dir,{withFileTypes:true})){
 const p=path.join(dir,file.name);if(file.isDirectory()){walk(p);continue}
 const content=fs.readFileSync(p,'utf8');assert.ok(content.length>0 && !content.includes('\u0000'),p+': malformed resource');total++;
 if(p.endsWith('.js')||p.endsWith('.mjs')){const result=spawnSync(process.execPath,['--check',p],{encoding:'utf8'});assert.equal(result.status,0,`${p}: ${result.stderr}`);js++}
 if(p.endsWith('.json')){JSON.parse(content);json++}
}}
walk(root);assert.equal(total,24);console.log(JSON.stringify({embeddedSources:total,checkedJavaScript:js,checkedJSON:json,result:'PASS'}));
