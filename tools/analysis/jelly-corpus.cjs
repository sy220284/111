'use strict';
// Independent, bounded, sharded full-corpus Jelly points-to scan.
// Produces *candidate evidence*, never verified runtime calls.
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const zlib=require('node:zlib');
const {spawnSync}=require('node:child_process');
const {inputFiles}=require('./code-graph.cjs');
const sha=b=>crypto.createHash('sha256').update(b).digest('hex');
const loc=/^(\d+):(\d+):(\d+):(\d+):(\d+)$/;
function args(argv){
  const o={shard:0,shards:1,batchSize:16,timeout:110,indirections:2,root:path.resolve(__dirname,'../..')};
  while(argv.length){
    const k=argv.shift(),v=argv.shift();
    if(!k?.startsWith('--')||v===undefined||!['root','jelly','out','shard','shards','batch-size','timeout','indirections'].includes(k.slice(2)))throw Error('Invalid option '+k);
    if(['shard','shards','batch-size','timeout','indirections'].includes(k.slice(2)))o[k==='--batch-size'?'batchSize':k.slice(2)]=Number(v);
    else o[k.slice(2)]=v;
  }
  if(!o.jelly||!o.out||![o.shard,o.shards,o.batchSize,o.timeout,o.indirections].every(Number.isInteger)||
     o.shard<0||o.shards<1||o.shards>64||o.shard>=o.shards||
     o.batchSize<1||o.batchSize>128||o.timeout<10||o.timeout>600||
     o.indirections<0||o.indirections>5)throw Error('Invalid parameters');
  return o;
}
function decode(value,files){
  if(typeof value!=='string')return null;
  const m=loc.exec(value);
  if(!m)return null;
  const [idx,line,col,endLine,endColumn]=m.slice(1).map(Number);
  const f=files[idx];
  if(typeof f!=='string'||!Number.isSafeInteger(line)||line<1||col<1||endLine<line||endColumn<1)return null;
  if(f.startsWith('/')||f.split('/').includes('..')||f.includes('\\'))return null;
  return {file:f,line,column:col,endLine,endColumn};
}
function normalizeGraph(raw,expected){
  if(!raw||raw.ignoreDependencies!==true||!Array.isArray(raw.files)||!raw.calls||
     !raw.functions||!Array.isArray(raw.call2fun)||!Array.isArray(raw.fun2fun))
    throw Error('Invalid or non-local Jelly graph schema');
  const files=raw.files;
  const expect=new Set(expected);
  if(files.some(x=>!expect.has(x)))throw Error('Unexpected analyzed module from Jelly');
  if(expected.some(x=>!files.includes(x)))throw Error('Missing input module in Jelly graph');
  const pairs=new Set(),rows=[];
  let malformed=0;
  for(const item of raw.call2fun){
    if(!Array.isArray(item)||item.length!==2){malformed++;continue;}
    const call=decode(raw.calls[item[0]],files),fn=decode(raw.functions[item[1]],files);
    // Module pseudo functions may have ?:? locations: not a concrete target.
    if(!call||!fn){malformed++;continue;}
    const line=[call.file,call.line,call.column,call.endLine,call.endColumn,
      fn.file,fn.line,fn.column,fn.endLine,fn.endColumn].join('\t');
    if(!pairs.has(line)){pairs.add(line);rows.push(line);}
  }
  return {rows,malformed,
    functions:Object.keys(raw.functions).length,
    calls:Object.keys(raw.calls).length,
    fun2fun:raw.fun2fun.length,call2fun:raw.call2fun.length};
}
function run(params,exec=spawnSync){
  const o=args([...params]),root=path.resolve(o.root),out=path.resolve(o.out);
  const sources=inputFiles(root).filter(x=>x.startsWith('src/')&&x.endsWith('.js'));
  const chosen=sources.filter((_,i)=>i%o.shards===o.shard);
  fs.mkdirSync(out,{recursive:true});
  fs.mkdirSync(path.join(out,'raw'),{recursive:true});
  const report={schema:1,tool:'@cs-au-dk/jelly',kind:'111-independent-points-to-corpus',
    runtimeCallsConfirmed:0,elfEquivalenceVerified:false,shard:o.shard,shards:o.shards,
    sourceModuleTotal:sources.length,selected:chosen.length,attempted:0,successful:0,
    failed:0,candidateEdges:0,uniqueCandidateEdges:0,calls:0,functions:0,
    batches:[],sources:[],method:'flow-insensitive approximate call2fun with --ignore-dependencies'};
  const tsv=fs.openSync(path.join(out,'call2fun-candidates.tsv'),'w');
  fs.writeSync(tsv,'调用模块\t调用行\t调用列\t调用结束行\t调用结束列\t候选目标模块\t候选目标行\t候选目标列\t候选目标结束行\t候选目标结束列\n');
  const allPairs=new Set();
  for(let i=0;i<chosen.length;i+=o.batchSize){
    const batch=chosen.slice(i,i+o.batchSize),j=Math.floor(i/o.batchSize);
    const output=path.join(out,'raw','batch-'+String(j).padStart(4,'0')+'.json');
    const cli=['--ignore-dependencies','--basedir',root,
      '--max-indirections',String(o.indirections),'--timeout',String(o.timeout),'-j',output,
      ...batch.map(x=>path.join(root,x))];
    const started=Date.now();
    const res=exec(o.jelly,cli,{cwd:root,encoding:'utf8',
      timeout:(o.timeout+25)*1000,maxBuffer:1024*1024*8,
      env:{...process.env,NODE_OPTIONS:'--max-old-space-size=4096'}});
    let norm=null,reason=null,raw=null;
    if(res.status===0&&!res.error&&fs.existsSync(output)){
      try{raw=JSON.parse(fs.readFileSync(output,'utf8'));norm=normalizeGraph(raw,batch);}
      catch(e){reason=String(e.message);}
    }else reason=res.error?.message||'jelly exit='+String(res.status);
    const row={batch:j,files:batch,status:norm?'analyzed_static_candidates':'failed_or_partial',
      durationMs:Date.now()-started,error:reason,
      stderrTail:(res.stderr||'').slice(-700),calls:norm?.calls||0,
      functions:norm?.functions||0,call2fun:norm?.call2fun||0,
      normalizedPairs:norm?.rows.length||0,malformedLocations:norm?.malformed||0};
    report.batches.push(row);
    report.attempted+=batch.length;
    report.successful+=norm?batch.length:0;
    report.failed+=norm?0:batch.length;
    report.calls+=row.calls;report.functions+=row.functions;
    report.candidateEdges+=row.call2fun;
    if(norm){
      for(const l of norm.rows)if(!allPairs.has(l)){allPairs.add(l);fs.writeSync(tsv,l+'\n');}
      const gz=output+'.gz';
      fs.writeFileSync(gz,zlib.gzipSync(fs.readFileSync(output),{level:6}));
      fs.unlinkSync(output);
    }else if(fs.existsSync(output))fs.renameSync(output,output+'.partial');
    for(const f of batch)report.sources.push({file:f,sha256:sha(fs.readFileSync(path.join(root,f))),
      status:row.status,batch:j});
    // Do not print huge raw graphs or per-file source content.
    console.log(JSON.stringify({shard:o.shard,batch:j,files:batch.length,status:row.status,
      calls:row.calls,candidateEdges:row.call2fun,error:row.error}));
  }
  fs.closeSync(tsv);
  report.uniqueCandidateEdges=allPairs.size;
  fs.writeFileSync(path.join(out,'summary.json'),JSON.stringify(report,null,2)+'\n');
  if(report.attempted!==report.selected)throw Error('Incomplete source inventory');
  return report;
}
if(require.main===module)try{
 const r=run(process.argv.slice(2));console.log(JSON.stringify({shard:r.shard,
   selected:r.selected,successful:r.successful,failed:r.failed,
   candidateEdges:r.candidateEdges,uniqueCandidateEdges:r.uniqueCandidateEdges}));
 if(r.failed)process.exitCode=2;
}catch(e){console.error(e.stack||e);process.exitCode=1;}
module.exports={args,decode,normalizeGraph,run};
