'use strict';
const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const os=require('node:os');
const path=require('node:path');
const cp=require('node:child_process');
const {
  field,businessPath,testPath,categoryPathAllowed,classifyCodeChange,registeredTest,
  auditDelivery,gitDiff,inspectGit,checkScriptIntegrity,delegatedHistoricalRename,exactManifestPaths,verifiedStandaloneSourceMoves
}=require('../scripts/check-restoration-delivery.cjs');

const impl='core/chunk-demo.js', suite='tests/model-transport.test.cjs';
const body=[
  '交付类别：功能恢复',
  '业务问题：真实 CLI 模型返回失败',
  '实际代码改动：core/chunk-demo.js 修复调用流程',
  '调用链：bin/claude-310.mjs → core/chunk-demo.js → 模型回包',
  '验收测试：tests/model-transport.test.cjs',
  '验证命令：node --test tests/model-transport.test.cjs',
  '验证结果：改动后真实逻辑正常及取消分支通过',
  '验收级别：局部行为已验证',
  '未验收范围：线上 OAuth 未验证'
].join('\n');
const src={path:impl,before:'export function run(){return 1}',after:'export function run(){return 2}',status:'M'};
const changed=[impl,suite];
const testcase={path:suite,before:"const {test}=require('node:test');",
  after:"const {test}=require('node:test');const assert=require('node:assert/strict');test('return',()=>assert.equal(2,2));",status:'M'};
function verify(options={}) {
  return auditDelivery({
    title:'修复模型传输调用',body,changed,sourceChanges:[src],testChanges:[testcase],...options
  });
}
function rejected(report,fragment) {
  assert.equal(report.ok,false, '应当拒绝但通过：'+JSON.stringify(report));
  assert.ok(report.errors.some(e=>e.includes(fragment)),report.errors.join('\n'));
}

test('实际源码修复+有效断言+完整验收说明通过',()=>{
  const r=verify();assert.equal(r.ok,true,r.errors.join('\n'));
  assert.deepEqual(r.implementation,[impl]);
  assert.deepEqual(r.tests,[suite]);
});
test('纯文档、证据台账冒充功能恢复必失败',()=>{
  rejected(verify({changed:['docs/review.md','resources/function-summary.json'],
    sourceChanges:[],testChanges:[]}), '缺少实际业务源码');
});
test('只补测试不能冒充业务恢复',()=>{
  rejected(verify({changed:[suite],sourceChanges:[]}), '缺少实际业务源码');
});
test('有真实源码但没有测试拒绝',()=>{
  rejected(verify({changed:[impl],testChanges:[]}), '缺少对应测试');
});
test('注释排版、分号差异均不能凑源码恢复',()=>{
  assert.equal(classifyCodeChange({path:impl,before:'function f(){return 1}',after:'/* note */function f() {return 1; }'}),'cosmetic');
  rejected(verify({sourceChanges:[{path:impl,before:'function f(){return 1}',after:'/* note */ function f(){return 1;}',status:'M'}]}),'只有删除/注释/格式');
});
test('纯函数名加可读别名不得当作功能恢复',()=>{
  const renamed={path:impl,before:'function QMn(){return 1;} QMn();',after:'function readHookOrigin(){return 1;} readHookOrigin();',status:'M'};
  assert.equal(classifyCodeChange(renamed),'identifier_only');
  rejected(verify({sourceChanges:[renamed]}),'只有删除/注释/格式');
});
test('修复错误调用目标不能被一概判成符号改名',()=>{
  const actual={path:impl,before:'function f(){return broken();}',after:'function f(){return fixed();}'};
  assert.equal(classifyCodeChange(actual),'effective');
});
test('修改条件变量及函数返回值属于有效源码差异',()=>{
  assert.equal(classifyCodeChange({path:impl,before:'if(oldState){run()}',after:'if(newState){run()}'}),'effective');
  assert.equal(classifyCodeChange(src),'effective');
});
test('新增文件不自动证明进入真实调用链，仅提供人工复核警告',()=>{
  const r=verify({sourceChanges:[{path:impl,before:null,after:'export const fixed=1',status:'A'}]});
  assert.equal(r.ok,true,r.errors.join('\n'));
  assert.ok(r.warnings.some(x=>x.includes('真实消费者调用链')));
});
test('仅删除业务源码不能冒充修复',()=>{
  rejected(verify({sourceChanges:[{path:impl,before:'function f(){}',after:null,status:'D'}]}),'只有删除/注释/格式');
  assert.equal(classifyCodeChange({path:impl,before:'function f(){}',after:null,status:'D'}),'deleted');
});
test('资源二进制内容变化可识别，只有删除则不可',()=>{
  assert.equal(classifyCodeChange({path:'resources/bunfs/native.node',before:Buffer.from([1,2]),after:Buffer.from([1,3]),status:'M'}),'effective');
  assert.equal(classifyCodeChange({path:'resources/data/asset.bin',before:null,after:Buffer.from([1]),status:'A'}),'added');
});
test('仅有零断言测试不能通过',()=>{
  rejected(verify({testChanges:[{path:suite,before:'',after:"const {test}=require('node:test');test('noop',()=>{});",status:'M'}]}),
    '测试必须真实注册用例并包含断言');
});
test('只加注释/重复已有测试不能冒充本次新增行为验证',()=>{
  const keep="const {test}=require('node:test');const assert=require('node:assert');test('ok',()=>assert.ok(true));";
  rejected(verify({testChanges:[{path:suite,before:keep,after:'/* 增量 */'+keep,status:'M'}]}),'没有有效新增/修改的可执行断言');
});
test('测试文件删除不等于新增或修复测试',()=>{
  rejected(verify({testChanges:[{path:suite,before:testcase.before,after:null,status:'D'}]}),'没有有效新增/修改的可执行断言');
});
test('测试文件未检查实际字节差异不得跳过',()=>{
  rejected(verify({testChanges:[]}), '测试文件缺少实际前后差异检查');
});
test('运行源码未检查实际差异不得跳过',()=>{
  rejected(verify({sourceChanges:[]}), '业务源码或资源缺少实际前后差异检查');
});
test('验证说明不能只是伪造的文件路径',()=>{
  rejected(verify({body:body.replace('验收测试：tests/model-transport.test.cjs','验收测试：tests/unrelated.test.cjs')}),
    '“验收测试”必须列出');
  rejected(verify({body:body.replace('实际代码改动：core/chunk-demo.js','实际代码改动：core/other.js')}),
    '“实际代码改动”必须指明');
});
test('验收字段必须有效、明确且唯一',()=>{
  rejected(verify({body:body.replace('验证结果：改动后真实逻辑正常及取消分支通过','验证结果：待填写')}),'验证结果');
  rejected(verify({body:body.replace('验收级别：局部行为已验证','验收级别：原版全功能等价')}),'验收级别只能');
  rejected(verify({body:body+'\n交付类别：功能恢复'}),'必须声明唯一');
  assert.equal(field('业务问题：第一\n业务问题：第二','业务问题'),'');
});
test('文档维护不能夹带脚本变更，不仅仅拦截 src/',()=>{
  const r=auditDelivery({title:'整理文档',body:'交付类别：文档维护\n不计入功能恢复',
    changed:['docs/plan.md','scripts/verify-function-ledger.cjs']});
  rejected(r,'交付类别与实际修改范围不符');
});
test('测试工作不能谎称纯文档',()=>{
  rejected(auditDelivery({title:'整理文档',body:'交付类别：文档维护\n不计入功能恢复',
    changed:['tests/protocol.test.cjs']}),'交付类别与实际修改范围不符');
});
test('治理配置仅允许仓库约定治理文件',()=>{
  const accepted=auditDelivery({title:'修正门禁',body:'交付类别：治理配置\n不计入功能恢复',
    changed:['AGENTS.md','.github/workflows/restoration-delivery-gate.yml',
      'scripts/check-restoration-delivery.cjs','tests/restoration-delivery-gate.test.cjs']});
  assert.equal(accepted.ok,true,accepted.errors.join('\n'));
  rejected(auditDelivery({title:'治理',body:'交付类别：治理配置\n不计入功能恢复',
    changed:['scripts/restore-plugin-testing-kit.cjs']}),'交付类别与实际修改范围不符');
});
test('独立测试允许提交但不可冒称已恢复功能',()=>{
  const ok=auditDelivery({title:'补充独立真实测试',body:'交付类别：独立测试\n不计入功能恢复',
    changed:[suite],testChanges:[testcase]});
  assert.equal(ok.ok,true,ok.errors.join('\n'));
  rejected(auditDelivery({title:'新增测试',body:'交付类别：独立测试\n不计入功能恢复',
    changed:['docs/report.md']}),'独立测试类没有实际修改测试');
});
test('非恢复类修改运行资源必须拒绝',()=>{
  rejected(auditDelivery({title:'文档维护',body:'交付类别：文档维护\n不计入功能恢复',
    changed:['resources/bunfs/asset.bin']}),'非恢复类不得夹带运行态');
});
test('功能恢复不得同时弱化主源码工作流',()=>{
  rejected(verify({changed:[...changed,'.github/workflows/source-restoration.yml']}),
    '功能 PR 不得修改验收器');
});
test('目录分类匹配 111 的真实结构',()=>{
  assert.equal(businessPath('plugin-testing-kit/collector.js'),true);
  assert.equal(businessPath('bin/claude-310.mjs'),true);
  assert.equal(businessPath('runtime/assets.mjs'),true);
  assert.equal(businessPath('resources/bunfs/native.node'),true);
  assert.equal(businessPath('resources/function-verification-ledger.json'),false);
  assert.equal(testPath('tests/query-loop.test.cjs'),true);
  assert.equal(testPath('scripts/batch-restoration-candidates.cjs'),false);
  assert.equal(categoryPathAllowed('文档维护','scripts/match.cjs'),false);
});
test('重命名源码内容相同仍不能视为业务修复',()=>{
  assert.equal(classifyCodeChange({path:'new.js',status:'R100',
    before:'export function f(){return 1}',after:'export function f(){return 1}'}),'none');
});
test('真实测试注册与断言识别',()=>{
  assert.equal(registeredTest(testcase.after,suite),true);
  assert.equal(registeredTest("test('noop',()=>{})",suite),false);
  assert.equal(registeredTest("const assert=require('node:assert');assert.ok(true)",suite),false);
});
test('真实 git rename/delete/create 名称和状态不丢失',()=>{
  const dir=fs.mkdtempSync(path.join(os.tmpdir(),'111-gate-git-')),cwd=process.cwd();
  function git(...args){return cp.execFileSync('git',args,{cwd:dir,encoding:'utf8'}).trim()}
  try {
    git('init','-q');
    git('config','user.email','gate@example.com');git('config','user.name','Gate Test');
    fs.writeFileSync(path.join(dir,'a.js'),'export const a=1\n');
    fs.writeFileSync(path.join(dir,'old.js'),'export const old=1\n');
    git('add','.');git('commit','-qm','初始');
    const start=git('rev-parse','HEAD');
    fs.renameSync(path.join(dir,'a.js'),path.join(dir,'b.js'));
    fs.rmSync(path.join(dir,'old.js'));
    fs.writeFileSync(path.join(dir,'added.js'),'export const added=1\n');
    git('add','-A');git('commit','-qm','变化');
    const end=git('rev-parse','HEAD');
    process.chdir(dir);
    const diffs=gitDiff(start,end);
    assert.ok(diffs.some(x=>x.status.startsWith('R')&&x.oldPath==='a.js'&&x.path==='b.js'),JSON.stringify(diffs));
    assert.ok(diffs.some(x=>x.status==='D'&&x.path==='old.js'));
    assert.ok(diffs.some(x=>x.status==='A'&&x.path==='added.js'));
  } finally {process.chdir(cwd);fs.rmSync(dir,{recursive:true,force:true});}
});

test('源码重命名到 docs 仍识别为源码删除，不能用文档类别绕过',()=>{
  const dir=fs.mkdtempSync(path.join(os.tmpdir(),'111-gate-rename-')),cwd=process.cwd();
  function g(...args){return cp.execFileSync('git',args,{cwd:dir,encoding:'utf8'}).trim()}
  try {
    g('init','-q');g('config','user.email','gate@example.com');g('config','user.name','Gate Test');
    fs.mkdirSync(path.join(dir,'src'));fs.mkdirSync(path.join(dir,'docs'));
    fs.writeFileSync(path.join(dir,'src','active.js'),'export function active(){return 1}\n');
    g('add','.');g('commit','-qm','初始');const base=g('rev-parse','HEAD');
    fs.renameSync(path.join(dir,'src','active.js'),path.join(dir,'docs','active.js'));
    g('add','-A');g('commit','-qm','改名');const head=g('rev-parse','HEAD');
    process.chdir(dir);
    const actual=inspectGit({pull_request:{base:{sha:base},head:{sha:head}}});
    assert.ok(actual.changed.includes('src/active.js'));
    assert.ok(actual.changed.includes('docs/active.js'));
    const r=auditDelivery({title:'文档整理',body:'交付类别：文档维护\n不计入功能恢复',
      changed:actual.changed,sourceChanges:actual.sourceChanges,testChanges:actual.testChanges});
    rejected(r,'非恢复类不得夹带运行态源码');
  }finally{process.chdir(cwd);fs.rmSync(dir,{recursive:true,force:true});}
});

test('测试文件重命名成文档不得逃避有效测试检查',()=>{
  const dir=fs.mkdtempSync(path.join(os.tmpdir(),'111-gate-test-rename-')),cwd=process.cwd();
  function g(...args){return cp.execFileSync('git',args,{cwd:dir,encoding:'utf8'}).trim()}
  try {
    g('init','-q');g('config','user.email','gate@example.com');g('config','user.name','Gate Test');
    fs.mkdirSync(path.join(dir,'tests'));fs.mkdirSync(path.join(dir,'docs'));
    fs.writeFileSync(path.join(dir,'tests','old.test.cjs'),"test('active',()=>assert.ok(true))\n");
    g('add','.');g('commit','-qm','初始');const base=g('rev-parse','HEAD');
    fs.renameSync(path.join(dir,'tests','old.test.cjs'),path.join(dir,'docs','old.test.cjs'));
    g('add','-A');g('commit','-qm','改名');const head=g('rev-parse','HEAD');
    process.chdir(dir);
    const actual=inspectGit({pull_request:{base:{sha:base},head:{sha:head}}});
    assert.ok(actual.changed.includes('tests/old.test.cjs'));
    const r=auditDelivery({title:'整理文档',body:'交付类别：文档维护\n不计入功能恢复',
      changed:actual.changed,sourceChanges:actual.sourceChanges,testChanges:actual.testChanges});
    rejected(r,'交付类别与实际修改范围不符');
  }finally{process.chdir(cwd);fs.rmSync(dir,{recursive:true,force:true});}
});

test('功能提交禁止同时改掉源码指纹、函数证据、调用图校验器',()=>{
  rejected(verify({changed:[...changed,'scripts/check-source-fingerprint.cjs']}),'不得修改验收器');
  rejected(verify({changed:[...changed,'scripts/verify-function-ledger.cjs']}),'不得修改验收器');
  rejected(verify({changed:[...changed,'tools/analysis/code-graph.cjs']}),'不得修改验收器');
});
test('不能把 npm test 中的旧回归检查偷偷删掉',()=>{
  const before=JSON.stringify({scripts:{
    test:'npm run check:source && npm run check:source-fingerprint && npm run check:closure',
    'check:source':'node tests/check-source.cjs',
    'check:source-fingerprint':'node scripts/check-source-fingerprint.cjs --check',
    'check:function-evidence':'node scripts/verify-function-ledger.cjs',
    'audit:functions':'node scripts/source-function-index.cjs --check',
    'check:restored-aliases':'node scripts/check-restored-aliases.cjs'
  }});
  const oldScripts=JSON.parse(before).scripts;
  const base=verify({packageBefore:before,packageAfter:before});
  assert.equal(base.ok,true,base.errors.join('\n'));
  const changedTest=JSON.stringify({scripts:{...oldScripts,test:'npm run check:source && npm run check:closure'}});
  rejected(verify({packageBefore:before,packageAfter:changedTest}),'npm test 删除或修改了既有回归检查');
  const changedCheck=JSON.stringify({scripts:{...oldScripts,'check:source-fingerprint':'echo passed'}});
  rejected(verify({packageBefore:before,packageAfter:changedCheck}),'核心校验命令被功能 PR 修改');
  const added=JSON.stringify({scripts:{...oldScripts,test:oldScripts.test+' && node --test tests/new-feature.test.cjs'}});
  assert.deepEqual(checkScriptIntegrity(before,added),[]);
});
test('恢复类不能同时声明不计入功能恢复',()=>{
  rejected(verify({body:body+'\n不计入功能恢复'}),'恢复类与');
});


test('目录整理允许文件与测试的真实归档，但必须保留来源及可运行校验',()=>{
 const body='交付类别：目录整理\n不计入功能恢复';
 const files=['docs/conventions/规范.md','tests/mcp/transport.test.cjs','resources/layout/relocations.json','resources/research/v37-supplemental-semantics.json','scripts/audit-v37-nonmain-inventory.cjs','.github/workflows/restoration-delivery-gate.yml','AGENTS.md','package.json','resources/function-verification-ledger.json'];
 const r=auditDelivery({title:'按业务整理目录',body,changed:files,
   testChanges:[{path:'tests/mcp/transport.test.cjs',before:null,after:"const {test}=require('node:test');const assert=require('node:assert');test('ok',()=>assert.equal(1,1));",status:'A'}]});
 assert.equal(r.ok,true,r.errors.join('\n'));
 assert.equal(categoryPathAllowed('目录整理','chunk-abc.js'),false);
 assert.equal(categoryPathAllowed('目录整理','resources/bunfs/raw.node'),false);
 assert.equal(categoryPathAllowed('目录整理','scripts/check-restoration-delivery.cjs'),false);
});
test('目录整理拒绝没有迁移证据、测试或混入运行时文件',()=>{
 const body='交付类别：目录整理\n不计入功能恢复';
 for(const changed of [
  ['docs/name.md','tests/model/m.test.cjs'],
  ['docs/name.md','resources/layout/relocations.json'],
  ['docs/name.md','tests/model/m.test.cjs','resources/layout/relocations.json','chunk-x.js']]){
  const result=auditDelivery({title:'整理',body,changed});
  assert.equal(result.ok,false);
 }
});


test('治理配置保留原路径与新目录的门禁回归测试文件白名单',()=>{
  assert.equal(categoryPathAllowed('治理配置','tests/restoration-delivery-gate.test.cjs'),true);
  assert.equal(categoryPathAllowed('治理配置','tests/governance/restoration-delivery-gate.test.cjs'),true);
  assert.equal(categoryPathAllowed('治理配置','tests/agents/agent-resume-errors.test.cjs'),false);
});

test('真实历史703用例仅迁移时委托专用完整工作流，其他场景不跳过',()=>{
  const previous='tests/v44-real-historical-703.test.cjs';
  const current='tests/restoration/v44-real-historical-703.test.cjs';
  const workflow='pull_request:\\n- tests/restoration/v44-real-historical-703.test.cjs\\nnode --test tests/restoration/v44-real-historical-703.test.cjs\\n--expected 703\\nOLD_SOURCE_ROOT';
  const change={oldPath:previous,path:current,status:'R100'};
  assert.equal(delegatedHistoricalRename(change,'目录整理',workflow),true);
  assert.equal(delegatedHistoricalRename(change,'独立测试',workflow),false);
  assert.equal(delegatedHistoricalRename({...change,status:'M'},'目录整理',workflow),false);
  assert.equal(delegatedHistoricalRename({...change,oldPath:'tests/unrelated.test.cjs'},'目录整理',workflow),false);
  assert.throws(()=>delegatedHistoricalRename(change,'目录整理','pull_request:\\nnode --test tests/restoration/v44-real-historical-703.test.cjs'),/不得绕过/);
});


test('目录整理对经过Git原始字节及清单验证的独立源码重命名单独认可',()=>{
 const old='src/embedded/runtime/state-library.js',current='embedded/runtime/state-library.js';
 const base={title:'独立源码归档',body:'交付类别：目录整理\n不计入功能恢复',
  changed:[old,current,'resources/layout/relocations.json','docs/architecture/structure.md','tests/runtime/a.test.cjs'],
  testChanges:[{path:'tests/runtime/a.test.cjs',before:null,after:"const {test}=require('node:test');const assert=require('node:assert');test('ok',()=>assert.ok(true));",status:'A'}]};
 const before=auditDelivery(base);
 assert.equal(before.ok,false);
 assert.ok(before.errors.some(x=>x.includes('运行态源码')));
 const accepted=auditDelivery({...base,verifiedRelocations:[{old,current}]});
 assert.equal(accepted.ok,true,accepted.errors.join('\\n'));
 const modified=auditDelivery({...base,changed:[...base.changed,'core/chunk-target.js'],verifiedRelocations:[{old,current}]});
 assert.equal(modified.ok,false);
 assert.ok(modified.errors.some(x=>x.includes('运行态源码')));
});


test('目录整理资源清单只接受实际文件完整搬迁后的路径变更',()=>{
 const records=new Map([['resources/tools/design-sync/a.mjs','tools/bundled/design-sync/a.mjs'],['src/embedded/runtime/state-library.js','embedded/runtime/state-library.js']]);
 const asset={version:'1',entries:{a:{path:'resources/tools/design-sync/a.mjs',decodedSha256:'stable'}}};
 const assetNew={version:'1',entries:{a:{path:'tools/bundled/design-sync/a.mjs',decodedSha256:'stable'}}};
 assert.equal(exactManifestPaths(asset,assetNew,records,'asset'),true);
 assert.equal(exactManifestPaths(asset,{...assetNew,entries:{a:{...assetNew.entries.a,decodedSha256:'mutated'}}},records,'asset'),false);
 assert.equal(exactManifestPaths(asset,assetNew,new Map(),'asset'),false);
 const embedded={version:'1',entries:[{old:'original.mjs',path:'src/embedded/runtime/state-library.js',gitBlobSha1:'unchanged'}]};
 const embeddedNew={version:'1',entries:[{...embedded.entries[0],path:'embedded/runtime/state-library.js'}]};
 assert.equal(exactManifestPaths(embedded,embeddedNew,records,'embedded'),true);
 assert.equal(exactManifestPaths(embedded,{...embeddedNew,entries:[{...embeddedNew.entries[0],gitBlobSha1:'changed'}]},records,'embedded'),false);
 const context={title:'目录整理',body:['交付类别：目录整理','不计入功能恢复'].join(String.fromCharCode(10)),
  changed:['docs/architecture/name.md','tests/mcp/m.test.cjs','resources/layout/relocations.json','resources/asset-layout.json'],
  testChanges:[{path:'tests/mcp/m.test.cjs',before:null,after:"const {test}=require('node:test');const assert=require('node:assert');test('works',()=>assert.ok(1));",status:'A'}]};
 assert.equal(auditDelivery(context).ok,false);
 assert.equal(auditDelivery({...context,verifiedMetadata:['resources/asset-layout.json']}).ok,true);
});


test('目录整理独立工具源码只允许Git R100无损搬迁，不改变内嵌资源的业务语义',()=>{
 const old='resources/tools/design-sync/lib/common.mjs',current='tools/bundled/design-sync/lib/common.mjs';
 const context={title:'源码整理',body:'交付类别：目录整理\n不计入功能恢复',
   changed:[old,current,'docs/architecture/README.md','resources/layout/relocations.json','tests/layout-check.test.cjs'],
   testChanges:[{path:'tests/layout-check.test.cjs',status:'A',before:null,
     after:"const {test}=require('node:test');const assert=require('node:assert');test('file',()=>assert.ok(true));"}]};
 assert.equal(auditDelivery(context).ok,false);
 const accepted=auditDelivery({...context,verifiedRelocations:[{old,current}]});
 assert.equal(accepted.ok,true,accepted.errors.join('\n'));
 assert.equal(auditDelivery({...context,changed:[...context.changed,'resources/bunfs/native.node'],verifiedRelocations:[{old,current}]}).ok,false);
});

test('原始工具来源JSON只能R100归入 resources/layout，不允许修改字节或借机放行业务源码',()=>{
 const old='resources/tools/claude-test/source-layout.json';
 const current='resources/layout/claude-test-source-layout.json';
 const base={title:'工具来源布局归档',body:['交付类别：目录整理','不计入功能恢复'].join(String.fromCharCode(10)),
 changed:[old,current,'docs/README.md','resources/layout/relocations.json','tests/repository-layout.test.cjs'],
 testChanges:[{path:'tests/repository-layout.test.cjs',status:'A',before:null,
 after:"const {test}=require('node:test');const assert=require('node:assert');test('check',()=>assert.ok(true));"}]};
 assert.equal(auditDelivery(base).ok,false);
 assert.equal(auditDelivery({...base,verifiedRelocations:[{old,current}]}).ok,true);
 assert.equal(auditDelivery({...base,verifiedRelocations:[{old,current}],changed:[...base.changed,'chunk-1234.js']}).ok,false);
});


test('core 中的实际业务源码不得绕开源码变更门禁',()=>{
 assert.equal(businessPath('core/chunk-1234abcd.js'),true);
 assert.equal(categoryPathAllowed('目录整理','core/chunk-1234abcd.js'),false);
});

test('整合验收必须同时核实真实来源映射、业务修改和全部原有测试入口',()=>{
  const body=[
    '交付类别：整合验收',
    '业务问题：统一源码位置并保留主程序功能完整性',
    '实际代码改动：core/chunk-kj2kfe1q.js 与MCP等当前源码',
    '调用链：bin/claude-310.mjs → core/cli.js → 真实MCP/Daemon子流程',
    '验收测试：tests/mcp-rpc-cancellation.test.cjs 与 tests/cli-live-daemon-worker-recovery.test.cjs',
    '验证命令：npm test与真实Node/Bun进程级差分',
    '验证结果：已执行定向行为回归并保存运行结果',
    '验收级别：部分完成',
    '未验收范围：原版ELF与线上凭据协议的完整等价'
  ].join('\n');
  const testFile='tests/mcp-rpc-cancellation.test.cjs';
  const before=JSON.stringify({scripts:{
    test:Array.from({length:65},(_,i)=>'npm run check:step-'+i).join(' && '),
    'check:source':'node src-check.js','check:source-fingerprint':'node hash.js',
    'check:function-evidence':'node evidence.js','audit:functions':'node ast.js',
    'check:restored-aliases':'node aliases.js'
  }});
  const actual={changed:['src/chunk-1.js','core/chunk-1.js','resources/layout/root-source-relocations.json',
      'resources/layout/relocations.json','resources/asset-layout.json','resources/embedded-source-layout.json',
      testFile,'tests/cli-live-daemon-worker-recovery.test.cjs','docs/integration.md'],
    body,
    testChanges:[{path:testFile,status:'A',before:null,
      after:"const {test}=require('node:test');const assert=require('node:assert/strict');test('real',()=>assert.equal(1,1))"},
     {path:'tests/cli-live-daemon-worker-recovery.test.cjs',status:'A',before:null,
      after:"const {test}=require('node:test');const assert=require('node:assert/strict');test('daemon',()=>assert.equal(2,2))"}],
    verifiedRootSources:Array.from({length:2554},(_,i)=>({old:'src/chunk-'+i+'.js',current:'core/chunk-'+i+'.js',changedFromBase:i===0,importAdjusted:false})),
    verifiedRelocations:Array.from({length:318},(_,i)=>({old:'src/embedded/file'+i,current:'embedded/file'+i})),
    verifiedMetadata:['resources/asset-layout.json','resources/embedded-source-layout.json'],
    packageBefore:before,packageAfter:before};
  const accepted=auditDelivery(actual);
  assert.equal(accepted.ok,true,accepted.errors.join('\n'));
  const noProof=auditDelivery({...actual,verifiedRootSources:[]});
  assert.equal(noProof.ok,false);
  assert.ok(noProof.errors.some(x=>x.includes('2554项可信Git')));
  const dropped=auditDelivery({...actual,packageAfter:JSON.stringify({scripts:{
    ...JSON.parse(before).scripts,test:'npm run check:step-0'
  }})});
  assert.equal(dropped.ok,false);
  assert.ok(dropped.errors.some(x=>x.includes('移除任何原有npm test')));
  const fakeLevel=auditDelivery({...actual,body:body.replace('验收级别：部分完成','验收级别：业务链已验证')});
  assert.equal(fakeLevel.ok,false);
});

test('严格按Git文件树原始字节核验搬迁，不依赖易漏报的R100检测',()=>{
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'111-tree-')),cwd=process.cwd();
 const git=(...args)=>cp.execFileSync('git',args,{cwd:dir,encoding:'utf8'}).trim();
 try{
  process.chdir(dir);
  git('init','-q');git('config','user.email','tree@example.com');git('config','user.name','Tree Test');
  fs.mkdirSync(path.join(dir,'src/embedded'),{recursive:true});
  const old='src/embedded/probe.mjs',current='embedded/probe.mjs';
  fs.writeFileSync(path.join(dir,old),'export const actual=1;\n');
  git('add','-A');git('commit','-qm','original');
  const ancestor=git('rev-parse','HEAD'),originalGitBlob=git('rev-parse','HEAD:'+old);
  fs.mkdirSync(path.join(dir,'embedded'));
  fs.renameSync(path.join(dir,old),path.join(dir,current));
  fs.mkdirSync(path.join(dir,'resources/layout'),{recursive:true});
  fs.writeFileSync(path.join(dir,'resources/layout/relocations.json'),
   JSON.stringify({files:[{old,current,kind:'embedded-source',originalGitBlob}]}));
  git('add','-A');git('commit','-qm','relocated');
  assert.deepEqual(verifiedStandaloneSourceMoves({ancestor,diffs:[]},git('rev-parse','HEAD')),
    [{old,current}],'没有R100时仍能核对可信迁移');
  fs.writeFileSync(path.join(dir,current),'export const actual=2;\n');
  git('add','-A');git('commit','-qm','modified');
  assert.deepEqual(verifiedStandaloneSourceMoves({ancestor,diffs:[]},git('rev-parse','HEAD')),[],
    '真实字节改变必须拒绝视为纯迁移');
 }finally{process.chdir(cwd);fs.rmSync(dir,{recursive:true,force:true})}
});

test('资源映射验收同时核对主源码文件和消费者，不允许更改哈希等其他字段',()=>{
 const old={entries:{chart:{path:'src/chart.js',literalConsumers:['src/chunk-a.js'],sha256:'frozen'}}};
 const changed={entries:{chart:{path:'core/chart.js',literalConsumers:['core/chunk-a.js'],sha256:'frozen'}}};
 const originals=new Map([['src/chart.js','core/chart.js'],['src/chunk-a.js','core/chunk-a.js']]);
 assert.equal(exactManifestPaths(old,changed,new Map(),'asset',originals),true);
 assert.equal(exactManifestPaths(old,{entries:{chart:{...changed.entries.chart,sha256:'tampered'}}},
  new Map(),'asset',originals),false);
});
