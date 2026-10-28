import {test} from 'node:test';
import assert from 'node:assert/strict';
import {existsSync} from 'node:fs';
import {resolve} from 'node:path';
import {LinuxClipboard} from '../../runtime/linux/clipboard-linux.mjs';
import {runBoundedProcess} from '../../runtime/linux/process-transport.mjs';
import {LazyImagePipeline} from '../../runtime/linux/lazy-image-pipeline.mjs';
import {CellSegmenter} from '../../runtime/terminal/cell-segmenter.mjs';
import {loadNativeLinuxAlternative,nativeLinuxAlternativeNames} from '../../runtime/native-linux.mjs';

// This is a cross-module integration test, NOT evidence that core/cli.js
// consumes the new optional backends, nor that physical devices are verified.
test('real process output passes through clipboard fallback and terminal cells',async()=>{
  const seen=[];
  const run=async (program,args)=>{
    seen.push(program);
    if(program!=='xclip')return {code:127,stdout:''};
    const result=await runBoundedProcess(process.execPath,
      ['-e','process.stdout.write("主线中");'],undefined,{timeoutMs:2500,maxStdoutBytes:256});
    return {code:result.code,stdout:result.stdout.toString('utf8')};
  };
  const clipboard=new LinuxClipboard(run);
  const text=await clipboard.read('clipboard');
  assert.equal(text,'主线中');
  assert.deepEqual(seen,['wl-paste','xclip']);
  const segmenter=new CellSegmenter();
  const cells=new Int32Array(32),runs=new Int32Array(32);
  assert.equal(segmenter.segment(text,cells,runs),3);
  assert.deepEqual([0,1,2].map(i=>segmenter.graphemes[cells[i*2]]),['主','线','中']);
  assert.deepEqual([0,1,2].map(i=>cells[i*2+1]&255),[2,2,2]);
  const screen=new Int32Array(20),charIds=new Int32Array(segmenter.graphemes.length);
  for(let i=0;i<charIds.length;i++)charIds[i]=i+40;
  const packed=segmenter.paint(screen,10,0,0,cells,3,undefined,charIds,new Int32Array(16));
  assert.ok(packed>0);assert.equal(screen[3]&3,2);
});
test('lazy image operations remain ordered and nonduplicated with alternate backend',async()=>{
  const calls=[];const input=Buffer.from('fixture');
  const decoder=async(bytes)=>({metadata:()=>({width:4,height:3,format:'png'}),
    resize:(...args)=>{calls.push(['resize',args]);},
    png:()=>{calls.push(['png']);},
    toBuffer:async()=>Buffer.from(bytes)});
  const p=new LazyImagePipeline(input,decoder);
  assert.equal((await p.metadata()).width,4);
  p.resize(2,2,{fit:'fill'}).png();
  assert.deepEqual(await p.toBuffer(),input);
  assert.deepEqual(await p.toBuffer(),input);
  assert.deepEqual(calls.map(x=>x[0]),['resize','png']);
});
test('original ELF add-ons are not overwritten by opt-in native alternative loader',()=>{
  assert.deepEqual([...nativeLinuxAlternativeNames].sort(),['audio','clipboard','linuxSystem']);
  assert.throws(()=>loadNativeLinuxAlternative('../invalid'),RangeError);
  const orig=resolve('resources/bunfs/audio-capture.node');
  assert.ok(existsSync(orig));
  const alt=resolve('.generated/native-linux/audio-capture.node');
  assert.notEqual(alt,orig);
});