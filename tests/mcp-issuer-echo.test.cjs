'use strict';
const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');

const source=fs.readFileSync(path.resolve(__dirname,'../core/chunk-x436erds.js'),'utf8');
function fromOriginal({strict=false,reportThrows=false,flagThrows=false}={}){
  const start=source.indexOf('function le(){'),end=source.indexOf('class KCs extends nO',start);
  assert.ok(start>0&&end>start,'2.1.310 MCP issuer-echo functions must be present');
  const telemetry=[],warnings=[];
  class IssuerEchoDenied extends Error {
    constructor(expected,received){super('issuer echo cross-origin mismatch');this.name='IssuerEchoCrossOriginError';this.expected=expected;this.received=received;}
  }
  const context={
    URL,
    k:()=>{if(flagThrows)throw Error('feature flag unavailable');return strict;},
    a:(name,data)=>{if(reportThrows)throw Error('telemetry unavailable');telemetry.push({name,data});},
    c:x=>x,b:x=>x,Ka:xs=>xs,cn:s=>'hash-'+s.length,Ak:()=>undefined,Xw:()=>undefined,sC:()=>undefined,
    se:(...args)=>warnings.push(args),
    d:e=>String(e),h4:IssuerEchoDenied
  };
  const api=vm.runInNewContext(source.slice(start,end)+';({pt,pe,_t,mt,kUn,isEquivalentMcpIssuerEcho,parseMcpIssuerHttpUrl,classifyMcpIssuerOrigin,listMcpIssuerDifferenceFacets,verifyMcpOAuthIssuerEcho})',context);
  return {...api,telemetry,warnings};
}
const issuer=(receivedIssuer,expectedIssuer='https://auth.example.com/path')=>({
  serverName:'test-mcp',site:'rfc9728_chain',expectedIssuer,receivedIssuer
});

test('Issuer 回显的原版等价规则只允许完全相同或预期尾斜杠归一化',()=>{
 const {pt}=fromOriginal();
 assert.equal(pt('https://a.example/','https://a.example'),true);
 assert.equal(pt('https://a.example','https://a.example/'),false);
 assert.equal(pt('https://a.example','https://a.example'),true);
 assert.equal(pt('https://a.example/path','https://a.example/other'),false);
});
test('只接受可解析的 HTTP(S) URL，拒绝文件、脚本和损坏的 Issuer',()=>{
 const {pe}=fromOriginal();
 assert.equal(pe('https://auth.example/path').hostname,'auth.example');
 assert.equal(pe('http://localhost:1234').protocol,'http:');
 for(const value of ['not a url','file:///tmp/test','javascript:alert(1)','data:text/plain,a']){
  assert.equal(pe(value),undefined);
 }
});
test('Issuer 来源分类拒绝不同 host、scheme、显式用户信息与回显 query/fragment',()=>{
 const { _t }=fromOriginal();
 assert.equal(_t('https://auth.example/path','https://auth.example/other'),'same_origin');
 assert.equal(_t('https://auth.example/path','https://other.example/path'),'cross_origin');
 assert.equal(_t('https://auth.example/path','http://auth.example/path'),'cross_origin');
 assert.equal(_t('https://auth.example/path','https://auth.example/path?token=x'),'cross_origin');
 assert.equal(_t('https://auth.example/path','https://auth.example/path#section'),'cross_origin');
 assert.equal(_t('https://auth.example/path','https://u:p@auth.example/path'),'cross_origin');
 assert.equal(_t('not a url','https://auth.example/'),'unparseable');
});
test('Issuer 差异维度完整覆盖 scheme、userinfo、host、port、path、query、fragment',()=>{
 const {mt}=fromOriginal();
 const before='https://auth.example.com/path';
 const changes=[
 ['http://auth.example.com/path','scheme'],
 ['https://u:p@auth.example.com/path','userinfo'],
 ['https://else.example.com/path','host'],
 ['https://auth.example.com:8443/path','port'],
 ['https://auth.example.com/other','path'],
 ['https://auth.example.com/path?x=1','query'],
 ['https://auth.example.com/path#part','fragment']
 ];
 for(const [after,facet] of changes) assert.ok(Array.from(mt(before,after)).includes(facet),facet);
 assert.equal(Array.from(mt(before,before))[0],'normalization_only');
 assert.equal(Array.from(mt(before,'not a url')).length,0);
});
test('默认观察模式只记录失配，不阻断不同来源；严格模式阻断跨源',()=>{
 const observer=fromOriginal();
 observer.kUn(issuer('https://evil.example/path'));
 assert.equal(observer.telemetry.length,1);
 assert.equal(observer.telemetry[0].data.outcome,'proceeded');
 assert.equal(observer.telemetry[0].data.originRelation,'cross_origin');
 const enforcer=fromOriginal({strict:true});
 assert.throws(()=>enforcer.kUn(issuer('https://evil.example/path')), e=>e.name==='IssuerEchoCrossOriginError');
 assert.equal(enforcer.telemetry[0].data.outcome,'denied');
 assert.equal(enforcer.telemetry[0].data.mode,'enforce');
});
test('严格模式允许同源不同路径，但拒绝无法解析或缺失的 Issuer',()=>{
 const f=fromOriginal({strict:true});
 f.kUn(issuer('https://auth.example.com/other'));
 assert.equal(f.telemetry.at(-1).data.originRelation,'same_origin');
 assert.throws(()=>f.kUn(issuer('data:text/plain,test')), /issuer echo/);
 assert.throws(()=>f.kUn({serverName:'test-mcp',site:'fallback',expectedIssuer:'https://auth.example.com'}),/issuer echo/);
});
test('完全匹配不产生日志，缺失预期 Issuer 不做额外推断',()=>{
 const f=fromOriginal({strict:true});
 f.kUn(issuer('https://auth.example.com/path'));
 f.kUn({serverName:'test-mcp',receivedIssuer:'https://unknown.example'});
 assert.equal(f.telemetry.length,0);
});
test('遥测或功能开关读取失败不改变原版安全/观察决策',()=>{
 const f=fromOriginal({strict:true,reportThrows:true});
 assert.throws(()=>f.kUn(issuer('https://other.example/path')), /issuer echo/);
 assert.ok(f.warnings.length>0);
 const g=fromOriginal({flagThrows:true});
 assert.doesNotThrow(()=>g.kUn(issuer('https://other.example/path')));
 assert.equal(g.telemetry[0].data.mode,'observe');
});
test('五个可读函数与原短符号保持单一调用目标',()=>{
 const f=fromOriginal();
 for(const [oldName,newName] of [
  ['pt','isEquivalentMcpIssuerEcho'],['pe','parseMcpIssuerHttpUrl'],['_t','classifyMcpIssuerOrigin'],
  ['mt','listMcpIssuerDifferenceFacets'],['kUn','verifyMcpOAuthIssuerEcho']
 ])assert.equal(f[oldName],f[newName]);
});
