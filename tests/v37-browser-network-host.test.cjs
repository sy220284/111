'use strict';
// Real TCP listener and real Claude Test lazy JSON-RPC process; only the DNS
// lookup and simulated public destination are controlled. No consent bypass.
const {test}=require('node:test');
const assert=require('node:assert/strict');
const net=require('node:net');
const path=require('node:path');
const fs=require('node:fs');
const os=require('node:os');
const {spawn}=require('node:child_process');
const readline=require('node:readline');
const entry=path.resolve(__dirname,'../resources/tools/claude-test/scripts/playwright-mcp.mjs');
const fence='../resources/tools/claude-test/scripts/lib/fence-proxy.mjs';
function response(port,request) {
 return new Promise((resolve,reject)=>{
  const socket=net.createConnection(port,'127.0.0.1');
  let content='';
  socket.setTimeout(4500,()=>socket.destroy(Error('proxy timeout')));
  socket.on('connect',()=>socket.end(request));
  socket.on('data',x=>content+=String(x));
  socket.once('end',()=>resolve(content));
  socket.once('error',reject);
 });
}
test('real fence TCP listener forbids forbidden origins and rebound-private DNS without dialing',{timeout:15000},async()=>{
 const {startFenceProxy}=await import(fence);
 let dials=0;
 const guard=await startFenceProxy({
   allow:(host)=>host==='allowed.example',
   lookup:async()=>[{address:'127.0.0.1',family:4}],
   connect:()=>{dials++;throw Error('private connect attempted')},
   onRefused:()=>{}
 });
 try{
  const rejected=await response(guard.port,'CONNECT denied.example:443 HTTP/1.1\r\nHost: denied.example\r\n\r\n');
  assert.match(rejected,/403 Forbidden/);
  const rebound=await response(guard.port,'CONNECT allowed.example:443 HTTP/1.1\r\nHost: allowed.example\r\n\r\n');
  assert.match(rebound,/403 Forbidden/);
  assert.equal(dials,0,'private DNS IP must never trigger an actual connect');
 }finally{guard.close()}
});
test('real fence proxy forwards bytes through an approved CONNECT tunnel',{timeout:15000},async()=>{
 const {startFenceProxy}=await import(fence);
 let dialed=0;
 const destination=net.createServer(sock=>{
  sock.once('data',chunk=>{
   assert.match(chunk.toString(),/fixture/);
   sock.end('HTTP/1.1 200 OK\r\nContent-Length: 3\r\n\r\nYES');
  });
 });
 await new Promise(r=>destination.listen(0,'127.0.0.1',r));
 const fenceHost=await startFenceProxy({
  allow:(host,port)=>host==='approved.example'&&port===443,
  lookup:async()=>[{address:'8.8.8.8',family:4}],
  // Only the tested, injected connector points at our controlled local fixture.
  connect:()=>{dialed++;return net.connect(destination.address().port,'127.0.0.1')}
 });
 try{
  const sock=net.connect(fenceHost.port,'127.0.0.1');
  let got='';
  await new Promise((resolve,reject)=>{
   sock.setTimeout(6000,()=>sock.destroy(Error('tunnel timeout')));
   sock.once('connect',()=>sock.write('CONNECT approved.example:443 HTTP/1.1\r\nHost: approved.example\r\n\r\n'));
   sock.on('data',buf=>{
    got+=String(buf);
    if(got.includes('200 Connection Established')&&!got.includes('YES'))
      sock.write('GET /fixture HTTP/1.1\r\nHost: approved.example\r\n\r\n');
   });
   sock.once('close',resolve);sock.once('error',reject);
  });
  assert.match(got,/200 Connection Established/);
  assert.match(got,/YES/);
  assert.equal(dialed,1);
 }finally{fenceHost.close();await new Promise(resolve=>destination.close(resolve))}
});
test('real Claude Test MCP subprocess initializes and enumerates actual tools safely',{timeout:20000},async t=>{
 const home=fs.mkdtempSync(path.join(os.tmpdir(),'111-v37-mcp-'));
 t.after(()=>fs.rmSync(home,{recursive:true,force:true}));
 const child=spawn(process.execPath,[entry],{
  cwd:home,env:{...process.env,HOME:home,CLAUDE_TEST_EAGER:'0',CLAUDE_TEST_DISABLE_MCP:'0',
    CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC:'1'},
  stdio:['pipe','pipe','pipe']
 });
 t.after(()=>{if(child.exitCode===null)child.kill('SIGKILL')});
 let stderr='';
 child.stderr.on('data',x=>stderr+=String(x));
 const received=new Map();
 const rl=readline.createInterface({input:child.stdout});
 rl.on('line',line=>{try{const msg=JSON.parse(line);if(msg.id!==undefined){
   const p=received.get(msg.id);if(p){received.delete(msg.id);p.resolve(msg);}
 }}catch{}});
 function send(id,method,params={}){
  return new Promise((resolve,reject)=>{
   const timer=setTimeout(()=>{received.delete(id);reject(Error('MCP did not answer '+method+': '+stderr.slice(-1000)))},10000);
   received.set(id,{resolve:msg=>{clearTimeout(timer);resolve(msg)}});
   child.stdin.write(JSON.stringify({jsonrpc:'2.0',id,method,params})+'\n');
  });
 }
 try{
  const init=await send(1,'initialize',{protocolVersion:'2025-06-18',capabilities:{},clientInfo:{name:'111-acceptance',version:'1'}});
  assert.equal(init.result.serverInfo.name,'claude-test-browser');
  const tools=await send(2,'tools/list');
  assert.ok(Array.isArray(tools.result.tools));
  assert.ok(tools.result.tools.some(x=>x.name==='claude_test_install'));
  assert.ok(tools.result.tools.some(x=>String(x.name).startsWith('browser_')));
  assert.ok(tools.result.tools.some(x=>/allow|consent/i.test(x.name)),
    'browser MCP should preserve the permission/consent tool');
 }finally{child.stdin.end();rl.close()}
});
