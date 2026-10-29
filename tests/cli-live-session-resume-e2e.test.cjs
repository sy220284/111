'use strict';
// Two real CLI processes and one persistent session, using a local SSE model.
const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const os=require('node:os');
const path=require('node:path');
const http=require('node:http');
const {spawn}=require('node:child_process');
const bin=path.resolve(__dirname,'../bin/claude-310.mjs');
const runtime=process.env.CLAUDE_E2E_RUNTIME||process.execPath;
const initial='INITIAL_RESUME_USER_111',second='FOLLOWUP_RESUME_USER_111';
const answer='RESUMED_MODEL_111';
function sse(res,type,data){res.write('event: '+type+'\ndata: '+JSON.stringify(data)+'\n\n')}
function model(res,body){
 const m={id:'msg_resume_111',type:'message',role:'assistant',content:[],model:body.model||'claude-sonnet-4-5',stop_reason:null,stop_sequence:null,usage:{input_tokens:24,output_tokens:0}};
 const text=JSON.stringify(body.messages||[]).includes(second)?answer:'INITIAL_MODEL_111';
 res.writeHead(200,{'content-type':'text/event-stream','cache-control':'no-cache'});
 sse(res,'message_start',{type:'message_start',message:m});
 sse(res,'content_block_start',{type:'content_block_start',index:0,content_block:{type:'text',text:''}});
 sse(res,'content_block_delta',{type:'content_block_delta',index:0,delta:{type:'text_delta',text}});
 sse(res,'content_block_stop',{type:'content_block_stop',index:0});
 sse(res,'message_delta',{type:'message_delta',delta:{stop_reason:'end_turn',stop_sequence:null},usage:{output_tokens:8}});
 sse(res,'message_stop',{type:'message_stop'});res.end();
}
async function run(env,cwd,args){
 const child=spawn(runtime,[bin,'--bare','--print','--debug-to-stderr',...args,'--model','claude-sonnet-4-5','--output-format','json','--max-turns','1'],{cwd,env,stdio:['ignore','pipe','pipe']});
 let stdout='',stderr='';child.stdout.on('data',x=>stdout+=x);child.stderr.on('data',x=>stderr+=x);
 let timer;try{const result=await Promise.race([new Promise((resolve,reject)=>{child.once('error',reject);child.once('close',(code,signal)=>resolve({code,signal}))}),new Promise((_,reject)=>{timer=setTimeout(()=>{child.kill('SIGKILL');reject(Error('resume CLI timed out: '+stderr.slice(-2500)))},50000)})]);
 assert.equal(result.code,0,`code=${result.code} stdout=${stdout.slice(-2800)} stderr=${stderr.slice(-2800)}`);
 const lines=stdout.trim().split('\n');let payload;try{payload=JSON.parse(lines.at(-1))}catch{payload=JSON.parse(stdout)}
 return {payload,stdout,stderr};
 }finally{if(timer)clearTimeout(timer);if(child.exitCode===null)child.kill('SIGKILL')}
}
test('两次真实 CLI 进程恢复同一会话并携带上轮对话给模型',{timeout:130000},async()=>{
 const cwd=fs.mkdtempSync(path.join(os.tmpdir(),'111-session-resume-'));const requests=[];
 const server=http.createServer((req,res)=>{const chunks=[];req.on('data',x=>chunks.push(x));req.on('end',()=>{
  let b={};try{b=JSON.parse(Buffer.concat(chunks).toString('utf8'))}catch{}
  const endpoint=new URL(req.url,'http://localhost').pathname;
  if(endpoint.endsWith('/messages/count_tokens')){res.writeHead(200,{'content-type':'application/json'});res.end(JSON.stringify({input_tokens:24}));return}
  if(!endpoint.endsWith('/messages')){res.writeHead(404,{'content-type':'application/json'});res.end('{}');return}
  requests.push(b);model(res,b);
 })});
 try{
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  const env={...process.env,HOME:cwd,CLAUDE_CONFIG_DIR:path.join(cwd,'.claude'),CLAUDE_CODE_SIMPLE:'1',
   CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC:'1',DISABLE_TELEMETRY:'1',CI:'true',NO_COLOR:'1',TERM:'dumb',
   ANTHROPIC_API_KEY:'sk-ant-api03-local-session-fake',ANTHROPIC_AUTH_TOKEN:'',
   ANTHROPIC_BASE_URL:'http://127.0.0.1:'+server.address().port,
   HTTP_PROXY:'',HTTPS_PROXY:'',ALL_PROXY:'',NO_PROXY:'127.0.0.1,localhost'};
  const first=await run(env,cwd,[initial]);
  const session=first.payload.session_id;
  assert.match(session,/^[0-9a-f-]{20,}$/i,'first CLI did not persist a session id: '+first.stdout.slice(-1200));
  const secondOutput=await run(env,cwd,['--resume',session,second]);
  assert.ok(secondOutput.stdout.includes(answer),'resume did not return follow-up model answer');
  assert.ok(requests.some(b=>JSON.stringify(b.messages||[]).includes(second)&&JSON.stringify(b.messages||[]).includes(initial)),
   'second real CLI model request omitted the original user turn');
  assert.equal(secondOutput.payload.session_id,session,'resume unexpectedly switched session identity');
 }finally{await new Promise(resolve=>server.close(resolve));fs.rmSync(cwd,{recursive:true,force:true})}
});
