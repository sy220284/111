'use strict';
// Cross-process functional runner: real 2.1.310 adoption I/O declarations with explicit local-FS adapters.
// Does not start the proprietary Daemon supervisor or perform online operations.
const fs=require('node:fs/promises');
const path=require('node:path');
const ts=require('typescript');
const vm=require('node:vm');
async function main(){
  const [mode,dir]=process.argv.slice(2);
  if(!['write','claim'].includes(mode)||!dir)throw Error('mode write|claim and directory required');
  const file=path.resolve(__dirname,'../../core/chunk-bwt94x0y.js');
  const ast=ts.createSourceFile(file,await fs.readFile(file,'utf8'),ts.ScriptTarget.Latest,true,ts.ScriptKind.JS);
  if(ast.parseDiagnostics.length)throw Error('2.1.310 JS parse failed');
  const names=new Set(['writeAdoptJson','readAndConsumeAdoptJson']);
  const source=ast.statements.filter(x=>ts.isFunctionDeclaration(x)&&names.has(x.name?.text))
    .map(x=>x.getText(ast)).join('\n');
  const log=()=>{};
  const dataStore={
    process,Date,
    j:path.join,Y:p=>p,z:()=>false,_r:()=>false,_o:x=>x,
    Fe:()=>({safeParse:obj=>obj&&Array.isArray(obj.shells)?{success:true,data:obj}:{success:false,error:{message:'missing shells'}}}),
    Oe:()=>({safeParse:obj=>obj&&Array.isArray(obj.shells)?{success:true,data:obj}:{success:false,error:{message:'missing shells'}}}),
    He:fs.readFile,te:JSON.parse,
    Dn:async(p,data)=>{
      const tmp=p+'.pending-'+process.pid;
      await fs.writeFile(tmp,data,{mode:0o600});
      await fs.rename(tmp,p);
    },
    _:JSON.stringify,et:(_old,now)=>now,
    Os:async(from,to)=>{await fs.rename(from,to);return false},
    zK:async p=>({kind:'ok',content:await fs.readFile(p,'utf8')}),
    N:fs.unlink,E:e=>e.code,
    X:async()=>{},Ke:0,Clt:86400000,rgi:60000,Ge:()=>{},
    a:log,t:log,b:x=>x,yh:e=>String(e),vq:new Set(),f:log,
    F:(arr,p)=>arr.filter(p).length
  };
  const handlers=vm.runInNewContext(source+';({writeAdoptJson,readAndConsumeAdoptJson})',dataStore);
  if(mode==='write'){
    const payload={writtenAtMs:Date.now(),shells:[],cron:[],agents:[{agentId:'process-1',description:'durable restart handoff'}],workflows:[]};
    await handlers.writeAdoptJson(dir,payload);
    console.log(JSON.stringify({wrote:true,agent:payload.agents[0].agentId}));
  }else{
    const loaded=await handlers.readAndConsumeAdoptJson(dir,{waitMs:0});
    console.log(JSON.stringify({loaded:loaded===null?null:{agent:loaded.agents?.[0]?.agentId,ts:loaded.writtenAtMs}}));
  }
}
main().catch(e=>{console.error(e.stack||String(e));process.exitCode=1});
