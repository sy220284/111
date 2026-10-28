'use strict';
const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');
const source=fs.readFileSync(path.resolve(__dirname,'../../src/chunk-r4g83g7p.js'),'utf8');

function harness({modern=true,declaredTask,callResult={content:[{type:'text',text:'ok'}]}}={}){
 const start=source.indexOf('function fetchUncachedMcpToolList(e, n)');
 const end=source.indexOf('var Yd =',start);
 assert.ok(start>=0&&end>start,'MCP tool and task forwarding chain missing');
 const traffic=[],server={
   request:async (...args)=>{traffic.push(['request',...args]);return {tools:[{name:'sample'}]};},
   readResource:async (...args)=>{traffic.push(['read',...args]);return {contents:[{uri:'file:///test',text:'sample'}]};},
   callTool:async (...args)=>{traffic.push(['call',...args]);return callResult;},
   getProtocolEra:()=>modern?'modern':'legacy'
 };
 const api=vm.runInNewContext(source.slice(start,end)+
 ';({Vg,Wg,Gg,Gd,fetchUncachedMcpToolList,readUncachedMcpResource,callMcpToolWithTaskContext,attachDeclaredMcpTaskContext})',{
   Ft:()=>server,
   V8e:{schema:'tools-list'},
   pOe:'com.claude/task',
   i3n:()=>({taskContext:'new-task'}),
   Sun:()=>({sessionId:'session-one'}),
   xt:()=>({clientTasksDeclaredBeforeServerList:new Map(declaredTask===undefined?[]:[[server,declaredTask]])})
 });
 return {...api,traffic,server};
}
test('MCP tools/list 强制无缓存查询，不改写调用者的超时或 signal',async()=>{
 const f=harness(),options={timeout:1234,headers:{Authorization:'test'}};
 const result=await f.Vg(f.server,options);
 assert.equal(result.tools[0].name,'sample');
 const [kind,request,schema,applied]=f.traffic[0];
 assert.equal(kind,'request');
 assert.equal(request.method,'tools/list');
 assert.equal(schema.schema,'tools-list');
 assert.equal(applied.timeout,1234);
 assert.equal(applied.cacheMode,'bypass');
 assert.equal(options.cacheMode,undefined);
 assert.equal(f.Vg,f.fetchUncachedMcpToolList);
});
test('MCP resource/read 总是 cache bypass，严格保留 URI 及其他请求参数',async()=>{
 const f=harness();
 await f.Wg(f.server,'file:///test',{timeout:5000,cacheMode:'refresh'});
 const [kind,target,opts]=f.traffic[0];
 assert.equal(kind,'read');
 assert.equal(target.uri,'file:///test');
 assert.equal(opts.timeout,5000);
 assert.equal(opts.cacheMode,'bypass');
 assert.equal(f.Wg,f.readUncachedMcpResource);
});
test('现代 MCP tools/call 注入原版任务上下文并保留调用结果与超时',async()=>{
 const result={isError:false,content:[{type:'text',text:'done'}]};
 const f=harness({modern:true,callResult:result});
 const input={name:'create_task',arguments:{url:'https://example.com'},_meta:{custom:8}};
 const options={timeout:60000};
 const returned=await f.Gg(f.server,input,options);
 assert.equal(returned,result);
 const [kind,forwarded,forwardOptions]=f.traffic[0];
 assert.equal(kind,'call');
 assert.equal(forwarded.name,'create_task');
 assert.equal(forwarded._meta.custom,8);
 assert.equal(forwarded._meta['com.claude/task'].taskContext,'new-task');
 assert.equal(input._meta['com.claude/task'],undefined);
 assert.equal(forwardOptions,options);
 assert.equal(f.Gg,f.callMcpToolWithTaskContext);
});
test('旧 MCP 协议不注入任务信息，不克隆原始调用对象',async()=>{
 const f=harness({modern:false});
 const input={name:'legacy',_meta:{keep:'yes'}};
 await f.Gg(f.server,input,{timeout:100});
 assert.equal(f.traffic[0][1],input);
 assert.equal(Object.hasOwn(input._meta,'com.claude/task'),false);
});
test('现代协议将预先登记的任务 ID 写入后续请求，旧协议保持输入',()=>{
 const f=harness({modern:true,declaredTask:'task-123'});
 const params={taskId:'task-123',_meta:{existing:true}};
 const result=f.Gd(f.server,params);
 assert.equal(result._meta['com.claude/task'],'task-123');
 assert.equal(result._meta.existing,true);
 assert.equal(params._meta['com.claude/task'],undefined);
 assert.equal(f.Gd,f.attachDeclaredMcpTaskContext);
 const legacy=harness({modern:false,declaredTask:'task-123'});
 assert.equal(legacy.Gd(legacy.server,params),params);
});
test('MCP 未提前登记 task 信息时不注入伪造标识',()=>{
 const f=harness({modern:true});
 const params={taskId:'known'};
 assert.equal(f.Gd(f.server,params),params);
 assert.equal(params._meta,undefined);
});
