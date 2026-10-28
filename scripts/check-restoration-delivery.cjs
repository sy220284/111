'use strict';
// 111 功能恢复交付门禁：静态变更分类 + 本次测试真实运行；不宣称语义等价。
// PR 检查必须从 BASE 提取本文件执行，不能运行 PR 内可任意改写的验证器。
const fs = require('node:fs');
const cp = require('node:child_process');
const path = require('node:path');
const ts = require('typescript');

const CATEGORIES = new Set(['功能恢复','缺陷修复','独立测试','工具维护','文档维护','治理配置','目录整理']);
const FUNCTIONAL = new Set(['功能恢复','缺陷修复']);
const REQUIRED = ['业务问题','实际代码改动','调用链','验收测试','验证命令','验证结果','验收级别','未验收范围'];
const LEVELS = new Set(['局部行为已验证','业务链已验证','部分完成']);
const PLACEHOLDER = /^(?:待填写|待补充|待测试|TODO|TBD|未执行|无内容|占位|.{0,2}|[-—])$/i;
const SOURCE_EXT = /\.(?:cjs|mjs|js|jsx|ts|tsx)$/;
const TEST_EXT = /\.(?:test|spec)\.(?:cjs|mjs|js|ts)$/;
const PROTECTED_CHECKS = new Set([
  '.github/workflows/source-restoration.yml',
  '.github/workflows/restoration-delivery-gate.yml',
  'scripts/check-source-fingerprint.cjs',
  'scripts/verify-function-ledger.cjs',
  'scripts/check-restored-aliases.cjs',
  'scripts/source-function-index.cjs',
  'tools/analysis/code-graph.cjs',
  'tools/analysis/closure-audit.cjs',
  'tests/check-source.cjs'
]);
const REQUIRED_SCRIPT_KEYS = [
  'test','check:source','check:source-fingerprint','check:function-evidence',
  'audit:functions','check:restored-aliases'
];

function field(body, name) {
  const matches = String(body || '').split(/\r?\n/)
    .map(line => line.match(new RegExp('^\\s*(?:[-*]\\s*)?' + name + '[：:]\\s*(.*)$')))
    .filter(Boolean);
  return matches.length === 1 ? matches[0][1].trim() : '';
}
function businessPath(p) {
  return /^(?:src|runtime|bin)\//.test(p) || /^resources\/(?:bunfs|data)\//.test(p);
}
function testPath(p) { return /^tests\/.+/.test(p) && TEST_EXT.test(p); }
function docPath(p) {
  return /^docs\//.test(p) || /^resources\/docs\//.test(p) ||
    ['README.md','LICENSE','CHANGELOG.md'].includes(p);
}
function categoryPathAllowed(category, p) {
  if (category === '文档维护') return docPath(p);
  if (category === '独立测试') return /^tests\//.test(p) || docPath(p);
  if (category === '工具维护') return /^(?:scripts|tools|tests)\//.test(p) ||
    docPath(p) || ['package.json','package-lock.json'].includes(p) ||
    p === '.github/workflows/dev-toolchain.yml';
  if (category === '目录整理') return p!=='scripts/check-restoration-delivery.cjs' && !/^(?:src|runtime|bin)\//.test(p) &&
    !/^resources\/(?:bunfs|data)\//.test(p) &&
    !PROTECTED_CHECKS.has(p.replace(/^\.github\/workflows\/restoration-delivery-gate\.yml$/,'__allowed_workflow_path__')) &&
    (/^(?:docs|tests|scripts|tools)\//.test(p) || /^\.github\/workflows\//.test(p) ||
    /^resources\/(?:layout|research)\//.test(p) ||
    p==='resources/function-verification-ledger.json' ||
    ['AGENTS.md','README.md','package.json','package-lock.json'].includes(p));
  if (category === '治理配置') return p === 'AGENTS.md' || /^\.github\//.test(p) ||
    /^(?:scripts\/check-restoration-delivery\.cjs|tests\/(?:governance\/)?restoration-delivery-gate\.test\.cjs)$/.test(p) ||
    docPath(p);
  return true;
}
function asText(value) { return Buffer.isBuffer(value) ? value.toString('utf8') : String(value); }
function parsed(code, filename = 'file.js') {
  const kind = /\.tsx$/.test(filename) ? ts.ScriptKind.TSX :
    /\.ts$/.test(filename) ? ts.ScriptKind.TS : ts.ScriptKind.JS;
  const ast = ts.createSourceFile(filename, asText(code), ts.ScriptTarget.Latest, true, kind);
  if (ast.parseDiagnostics.length) throw Error('无法解析源码：' + filename);
  return ast;
}
function declarationId(node) {
  const p = node.parent;
  return !!(p && p.name === node && (
    ts.isVariableDeclaration(p) || ts.isFunctionDeclaration(p) || ts.isFunctionExpression(p) ||
    ts.isClassDeclaration(p) || ts.isClassExpression(p) || ts.isParameter(p) ||
    ts.isBindingElement(p) || ts.isMethodDeclaration(p) || ts.isPropertyDeclaration(p) ||
    ts.isImportSpecifier(p) || ts.isImportClause(p) || ts.isNamespaceImport(p)
  ));
}
function astShape(code, filename = 'file.js') {
  const ast = parsed(code, filename);
  const tokens = [], ids = [];
  function visit(node) {
    tokens.push(node.kind);
    if (ts.isIdentifier(node)) ids.push({text:node.text, declaration:declarationId(node)});
    else if (ts.isStringLiteral(node) || ts.isNumericLiteral(node) ||
      ts.isBigIntLiteral(node) || ts.isRegularExpressionLiteral(node) ||
      ts.isNoSubstitutionTemplateLiteral(node)) tokens.push(node.getText(ast));
    ts.forEachChild(node, visit);
  }
  visit(ast);
  return {shape:tokens.join(','), ids};
}
function classifyCodeChange(change) {
  const {before,after,path:p,status} = change;
  if (status && status.startsWith('D') || after === null || after === undefined) return 'deleted';
  if (before === null || before === undefined) return 'added';
  if (!SOURCE_EXT.test(p)) {
    return Buffer.compare(Buffer.from(before),Buffer.from(after)) === 0 ? 'none' : 'effective';
  }
  if (asText(before) === asText(after)) return 'none';
  const a=astShape(before,p), b=astShape(after,p);
  if (a.shape !== b.shape || a.ids.length !== b.ids.length) return 'effective';
  const rename = new Map();
  let idChanged = false;
  for (let i=0;i<a.ids.length;i++) {
    const old=a.ids[i], now=b.ids[i];
    if (old.text === now.text) continue;
    idChanged = true;
    if (old.declaration && now.declaration) {
      if (rename.has(old.text) && rename.get(old.text)!==now.text) return 'effective';
      rename.set(old.text,now.text);
    }
  }
  for(let i=0;i<a.ids.length;i++) {
    const old=a.ids[i], now=b.ids[i];
    if (old.text!==now.text && rename.get(old.text)!==now.text) return 'effective';
  }
  return idChanged ? 'identifier_only' : 'cosmetic';
}
function registeredTest(code, filename) {
  const ast=parsed(code,filename);
  let registered=false, asserted=false;
  function visit(n) {
    if (ts.isCallExpression(n)) {
      const expr=n.expression;
      const name=ts.isIdentifier(expr)?expr.text:
        ts.isPropertyAccessExpression(expr)?expr.name.text:'';
      if (['test','it','describe'].includes(name) && n.arguments.length > 0) registered=true;
      if (['assert','equal','strictEqual','deepEqual','deepStrictEqual','ok','match','rejects',
        'throws','doesNotThrow','notEqual','notStrictEqual','notDeepEqual','notDeepStrictEqual',
        'expect','fail'].includes(name)) asserted=true;
    }
    ts.forEachChild(n,visit);
  }
  visit(ast);
  return registered && asserted;
}
function checkScriptIntegrity(before, after) {
  const errors=[];
  if(!before || !after) return ['缺少 PR 前后 package.json，无法核对完整回归入口'];
  let oldScripts,newScripts;
  try {
    oldScripts=JSON.parse(asText(before)).scripts;
    newScripts=JSON.parse(asText(after)).scripts;
  }catch{ return ['package.json 解析失败，不能验收功能恢复']; }
  if(!oldScripts || !newScripts)return ['缺少 package.json scripts，不能验收功能恢复'];
  for(const key of REQUIRED_SCRIPT_KEYS) {
    const previous=oldScripts[key], next=newScripts[key];
    if(typeof previous!=='string'||typeof next!=='string') {
      errors.push('必需源码回归命令缺失：'+key);continue;
    }
    if(key==='test') {
      const cmds=previous.split('&&').map(x=>x.trim()).filter(Boolean);
      const actual=next.split('&&').map(x=>x.trim()).filter(Boolean);
      let at=0;
      for(const cmd of actual)if(cmd===cmds[at])at++;
      if(at!==cmds.length)errors.push('npm test 删除或修改了既有回归检查，必须先独立治理变更');
    } else if(previous!==next) {
      errors.push('核心校验命令被功能 PR 修改：'+key);
    }
  }
  return errors;
}
function auditDelivery({title='',body='',changed=[],sourceChanges=[],testChanges=[],packageBefore=null,packageAfter=null}) {
  const errors=[], warnings=[];
  const category=field(body,'交付类别');
  if (!CATEGORIES.has(category)) errors.push('必须声明唯一有效的交付类别');
  const implementation=changed.filter(businessPath);
  const tests=changed.filter(testPath);
  const isFunction=FUNCTIONAL.has(category);
  const statusByPath=new Map([...sourceChanges,...testChanges].map(x=>[x.path,x]));
  if (isFunction) {
    if (/^\s*不计入功能恢复\s*$/m.test(body)) errors.push('恢复类与“不计入功能恢复”声明矛盾');
    if (!implementation.length) errors.push('缺少实际业务源码或运行资源变更');
    if (!tests.length) errors.push('缺少对应测试文件的实际变更');
    for (const name of REQUIRED) {
      const answer=field(body,name);
      if (!answer || PLACEHOLDER.test(answer)) errors.push('缺少有效交付字段：'+name);
    }
    if (field(body,'验收级别') && !LEVELS.has(field(body,'验收级别')))
      errors.push('验收级别只能为：局部行为已验证、业务链已验证、部分完成');
    if (field(body,'实际代码改动') && implementation.length &&
      !implementation.some(p=>field(body,'实际代码改动').includes(p)))
      errors.push('“实际代码改动”必须指明本次变更的真实业务文件');
    if (field(body,'验收测试') && tests.length &&
      !tests.some(p=>field(body,'验收测试').includes(p)))
      errors.push('“验收测试”必须列出本次实际修改的测试文件');
    if (implementation.some(p=>!sourceChanges.some(s=>s.path===p)))
      errors.push('业务源码或资源缺少实际前后差异检查');
    const codes=sourceChanges.filter(s=>implementation.includes(s.path));
    let effective=0;
    for(const s of codes) {
      try {if(['effective','added'].includes(classifyCodeChange(s))) effective++;}
      catch(e) {errors.push(e.message);}
    }
    if (codes.length && !effective)
      errors.push('只有删除/注释/格式/符号改名，缺少实际业务行为变更');
    if (codes.length && codes.every(s=>s.before===null))
      warnings.push('业务修改仅包含新增文件：仍须人工确认它已进入真实消费者调用链');
  } else if (CATEGORIES.has(category)) {
    if (!/^\s*不计入功能恢复\s*$/m.test(body)) errors.push('非恢复类必须独立声明“不计入功能恢复”');
    if (implementation.length) errors.push('非恢复类不得夹带运行态源码或资源变更');
    const unexpected=changed.filter(p=>!categoryPathAllowed(category,p));
    if (unexpected.length) errors.push('交付类别与实际修改范围不符：'+unexpected.join(', '));
    if (category==='独立测试' && !tests.length) errors.push('独立测试类没有实际修改测试');
    if(category==='目录整理'){
      if(!changed.includes('resources/layout/relocations.json')) errors.push('目录整理必须提交实际旧新路径迁移清单');
      if(!tests.length) errors.push('目录整理必须包含实际迁移的回归测试');
      if(!changed.some(p=>p.startsWith('docs/'))) errors.push('目录整理必须包含实际归档文档');
    }
    if (/(?:完成|实现|修复|恢复).*(?:完整业务|全部功能|真实业务恢复|功能等价)/.test(title))
      errors.push('非恢复类标题不得宣称已完成业务恢复或功能等价');
  }
  if (isFunction || category==='独立测试' || category==='目录整理') {
    if(tests.some(p=>!testChanges.some(s=>s.path===p)))
      errors.push('测试文件缺少实际前后差异检查');
    let valid=0;
    for(const s of testChanges.filter(s=>tests.includes(s.path))) {
      try {
        const state=classifyCodeChange(s);
        if (state==='effective' || state==='added') {
          if(registeredTest(s.after,s.path)) valid++;
          else errors.push('测试必须真实注册用例并包含断言：'+s.path);
        }
      } catch(e) {errors.push(e.message);}
    }
    if(tests.length && !valid) errors.push('没有有效新增/修改的可执行断言测试');
  }
  if(isFunction) {
    const touched=changed.filter(p=>PROTECTED_CHECKS.has(p));
    if(touched.length)errors.push('功能 PR 不得修改验收器、指纹与回归门禁：'+touched.join(', '));
    if(packageBefore!==null || packageAfter!==null)
      errors.push(...checkScriptIntegrity(packageBefore,packageAfter));
  }
  return {ok:errors.length===0,category,implementation,tests,errors,warnings};
}
function git(args) {
  return cp.execFileSync('git',args,{maxBuffer:128*1024*1024});
}
function gitAt(revision,p) {
  try {return git(['show',revision+':'+p]);}
  catch(e) {if(e.status!==128)throw e;return null;}
}
function gitDiff(base,head) {
  const parts=git(['diff','--name-status','--find-renames','-z',base,head])
    .toString('utf8').split('\0').filter(Boolean);
  const results=[];
  for (let i=0;i<parts.length;) {
    const status=parts[i++], oldPath=/^[RC]/.test(status)?parts[i++]:null;
    const file=parts[i++];
    if (!file) throw Error('Git diff 文件状态格式异常');
    results.push({path:file,oldPath:oldPath||file,status});
  }
  return results;
}
function inspectGit(event) {
  const base=event.pull_request.base.sha, head=event.pull_request.head.sha;
  if(!/^[a-f0-9]{40}$/.test(base)|| !/^[a-f0-9]{40}$/.test(head))
    throw Error('PR 缺少可验证的完整 Git 提交 SHA');
  if(git(['rev-parse','HEAD']).toString('utf8').trim() !== head)
    throw Error('当前工作区 HEAD 与 PR 预期 SHA 不一致');
  const ancestor=git(['merge-base',base,head]).toString('utf8').trim();
  const diffs=gitDiff(ancestor,head);
  // 重命名跨出运行态/测试目录必须按“原文件已移除”审计，不能借 docs/ 目标路径逃逸。
  const changed=diffs.flatMap(x=>[
    x.path,
    ...(x.oldPath!==x.path && businessPath(x.oldPath) && !businessPath(x.path) ? [x.oldPath] : []),
    ...(x.oldPath!==x.path && testPath(x.oldPath) && !testPath(x.path) ? [x.oldPath] : [])
  ]);
  const sourceChanges=diffs
    .filter(x=>businessPath(x.path)||businessPath(x.oldPath))
    .map(x=>{
      const movedOut=!businessPath(x.path);
      return {...x,path:movedOut?x.oldPath:x.path,status:movedOut?'D':x.status,
        before:gitAt(ancestor,x.oldPath),after:movedOut?null:gitAt(head,x.path)};
    });
  const testChanges=diffs
    .filter(x=>testPath(x.path)||testPath(x.oldPath))
    .map(x=>{
      const movedOut=!testPath(x.path);
      return {...x,path:movedOut?x.oldPath:x.path,status:movedOut?'D':x.status,
        before:gitAt(ancestor,x.oldPath),after:movedOut?null:gitAt(head,x.path)};
    });
  return {diffs,changed,sourceChanges,testChanges,ancestor};
}
// 历史真实源码对照用例必须带完整旧版源码与 703 项生成证据；
// 仅对完全相同的目录搬迁委托给专用的真实历史对照 CI。
function delegatedHistoricalRename(change, category, workflow) {
  if(category!=='目录整理' || change.oldPath!=='tests/v44-real-historical-703.test.cjs' ||
     change.path!=='tests/restoration/v44-real-historical-703.test.cjs' ||
     !/^R[0-9]*$/.test(change.status)) return false;
  if(!workflow.includes('pull_request:') ||
     !workflow.includes('tests/restoration/v44-real-historical-703.test.cjs') ||
     !workflow.includes('node --test tests/restoration/v44-real-historical-703.test.cjs') ||
     !workflow.includes('--expected 703') || !workflow.includes('OLD_SOURCE_ROOT'))
    throw Error('历史 703 项测试必须由完整的专用工作流执行，不得绕过');
  return true;
}
function runChangedTests(report, changes) {
  if(!report.ok) return;
  if (!FUNCTIONAL.has(report.category) && report.category!=='独立测试' && report.category!=='目录整理') return;
  const specializedWorkflow=fs.readFileSync('.github/workflows/v43-historical-real-compare.yml','utf8');
  const selected=changes.testChanges.filter(x=>report.tests.includes(x.path) && x.after!==null);
  const delegated=selected.filter(x=>delegatedHistoricalRename(x,report.category,specializedWorkflow));
  const paths=selected.filter(x=>!delegated.includes(x)).map(x=>x.path);
  for(const file of delegated) console.log('专用历史源码工作流验证：'+file.path);
  if(!paths.length) throw Error('没有实际可运行的变更测试');
  console.log('本次增量测试执行：',paths.join(', '));
  const run=cp.spawnSync(process.execPath,
    ['--no-warnings','--experimental-vm-modules','--test',...paths],{
      stdio:'inherit',timeout:240000,env:{...process.env,CI:'true',NO_COLOR:'1'}
    });
  if(run.error)throw run.error;
  if(run.status!==0)throw Error('本次变更的真实测试执行失败，exit='+run.status);
}
function main() {
  if(!process.env.GITHUB_EVENT_PATH)throw Error('必须由 GitHub pull_request 事件运行');
  const event=JSON.parse(fs.readFileSync(process.env.GITHUB_EVENT_PATH,'utf8'));
  if(!event.pull_request)throw Error('必须收到 pull_request 事件');
  const changes=inspectGit(event);
  const report=auditDelivery({
    title:event.pull_request.title,body:event.pull_request.body,
    changed:changes.changed,
    sourceChanges:changes.sourceChanges,testChanges:changes.testChanges,
    packageBefore:gitAt(changes.ancestor,'package.json'),
    packageAfter:gitAt(event.pull_request.head.sha,'package.json')
  });
  const summary=[
    '## 111 恢复交付门禁（基于 BASE 信任版本）',
    '- 类别：'+(report.category||'未声明'),
    '- PR head：'+event.pull_request.head.sha,
    '- 实际变更文件：'+changes.diffs.length,
    '- 业务源码/资源：'+report.implementation.length,
    '- 修改的测试：'+report.tests.length,
    '- 交付检查：'+(report.ok?'通过':'失败'),
    ...report.errors.map(e=>'- 失败：'+e),
    ...report.warnings.map(w=>'- 人工复查：'+w),
    '',
    '注意：检查只保证差异与可执行测试的最低要求，不证明业务入口可达、语义等价、原版行为一致或线上服务正常。'
  ];
  const line=summary.join('\n')+'\n';
  console.log(line);
  if(process.env.GITHUB_STEP_SUMMARY)fs.appendFileSync(process.env.GITHUB_STEP_SUMMARY,line);
  if(!report.ok)process.exitCode=1;
  else runChangedTests(report,changes);
}
if(require.main===module)main();
module.exports={field,businessPath,testPath,docPath,categoryPathAllowed,astShape,
  classifyCodeChange,registeredTest,checkScriptIntegrity,auditDelivery,gitDiff,inspectGit,runChangedTests,delegatedHistoricalRename};
