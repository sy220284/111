import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, chmodSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { runBoundedProcess } from '../runtime/linux/process-transport.mjs';
import { LinuxClipboard } from '../runtime/linux/clipboard-linux.mjs';
import { LazyImagePipeline } from '../runtime/linux/lazy-image-pipeline.mjs';
import { imageMagickDecoder } from '../runtime/linux/imagemagick-image-processor.mjs';

test('process transport preserves UTF-8 stdin/stdout, stderr and exit status',async()=>{
 const js='process.stdin.on("data",x=>process.stdout.write(x));process.stdin.on("end",()=>{process.stderr.write("warn");process.exitCode=7})';
 const r=await runBoundedProcess(process.execPath,['-e',js],'中文📦',{timeoutMs:4000});
 assert.equal(r.stdout.toString(),'中文📦');assert.equal(r.stderr.toString(),'warn');assert.equal(r.code,7);
});
test('process transport rejects invalid limits and missing binary',async()=>{
 await assert.rejects(runBoundedProcess(process.execPath,[],undefined,{timeoutMs:0}),RangeError);
 await assert.rejects(runBoundedProcess('/nonexistent/111-runner',[],undefined,{timeoutMs:500}));
});
test('process transport actually times out a hung child',async()=>{
 const start=Date.now();
 await assert.rejects(runBoundedProcess(process.execPath,['-e','setInterval(()=>{},10000)'],undefined,{timeoutMs:180}),/timed out/);
 assert.ok(Date.now()-start<2500);
});
test('process transport enforces real stdout and stderr caps',async()=>{
 await assert.rejects(runBoundedProcess(process.execPath,['-e','process.stdout.write("x".repeat(65536))'],undefined,{maxStdoutBytes:2000,timeoutMs:2000}),/stdout exceeded/);
 await assert.rejects(runBoundedProcess(process.execPath,['-e','process.stderr.write("x".repeat(65536))'],undefined,{maxStderrBytes:2000,timeoutMs:2000}),/stderr exceeded/);
});
test('Linux clipboard reads in wl-paste, xclip, xsel, N-API precedence',async()=>{
 const calls=[];
 const cp=new LinuxClipboard(async(prog,args)=>{calls.push([prog,args]);return {code:prog==='xsel'?0:127,stdout:prog==='xsel'?'测试':''}},async()=>{throw Error('native should not be reached')});
 assert.equal(await cp.read('primary'),'测试');
 assert.deepEqual(calls.map(x=>x[0]),['wl-paste','xclip','xsel']);
 assert.ok(calls[0][1].includes('--primary'));
});
test('Linux clipboard invokes native fallback with timeout only if all command backends fail',async()=>{
 const calls=[];
 const cp=new LinuxClipboard(async()=>({code:127,stdout:''}),async(primary,timeout)=>{calls.push([primary,timeout]);return 'Native 文本\r\n'});
 assert.equal(await cp.read('primary'),'Native 文本');
 assert.deepEqual(calls,[[true,2000]]);
});
test('Linux clipboard preserves empty successful command response',async()=>{
 let called=false;
 const cp=new LinuxClipboard(async()=>({code:0,stdout:''}),async()=>{called=true;return 'native'});
 assert.equal(await cp.read(),'');assert.equal(called,false);
});
test('Linux clipboard protects PRIMARY against outdated write generation',async()=>{
 let firstResolve;
 let started=0;
 const seen=[];
 const cp=new LinuxClipboard(async(prog,args,text)=>{
   seen.push([prog,args,text]);
   if(args.length===0&&text==='old'){started++;return new Promise(resolve=>firstResolve=()=>resolve({code:0,stdout:''}));}
   return {code:0,stdout:''};
 });
 const first=cp.writeWayland('old');
 assert.equal(started,1);
 assert.equal(await cp.writeWayland('new'),true);
 firstResolve();assert.equal(await first,true);
 assert.deepEqual(seen.filter(x=>x[1].includes('--primary')).map(x=>x[2]),['new']);
});
test('real clipboard process timeout is bounded at 2 seconds',async()=>{
 const dir=mkdtempSync(join(tmpdir(),'111-clipboard-proc-'));
 try {
  const binary=join(dir,'wl-paste');writeFileSync(binary,'#!/bin/sh\nsleep 20\n');chmodSync(binary,0o755);
  const start=Date.now();
  await assert.rejects(runBoundedProcess(binary,[],undefined,{timeoutMs:200,maxStdoutBytes:1000}),/timed out/);
  assert.ok(Date.now()-start<3000);
 } finally{rmSync(dir,{recursive:true,force:true})}
});
test('actual ImageMagick backend performs decode, resize and JPEG/PNG/WebP output',{skip:spawnSync('magick',['-version'],{encoding:'utf8'}).status!==0},async()=>{
 const initial=spawnSync('magick',['-size','12x9','xc:tomato','png:-'],{maxBuffer:2000000});
 assert.equal(initial.status,0);
 const decoder=imageMagickDecoder('magick',{timeoutMs:10000,maxStdoutBytes:2000000});
 const pipe=new LazyImagePipeline(new Uint8Array(initial.stdout),decoder);
 const metadata=await pipe.metadata();assert.equal(metadata.width,12);assert.equal(metadata.height,9);
 for(const format of ['png','jpeg','webp']){
  const p=new LazyImagePipeline(new Uint8Array(initial.stdout),decoder).resize(6,4,{fit:'fill'});
  p[format]();
  const result=await p.toBuffer();assert.ok(result.length>20);
  const meta=await decoder(result);assert.deepEqual([meta.metadata().width,meta.metadata().height],[6,4]);
 }
});