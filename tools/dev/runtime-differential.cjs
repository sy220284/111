'use strict';
// Differential *observed* behavior of reconstructed CLI under Node and Bun.
// Runs actual CLI processes and an actual Node MCP stdio child with a local
// synthetic SSE model. Does NOT compare an original Claude ELF or live cloud.
const fs=require('node:fs');
const path=require('node:path');
const os=require('node:os');
const cp=require('node:child_process');
const assert=require('node:assert/strict');
const ROOT=path.resolve(__dirname,'../..');
const SUITES=[
  {id:'model-sse',file:'tests/cli-live-model-turn.test.cjs'},
  {id:'mcp-tool-loop',file:'tests/cli-live-mcp-tool-model-e2e.test.cjs'}
];
function compareTraces(nodeTrace,bunTrace){
  assert.equal(nodeTrace.schema,1,'missing schema on Node trace');
  assert.equal(bunTrace.schema,1,'missing schema on Bun trace');
  assert.equal(nodeTrace.mockedLocalHTTP,true);
  assert.equal(bunTrace.mockedLocalHTTP,true);
  assert.equal(nodeTrace.originalELFCompared,false);
  assert.equal(bunTrace.originalELFCompared,false);
  assert.ok(nodeTrace.requestCount>=1);
  assert.ok(bunTrace.requestCount>=1);
  assert.equal(nodeTrace.cliExitCode,0);
  assert.equal(bunTrace.cliExitCode,0);
  assert.equal(nodeTrace.stdoutContainsExpectedMarker,true);
  assert.equal(bunTrace.stdoutContainsExpectedMarker,true);
  // Exact comparison of canonical observable HTTP request sequences,
  // MCP tool execution, and final synthetic model response. No ports, time,
  // trace IDs, API credentials or original raw request payloads.
  assert.deepEqual(bunTrace,nodeTrace,'Node/Bun local-model or MCP sequence diverged');
}
function main(argv,spawn=cp.spawnSync){
  let node=process.execPath,bun=null,out=null;
  while(argv.length){
    const k=argv.shift(),v=argv.shift();
    if(!v||!['--node','--bun','--out'].includes(k))throw Error('usage: runtime-differential.cjs --bun /path/to/bun --out /path [--node /path]');
    if(k==='--node')node=v;
    if(k==='--bun')bun=v;
    if(k==='--out')out=v;
  }
  if(!bun||!out)throw Error('explicit --bun and --out required; no simulated Bun pass');
  const target=path.resolve(out);
  fs.mkdirSync(target,{recursive:true});
  const report={schema:1,kind:'111-node-bun-live-differential',
    toolchain:{nodePath:node,bunPath:bun},source:'recovered 2.1.310 real CLI',
    localFixtureOnly:true,originalELFCompared:false,cloudParity:false,
    mcpStdioChildRuntime:'node',status:'not_verified',cases:[]};
  let bad=false;
  try{
    for(const suite of SUITES){
      const evidence={name:suite.id,passed:false,reason:null,variants:{}};
      report.cases.push(evidence);
      for(const [name,runtime] of [['node',node],['bun',bun]]){
        const trace=path.join(target,suite.id+'-'+name+'.json');
        const args=['--test',path.join(ROOT,suite.file)];
        const result=spawn(node,args,{cwd:ROOT,encoding:'utf8',
          env:{...process.env,CLAUDE_E2E_RUNTIME:runtime,CLAUDE_E2E_TRACE_FILE:trace,
            CI:'true',NO_COLOR:'1',TERM:'dumb',DISABLE_TELEMETRY:'1'},
          timeout:95000,maxBuffer:1024*1024*5});
        fs.writeFileSync(path.join(target,suite.id+'-'+name+'.tap.log'),
          (result.stdout||'')+'\n--- stderr ---\n'+(result.stderr||''));
        evidence.variants[name]={status:result.status,
          signal:result.signal||null,tracePresent:fs.existsSync(trace)};
        if(result.error||result.status!==0||!fs.existsSync(trace)){
          throw Error(suite.id+'/'+name+' did not finish real subprocess: '+
            (result.error?.message||String(result.status))+
            '; trace='+trace+' exists='+fs.existsSync(trace)+
            '; TAP='+String(result.stdout||'').slice(-2200)+
            '; stderr='+String(result.stderr||'').slice(-1000));
        }
      }
      const left=JSON.parse(fs.readFileSync(path.join(target,suite.id+'-node.json'),'utf8'));
      const right=JSON.parse(fs.readFileSync(path.join(target,suite.id+'-bun.json'),'utf8'));
      compareTraces(left,right);
      evidence.passed=true;
      console.log('PASS real recovered CLI '+suite.id+' Node vs Bun: '+left.requestCount+' local model HTTP calls');
    }
    report.status='local_fixture_parity_passed';
  }catch(e){
    bad=true;
    report.status='local_fixture_parity_failed';
    report.failure=String(e.stack||e).slice(0,3000);
    console.error(report.failure);
  }finally{
    fs.writeFileSync(path.join(target,'summary.json'),JSON.stringify(report,null,2)+'\n');
  }
  if(bad)process.exitCode=1;
  return report;
}
if(require.main===module)main(process.argv.slice(2));
module.exports={compareTraces,main};
