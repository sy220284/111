'use strict';
const test=require('node:test'),assert=require('node:assert/strict');
const fs=require('node:fs'),os=require('node:os'),path=require('node:path');
const {run,parseArgs}=require('../tools/analysis/jelly-runner.cjs');
test('required options and limits are validated',()=>{
 assert.throws(()=>parseArgs(['--jelly','x','--out','out']),/invalid limits|missing/);
 assert.throws(()=>parseArgs(['--jelly','x','--out','out','--entry','x.js','--timeout','900']),/invalid limits/);
});
test('a separate tool output is retained as independent candidate evidence',()=>{
 const root=fs.mkdtempSync(path.join(os.tmpdir(),'111-jelly-test-'));
 try{
  fs.mkdirSync(root);
  fs.writeFileSync(path.join(root,'test.js'),'const o={run(){}};o.run();\n');
  const mock=path.join(root,'jelly');
  fs.writeFileSync(mock,'#!/usr/bin/env node\nconst fs=require("fs");const args=process.argv.slice(2);fs.writeFileSync(args[args.indexOf("-j")+1],JSON.stringify({fixture:true,edges:[1]}));\n');
  fs.chmodSync(mock,0o755);
  const result=run(['--jelly',mock,'--out',path.join(root,'report'),'--entry','test.js'],root);
  assert.equal(result.successes,1);
  assert.equal(result.records[0].status,'analyzed_static_candidates');
  assert.equal(result.records[0].runtimeCallsConfirmed,0);
  assert.equal(result.records[0].sourceSHA256.length,64);
  assert.ok(fs.existsSync(path.join(root,'report','111-jelly-evidence.json')));
 }finally{fs.rmSync(root,{recursive:true,force:true});}
});
test('tool exits or malformed graph never become successful analysis',()=>{
 const root=fs.mkdtempSync(path.join(os.tmpdir(),'111-jelly-fail-'));
 try{
  fs.mkdirSync(root);
  fs.writeFileSync(path.join(root,'test.js'),'function a(){}\n');
  const mock=path.join(root,'jelly');fs.writeFileSync(mock,'#!/bin/sh\nexit 4\n');fs.chmodSync(mock,0o755);
  const r=run(['--jelly',mock,'--out',path.join(root,'report'),'--entry','test.js'],root);
  assert.equal(r.successes,0);assert.equal(r.records[0].status,'failed_or_partial');
 }finally{fs.rmSync(root,{recursive:true,force:true});}
});
