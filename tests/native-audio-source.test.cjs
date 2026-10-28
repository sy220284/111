'use strict';
const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');
const source=fs.readFileSync(path.resolve(__dirname,'../chunk-2ctyt5p9.js'),'utf8');
const start=source.indexOf('let nativeAudioAddon = null;');
const end=source.indexOf('export {',start);
assert.ok(start>0&&end>start,'the real recovered audio adapter must exist');
const text=source.slice(start,end);
const exported=['isNativeAudioAvailable','startNativeRecording','stopNativeRecording','isNativeRecordingActive',
'startNativePlayback','writeNativePlaybackData','stopNativePlayback','isNativePlaying','microphoneAuthorizationStatus'];
function load(deps){return vm.runInNewContext(text+';({' + exported.join(',') + '})',deps);}
test('原生音频：正常导出与参数传递，且初始化只执行一次',()=>{
 const calls=[];
 const addon={startRecording:(...x)=>(calls.push(['startRecording',x]),'recording'),stopRecording:()=>calls.push(['stopRecording']),isRecording:()=>true,
 startPlayback:(...x)=>(calls.push(['startPlayback',x]),'playing'),writePlaybackData:x=>calls.push(['writePlaybackData',x]),
 stopPlayback:()=>calls.push(['stopPlayback']),isPlaying:()=>true,microphoneAuthorizationStatus:()=>2};
 let loaded=0;
 const audio=load({HKo:()=>{loaded++;return addon;},Fe:()=>{throw Error('should not fallback');}});
 assert.equal(audio.isNativeAudioAvailable(),true);
 assert.equal(audio.startNativeRecording('default',42),'recording');
 assert.equal(audio.isNativeRecordingActive(),true);
 assert.equal(audio.startNativePlayback('pcm',48000),'playing');
 audio.writeNativePlaybackData('bytes');audio.stopNativePlayback();audio.stopNativeRecording();
 assert.equal(audio.isNativePlaying(),true);
 assert.equal(audio.microphoneAuthorizationStatus(),2);
 assert.equal(loaded,1);
 assert.deepEqual(calls[0],['startRecording',['default',42]]);
 assert.deepEqual(calls[1],['startPlayback',['pcm',48000]]);
 assert.equal(calls.length,5);
});
test('原生音频：原始平台回退搜索顺序',()=>{
 const attempts=[];
 const fallback={isRecording:()=>false};
 const audio=load({HKo:()=>{throw Error('default missing');}, Fe:(name)=>{attempts.push(name);if(name.endsWith('linux-musl/audio-capture.node'))return fallback;throw Error('not here');}});
 assert.equal(audio.isNativeAudioAvailable(),true);
 assert.equal(audio.isNativeRecordingActive(),false);
 assert.deepEqual(attempts,[
 './vendor/audio-capture/x64-linux/audio-capture.node',
 '../audio-capture/x64-linux/audio-capture.node',
 './vendor/audio-capture/x64-linux-musl/audio-capture.node',
 ]);
});
test('原生音频：不可用时按旧契约返回，并且不重复加载',()=>{
 let retries=0;
 const audio=load({HKo:()=>{retries++;throw Error('missing');},Fe:()=>{retries++;throw Error('missing');}});
 assert.equal(audio.isNativeAudioAvailable(),false);
 assert.equal(audio.isNativeRecordingActive(),false);
 assert.equal(audio.startNativeRecording('mic',()=>{}),false);
 assert.equal(audio.startNativePlayback('pcm',{}),false);
 assert.equal(audio.microphoneAuthorizationStatus(),0);
 assert.equal(audio.stopNativeRecording(),undefined);
 assert.equal(audio.stopNativePlayback(),undefined);
 assert.equal(audio.writeNativePlaybackData('bytes'),undefined);
 assert.equal(retries,5);
});
