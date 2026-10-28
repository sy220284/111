'use strict';
// Use *actual* request JSON emitted by the 111 CLI through the local model HTTP
// integration test; then mutate copies to isolate thinking and compaction cases.
// No real account, tokens, private prompts or cloud endpoint are involved.
const fs=require('node:fs'),os=require('node:os'),path=require('node:path'),cp=require('node:child_process');
const assert=require('node:assert/strict');
const root=path.resolve(__dirname,'..'),dir=process.env.V37_REPORT_DIR||fs.mkdtempSync(path.join(os.tmpdir(),'111-model-evidence-'));
const source=path.join(dir,'actual-cli-model-requests.jsonl');
assert.ok(fs.existsSync(source),'run the actual 111 CLI MCP tool + local model HTTP test before replay');
const calls=fs.readFileSync(source,'utf8').trim().split('\n').map(x=>JSON.parse(x));
assert.ok(calls.length>=2,'real CLI must make multiple model requests');
const analysis=path.join(root,'resources/tools/evaluation/prefix_diff.py');
const temp=fs.mkdtempSync(path.join(os.tmpdir(),'111-thinking-replay-'));
function diff(a,b){
 const pa=path.join(temp,'before.json'),pb=path.join(temp,'after.json');
 fs.writeFileSync(pa,JSON.stringify(a));fs.writeFileSync(pb,JSON.stringify(b));
 const r=cp.spawnSync('python3',[analysis,'--json',pa,pb],
  {cwd:root,encoding:'utf8',timeout:18000,
   env:{...process.env,PYTHONDONTWRITEBYTECODE:'1'}});
 assert.ok([0,1].includes(r.status),'real Python analyzer crashed: '+r.stderr);
 const out=JSON.parse(r.stdout);assert.equal(out.length,1);return out[0];
}
try{
 const observed=diff(calls[0],calls[1]);
 const changedSystem={...calls[0],system:[{type:'text',text:'CHANGED_SYNTHETIC_SYSTEM'}]};
 const sys=diff(calls[0],changedSystem);
 assert.equal(sys.verdict,'mismatch','changed system should invalidate the prefix');
 const base={...calls[0],messages:[
   {role:'user',content:[{type:'text',text:'synthetic question A'}]},
   {role:'assistant',content:[
     {type:'thinking',thinking:'thinking fixture A',signature:'fixture-signature-A'},
     {type:'text',text:'response A'}]},
   {role:'user',content:[{type:'text',text:'synthetic question B'}]},
   {role:'assistant',content:[
     {type:'thinking',thinking:'thinking fixture B',signature:'fixture-signature-B'},
     {type:'text',text:'response B'}]},
   {role:'user',content:[{type:'text',text:'synthetic question C'}]}
 ]};
 const kept={...base,messages:[...base.messages,
   {role:'assistant',content:[{type:'text',text:'response C'}]}]};
 const removedMiddle={...kept,messages:kept.messages.filter((_,i)=>i!==2)};
 const middle=diff(base,removedMiddle);
 const compacted={...base,messages:[
   {role:'user',content:[{type:'text',text:'Compacted: synthetic summary of A and B'}]},
   {role:'user',content:[{type:'text',text:'synthetic question C'}]}
 ]};
 const compact=diff(base,compacted);
 const data={schema:1,kind:'real-111-cli-request-capture-and-v37-prefix-diff',
   capture:{source:'actual 111 CLI + local MCP + HTTP SSE mock-model',
     modelRequests:calls.length,containsActualCloudRequests:false},
   comparisons:{actualNextTurn:observed,mutatedSystem:sys,
     removedInteriorThinkingChain:middle,syntheticCompaction:compact},
   caveats:['Original CLI traffic is truly emitted, but sent only to a local synthetic model.',
    'Thinking and compaction mutation scenarios are isolated fixtures, not captured production provider traces.',
    'Python analyzer verdict is diagnostic, not verification of remote provider acceptance.']};
 fs.writeFileSync(path.join(dir,'thinking-compaction-diff.json'),JSON.stringify(data,null,2)+'\n');
 console.log(JSON.stringify({requests:calls.length,verdicts:Object.fromEntries(
   Object.entries(data.comparisons).map(([k,v])=>[k,v.verdict]))},null,2));
}finally{fs.rmSync(temp,{recursive:true,force:true})}
