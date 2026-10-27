'use strict';
const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');
const source=fs.readFileSync(path.resolve(__dirname,'../src/chunk-fbc1ty1s.js'),'utf8');

function harness({clientType='failed',abortOnWait=false,changeAccountOnWait=false,blocked=false,
  dialFailure=false,eligibleConfig={url:'https://mcp.example'}}={}){
 const from=source.indexOf('function findRetryableMcpConnections(n, o, e = !0)');
 const to=source.indexOf('async function A(n, o, e)',from);
 assert.ok(from>=0&&to>from,'2.1.310 MCP retry executor missing');
 const pending=new Map(),events=[],metrics=[],signal={aborted:false};
 let epoch=1,dials=0,now=1000;
 const server='mcp-main',config={url:'https://mcp.example'};
 let client={name:server,type:clientType,config};
 const host={signal,getClients:()=>[client]};
 const adapter={
   areMcpConfigsEqual:(a,b)=>JSON.stringify(a)===JSON.stringify(b),
   discardMemoizedConnectResult:async()=>{events.push('discard');return true;},
   getMcpToolsCommandsAndResources:async(onResult,configs)=>{
     dials++;
     events.push('dial');
     if(dialFailure)throw Error('dial failed');
     onResult({client:{name:server,config:configs[server],type:'connected'},
       tools:[{name:'connected-tool'}],commands:[]});
   },
   detachAndCloseConnection:async()=>events.push('detach')
 };
 const context={
   R:()=>adapter,
   WLt:row=>row.type==='failed',
   bXe:row=>row.type==='connecting',
   xt:()=>({transientRetryPasses:pending}),
   Fr:()=>epoch,
   C6t:[500,1500,4000],
   F:6000,
   X:async delay=>{
     events.push('wait:'+delay);
     now+=delay;
     if(changeAccountOnWait)epoch=2;
     if(abortOnWait)signal.aborted=true;
   },
   __addDisposableResource:(env,value)=>{env.stack.push(value);return value;},
   __disposeResources:env=>{
     for(const item of env.stack.reverse())item[Symbol.dispose]();
     if(env.hasError)throw env.error;
   },
   performance:{now:()=>now},
   cu:()=>blocked?'managed-deny':null,
   jb:()=>({error:'managed-deny'}),
   mrn:row=>row.type==='failed',
   a:(event,payload)=>metrics.push({event,payload}),
   t:msg=>events.push('log:'+msg),
   Symbol
 };
 const mod=vm.runInNewContext(source.slice(from,to)+
 ';({R6t,retryFailedMcpConnections,QRn,aZr})',context);
 const results=[];
 const settle=row=>{
   results.push(row);
   client=row.client;
 };
 const run=()=>mod.R6t({[server]:eligibleConfig},host,{},settle);
 return {...mod,host,events,metrics,results,pending,run,
   get dialCount(){return dials;},
   get client(){return client;}
 };
}

test('MCP 实际重连入口保持原短名、具名函数与重试参数原约束',async()=>{
 const f=harness();
 assert.equal(f.R6t,f.retryFailedMcpConnections);
 await f.run();
 assert.deepEqual(f.events.filter(x=>x.startsWith('wait:')),['wait:500']);
 assert.equal(f.dialCount,1);
 assert.equal(f.client.type,'connected');
 assert.equal(f.results[0].tools[0].name,'connected-tool');
 assert.equal(f.metrics.length,1);
 assert.equal(f.metrics[0].payload.recovered,1);
 assert.equal(f.pending.size,0);
});
test('账户在退避期间切换后立即停止，禁止旧账户的 MCP 连接污染',async()=>{
 const f=harness({changeAccountOnWait:true});
 await f.run();
 assert.equal(f.dialCount,0);
 assert.equal(f.pending.size,0);
 assert.ok(f.events.some(x=>String(x).includes('identity changed')));
});
test('用户中断连接退避，不再拨号或发布已过期的连接结果',async()=>{
 const f=harness({abortOnWait:true});
 await f.run();
 assert.equal(f.dialCount,0);
 assert.equal(f.results.length,0);
 assert.equal(f.pending.size,0);
});
test('没有待重试 MCP 服务时直接退出，不创建 transient 标志或等待',async()=>{
 const f=harness({clientType:'connected'});
 await f.run();
 assert.equal(f.dialCount,0);
 assert.equal(f.events.filter(x=>x.startsWith('wait:')).length,0);
 assert.equal(f.pending.size,0);
});
test('配置变更后旧服务器结果不得重新拨号或落入工具状态',async()=>{
 const f=harness({eligibleConfig:{url:'https://different'}});
 await f.run();
 assert.equal(f.dialCount,0);
 assert.equal(f.results.length,0);
 assert.equal(f.pending.size,0);
});
test('策略拒绝的服务器不会发送拨号，仍保留原版三轮后收尾行为',async()=>{
 const f=harness({blocked:true});
 await f.run();
 assert.equal(f.dialCount,0);
 assert.equal(f.events.filter(x=>x==='discard').length,3);
 assert.equal(f.results.length,3);
 assert.ok(f.results.every(x=>x.client.type==='failed'));
 assert.equal(f.pending.size,0);
});
test('重拨抛异常时临时标志仍被释放且错误如实传播',async()=>{
 const f=harness({dialFailure:true});
 await assert.rejects(f.run(),/dial failed/);
 assert.equal(f.dialCount,1);
 assert.equal(f.pending.size,0);
});
