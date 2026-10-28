'use strict';
const { test }=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');
const ts=require('typescript');

const sourcePath=path.resolve(__dirname,'../core/chunk-9z5ybxhe.js');
const source=fs.readFileSync(sourcePath,'utf8');
const ast=ts.createSourceFile(sourcePath,source,ts.ScriptTarget.Latest,true,ts.ScriptKind.JS);
const legacy=new Map([
 ['gc','normalizeLegacyToolName'],
 ['Cse','getLegacyToolNames'],
 ['m','escapeRuleContent'],
 ['AQo','unescapeRuleContent'],
 ['Bn','permissionRuleValueFromString'],
 ['dn','permissionRuleValueToString'],
 ['R','findFirstUnescapedChar'],
 ['E','findLastUnescapedChar']
]);

function originalFixture(){
 const names=new Set([...legacy.values(),'X9','CQo']);
 const fn=ast.statements.filter(n=>ts.isFunctionDeclaration(n)&&names.has(n.name?.text));
 assert.equal(fn.length,names.size,'original 2.1.310 functions should exist exactly once');
 const globals=ast.statements.filter(n=>ts.isVariableStatement(n)&&n.declarationList.declarations.some(d=>['g','l'].includes(d.name.getText(ast))));
 assert.equal(globals.length,2,'actual v2.1.310 legacy tool map should be used');
 const body=[...globals.map(n=>n.getText(ast)),...fn.map(n=>n.getText(ast))];
 for(const [shortName,readable] of legacy){
  assert.ok(source.includes('const '+shortName+'='+readable+';'),'old symbol preserved: '+shortName);
  body.push('const '+shortName+'='+readable+';');
 }
 return vm.runInNewContext(body.join('\n')+';({'+[...legacy.keys(),...legacy.values(),'X9'].join(',')+'})');
}
const plain=x=>JSON.parse(JSON.stringify(x));

test('v2.1.88 的八项权限解析职责已落到 v2.1.310 原函数并保留兼容入口',()=>{
 const f=originalFixture();
 for(const [oldName,name] of legacy)assert.equal(f[oldName],f[name],name);
});

test('旧版 Task/KillShell 兼容映射仍有效，新版额外保留 KillBash',()=>{
 const f=originalFixture();
 assert.equal(f.gc('Task'),'Agent');
 assert.equal(f.gc('KillShell'),'TaskStop');
 assert.equal(f.gc('KillBash'),'TaskStop');
 assert.equal(f.gc('Bash'),'Bash');
 assert.equal(f.gc('__proto__'),'__proto__');
 assert.deepEqual([...f.Cse('TaskStop')].sort(),['KillBash','KillShell']);
});

test('2.1.88 历史规则转义与 2.1.310 有效规则的回环兼容',()=>{
 const f=originalFixture();
 for(const content of ['hello','psycopg2.connect()','C:\\tool\\(x86)\\app','a(b)\\c(d)','','\\(\\)']){
  assert.equal(f.AQo(f.m(content)),content);
  const rule={toolName:'Bash',ruleContent:content};
  if(content)assert.deepEqual(plain(f.Bn(f.dn(rule))),rule);
  else assert.equal(f.dn(rule),'Bash');
 }
});

test('解析旧格式工具规则，并保留后期版本的新增规则类型保护',()=>{
 const f=originalFixture();
 assert.deepEqual(plain(f.Bn('Task')),{toolName:'Agent'});
 assert.deepEqual(plain(f.Bn('KillShell(foo)')),{toolName:'TaskStop',ruleContent:'foo'});
 assert.deepEqual(plain(f.Bn('Bash(*)')),{toolName:'Bash'});
 assert.deepEqual(plain(f.Bn('Bash()')),{toolName:'Bash'});
 assert.deepEqual(plain(f.Bn('Bash(foo)trailing')),{toolName:'Bash(foo)trailing'});
 assert.equal(f.X9('Bash(foo)trailing').kind,'malformed');
});

test('括号扫描遵循旧版的奇偶反斜杠转义判定',()=>{
 const f=originalFixture(),slash=String.fromCharCode(92);
 const input='Bash(echo '+slash+'(hello'+slash+') and (real))';
 assert.equal(f.R(input,'('),input.indexOf('('));
 assert.equal(f.E(input,')'),input.length-1);
 assert.equal(f.R('Bash'+slash+'(','('),-1);
 assert.equal(f.R('Bash'+slash+slash+'(','('),6);
 assert.equal(f.E('Bash'+slash+')',')'),-1);
});

test('旧版已弃用的工具名不强制注入新版映射',()=>{
 const f=originalFixture();
 assert.equal(f.gc('BashOutputTool'),'BashOutputTool');
 assert.equal(f.gc('AgentOutputTool'),'AgentOutputTool');
 assert.equal(f.gc('ReadMcpResource'),'ReadMcpResourceTool');
 assert.deepEqual([...f.Cse('TaskStop')].sort(),['KillBash','KillShell']);
});
