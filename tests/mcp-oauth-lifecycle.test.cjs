'use strict';
const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');
const source=fs.readFileSync(path.resolve(__dirname,'../src/chunk-x436erds.js'),'utf8');

function inFlightFixture({initial={mcpOAuth:{}},preMutation,failMutation=false}={}){
 const start=source.indexOf('function registerActiveMcpOAuthFlow(e,t)');
 const end=source.indexOf('async function $e({serverName:e',start);
 assert.ok(start>=0&&end>start,'original OAuth flow and placeholder functions not found');
 let state=initial;
 const activeOAuthFlows=new Map(),warnings=[];
 const api=vm.runInNewContext(source.slice(start,end)+';({jAi,WAi,LTr,registerActiveMcpOAuthFlow,getActiveMcpOAuthFlow,clearTokenlessMcpOAuthPlaceholder})',{
  xt:()=>({activeOAuthFlows}),
  td:(server,config)=>server+':'+config,
  Kn:()=>({readAsync:async()=>state,mutate:async updater=>{
    if(preMutation)state=preMutation(state);
    if(failMutation)throw Error('credential store unavailable');
    state=updater(state);return state;
  }}),
  se:(...args)=>warnings.push(args),
  d:e=>String(e)
 });
 return {...api,activeOAuthFlows,warnings,getState:()=>state};
}
function deferred(){
 let resolve,reject;
 const promise=new Promise((ok,fail)=>{resolve=ok;reject=fail;});
 return {promise,resolve,reject};
}
const settle=()=>new Promise(resolve=>setImmediate(resolve));

test('授权流程登记当前 Promise，完成后清理自身的 active 条目',async()=>{
 const f=inFlightFixture(),flow=deferred();
 f.registerActiveMcpOAuthFlow('server',flow.promise);
 assert.equal(f.getActiveMcpOAuthFlow('server'),flow.promise);
 flow.resolve('authorized');await settle();
 assert.equal(f.getActiveMcpOAuthFlow('server'),undefined);
 assert.equal(f.jAi,f.registerActiveMcpOAuthFlow);
 assert.equal(f.WAi,f.getActiveMcpOAuthFlow);
});
test('旧授权流程结束不清除同 key 新流程，拒绝的 Promise 也被正确回收',async()=>{
 const f=inFlightFixture(),old=deferred(),newer=deferred();
 f.jAi('server',old.promise);f.jAi('server',newer.promise);
 old.resolve('old success');await settle();
 assert.equal(f.WAi('server'),newer.promise);
 newer.reject(Error('auth denied'));await settle();
 assert.equal(f.WAi('server'),undefined);
});
test('没有令牌的 OAuth 占位项会删除；已有 access/refresh token 始终保留',async()=>{
 const state={mcpOAuth:{'s:c':{},'s:access':{accessToken:'token'},'s:refresh':{refreshToken:'refresh'}}};
 const f=inFlightFixture({initial:state});
 await f.clearTokenlessMcpOAuthPlaceholder('s','c');
 assert.equal(f.getState().mcpOAuth['s:c'],undefined);
 await f.clearTokenlessMcpOAuthPlaceholder('s','access');
 await f.clearTokenlessMcpOAuthPlaceholder('s','refresh');
 assert.equal(f.getState().mcpOAuth['s:access'].accessToken,'token');
 assert.equal(f.getState().mcpOAuth['s:refresh'].refreshToken,'refresh');
 assert.equal(f.LTr,f.clearTokenlessMcpOAuthPlaceholder);
});
test('读写竞争：删除前收到新令牌时，不清除成功认证的记录',async()=>{
 const f=inFlightFixture({
  initial:{mcpOAuth:{'s:c':{}}},
  preMutation:state=>({...state,mcpOAuth:{...state.mcpOAuth,'s:c':{accessToken:'new-token'}}})
 });
 await f.LTr('s','c');
 assert.equal(f.getState().mcpOAuth['s:c'].accessToken,'new-token');
});
test('凭据库写入失败只产生日志，不将清理异常冒充登录失败',async()=>{
 const f=inFlightFixture({initial:{mcpOAuth:{'s:c':{}}},failMutation:true});
 await assert.doesNotReject(f.LTr('s','c'));
 assert.equal(f.warnings.length,1);
 assert.match(f.warnings[0][1],/clear tokenless stub failed/);
});
function scopeFixture(){
 const start=source.indexOf('function selectMcpOAuthMetadataScope(e)');
 const end=source.indexOf('export{registerActiveMcpOAuthFlow',start);
 assert.ok(start>0&&end>start,'original OAuth scope helpers missing');
 return vm.runInNewContext(source.slice(start,end)+';({Le,Dhi,Hhi,selectMcpOAuthMetadataScope,includeSupportedOfflineAccessScope,isMicrosoftMcpOAuthIssuer})',{URL});
}
test('优先明确 scope，然后 default_scope；不对非字符串进行猜测',()=>{
 const f=scopeFixture();
 assert.equal(f.Le({scope:'read',default_scope:'fallback'}),'read');
 assert.equal(f.Le({default_scope:'fallback'}),'fallback');
 assert.equal(f.Le({scope:99,default_scope:'fallback'}),'fallback');
 assert.equal(f.Le(null),undefined);
 assert.equal(f.Le({scope:null}),undefined);
 assert.equal(f.Le,f.selectMcpOAuthMetadataScope);
});
test('offline_access 仅在服务器支持时添加，已有时不会重复添加',()=>{
 const f=scopeFixture(),metadata={scopes_supported:['read','offline_access']};
 assert.equal(f.Dhi('read',metadata),'read offline_access');
 assert.equal(f.Dhi('read offline_access',metadata),'read offline_access');
 assert.equal(f.Dhi(null,metadata),'offline_access');
 assert.equal(f.Dhi('read',{scopes_supported:['read']}),'read');
 assert.equal(f.Dhi('read',undefined),'read');
 assert.equal(f.Dhi,f.includeSupportedOfflineAccessScope);
});
test('Microsoft OAuth Issuer 仅命中原版列出的主机或有效 B2C/CIAM 子域',()=>{
 const f=scopeFixture();
 for(const good of [
  'https://login.microsoftonline.com/tenant',
  'https://login.microsoftonline.us',
  'https://login.partner.microsoftonline.cn',
  'https://login.chinacloudapi.cn',
  'https://tenant.b2clogin.com/path',
  'https://tenant.ciamlogin.com'
 ])assert.equal(f.Hhi(good),true,good);
 for(const bad of [
  'https://login.microsoftonline.com.attacker.example',
  'https://fakeb2clogin.com',
  'https://b2clogin.com',
  'https://other.example','garbage'
 ])assert.equal(f.Hhi(bad),false,bad);
 assert.equal(f.Hhi(new URL('https://tenant.ciamlogin.com')),true);
 assert.equal(f.Hhi,f.isMicrosoftMcpOAuthIssuer);
});
test('所有恢复函数保留 2.1.310 原调用符号',()=>{
 for(const item of [
  'const jAi=registerActiveMcpOAuthFlow','const WAi=getActiveMcpOAuthFlow',
  'const LTr=clearTokenlessMcpOAuthPlaceholder',
  'const Le=selectMcpOAuthMetadataScope','const Dhi=includeSupportedOfflineAccessScope',
  'const Hhi=isMicrosoftMcpOAuthIssuer'
 ])assert.ok(source.includes(item),item);
});
