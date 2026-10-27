'use strict';
const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const {fragmentToModuleExports}=require('./helpers/source-extraction.cjs');
test('源码片段提取不依赖最终 ESM 导出名单或空格',()=>{
 const prefix='function Gr(e){return e+1;}\nfunction Pu(e){return e*2;}';
 for(const ending of ['export { A, B };','export{McpOAuthProvider,fn};','export { renamedOne, A, Z };']){
  const source=prefix+'\n'+ending;
  assert.equal(fragmentToModuleExports(source,'function Gr(e)'),prefix);
 }
});
test('丢失被测函数或尾部导出时必须失败，不能产生虚假绿色回归',()=>{
 assert.throws(()=>fragmentToModuleExports('function fn(){}\nexport {fn};','function missing('),/source fragment missing/);
 assert.throws(()=>fragmentToModuleExports('function Gr(){}','function Gr('),/source fragment missing/);
});
test('实际主代理与 OAuth 模块的已恢复函数片段不受导出排序影响',()=>{
 const root=path.resolve(__dirname,'..');
 const query=fs.readFileSync(path.join(root,'src/chunk-tqjdehqd.js'),'utf8');
 const oauth=fs.readFileSync(path.join(root,'src/chunk-x436erds.js'),'utf8');
 const q=fragmentToModuleExports(query,'function Gr(e)');
 const o=fragmentToModuleExports(oauth,'function selectMcpOAuthMetadataScope(e)');
 for(const s of ['function Gr(e)','function Pu(e)','function Qf(e'])assert.ok(q.includes(s),s);
 for(const s of ['function selectMcpOAuthMetadataScope(e)','function includeSupportedOfflineAccessScope(e','function isMicrosoftMcpOAuthIssuer(e)'])assert.ok(o.includes(s),s);
 assert.doesNotMatch(q,/\nexport\s*\{/);
 assert.doesNotMatch(o,/\nexport\s*\{/);
});
