'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const root=path.resolve(__dirname,'..');
const ledger=JSON.parse(fs.readFileSync(path.join(root,'resources/function-verification-ledger.json'),'utf8'));
const summary=JSON.parse(fs.readFileSync(path.join(root,'resources/function-summary.json'),'utf8'));
assert.equal(ledger.version,summary.version);
assert.equal(summary.modules,2551);
assert.ok(summary.functions>ledger.entries.length,'the AST function count must include the small tested subset');
const seen=new Set();
let count=0;
for(const row of ledger.entries){
  assert.equal(row.status,'targeted_behavior_test_only');
  assert.ok(row.source.startsWith('src/') && row.test.startsWith('tests/'),'paths must be repo-owned');
  const key=row.source+'::'+row.symbol;
  assert.ok(!seen.has(key),'duplicate function evidence '+key);
  seen.add(key);
  const src=fs.readFileSync(path.join(root,row.source),'utf8');
  const test=fs.readFileSync(path.join(root,row.test),'utf8');
  assert.ok(src.includes(row.anchor),'missing source anchor '+key);
  assert.ok(test.includes(path.basename(row.source)),'test must identify original module '+row.test);
  assert.ok(test.includes('test('),'behavior test not found '+row.test);
  count++;
}
console.log(JSON.stringify({result:'PASS',version:ledger.version,sourceModules:summary.modules,astFunctionEntities:summary.functions,targetedFunctionContracts:count,sourceReimplementationsVerifiedByThisLedger:0,wholeBusinessFlowsVerifiedByThisLedger:0}));
