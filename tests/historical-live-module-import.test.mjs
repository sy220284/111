import {test} from 'node:test';
import assert from 'node:assert/strict';
// The actual ESM modules, including their real imports and export tables.
// No AST extraction, mocked implementations or hand-written replicas here.
import {
  gc,normalizeLegacyToolName,Cse,getLegacyToolNames,escapeRuleContent,
  AQo,unescapeRuleContent,Bn,permissionRuleValueFromString,
  dn,permissionRuleValueToString,findFirstUnescapedChar,findLastUnescapedChar
} from '../chunk-9z5ybxhe.js';
import {Hxe,F,L} from '../chunk-wrtp8qz8.js';

test('真实ESM权限解析入口：函数别名身份与新增旧版兼容规则',()=>{
 for(const [short,readable] of [
  [gc,normalizeLegacyToolName],[Cse,getLegacyToolNames],
  [AQo,unescapeRuleContent],
  [Bn,permissionRuleValueFromString],[dn,permissionRuleValueToString]
 ])assert.strictEqual(short,readable);
 assert.equal(gc('Task'),'Agent');
 assert.equal(gc('KillBash'),'TaskStop');
 assert.equal(gc('KillShell'),'TaskStop');
 assert.equal(gc('unknownCustomTool'),'unknownCustomTool');
 assert.ok(Cse('TaskStop').includes('KillBash'));
 assert.equal(AQo(escapeRuleContent('word\\(nested)')),'word\\(nested)');
 assert.equal(findFirstUnescapedChar('\\(inner)(outer)','('),8);
});
test('真实ESM数组入口：生产模块导出、计数/唯一化及插入语义',()=>{
 const input=['a','b','c'];
 assert.deepEqual(Array.from(Hxe(input,i=>'s'+i)),['a','s1','b','s2','c']);
 assert.equal(F([0,1,2,3],x=>x>=2),2);
 assert.deepEqual(Array.from(L([3,1,3,2,1])),[3,1,2]);
});
