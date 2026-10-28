'use strict';
// Exercise the real CLI query path against a local HTTP/SSE Anthropic-compatible server.
// No Anthropic account or external network is used. This is an integration test, not proof of cloud parity.
const {test}=require('node:test');
const assert=require('node:assert/strict');
const {spawn}=require('node:child_process');
const http=require('node:http');
const fs=require('node:fs');
const os=require('node:os');
const path=require('node:path');

const root=path.resolve(__dirname,'..');
const bin=path.join(root,'bin/claude-310.mjs');
const runtime=process.env.CLAUDE_E2E_RUNTIME||process.execPath;
const marker='MODEL_E2E_PASS';

function sse(res,kind,data){
  res.write('event: '+kind+'\n');
  res.write('data: '+JSON.stringify(data)+'\n\n');
}
function serve(req,res,received){
  const chunks=[];
  req.on('data',x=>chunks.push(x));
  req.on('end',()=>{
    const raw=Buffer.concat(chunks).toString('utf8');
    let body;
    try{body=raw?JSON.parse(raw):{}}catch{body={raw}}
    const request={url:req.url,method:req.method,body};
    received.push(request);
    const endpoint=new URL(req.url,'http://localhost').pathname;
    const json=(status,body)=>{res.writeHead(status,{'content-type':'application/json'});res.end(JSON.stringify(body))};
    if(endpoint.endsWith('/messages/count_tokens'))return json(200,{input_tokens:23});
    if(endpoint.endsWith('/models'))return json(200,{data:[]});
    if(!endpoint.endsWith('/messages')||req.method!=='POST')return json(404,{error:{type:'not_found_error',message:'unsupported '+endpoint}});
    const message={
      id:'msg_local_e2e',type:'message',role:'assistant',content:[],
      model:body.model||'claude-sonnet-4-5',stop_reason:null,stop_sequence:null,
      usage:{input_tokens:23,output_tokens:0}
    };
    if(!body.stream)return json(200,{...message,content:[{type:'text',text:marker}],stop_reason:'end_turn',usage:{input_tokens:23,output_tokens:4}});
    res.writeHead(200,{'content-type':'text/event-stream','cache-control':'no-cache','request-id':'req_local_e2e'});
    sse(res,'message_start',{type:'message_start',message});
    sse(res,'content_block_start',{type:'content_block_start',index:0,content_block:{type:'text',text:''}});
    sse(res,'content_block_delta',{type:'content_block_delta',index:0,delta:{type:'text_delta',text:marker}});
    sse(res,'content_block_stop',{type:'content_block_stop',index:0});
    sse(res,'message_delta',{type:'message_delta',delta:{stop_reason:'end_turn',stop_sequence:null},usage:{output_tokens:4}});
    sse(res,'message_stop',{type:'message_stop'});
    res.end();
  });
}

function diagnosticModules(){
  const d=[
    {original:'cli.js',copy:'.e2e-cli-debug.js'},
    {original:'chunk-kfdf5pq3.js',copy:'.e2e-main-debug.js'},
    {original:'chunk-mh7sh27p.js',copy:'.e2e-commander-debug.js'},
    {original:'chunk-6d8swme1.js',copy:'.e2e-action-debug.js'}
  ];
  const files=[];
  try {
    const edits={
      'cli.js':[
        ['async function Ye(){','async function Ye(){console.error("TRACE_CLI_ENTRY");'],
        ['import("./chunk-kfdf5pq3.js")','import("./.e2e-main-debug.js")'],
        ['await Pe(s),P("cli_after_main_complete")','console.error("TRACE_MAIN_CALL"),await Pe(s),console.error("TRACE_MAIN_RETURN"),P("cli_after_main_complete")']
      ],
      'chunk-kfdf5pq3.js':[
        ['from"./chunk-mh7sh27p.js"','from"./.e2e-commander-debug.js"'],
        ['async function xu(n){','async function xu(n){console.error("TRACE_XU");'],
        ['async function rr(n,e,C){','async function rr(n,e,C){console.error("TRACE_RR");'],
        ['if(D&&!H)return go("run_before_parse"),await s.parseAsync(process.argv),go("run_after_parse"),s;',
          'if(D&&!H)return console.error("TRACE_PARSE_IN"),go("run_before_parse"),await s.parseAsync(process.argv),console.error("TRACE_PARSE_OUT"),go("run_after_parse"),s;']
      ],
      'chunk-mh7sh27p.js':[
        ['from"./chunk-6d8swme1.js"','from"./.e2e-action-debug.js"'],
        ['function iis(e){','function iis(e){console.error("TRACE_IIS");'],
        ['r.hook("preAction",async(h,y)=>{','r.hook("preAction",async(h,y)=>{console.error("TRACE_PRE_ACTION");'],
        ['await Promise.all([ONe(),pwn()])','console.error("TRACE_AWAIT_INIT"),await Promise.all([ONe(),pwn()]),console.error("TRACE_INIT_DONE")'],
        ['n=await Jio(','console.error("TRACE_JIO"),n=await Jio('],
        ['B.action(async(h,y)=>{','B.action(async(h,y)=>{console.error("TRACE_ACTION");']
      ],
      'chunk-6d8swme1.js':[
        ['async function Yoo(e, o, n, r, u, p, h, S, y) {','async function Yoo(e, o, n, r, u, p, h, S, y) {console.error("TRACE_YOO_BEGIN");'],
        ['let Cp = !1, Qr = !1, il = !1, Ke = await na(o, {','console.error("TRACE_NA_START");let Cp = !1, Qr = !1, il = !1, Ke = await na(o, {'],
        ['if (!Ke.ok)','console.error("TRACE_NA_DONE",Ke.ok);if (!Ke.ok)'],
        ['q4t("action_before_mcp_policy_wait"), await VB({','console.error("TRACE_VB_START"),q4t("action_before_mcp_policy_wait"), await VB({'],
        ['let Me = await oys(e || "", j ?? "text")','console.error("TRACE_OYS_START"), Me = await oys(e || "", j ?? "text")'],
        ['go("action_after_input_prompt");','console.error("TRACE_OYS_DONE"),go("action_after_input_prompt");'],
        ['let Nl = Date.now()','console.error("TRACE_SETUP_IMPORT"),let Nl = Date.now()'],
        ['await Bl;','await Bl;console.error("TRACE_SETUP_DONE");']
      ]
    };
    for(const {original,copy} of d){
      const input=fs.readFileSync(path.join(root,'src',original),'utf8');
      let output=input;
      for(const [needle,replacement] of edits[original]){
        assert.ok(output.includes(needle),'instrumentation anchor missing '+original+': '+needle);
        output=output.replace(needle,replacement);
      }
      const target=path.join(root,'src',copy);
      fs.writeFileSync(target,output);
      files.push(target);
    }
    return {bin:files[0],cleanup:()=>{for(const p of files)fs.rmSync(p,{force:true})}};
  }catch(e){
    for(const p of files)fs.rmSync(p,{force:true});
    throw e;
  }
}

test('真实 CLI --print 从本地模型 SSE 响应流生成最终结果', {timeout:75000},async()=>{
  const dir=fs.mkdtempSync(path.join(os.tmpdir(),'111-model-e2e-'));
  const requests=[];
  const server=http.createServer((req,res)=>serve(req,res,requests));
  let child,timeout;
  const trace=diagnosticModules();
  try{
    await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
    const port=server.address().port;
    const env={
      ...process.env,HOME:dir,CLAUDE_CONFIG_DIR:path.join(dir,'.claude'),
      CLAUDE_CODE_SIMPLE:'1',DISABLE_TELEMETRY:'1',
      CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC:'1',CI:'true',NO_COLOR:'1',TERM:'dumb',
      ANTHROPIC_API_KEY:'sk-ant-api03-local-test-not-a-real-key',
      ANTHROPIC_AUTH_TOKEN:'',ANTHROPIC_BASE_URL:'http://127.0.0.1:'+port,
      HTTP_PROXY:'',HTTPS_PROXY:'',ALL_PROXY:'',NO_PROXY:'127.0.0.1,localhost'
    };
    const args=[trace.bin,'--bare','--print','Say '+marker+' exactly',
      '--model','claude-sonnet-4-5','--output-format','json',
      '--no-session-persistence','--max-turns','1'];
    child=spawn(runtime,args,{cwd:dir,env,stdio:['ignore','pipe','pipe']});
    let stdout='',stderr='';
    child.stdout.on('data',x=>stdout+=x);
    child.stderr.on('data',x=>stderr+=x);
    const exit=await Promise.race([
      new Promise((resolve,reject)=>{child.once('error',reject);child.once('close',(code,signal)=>resolve({code,signal}));}),
      new Promise((_,reject)=>{timeout=setTimeout(()=>{child.kill('SIGKILL');reject(Error('model turn timed out; stderr='+stderr.slice(-2000)))},45000)})
    ]);
    clearTimeout(timeout);timeout=null;
    assert.equal(exit.code,0,'real CLI failed; stdout='+stdout.slice(-3000)+' stderr='+stderr.slice(-3500)+' requests='+JSON.stringify(requests.map(x=>x.url)));
    assert.ok(requests.some(r=>new URL(r.url,'http://localhost').pathname.endsWith('/messages')&&r.method==='POST'),'actual model messages HTTP request missing; stdout='+stdout.slice(-4000)+' stderr='+stderr.slice(-4000)+' requests='+JSON.stringify(requests.map(x=>x.url)));
    assert.ok(stdout.includes(marker),'model SSE text absent from CLI result: '+stdout.slice(-3000));
  }finally{
    if(timeout)clearTimeout(timeout);
    if(child&&child.exitCode===null)child.kill('SIGKILL');
    await new Promise(resolve=>server.close(resolve));
    fs.rmSync(dir,{recursive:true,force:true});
    trace.cleanup();
  }
});
