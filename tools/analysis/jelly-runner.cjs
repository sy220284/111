'use strict';
// Jelly is an independent, approximate points-to graph. Never promote its
// output into confirmed 111 runtime/ELF function edges automatically.
const fs=require('node:fs');
const path=require('node:path');
const os=require('node:os');
const crypto=require('node:crypto');
const {spawnSync}=require('node:child_process');
const sha=b=>crypto.createHash('sha256').update(b).digest('hex');
function parseArgs(args){
  const p={entries:[],timeout:100,indirections:2};
  while(args.length){
    const k=args.shift(),v=args.shift();
    if(!['--jelly','--out','--entry','--timeout','--indirections'].includes(k)||!v)
      throw Error('Usage: jelly-runner.cjs --jelly PATH --out DIR --entry SOURCE [--entry SOURCE ...] [--timeout SECONDS] [--indirections N]');
    if(k==='--entry')p.entries.push(v);
    else if(k==='--timeout'||k==='--indirections')p[k.slice(2)]=Number(v);
    else p[k.slice(2)]=v;
  }
  if(!p.jelly||!p.out||!p.entries.length||!Number.isInteger(p.timeout)||
     p.timeout<1||p.timeout>600||!Number.isInteger(p.indirections)||
     p.indirections<0||p.indirections>5)throw Error('missing args or invalid limits');
  return p;
}
function run(args,root=path.resolve(__dirname,'../..')){
  const options=parseArgs([...args]),out=path.resolve(options.out);
  fs.mkdirSync(out,{recursive:true});
  const records=[];
  for(const entry of options.entries){
    const file=path.resolve(root,entry),relative=path.relative(root,file).replace(/\\/g,'/');
    if(relative.startsWith('../')||path.isAbsolute(relative)||!fs.statSync(file).isFile())
      throw Error('entry must be a checked-out source file: '+entry);
    const id=sha(relative).slice(0,16),output=path.join(out,id+'.jelly.json');
    const input=fs.readFileSync(file);
    const cli=['--ignore-dependencies','--basedir',root,'--max-indirections',String(options.indirections),
      '--timeout',String(options.timeout),'-j',output,file];
    const started=Date.now();
    const result=spawnSync(options.jelly,cli,{
      cwd:root,encoding:'utf8',timeout:(options.timeout+25)*1000,
      env:{...process.env,NODE_OPTIONS:'--max-old-space-size=4096'},
      maxBuffer:8*1024*1024
    });
    let parseError=null,raw=null;
    if(fs.existsSync(output)){
      try{raw=JSON.parse(fs.readFileSync(output,'utf8'));}
      catch(e){parseError=e.message;}
    }
    const verified=result.status===0&&!result.error&&!parseError&&raw!==null;
    records.push({entry:relative,sourceSHA256:sha(input),sourceBytes:input.length,
      status:verified?'analyzed_static_candidates':'failed_or_partial',
      reason:result.error?.message||parseError||(verified?null:'exit='+result.status),
      exitCode:result.status,elapsedMs:Date.now()-started,
      jellyJson:id+'.jelly.json',jellyJsonBytes:fs.existsSync(output)?fs.statSync(output).size:0,
      topLevelKeys:raw&&typeof raw==='object'?Object.keys(raw).slice(0,30):[],
      stderrTail:(result.stderr||'').slice(-1200),
      runtimeCallsConfirmed:0,elfEquivalenceVerified:false});
  }
  const report={schema:1,kind:'111-jelly-points-to-source-probe',
    tool:'@cs-au-dk/jelly',method:'static flow-insensitive points-to',
    disclaimer:'Jelly analysis output is independent approximate evidence, not executed calls, and is not merged into verified edges.',
    requestedEntries:options.entries.length,successes:records.filter(r=>r.status==='analyzed_static_candidates').length,
    records};
  fs.writeFileSync(path.join(out,'111-jelly-evidence.json'),JSON.stringify(report,null,2)+'\n');
  return report;
}
if(require.main===module){
  try{
    const r=run(process.argv.slice(2));
    console.log(JSON.stringify(r,null,2));
    if(r.successes!==r.requestedEntries)process.exitCode=1;
  }catch(e){console.error(e.stack||e);process.exitCode=1;}
}
module.exports={run,parseArgs};
