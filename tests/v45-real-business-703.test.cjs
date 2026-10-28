'use strict';
// Per-candidate *actual graph source attribution* tests, not 703 runtime parity claims.
const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const root=path.resolve(__dirname,'..');
const auditPath=path.join(root,'.generated/v45-703-business-chains.json');
if(!fs.existsSync(auditPath)){
 test('真实703条历史源及图谱尚未注入此普通CI：交由固定历史工作流实际验收',{skip:'historical CI must provide real 2.1.88 and 703 report'},()=>{
   assert.equal(fs.existsSync(auditPath),false);
 });
}else{
const graph=JSON.parse(fs.readFileSync(auditPath,'utf8'));
assert.equal(graph.schema,'703-business-chain-audit-v1');
assert.equal(graph.summary.examined,703);
assert.equal(graph.summary.sourceDefinitionsLocated,703);
assert.equal(graph.items.length,703);
const ids=new Set();
for(const [i,row] of graph.items.entries()){
 test('v45 703-chain #'+String(i+1).padStart(3,'0')+' '+row.id,()=>{
  assert.ok(!ids.has(row.id),'duplicate audited AST location '+row.id);ids.add(row.id);
  const d=row.dependencies,l=row.location;
  assert.equal(row.verification.actualCurrentAstLocated,true);
  assert.equal(row.verification.fullModuleGraphJoined,true);
  assert.equal(row.verification.witnessedRuntimeCall,false);
  assert.equal(row.verification.validatedBusinessChain,false);
  assert.equal(row.crossVersion.runtimeBehaviorVerified,false);
  assert.equal(row.crossVersion.semanticEquivalenceConfirmed,false);
  assert.ok(l.file.startsWith(''));
  assert.ok(Number.isInteger(l.offset)&&l.offset>=0);
  assert.ok(l.definitionNameCollisions>=1);
  assert.ok(Number.isInteger(d.lexicalIdentifierReferencesOutsideDefinition));
  assert.ok(Number.isInteger(d.candidateCallSiteCount));
  if(l.definitionNameCollisions>1)assert.equal(d.candidateCallSiteCount,0);
  if(d.cliModuleImportRoute){
   assert.equal(d.cliModuleImportRoute[0],'cli.js');
   assert.equal(d.cliModuleImportRoute.at(-1),l.file);
  }
  if(d.hookModuleImportRoute)
   assert.equal(d.hookModuleImportRoute.at(-1),l.file);
  for(const caller of d.candidateCallers){
   assert.ok([
    'same_module_candidate','import_binding_candidate','namespace_import_candidate',
    'lexical_function_candidate','lexical_object_method_candidate',
    'module_object_method_candidate','immediate_function_candidate',
    'namespace_object_member_candidate','imported_object_method_candidate'
   ].includes(caller.kind),'候选必须由已定义的静态解析规则产生，不能伪造实际执行');
   assert.ok(caller.evidence.every(x=>x.file&&x.line>0&&x.column>0));
  }
 });
}
test('703图谱汇总不得将静态调用证据虚报为动态语义验收',()=>{
 assert.equal(graph.summary.semanticallyConfirmed,0);
 assert.equal(graph.summary.runtimeBusinessChainsProved,0);
 assert.equal(Object.values(graph.summary.crossVersionClassification).reduce((a,b)=>a+b,0),703);
});

}
