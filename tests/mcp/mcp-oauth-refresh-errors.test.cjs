'use strict';
const {test}=require('node:test');
const assert=require('node:assert/strict');
const vm=require('node:vm');
const fs=require('node:fs');
const path=require('node:path');
const source=fs.readFileSync(path.resolve(__dirname,'../../src/chunk-x436erds.js'),'utf8');

function buildHarness({answers=[],winner,tokenData,metadata=true,client=true,strict=false}={}){
 const start=source.indexOf('async _doRefresh(e){'),end=source.indexOf('const Ben=McpOAuthProvider;',start);
 assert.ok(start>0&&end>start,'actual 2.1.310 refresh method missing');
 const raw=source.slice(start,end).trim();
 assert.ok(raw.endsWith('}}'),'source must contain the original class/method braces');
 const calls=[],events=[],saved=[],invalidations=[],presented=[];
 let attempt=0;
 class OAuthError extends Error {
   constructor(code){super('OAuth error '+code);this.code=code;}
 }
 class EchoError extends Error{}
 class CredentialLockedError extends Error{}
 const Uae={InvalidGrant:'invalid_grant',ServerError:'server_error',TemporarilyUnavailable:'temporarily_unavailable',TooManyRequests:'too_many_requests'};
 const contexts={
   Ak:()=>undefined,
   a:(name,detail)=>events.push({name,detail}),
   c:v=>v,
   se:(_name,msg)=>calls.push({type:'log',msg:String(msg)}),
   Eme:()=>()=>{},
   Fen:()=>undefined,
   le:()=>strict,
   fe:async()=>metadata?{issuer:'https://auth.example'}:undefined,
   ho:x=>String(x),
   jDt:async()=>({issuer:'https://auth.example'}),
   kUn:()=>{},
   bbo:async()=>{
     const answer=answers[attempt++];
     calls.push({type:'exchange',attempt});
     if(answer?.oauthCode)throw new OAuthError(answer.oauthCode);
     if(answer instanceof Error)throw answer;
     return answer;
   },
   m:(category,reason)=>events.push({name:'failure-metric',detail:reason}),
   g:category=>events.push({name:'success-metric',detail:category}),
   f:(category,reason)=>events.push({name:'concurrent-metric',detail:reason}),
   h4:EchoError,
   y4:OAuthError,
   Uae,
   ut:new Set(['invalid_grant','invalid_client','unauthorized_client','invalid_request']),
   ye:()=>false,
   q0e:CredentialLockedError,
   xN:error=>String(error),
   d:error=>String(error),
   Fme:{emit:name=>calls.push({type:'emit',name})},
   X:async(ms)=>calls.push({type:'sleep',ms}),
   URL,
   Error
 };
 const Harness=vm.runInNewContext('class Harness{'+raw+'; Harness',contexts);
 const instance=new Harness();
 instance.serverName='mcp-example';
 instance.serverConfig={type:'http',url:'https://server.example/mcp'};
 instance._metadata=metadata?{issuer:'https://auth.example'}:undefined;
 instance._metadataEchoMode=strict?'enforce':'observe';
 instance.discoveryState=async()=>undefined;
 instance.clientInformation=async()=>client?{client_id:'client-1'}:undefined;
 instance.saveTokens=async data=>saved.push(data);
 instance.readConcurrentRefreshWinner=async()=>({freshTokens:winner,tokenData});
 instance.invalidateCredentials=async scope=>invalidations.push(scope);
 instance._presented={record:token=>presented.push(token)};
 return {instance,calls,events,saved,invalidations,presented,OAuthError,EchoError};
}

test('刷新成功保存新令牌并上报成功，不额外重试',async()=>{
 const f=buildHarness({answers:[{access_token:'next',refresh_token:'rotated'}]});
 const result=await f.instance._doRefresh('old-refresh');
 assert.equal(result.access_token,'next');
 assert.equal(f.saved.length,1);
 assert.equal(f.saved[0].refresh_token,'rotated');
 assert.deepEqual(f.presented,['old-refresh']);
 assert.equal(f.calls.filter(x=>x.type==='exchange').length,1);
 assert.ok(f.events.some(x=>x.name==='tengu_mcp_oauth_refresh_success'));
});
test('没有元数据或注册信息时不发送刷新请求',async()=>{
 const noMetadata=buildHarness({metadata:false});
 assert.equal(await noMetadata.instance._doRefresh('old'),undefined);
 assert.equal(noMetadata.calls.filter(x=>x.type==='exchange').length,0);
 assert.ok(noMetadata.events.some(x=>x.detail==='mcp_oauth_refresh_metadata_failed'));
 const noClient=buildHarness({client:false});
 assert.equal(await noClient.instance._doRefresh('old'),undefined);
 assert.ok(noClient.events.some(x=>x.detail==='mcp_oauth_refresh_no_client_info'));
});
test('invalid_grant 有并发赢家时直接返回赢家，不执行失效操作',async()=>{
 const winner={access_token:'already-rotated'};
 const g=buildHarness({answers:[{oauthCode:'invalid_grant'}],winner});
 assert.equal((await g.instance._doRefresh('old')).access_token,'already-rotated');
 assert.deepEqual(g.invalidations,[]);
 assert.ok(g.events.some(x=>x.detail==='mcp_oauth_refresh_concurrent_winner'));
});
test('invalid_grant 没有并发赢家时只清理 tokens 并通知监听者',async()=>{
 const f=buildHarness({answers:[{oauthCode:'invalid_grant'}]});
 assert.equal(await f.instance._doRefresh('old'),undefined);
 assert.deepEqual(f.invalidations,['tokens']);
 assert.equal(f.calls.filter(x=>x.type==='emit').length,1);
});
test('invalid_client 并发注册变化时不清空新 client，普通失败才清理 all',async()=>{
 const f=buildHarness({answers:[{oauthCode:'invalid_client'}],tokenData:{clientId:'new-client'}});
 assert.equal(await f.instance._doRefresh('old'),undefined);
 assert.deepEqual(f.invalidations,[]);
 assert.ok(f.events.some(x=>x.detail==='mcp_oauth_refresh_concurrent_reregister'));
 const noWinner=buildHarness({answers:[{oauthCode:'unauthorized_client'}]});
 assert.equal(await noWinner.instance._doRefresh('old'),undefined);
 assert.deepEqual(noWinner.invalidations,['all']);
});
test('transient failure 使用原版指数退避(1s,2s)，成功后停止',async()=>{
 const f=buildHarness({answers:[Error('timeout'),Error('ECONNRESET'),{access_token:'ok'}]});
 const result=await f.instance._doRefresh('refresh');
 assert.equal(result.access_token,'ok');
 assert.deepEqual(f.calls.filter(x=>x.type==='sleep').map(x=>x.ms),[1000,2000]);
 assert.equal(f.calls.filter(x=>x.type==='exchange').length,3);
});
test('非暂时性错误不重试，三次超时耗尽时上报失败',async()=>{
 const f=buildHarness({answers:[Error('invalid parameter')]});
 assert.equal(await f.instance._doRefresh('refresh'),undefined);
 assert.equal(f.calls.filter(x=>x.type==='sleep').length,0);
 const exhausted=buildHarness({answers:[Error('timeout'),Error('timeout'),Error('timeout')]});
 assert.equal(await exhausted.instance._doRefresh('refresh'),undefined);
 assert.deepEqual(exhausted.calls.filter(x=>x.type==='sleep').map(x=>x.ms),[1000,2000]);
 assert.ok(exhausted.events.some(x=>x.name==='tengu_mcp_oauth_refresh_failure'&&x.detail.reason==='transient_retries_exhausted'));
});
test('Provider 改名仍保留 Ben 类身份与刷新方法边界',()=>{
 assert.match(source,/class McpOAuthProvider\{/);
 assert.match(source,/const Ben=McpOAuthProvider/);
 assert.match(source,/async refreshAuthorization\(e\)/);
 assert.match(source,/async readConcurrentRefreshWinner\(\)/);
 assert.match(source,/async _doRefresh\(e\)/);
});
