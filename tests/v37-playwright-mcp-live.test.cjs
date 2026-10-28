'use strict';
// Use the pinned real @playwright/mcp package (not a fake MCP responder) to
// drive a real Chromium browser to an isolated localhost HTTP fixture.
// Direct MCP is deliberately separate from the Claude Test consent-wrapper;
// it cannot certify that wrapper's end-user consent flow.
const {test}=require('node:test'),assert=require('node:assert/strict');
const http=require('node:http'),fs=require('node:fs'),os=require('node:os'),path=require('node:path');
const readline=require('node:readline'),{spawn}=require('node:child_process');
const cli=process.env.V37_PLAYWRIGHT_MCP_CLI;
test('real pinned Playwright MCP launches Chromium, navigates and snapshots test fixture',
 {timeout:90000,skip:!cli?'pinned MCP CLI not installed in this job':false},async t=>{
 assert.ok(fs.existsSync(cli),'actual @playwright/mcp CLI missing');
 const server=http.createServer((req,res)=>{
  res.writeHead(200,{'content-type':'text/html; charset=utf-8'});
  res.end('<!doctype html><title>V37 real MCP</title><h1>REAL_MCP_CHROMIUM_RENDER</h1>');
 });
 await new Promise(r=>server.listen(0,'127.0.0.1',r));
 t.after(()=>server.close());
 const home=fs.mkdtempSync(path.join(os.tmpdir(),'111-v37-real-mcp-'));
 t.after(()=>fs.rmSync(home,{recursive:true,force:true}));
 const child=spawn(process.execPath,[cli,'--headless','--browser','chromium','--isolated'],{
  cwd:home,env:{...process.env,HOME:home,NO_COLOR:'1',
    CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC:'1'},
  stdio:['pipe','pipe','pipe']
 });
 t.after(()=>{if(child.exitCode===null)child.kill('SIGKILL')});
 let stderr='',exited=false;child.stderr.on('data',x=>stderr+=String(x));
 child.on('exit',()=>{exited=true});
 const messages=new Map(),rl=readline.createInterface({input:child.stdout});
 rl.on('line',line=>{try{const msg=JSON.parse(line),p=messages.get(msg.id);if(p){
   messages.delete(msg.id);p.resolve(msg)
 }}catch{}});
 t.after(()=>rl.close());
 function request(id,method,params){
  if(exited)throw Error('playwright MCP exited unexpectedly: '+stderr.slice(-800));
  return new Promise((resolve,reject)=>{
   const timer=setTimeout(()=>{messages.delete(id);
     reject(Error('real MCP '+method+' timed out: '+stderr.slice(-1400)))},45000);
   messages.set(id,{resolve:msg=>{clearTimeout(timer);resolve(msg)}});
   child.stdin.write(JSON.stringify({jsonrpc:'2.0',id,method,params})+'\n');
  });
 }
 try{
  const a=await request(1,'initialize',{protocolVersion:'2025-06-18',capabilities:{},
    clientInfo:{name:'111-v37-real-browser',version:'1'}});
  assert.ok(a.result?.serverInfo,'real MCP initialize failed: '+JSON.stringify(a));
  child.stdin.write(JSON.stringify({jsonrpc:'2.0',method:'notifications/initialized',params:{}})+'\n');
  const listing=await request(2,'tools/list',{});
  assert.ok(listing.result?.tools?.some(t=>t.name==='browser_navigate'),'real MCP browser_navigate unavailable');
  const url='http://127.0.0.1:'+server.address().port+'/';
  const navigate=await request(3,'tools/call',{name:'browser_navigate',arguments:{url}});
  assert.ok(!navigate.result?.isError,'actual browser navigation failed: '+
    JSON.stringify(navigate).slice(-1600)+' '+stderr.slice(-850));
  const snapshot=await request(4,'tools/call',{name:'browser_snapshot',arguments:{}});
  assert.ok(JSON.stringify(snapshot).includes('REAL_MCP_CHROMIUM_RENDER'),
    'real Chromium DOM content absent from MCP snapshot: '+JSON.stringify(snapshot).slice(-2500));
  if(process.env.V37_REPORT_DIR){
    fs.mkdirSync(process.env.V37_REPORT_DIR,{recursive:true});
    fs.writeFileSync(path.join(process.env.V37_REPORT_DIR,'real-playwright-mcp.json'),
      JSON.stringify({status:'pass',server:a.result.serverInfo,url:'local-fixture',
        navigateError:navigate.result?.isError||false,snapshotContainsMarker:true,
        scope:'actual @playwright/mcp + actual Chromium, not Claude Test consent-wrapper'},null,2));
  }
 }finally{child.stdin.end()}
});
