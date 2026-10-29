'use strict';
// True CLI + real filesystem Write tool + local HTTP/SSE test model.
// Does not claim cloud login or user-interactive approval was tested.
const {test}=require('node:test');
const assert=require('node:assert/strict');
const {spawn}=require('node:child_process');
const fs=require('node:fs');
const os=require('node:os');
const path=require('node:path');
const http=require('node:http');
const bin=path.resolve(__dirname,'../bin/claude-310.mjs');
const runtime=process.env.CLAUDE_E2E_RUNTIME||process.execPath;
const marker='CLI_WRITE_TOOL_SUCCESS';
function event(res,name,data){res.write('event: '+name+'\n');res.write('data: '+JSON.stringify(data)+'\n\n')}
function reply(res,body,tool,file){
 const message={id:tool?'msg_write_tool':'msg_write_final',type:'message',role:'assistant',content:[],model:body.model||'claude-sonnet-4-5',stop_reason:null,stop_sequence:null,usage:{input_tokens:42,output_tokens:0}};
 res.writeHead(200,{'content-type':'text/event-stream','cache-control':'no-cache','request-id':'req_local_write'});
 event(res,'message_start',{type:'message_start',message});
 if(tool){
  event(res,'content_block_start',{type:'content_block_start',index:0,content_block:{type:'tool_use',id:'toolu_real_write',name:'Write',input:{}}});
  event(res,'content_block_delta',{type:'content_block_delta',index:0,delta:{type:'input_json_delta',partial_json:JSON.stringify({file_path:file,content:marker+'\n'})}});
  event(res,'content_block_stop',{type:'content_block_stop',index:0});
  event(res,'message_delta',{type:'message_delta',delta:{stop_reason:'tool_use',stop_sequence:null},usage:{output_tokens:16}});
 }else{
  event(res,'content_block_start',{type:'content_block_start',index:0,content_block:{type:'text',text:''}});
  event(res,'content_block_delta',{type:'content_block_delta',index:0,delta:{type:'text_delta',text:marker}});
  event(res,'content_block_stop',{type:'content_block_stop',index:0});
  event(res,'message_delta',{type:'message_delta',delta:{stop_reason:'end_turn',stop_sequence:null},usage:{output_tokens:8}});
 }
 event(res,'message_stop',{type:'message_stop'});res.end();
}
test('真实 CLI 模型调用 Write 后修改磁盘并将工具结果返回给下一轮模型',{timeout:80000},async()=>{
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'111-real-write-'));
 const file=path.join(dir,'checked-write.txt'),calls=[];
 const server=http.createServer((req,res)=>{
  const chunks=[];req.on('data',c=>chunks.push(c));req.on('end',()=>{
   let body={};try{body=JSON.parse(Buffer.concat(chunks).toString('utf8'))}catch{}
   const endpoint=new URL(req.url,'http://localhost').pathname;
   if(endpoint.endsWith('/messages/count_tokens')){res.writeHead(200,{'content-type':'application/json'});res.end(JSON.stringify({input_tokens:42}));return}
   if(!endpoint.endsWith('/messages')){res.writeHead(404,{'content-type':'application/json'});res.end('{}');return}
   calls.push(body);
   const gotResult=JSON.stringify(body.messages||[]).includes('toolu_real_write');
   reply(res,body,!gotResult,file);
  });
 });
 let child,timer;
 try{
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  const env={...process.env,HOME:dir,CLAUDE_CONFIG_DIR:path.join(dir,'.claude'),CLAUDE_CODE_SIMPLE:'1',
   CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC:'1',DISABLE_TELEMETRY:'1',CI:'true',NO_COLOR:'1',TERM:'dumb',
   ANTHROPIC_API_KEY:'sk-ant-api03-local-write-fake',ANTHROPIC_AUTH_TOKEN:'',
   ANTHROPIC_BASE_URL:'http://127.0.0.1:'+server.address().port,
   HTTP_PROXY:'',HTTPS_PROXY:'',ALL_PROXY:'',NO_PROXY:'127.0.0.1,localhost'};
  child=spawn(runtime,[bin,'--bare','--print','Create '+file+' with '+marker,
   '--dangerously-skip-permissions','--model','claude-sonnet-4-5','--output-format','json',
   '--no-session-persistence','--max-turns','3'],{cwd:dir,env,stdio:['ignore','pipe','pipe']});
  let stdout='',stderr=''; child.stdout.on('data',c=>stdout+=c);child.stderr.on('data',c=>stderr+=c);
  const result=await Promise.race([new Promise((resolve,reject)=>{child.once('error',reject);child.once('close',(code,signal)=>resolve({code,signal}))}),new Promise((_,reject)=>{timer=setTimeout(()=>{child.kill('SIGKILL');reject(Error('Write tool CLI timeout '+stderr.slice(-1800)))},55000)})]);
  clearTimeout(timer);timer=null;
  const diag=()=>`code=${result.code} stdout=${stdout.slice(-3500)} stderr=${stderr.slice(-4000)} calls=${calls.length}`;
  assert.equal(result.code,0,diag());
  assert.equal(fs.readFileSync(file,'utf8'),marker+'\n','actual file was not created: '+diag());
  assert.ok(calls.length>=2,'model was not called again after Write: '+diag());
  assert.ok(calls.slice(1).some(body=>JSON.stringify(body.messages||[]).includes('toolu_real_write')),'real Write tool result not returned to model: '+diag());
  assert.ok(stdout.includes(marker),'final response missing: '+diag());
 }finally{if(timer)clearTimeout(timer);if(child&&child.exitCode===null)child.kill('SIGKILL');await new Promise(resolve=>server.close(resolve));fs.rmSync(dir,{recursive:true,force:true})}
});
