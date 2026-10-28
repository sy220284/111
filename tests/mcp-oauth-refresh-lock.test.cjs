'use strict';
const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');

const source=fs.readFileSync(path.resolve(__dirname,'../core/chunk-x436erds.js'),'utf8');
function fixture({serverMode=false,lockFailures=[],stored,now=2000000,refreshFailure=false,maxAttempts=3}={}){
 const first=source.indexOf('async refreshAuthorization(e){');
 const end=source.indexOf('async _doRefresh(e){',first);
 assert.ok(first>0&&end>first,'real 2.1.310 refresh methods missing');
 const events=[],tracked=[],releases=[];
 let tries=0,reads=0,refreshes=0;
 const state=stored===undefined?{'server/key':{refreshToken:'stored-refresh',accessToken:'stale',expiresAt:now+200000}}:stored;
 const bindings={
   J:()=>serverMode,
   se:(_name,message)=>events.push(String(message)),
   td:()=> 'server/key',
   O0:()=>'/safe-state',
   ie:()=>({mkdir:async folder=>events.push('mkdir:'+folder)}),
   nt:(folder,name)=>path.posix.join(folder,name),
   Ae:maxAttempts,
   vl:async (filename,opts)=>{
      tries++;
      events.push('lock:'+filename);
      assert.equal(opts.realpath,false);
      assert.equal(opts.stale,60000);
      assert.equal(opts.update,5000);
      const outcome=lockFailures[tries-1];
      if(outcome)throw Object.assign(Error(outcome),{code:outcome});
      return async()=>{releases.push(filename);};
   },
   E:error=>error.code,
   X:async()=>events.push('sleep'),
   X5:()=>events.push('refresh-cache-check'),
   Kn:()=>({readAsync:async()=>{reads++;return{mcpOAuth:state}}}),
   Date:{now:()=>now}
 };
 const type=vm.runInNewContext('class RefreshHarness {'+source.slice(first,end)+'}; RefreshHarness',bindings);
 const instance=new type();
 instance.serverName='example';
 instance.serverConfig={type:'http'};
 instance._presented={record:token=>tracked.push(token)};
 instance._doRefresh=async token=>{
  refreshes++;
  events.push('actual-refresh:'+token);
  if(refreshFailure)throw Error('failed refresh');
  return {access_token:'new',refresh_token:'rotated'};
 };
 return {instance,events,tracked,releases,get tries(){return tries;},get reads(){return reads;},get refreshes(){return refreshes;}};
}

test('服务端会话禁用刷新，不触碰文件锁或凭据库',async()=>{
 const f=fixture({serverMode:true});
 assert.equal(await f.instance.refreshAuthorization('old'),undefined);
 assert.equal(f.tries,0);
 assert.equal(f.reads,0);
 assert.equal(f.refreshes,0);
});
test('刷新文件锁使用隔离路径，成功刷新后释放锁',async()=>{
 const f=fixture({stored:{'server/key':{refreshToken:'from-disk',accessToken:'expired',expiresAt:1000000}}});
 const next=await f.instance.refreshAuthorization('from-caller');
 assert.equal(next.access_token,'new');
 assert.ok(f.events.includes('lock:/safe-state/mcp-refresh-server_key.lock'));
 assert.ok(f.events.includes('actual-refresh:from-disk'));
 assert.equal(f.tracked[0],'from-disk');
 assert.equal(f.releases.length,1);
 assert.equal(f.refreshes,1);
});
test('其他进程已有有效令牌则直接采用、绝不触发二次刷新',async()=>{
 const f=fixture({stored:{'server/key':{accessToken:'winner',refreshToken:'winner-refresh',expiresAt:2500000,scope:'read'}}});
 const result=await f.instance.refreshAuthorization('old');
 assert.equal(result.access_token,'winner');
 assert.equal(result.refresh_token,'winner-refresh');
 assert.equal(result.token_type,'Bearer');
 assert.equal(result.expires_in,500);
 assert.equal(f.refreshes,0);
 assert.equal(f.releases.length,1);
});
test('未记录到期时间且令牌存在时，仍按原版规则视为已刷新',async()=>{
 const f=fixture({stored:{'server/key':{accessToken:'no-expiry'}}});
 const result=await f.instance.refreshAuthorization('old');
 assert.equal(result.access_token,'no-expiry');
 assert.equal(result.expires_in,undefined);
 assert.equal(f.refreshes,0);
});
test('剩余300秒处需要继续刷新；超过300秒无需刷新',async()=>{
 const f=fixture({stored:{'server/key':{accessToken:'near-expiry',refreshToken:'fresh-secret',expiresAt:2300000}}});
 const result=await f.instance.refreshAuthorization('old');
 assert.equal(result.access_token,'new');
 assert.equal(f.refreshes,1);
 assert.equal(f.events.at(-2),'actual-refresh:fresh-secret');
});
test('锁暂时被占用可重试，超限或非锁占用错误必须跳过刷新',async()=>{
 const first=fixture({lockFailures:['ELOCKED','ELOCKED']});
 await first.instance.refreshAuthorization('old');
 assert.equal(first.tries,3);
 assert.equal(first.events.filter(x=>x==='sleep').length,2);
 assert.equal(first.releases.length,1);
 const exhausted=fixture({lockFailures:['ELOCKED','ELOCKED','ELOCKED']});
 assert.equal(await exhausted.instance.refreshAuthorization('old'),undefined);
 assert.equal(exhausted.refreshes,0);
 assert.equal(exhausted.releases.length,0);
 const unexpected=fixture({lockFailures:['EACCES']});
 assert.equal(await unexpected.instance.refreshAuthorization('old'),undefined);
 assert.equal(unexpected.tries,1);
 assert.equal(unexpected.refreshes,0);
});
test('内部刷新发生异常时仍必须释放已获取的多进程文件锁',async()=>{
 const f=fixture({stored:{'server/key':{refreshToken:'r',accessToken:'old',expiresAt:0}},refreshFailure:true});
 await assert.rejects(f.instance.refreshAuthorization('old'),/failed refresh/);
 assert.equal(f.releases.length,1);
});
test('从凭据库重新确认并发刷新赢家采用严格300秒判断',async()=>{
 const current=fixture({stored:{'server/key':{accessToken:'newer',refreshToken:'fresh',expiresAt:2400000}}});
 const win=await current.instance.readConcurrentRefreshWinner();
 assert.equal(win.freshTokens.access_token,'newer');
 assert.equal(win.tokenData.accessToken,'newer');
 const near=fixture({stored:{'server/key':{accessToken:'soon',expiresAt:2300000}}});
 const hit=await near.instance.readConcurrentRefreshWinner();
 assert.equal(hit.tokenData.accessToken,'soon');
 assert.equal(hit.freshTokens,undefined);
 const absent=fixture({stored:{}});
 const none=await absent.instance.readConcurrentRefreshWinner();
 assert.equal(none.tokenData,undefined);
 assert.equal(none.freshTokens,undefined);
});
