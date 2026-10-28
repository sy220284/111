'use strict';
// Real CLI process test: no VM extraction or simulated protocol client/server.
const {test}=require('node:test');
const assert=require('node:assert/strict');
const {spawn,spawnSync}=require('node:child_process');
const fs=require('node:fs');
const os=require('node:os');
const path=require('node:path');
const root=path.resolve(__dirname,'..');
const executable=path.join(root,'bin/claude-310.mjs');
const runtime=process.env.CLAUDE_E2E_RUNTIME||process.execPath;
const timeout=30000;

function isolatedEnv(home){
  return {...process.env,HOME:home,CLAUDE_CONFIG_DIR:path.join(home,'.claude'),
    CI:'true',NO_COLOR:'1',TERM:'dumb',DISABLE_TELEMETRY:'1',
    CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC:'1',
    ANTHROPIC_API_KEY:''};
}
function isolatedRun(args){
  const home=fs.mkdtempSync(path.join(os.tmpdir(),'claude-310-cli-e2e-'));
  try {
    return spawnSync(runtime,[executable,...args],{
      cwd:home,env:isolatedEnv(home),encoding:'utf8',timeout,maxBuffer:8*1024*1024
    });
  } finally {fs.rmSync(home,{recursive:true,force:true});}
}
test('实际 CLI 进程执行版本与帮助命令，不依赖已配置账号',()=>{
  const version=isolatedRun(['--version']);
  assert.equal(version.status,0,'version failed: '+version.stderr);
  assert.match(version.stdout,/2\.1\.310/);
  const help=isolatedRun(['--help']);
  assert.equal(help.status,0,'help failed: '+help.stderr);
  assert.match(help.stdout,/\bmcp\b/i);
});

test('实际 MCP stdio 服务完成 initialize 与 tools/list 协议链路',async()=>{
  const home=fs.mkdtempSync(path.join(os.tmpdir(),'claude-310-mcp-e2e-'));
  const child=spawn(runtime,[executable,'mcp','serve'],{
    cwd:home,env:isolatedEnv(home),stdio:['pipe','pipe','pipe']
  });
  let stderr='', buffer='',unexpected=[];
  const pending=new Map();
  const closed=new Promise(resolve=>child.once('close',(code,signal)=>resolve({code,signal})));
  child.stderr.setEncoding('utf8');
  child.stderr.on('data',d=>stderr+=d);
  child.stdout.setEncoding('utf8');
  child.stdout.on('data',data=>{
    buffer+=data;
    let pos;
    while((pos=buffer.indexOf('\n'))!==-1){
      const line=buffer.slice(0,pos).trim();buffer=buffer.slice(pos+1);
      if(!line)continue;
      let packet;
      try{packet=JSON.parse(line)}catch{unexpected.push(line.slice(0,300));continue}
      if(packet.id!==undefined&&pending.has(packet.id)){
        pending.get(packet.id)(packet);pending.delete(packet.id);
      }
    }
  });
  function request(id,method,params){
    return new Promise((resolve,reject)=>{
      pending.set(id,resolve);
      child.stdin.write(JSON.stringify({jsonrpc:'2.0',id,method,params})+'\n',err=>{
        if(err){pending.delete(id);reject(err)}
      });
    });
  }
  try{
    const end=Date.now()+timeout;
    const reply=async promise=>{
      const remaining=end-Date.now();
      assert.ok(remaining>0,'MCP protocol timeout: '+stderr);
      let timer;
      try {
        return await Promise.race([
          promise,
          new Promise((_,reject)=>{timer=setTimeout(()=>reject(Error('MCP response timeout; stderr: '+stderr.slice(-2000)+' stdout: '+unexpected.join(';'))),remaining)})
        ]);
      } finally {clearTimeout(timer)}
    };
    const init=await reply(request(1,'initialize',{
      protocolVersion:'2025-06-18',
      capabilities:{},
      clientInfo:{name:'111-cli-e2e',version:'1.0.0'}
    }));
    assert.ok(init.result,'MCP initialize returned error: '+JSON.stringify(init.error));
    assert.equal(typeof init.result.serverInfo?.name,'string');
    child.stdin.write(JSON.stringify({jsonrpc:'2.0',method:'notifications/initialized'})+'\n');
    const tools=await reply(request(2,'tools/list',{}));
    assert.ok(tools.result,'MCP tools/list returned error: '+JSON.stringify(tools.error));
    assert.ok(Array.isArray(tools.result.tools),'MCP must expose tools array');
    assert.equal(unexpected.length,0,'MCP stdout must only contain JSON-RPC: '+unexpected.join(';'));
  }finally{
    child.kill('SIGTERM');
    await Promise.race([closed,new Promise(resolve=>setTimeout(resolve,1500))]);
    if(child.exitCode===null)child.kill('SIGKILL');
    fs.rmSync(home,{recursive:true,force:true});
  }
});
