'use strict';
const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const root=path.resolve(__dirname,'..');
const {build,category,astMetrics}=require('../scripts/audit-v37-nonmain-inventory.cjs');

test('v37固定版主源码之外652份文件分类完整，不混入2551主模块',()=>{
 const r=build({root,verify:false});
 assert.deepEqual(r.problems,[]);
 assert.equal(r.metrics.nonMainFiles,652);
 assert.equal(r.metrics.independentlyRecoveredSourceFiles,318);
 assert.equal(r.metrics.remainingOriginalEntitiesOutsideKitAndEmbedded,334);
 assert.equal(r.groups.plugin_testing_kit.files,294);
 assert.equal(r.groups.independent_embedded_source.files,24);
 assert.equal(r.groups.bundled_tool_sources.files,56);
 assert.equal(r.groups.prebuilt_vendor_bundle.files,3);
 assert.equal(r.groups.other_embedded_resources.files,212);
 assert.equal(r.groups.historic_tests.files,44);
 assert.equal(r.groups.historic_build_scripts.files,8);
 assert.equal(r.groups.historic_ci.files,2);
 assert.equal(r.groups.historic_metadata_and_entrypoints.files,9);
 assert.equal(r.metrics.bundledJsFiles,47);
 assert.equal(r.metrics.bundledPythonFiles,3);
 assert.equal(r.metrics.nativeELFModules,2);
 assert.ok(r.metrics.toolSyntaxFunctions>200);
 assert.ok(r.metrics.toolCallExpressions>100);
 assert.ok(r.metrics.pythonFunctions>10);
 assert.equal(r.bundledTools.every(x=>x.ast.functions>=0),true);
});
test('JS AST distinguishes executable tool sources from vendor bundles and native binaries',()=>{
 assert.equal(category('resources/tools/claude-test/scripts/ct.mjs'),'bundled_tool_sources');
 assert.equal(category('resources/tools/design-sync/package-build.mjs'),'bundled_tool_sources');
 assert.equal(category('resources/tools/evaluation/prefix_diff.py'),'bundled_tool_sources');
 assert.equal(category('resources/bunfs/clipboard-napi.node'),'other_embedded_resources');
 assert.equal(category('src/mermaid.min.js'),'prebuilt_vendor_bundle');
 const s=astMetrics("export const a = x=>x; export async function f(x){return import('./adapter.js')}",'example.js');
 assert.equal(s.functions,2);
 assert.equal(s.dynamicImports,1);
 assert.deepEqual(s.exports,['a','f']);
 assert.throws(()=>astMetrics('function x( {','broken.js'),/AST parse failed/);
});
test('独立浏览器网络围栏：私网及回环识别、代理凭证拒绝、NO_PROXY边界',async()=>{
 const {isNonPublicAddress,plainAddress,upstreamProxySetting,noProxyMatcher}=await import('../resources/tools/claude-test/scripts/lib/fence-proxy.mjs');
 for(const ip of ['127.0.0.1','10.0.0.1','172.16.0.1','192.168.0.1','::1','fc00::1234','fe80::1','::ffff:127.0.0.1'])
  assert.equal(isNonPublicAddress(ip),true,'private IP incorrectly treated as public '+ip);
 assert.equal(isNonPublicAddress('8.8.8.8'),false);
 assert.deepEqual(plainAddress('::ffff:127.0.0.1'),{address:'127.0.0.1',family:4});
 const noProxy=noProxyMatcher({NO_PROXY:'.example.com:443,localhost'});
 assert.equal(noProxy('api.example.com',443),true);
 assert.equal(noProxy('api.example.com',80),false);
 assert.equal(noProxy('evil-example.com',443),false);
 assert.equal(noProxy('localhost',80),true);
 const creds=upstreamProxySetting({HTTPS_PROXY:'http://user:password@proxy.example:8080'});
 assert.equal(creds.proxy,null);
 assert.match(creds.problem,/password/);
 const valid=upstreamProxySetting({HTTPS_PROXY:'http://proxy.example:8080'});
 assert.equal(valid.proxy.host,'proxy.example');
 assert.equal(valid.proxy.port,8080);
});
test('浏览器安全来源与DNS钉住：输入边界必须能解析而不能忽略垃圾规则',async()=>{
 const {bypassOf,resolverPins}=await import('../resources/tools/claude-test/scripts/lib/guard.mjs');
 assert.deepEqual(bypassOf('https://example.com:8443'),['https://example.com:8443','wss://example.com:8443']);
 assert.deepEqual(bypassOf('http://example.com'),['http://example.com:80','ws://example.com:80']);
 assert.deepEqual(bypassOf('not/a/host'),[]);
 const pins=resolverPins([{name:'app.example',address:'127.0.0.1'},{name:'bad..domain',address:'10.0.0.1'},{name:'app.example',address:'192.168.0.1'},{name:'malformed.test',address:'999.999.999.999'}]);
 assert.ok(pins.includes('MAP app.example 127.0.0.1'));
 assert.ok(!pins.includes('bad..domain')&&!pins.includes('malformed.test'));
});
test('设计同步与浏览器工具归属固定、保留已知未验证的真实构建条件',()=>{
 for(const [file,expectedMoved,expectedSupporting] of [
  ['resources/tools/claude-test/source-layout.json',18,6],
  ['resources/tools/design-sync/source-layout.json',12,6]]){
  const m=JSON.parse(fs.readFileSync(path.join(root,file),'utf8'));
  assert.equal(m.migrated.length,expectedMoved);
  assert.equal(m.supporting.length,expectedSupporting);
  for(const e of [...m.migrated,...m.supporting])assert.ok(fs.existsSync(path.join(root,e.path)));
 }
 const design=JSON.parse(fs.readFileSync(path.join(root,'resources/tools/design-sync/source-layout.json'),'utf8'));
 assert.equal(design.runtimeNotYetVerified.length,2);
 assert.deepEqual(design.knownUnresolved,[]);
});
