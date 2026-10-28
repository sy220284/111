'use strict';
// Local JSON-RPC MCP tool used by the real Claude CLI integration test.
const fs=require('node:fs');
const path=require('node:path');
const logFile=process.env.MCP_E2E_LOG;
let input='';
function record(event){if(logFile)fs.appendFileSync(logFile,JSON.stringify(event)+'\n')}
function send(packet){process.stdout.write(JSON.stringify({jsonrpc:'2.0',...packet})+'\n')}
function handle(request){
  if(!request || typeof request!=='object')return;
  const {id,method,params={}}=request;
  if(method==='initialize'){
    record({kind:'initialize'});
    send({id,result:{protocolVersion:'2025-06-18',capabilities:{tools:{listChanged:false}},serverInfo:{name:'111-local-tool',version:'1.0.0'}}});
  }else if(method==='notifications/initialized'){
    record({kind:'initialized'});
  }else if(method==='tools/list'){
    record({kind:'list'});
    send({id,result:{tools:[{name:'echo',description:'Return the input text unmodified',inputSchema:{type:'object',properties:{text:{type:'string'}},required:['text']}}]}});
  }else if(method==='tools/call'){
    record({kind:'call',name:params.name,arguments:params.arguments});
    if(params.name!=='echo'){
      send({id,error:{code:-32602,message:'Unknown tool '+String(params.name)}});return;
    }
    send({id,result:{content:[{type:'text',text:'MCP_TOOL_RESULT_'+String(params.arguments?.text||'')}],isError:false}});
  }else if(id!==undefined){
    send({id,error:{code:-32601,message:'Unsupported method '+String(method)}});
  }
}
process.stdin.setEncoding('utf8');
process.stdin.on('data',data=>{
  input+=data;
  let n;
  while((n=input.indexOf('\n'))>=0){
    const line=input.slice(0,n).trim();input=input.slice(n+1);
    if(!line)continue;
    try{handle(JSON.parse(line))}catch(e){record({kind:'error',message:String(e)});}
  }
});
