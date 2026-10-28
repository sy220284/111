'use strict';
// Load actual original native .node modules in separate OS processes; no mocked require.
// Device/permission success remains unverified without real hardware.
const fs=require('node:fs'),path=require('node:path'),cp=require('node:child_process'),assert=require('node:assert/strict');
const root=path.resolve(__dirname,'..');
const out={schema:1,kind:'v37-real-native-ABI-environment',platform:process.platform,arch:process.arch,
 node:process.version,devices:{sound:fs.existsSync('/dev/snd'),video:fs.existsSync('/dev/video0'),
 display:!!process.env.DISPLAY,wayland:!!process.env.WAYLAND_DISPLAY},modules:[],
 limits:['Real ELF library load without a mock.', 'No physical device or permission proof on an isolated CI runner.',
 'Hardware and GUI environments are explicitly reported.']};
for(const name of ['audio-capture.node','clipboard-napi.node']){
 const file=path.join(root,'resources/bunfs',name),bytes=fs.readFileSync(file);
 assert.equal(bytes.subarray(0,4).toString('hex'),'7f454c46');
 const js="const x=require(process.argv[1]);console.log(JSON.stringify({loaded:true,methods:Object.keys(x).sort()}))";
 const result=cp.spawnSync(process.execPath,['-e',js,file],{encoding:'utf8',timeout:15000,cwd:root});
 let info;try{info=JSON.parse(String(result.stdout).trim().split('\n').at(-1))}catch{info=null}
 out.modules.push({name,ELF:true,abiLoaded:result.status===0&&info?.loaded===true,
 exports:info?.methods||[],exit:result.status,signal:result.signal||null,
 diagnostic:result.status===0?null:String(result.stderr||result.error||'').slice(-1000),
 status:result.status===0?'abi_load_confirmed_hardware_unverified':'abi_load_failed'});
}
// An isolated Xvfb clipboard is a real X11 transport test, never a test of
// the user's clipboard, actual desktop permission, or a physical device.
const xvfb=cp.spawnSync('sh',['-c','command -v xvfb-run'],{encoding:'utf8'});
if(xvfb.status===0){
 const filename=path.join(root,'resources/bunfs/clipboard-napi.node');
 const js=String.raw`const api=require(process.argv[1]);(async()=>{const text='111_V37_ISOLATED_CLIPBOARD';await api.setLinuxClipboardText(text);const actual=await api.getLinuxClipboardText();console.log(JSON.stringify({setCalled:true,readMatches:actual===text,readType:typeof actual}))})().catch(e=>{console.error(String(e));process.exitCode=1})`;
 const attempt=cp.spawnSync('xvfb-run',['-a',process.execPath,'-e',js,filename],
   {cwd:root,encoding:'utf8',timeout:12000});
 let parsed=null;try{parsed=JSON.parse(attempt.stdout.trim().split('\n').at(-1))}catch{}
 out.clipboardVirtualDisplay={
   attempted:true,virtualX11:true,readMatches:parsed?.readMatches===true,
   status:attempt.status===0&&parsed?.readMatches?'pass_isolated_x11':'not_verified',
   diagnostic:attempt.status===0?null:String(attempt.stderr||attempt.error||'').slice(-700)
 };
}else out.clipboardVirtualDisplay={attempted:false,status:'blocked_xvfb_not_installed'};
const target=process.env.V37_REPORT_DIR;
if(target){fs.mkdirSync(target,{recursive:true});
 fs.writeFileSync(path.join(target,'native-abi.json'),JSON.stringify(out,null,2)+'\n');}
console.log(JSON.stringify(out,null,2));
assert.equal(out.modules.length,2);
