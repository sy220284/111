'use strict';
// Real CLI + real local MCP stdio child + local Anthropic SSE model.
// No VM, no cloud credentials and no outbound network.
const {test}=require('node:test');
const assert=require('node:assert/strict');
const {spawn}=require('node:child_process');
const fs=require('node:fs');
const os=require('node:os');
const path=require('node:path');
const http=require('node:http');
const root=path.resolve(__dirname,'..');
const cli=path.join(root,'bin','claude-310.mjs');
const fixture=path.join(root,'tests','fixtures','real-mcp-tool-server.cjs');
const runtime=process.env.CLAUDE_E2E_RUNTIME||process.execPath;
const token='ECHO_E2E_INPUT';
const marker='MODEL_AFTER_TOOL_E2E_PASS';

function event(res,name,data){
  res.write('event: '+name+'\n');
  res.write('data: '+JSON.stringify(data)+'\n\n');
}
function reply(res,body,tool){
  const message={id:tool?'msg_tool_req':'msg_tool_reply',type:'message',role:'assistant',
    content:[],model:body.model||'claude-sonnet-4-5',stop_reason:null,stop_sequence:null,
    usage:{input_tokens:42,output_tokens:0}};
  if(!body.stream){
    res.writeHead(200,{'content-type':'application/json'});
    res.end(JSON.stringify({...message,content:[{type:'text',text:marker}],stop_reason:'end_turn',usage:{input_tokens:42,output_tokens:8}}));
    return;
  }
  res.writeHead(200,{'content-type':'text/event-stream','cache-control':'no-cache','request-id':'req_local_mcp'});
  event(res,'message_start',{type:'message_start',message});
  if(tool){
    event(res,'content_block_start',{type:'content_block_start',index:0,content_block:{
      type:'tool_use',id:'toolu_real_mcp_echo',name:'mcp__local__echo',input:{}}});
    event(res,'content_block_delta',{type:'content_block_delta',index:0,delta:{
      type:'input_json_delta',partial_json:JSON.stringify({text:token})}});
    event(res,'content_block_stop',{type:'content_block_stop',index:0});
    event(res,'message_delta',{type:'message_delta',delta:{stop_reason:'tool_use',stop_sequence:null},usage:{output_tokens:16}});
  }else{
    event(res,'content_block_start',{type:'content_block_start',index:0,content_block:{type:'text',text:''}});
    event(res,'content_block_delta',{type:'content_block_delta',index:0,delta:{type:'text_delta',text:marker}});
    event(res,'content_block_stop',{type:'content_block_stop',index:0});
    event(res,'message_delta',{type:'message_delta',delta:{stop_reason:'end_turn',stop_sequence:null},usage:{output_tokens:8}});
  }
  event(res,'message_stop',{type:'message_stop'});
  res.end();
}
function getEvents(file){
  if(!fs.existsSync(file))return [];
  return fs.readFileSync(file,'utf8').trim().split('\n').filter(Boolean).map(x=>JSON.parse(x));
}
test('真实 CLI 模型工具调用跨 MCP 子进程执行并将工具结果交回模型',{timeout:80000},async()=>{
  const dir=fs.mkdtempSync(path.join(os.tmpdir(),'111-mcp-tools-live-'));
  const calls=[];
  const log=path.join(dir,'mcp-events.ndjson'),config=path.join(dir,'mcp.json');
  const server=http.createServer((req,res)=>{
    const chunks=[];
    req.on('data',x=>chunks.push(x));
    req.on('end',()=>{
      const uri=new URL(req.url,'http://localhost').pathname;
      let body={};
      try{body=JSON.parse(Buffer.concat(chunks).toString('utf8'))}catch{}
      if(uri.endsWith('/messages/count_tokens')){
        res.writeHead(200,{'content-type':'application/json'});res.end(JSON.stringify({input_tokens:42}));return;
      }
      if(!uri.endsWith('/messages')){
        res.writeHead(404,{'content-type':'application/json'});res.end(JSON.stringify({error:{type:'not_found_error',message:'unsupported'}}));return;
      }
      calls.push(body);
      const containsResult=JSON.stringify(body.messages||[]).includes('MCP_TOOL_RESULT_'+token);
      reply(res,body,!containsResult);
    });
  });
  let child,timeout;
  try{
    await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
    fs.writeFileSync(config,JSON.stringify({mcpServers:{local:{command:process.execPath,args:[fixture],env:{MCP_E2E_LOG:log}}}}));
    const env={...process.env,HOME:dir,CLAUDE_CONFIG_DIR:path.join(dir,'.claude'),
      CLAUDE_CODE_SIMPLE:'1',CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC:'1',
      DISABLE_TELEMETRY:'1',CI:'true',NO_COLOR:'1',TERM:'dumb',
      ANTHROPIC_API_KEY:'sk-ant-api03-local-tool-not-real',
      ANTHROPIC_AUTH_TOKEN:'',ANTHROPIC_BASE_URL:'http://127.0.0.1:'+server.address().port,
      HTTP_PROXY:'',HTTPS_PROXY:'',ALL_PROXY:'',NO_PROXY:'127.0.0.1,localhost'};
    const args=[cli,'--bare','--print','Call the local echo MCP tool with '+token,
      '--mcp-config',config,'--strict-mcp-config','--dangerously-skip-permissions',
      '--model','claude-sonnet-4-5','--output-format','json',
      '--no-session-persistence','--max-turns','3'];
    child=spawn(runtime,args,{cwd:dir,env,stdio:['ignore','pipe','pipe']});
    let stdout='',stderr='';
    child.stdout.on('data',x=>stdout+=String(x));
    child.stderr.on('data',x=>stderr+=String(x));
    const result=await Promise.race([
      new Promise((resolve,reject)=>{child.once('error',reject);child.once('close',(code,signal)=>resolve({code,signal}));}),
      new Promise((_,reject)=>{timeout=setTimeout(()=>{child.kill('SIGKILL');reject(Error('tool test timeout stderr='+stderr.slice(-4000)))},50000)})
    ]);
    clearTimeout(timeout);timeout=null;
    const seen=getEvents(log);
    const diagnostics=()=>('code='+result.code+' signal='+result.signal+
      ' stdout='+stdout.slice(-3000)+' stderr='+stderr.slice(-4500)+
      ' mcp='+JSON.stringify(seen).slice(-1500)+' modelCalls='+calls.length);
    assert.equal(result.code,0,diagnostics());
    assert.ok(seen.some(e=>e.kind==='list'),'MCP tools/list absent: '+diagnostics());
    assert.ok(seen.some(e=>e.kind==='call'&&e.name==='echo'&&e.arguments?.text===token),
      'MCP tools/call did not execute: '+diagnostics());
    assert.ok(calls.length>=2,'model did not continue after tool: '+diagnostics());
    assert.ok(calls.slice(1).some(body=>JSON.stringify(body.messages||[]).includes('MCP_TOOL_RESULT_'+token)),
      'tool_result not returned to model: '+diagnostics());
    assert.ok(stdout.includes(marker),'model final answer absent: '+diagnostics());
    // Real CLI-emitted request bodies from the isolated local HTTP model service.
    // The fixture contains only synthetic tool/user data and no cloud credentials.
    if(process.env.V37_REPORT_DIR){
      fs.mkdirSync(process.env.V37_REPORT_DIR,{recursive:true});
      fs.writeFileSync(path.join(process.env.V37_REPORT_DIR,'actual-cli-model-requests.jsonl'),
        calls.map(x=>JSON.stringify(x)).join('\n')+'\n');
    }
  }finally{
    if(timeout)clearTimeout(timeout);
    if(child&&child.exitCode===null)child.kill('SIGKILL');
    await new Promise(resolve=>server.close(resolve));
    fs.rmSync(dir,{recursive:true,force:true});
  }
});
