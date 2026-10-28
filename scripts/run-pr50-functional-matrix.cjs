'use strict';
// Independently execute every already-registered npm test subcommand.
// Do not replace npm test: this matrix exposes failures hidden behind its first exit.
const fs=require('node:fs');
const cp=require('node:child_process');
const path=require('node:path');
const root=path.resolve(__dirname,'..');
const chunks=Number(process.argv[2]),part=Number(process.argv[3]);
if(!Number.isInteger(chunks)||chunks<1||!Number.isInteger(part)||part<0||part>=chunks)throw Error('invalid test partition');
const pkg=JSON.parse(fs.readFileSync(path.join(root,'package.json'),'utf8'));
const checks=[...pkg.scripts.test.matchAll(/npm run ([a-z0-9:.-]+)/g)].map(x=>x[1]);
if(checks.length<65||new Set(checks).size!==checks.length)throw Error('test matrix missing or duplicated checks: '+checks.length);
const selected=checks.filter((_,i)=>i%chunks===part);
const failures=[],successes=[];
console.log(JSON.stringify({partition:part,totalPartitions:chunks,registered:checks.length,selected}));
for(const name of selected){
 const started=Date.now();
 const result=cp.spawnSync('npm',['run',name],{cwd:root,encoding:'utf8',timeout:240000,maxBuffer:20*1024*1024,env:process.env});
 const ok=result.status===0&&!result.error;
 const record={name,status:result.status,error:result.error?.message||null,seconds:Math.round((Date.now()-started)/1000)};
 if(ok){successes.push(name);console.log('PASS '+JSON.stringify(record));}
 else{
  failures.push(record);
  console.log('FAIL '+JSON.stringify(record));
  console.error((result.stdout||'').slice(-3500));
  console.error((result.stderr||'').slice(-3500));
 }
}
console.log('MATRIX_RESULT '+JSON.stringify({part,chunks,total:checks.length,pass:successes.length,failed:failures.length,failures}));
if(failures.length)process.exitCode=1;
