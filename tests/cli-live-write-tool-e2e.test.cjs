'use strict';
// True CLI + real filesystem Edit tool + local HTTP/SSE test model.
// Does not claim cloud login or user-interactive approval was tested.
const {test}=require('node:test');
const assert=require('node:assert/strict');
const {spawn,spawnSync}=require('node:child_process');
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
  event(res,'content_block_start',{type:'content_block_start',index:0,content_block:{type:'tool_use',id:tool==='Read'?'toolu_real_read':'toolu_real_write',name:tool,input:{}}});
  event(res,'content_block_delta',{type:'content_block_delta',index:0,delta:{type:'input_json_delta',partial_json:JSON.stringify(tool==='Read'?{file_path:file}:{file_path:file,old_string:'BEFORE_EDIT_111',new_string:marker})}});
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
async function runRealEditFlow(disallowed){
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'111-real-write-'));
 const filename=disallowed==='nested'?'checked(write) target.txt':'checked-write.txt';
 fs.writeFileSync(path.join(dir,filename),'BEFORE_EDIT_111\n');
 const file=path.join(dir,filename),calls=[];
 const server=http.createServer((req,res)=>{
  const chunks=[];req.on('data',c=>chunks.push(c));req.on('end',()=>{
   let body={};try{body=JSON.parse(Buffer.concat(chunks).toString('utf8'))}catch{}
   const endpoint=new URL(req.url,'http://localhost').pathname;
   if(endpoint.endsWith('/messages/count_tokens')){res.writeHead(200,{'content-type':'application/json'});res.end(JSON.stringify({input_tokens:42}));return}
   if(!endpoint.endsWith('/messages')){res.writeHead(404,{'content-type':'application/json'});res.end('{}');return}
   calls.push(body);
   reply(res,body,calls.length===1?'Read':calls.length===2?'Edit':null,file);
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
  child=spawn(runtime,[bin,'--bare','--print','Edit '+file+' replacing BEFORE_EDIT_111 with '+marker,
   ...(disallowed==='nested'||disallowed==='scoped'?['--permission-mode','acceptEdits']:['--dangerously-skip-permissions']),...(disallowed?['--disallowedTools',disallowed==='nested'||disallowed==='scoped'?'Edit(/'+file+')':'Edit']:[]),'--model','claude-sonnet-4-5','--output-format','json',
   '--no-session-persistence','--max-turns','4'],{cwd:dir,env,stdio:['ignore','pipe','pipe']});
  let stdout='',stderr=''; child.stdout.on('data',c=>stdout+=c);child.stderr.on('data',c=>stderr+=c);
  const result=await Promise.race([new Promise((resolve,reject)=>{child.once('error',reject);child.once('close',(code,signal)=>resolve({code,signal}))}),new Promise((_,reject)=>{timer=setTimeout(()=>{child.kill('SIGKILL');reject(Error('Edit tool CLI timeout '+stderr.slice(-1800)))},55000)})]);
  clearTimeout(timer);timer=null;
  const diag=()=>`code=${result.code} stdout=${stdout.slice(-3500)} stderr=${stderr.slice(-4000)} calls=${calls.length} toolReplies=${JSON.stringify(calls.slice(1).flatMap(b=>(b.messages||[]).filter(m=>m.role==='user').flatMap(m=>Array.isArray(m.content)?m.content.filter(c=>c.type==='tool_result'):[]))).slice(-6500)}`;
  assert.equal(result.code,0,diag());
  assert.ok(fs.existsSync(file),'Edit did not change actual file: '+diag()+' tools='+JSON.stringify(calls.map(b=>(b.tools||[]).map(x=>x.name))));
  if(disallowed){
   assert.equal(fs.readFileSync(file,'utf8'),'BEFORE_EDIT_111\n','disallowed Edit changed the actual file: '+diag());
   assert.ok(calls.length>=3,'model did not receive disallowed Edit result: '+diag());
   const toolResults=calls.slice(2).flatMap(body=>(body.messages||[]).filter(m=>m.role==='user').flatMap(m=>Array.isArray(m.content)?m.content.filter(item=>item.type==='tool_result'):[]));
   assert.ok(toolResults.some(item=>item.tool_use_id==='toolu_real_write'&&item.is_error===true),'Edit denial was not returned as a tool error: '+diag());
  }else{
   assert.equal(fs.readFileSync(file,'utf8'),marker+'\n','actual file mismatch: '+diag());
   assert.ok(calls.length>=3,'model did not continue Read -> Edit -> final: '+diag());
   assert.ok(calls.slice(2).some(body=>JSON.stringify(body.messages||[]).includes('toolu_real_write')),'real Edit tool result not returned to model: '+diag());
  }
  assert.ok(stdout.includes(marker),'final response missing: '+diag());
 }finally{if(timer)clearTimeout(timer);if(child&&child.exitCode===null)child.kill('SIGKILL');await new Promise(resolve=>server.close(resolve));fs.rmSync(dir,{recursive:true,force:true})}
}
test('真实 CLI 模型按 Read→Edit 顺序修改磁盘并将结果回注',{timeout:80000},()=>runRealEditFlow(false));
test('真实 CLI 禁止 Edit 时必须拒绝模型工具调用且保持磁盘原字节',{timeout:80000},()=>runRealEditFlow(true));

test('真实文件检查点回退必须恢复磁盘字节，即使编辑后的 mtime 早于备份',{timeout:45000},()=>{
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'111-file-rewind-'));
 const file=path.join(dir,'checkpoint.txt');fs.writeFileSync(file,'ORIGINAL_111');
 try{
  const source=[
   "import fs from 'node:fs';",
   "import {fileHistoryEnabled,fileHistoryMakeSnapshot,fileHistoryTrackEdit,fileHistoryRewind,fileHistoryGetDiffStats,reduceFileHistoryState} from "+JSON.stringify(require('node:url').pathToFileURL(path.resolve(__dirname,'../core/chunk-w47s9bra.js')).href)+";",
   "const file=process.env.CLAUDE_TEST_CHECKPOINT_FILE;",
   "if(!fileHistoryEnabled())throw Error('file checkpointing is not enabled in real runtime');",
   "let state={trackedFiles:new Set(),snapshots:[],snapshotSequence:0,trackSequence:0};",
   "const get=()=>state,dispatch=event=>{state=reduceFileHistoryState(state,event)};",
   "await fileHistoryMakeSnapshot(get,dispatch,'before-edit');",
   "await fileHistoryTrackEdit(get,dispatch,file,'edit-turn');",
   "fs.writeFileSync(file,'MODIFIED_111');",
   "fs.utimesSync(file,new Date(1000),new Date(1000));",
   "const diff=await fileHistoryGetDiffStats(state,'before-edit');",
   "await fileHistoryRewind(get,'before-edit');",
   "if(fs.readFileSync(file,'utf8')!=='ORIGINAL_111')throw Error('rewind did not restore real file bytes: '+JSON.stringify({diff,state:{snapshots:state.snapshots.length,tracked:state.trackedFiles.size}}));",
   "process.stdout.write(JSON.stringify({enabled:true,snapshots:state.snapshots.length,tracked:state.trackedFiles.size,diff}));"
  ].join('\n');
  const env={...process.env,HOME:dir,CLAUDE_CONFIG_DIR:path.join(dir,'.claude'),CLAUDE_CODE_ENABLE_SDK_FILE_CHECKPOINTING:'1',CLAUDE_CODE_DISABLE_FILE_CHECKPOINTING:'',CLAUDE_TEST_CHECKPOINT_FILE:file,DISABLE_TELEMETRY:'1'};
  const result=spawnSync(process.execPath,['--input-type=module','-e',source],{cwd:dir,env,encoding:'utf8',timeout:30000});
  assert.equal(result.status,0,'real checkpoint subprocess failed: '+result.stderr+' stdout='+result.stdout);
  assert.equal(fs.readFileSync(file,'utf8'),'ORIGINAL_111','real file was not rewound');
  const output=JSON.parse(result.stdout);assert.equal(output.enabled,true);assert.ok(output.snapshots>=1);assert.equal(output.tracked,1);
 }finally{fs.rmSync(dir,{recursive:true,force:true})}
});

test('真实 CLI 拒绝包含嵌套括号和空格路径的 Edit，磁盘保持原字节',{timeout:80000},()=>runRealEditFlow('nested'));
test('真实 CLI 普通绝对路径限定 Edit 拒绝规则基线',{timeout:80000},()=>runRealEditFlow('scoped'));
