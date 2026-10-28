'use strict';
// The native modules here are independently implemented compatibility backends,
// not verbatim originals. Original vendor binaries are never overwritten.
const {test} = require('node:test');
const assert = require('node:assert/strict');
const cp = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const net = require('node:net');
const root = path.resolve(__dirname,'..');
const output = path.join(root,'.generated','native-linux');
const source = path.join(root,'native','linux');
const original = path.join(root,'resources','bunfs');
const isLinux = process.platform === 'linux' && process.arch === 'x64';
function native(name){return require(path.join(output,name+'.node'));}
function run(argv,opts={}){return cp.spawnSync(argv[0],argv.slice(1),{encoding:'utf8',timeout:15000,...opts});}
const build = isLinux ? run(['make','-C',source,'all'],{maxBuffer:4*1024*1024}) : null;
test('C N-API alternative modules compile with strict warnings', {skip:!isLinux},()=>{
  assert.equal(build.status,0, String(build.stderr||'')+'\n'+String(build.stdout||''));
  for(const n of ['bun-ant-linux','audio-capture','clipboard-napi']){
    const fn=path.join(output,n+'.node');
    assert.ok(fs.statSync(fn).size>10000,fn);
  }
});
test('SO_PEERCRED gets an actual peer PID and UID across an OS process boundary', {skip:!isLinux||build?.status!==0,timeout:12000},async()=>{
  const api=native('bun-ant-linux');
  const dir=fs.mkdtempSync(path.join(os.tmpdir(),'111-napi-peer-'));
  const sock=path.join(dir,'socket');
  let child,server,identity,peerError;
  try {
    assert.equal(typeof api.getPeerPid,'function');
    assert.equal(typeof api.getPeerUid,'function');
    server=net.createServer(c=>{
      try {identity={pid:api.getPeerPid(c._handle.fd),uid:api.getPeerUid(c._handle.fd)};} catch(e){peerError=e;}
      c.end('ok');
    });
    await new Promise((resolve,reject)=>server.once('error',reject).listen(sock,resolve));
    child=cp.spawn(process.execPath,['-e','require("node:net").createConnection(process.argv[1]).on("data",x=>process.stdout.write(x))',sock],{stdio:['ignore','pipe','pipe']});
    let result='';child.stdout.on('data',x=>result+=x);
    const code=await new Promise((resolve,reject)=>{child.once('error',reject);child.once('exit',resolve);});
    assert.equal(code,0);
    assert.equal(result,'ok');
    assert.ifError(peerError);
    assert.equal(identity.pid,child.pid);
    assert.equal(identity.uid,process.getuid());
    assert.throws(()=>api.getPeerUid(-1),/SO_PEERCRED/);
  } finally {
    child?.kill();
    if(server) await new Promise(resolve=>server.close(resolve));
    fs.rmSync(dir,{recursive:true,force:true});
  }
});
test('TTY, dumpable and invalid peer descriptor have real Linux behavior', {skip:!isLinux||build?.status!==0},()=>{
  const script=String.raw`
const assert=require('node:assert/strict'),fs=require('node:fs');
const api=require(process.argv[1]);
assert.equal(api.ttyName(fs.openSync('/dev/null','r')),null);
assert.equal(api.loginTty(-1),false);
assert.throws(()=>api.getPeerPid(-1),/SO_PEERCRED/);
const before=api.getDumpable();
assert.equal(api.setDumpable(false),true);assert.equal(api.getDumpable(),0);
assert.equal(api.setDumpable(true),true);assert.equal(api.getDumpable(),1);
console.log(JSON.stringify({before,ok:true}));`;
  const res=run([process.execPath,'-e',script,path.join(output,'bun-ant-linux.node')]);
  assert.equal(res.status,0,res.stderr);
  assert.equal(JSON.parse(res.stdout).ok,true);
});
test('exported audio/clipboard N-API ABI matches original binary surface', {skip:!isLinux||build?.status!==0},()=>{
  for(const n of ['audio-capture','clipboard-napi']){
    const orig=path.join(original,n+'.node');
    if(!fs.existsSync(orig)) continue;
    const actual=native(n), baseline=require(orig);
    assert.deepEqual(Object.keys(actual).sort(),Object.keys(baseline).sort());
  }
  const audio=native('audio-capture'),clip=native('clipboard-napi');
  for(const n of ['isPlaying','isRecording','stopPlayback','startPlayback','stopRecording','startRecording','writePlaybackData','microphoneAuthorizationStatus'])assert.equal(typeof audio[n],'function');
  for(const n of ['getLinuxClipboardText','setLinuxClipboardText'])assert.equal(typeof clip[n],'function');
});
test('audio idle/error path is callable without an installed sound card', {skip:!isLinux||build?.status!==0},()=>{
  const script=String.raw`
const assert=require('node:assert/strict');const a=require(process.argv[1]);
assert.equal(typeof a.isPlaying(),'boolean');assert.equal(typeof a.isRecording(),'boolean');
assert.equal(typeof a.microphoneAuthorizationStatus(),'number');
assert.equal(a.stopPlayback(),undefined);assert.equal(a.stopRecording(),undefined);
console.log('idle OK')`;
  const result=run([process.execPath,'-e',script,path.join(output,'audio-capture.node')]);
  assert.equal(result.status,0,String(result.stderr));
  assert.match(result.stdout,/idle OK/);
});
test('Xvfb isolated clipboard primary/clipboard round trip', {skip:!isLinux||build?.status!==0||run(['sh','-c','command -v xvfb-run']).status!==0,timeout:20000},()=>{
  const script=String.raw`
const assert=require('node:assert/strict');const c=require(process.argv[1]);
(async()=>{const txt='NAPI_111_中文_🏁';assert.equal(c.setLinuxClipboardText(txt),true);
const c1=await c.getLinuxClipboardText(false,1500); const c2=await c.getLinuxClipboardText(true,1500);
assert.equal(c1,txt);assert.equal(c2,txt);console.log('X11 PASS');})().catch(e=>{console.error(e);process.exitCode=1});`;
  const r=run(['xvfb-run','-a',process.execPath,'-e',script,path.join(output,'clipboard-napi.node')],{timeout:12000});
  assert.equal(r.status,0,`${r.signal} ${r.stderr}`);
  assert.match(r.stdout,/X11 PASS/);
});