'use strict';
const {test}=require('node:test');
const a=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),path=require('node:path'),ts=require('typescript');
const file=path.resolve(__dirname,'../core/chunk-t2adf9g4.js');
const ast=ts.createSourceFile(file,fs.readFileSync(file,'utf8'),ts.ScriptTarget.Latest,true,ts.ScriptKind.JS);
const names={st:'runDaemonGroupExclusively',Vn:'scheduleDaemonReconciliation',Rt:'isExpectedGitHead',vt:'runGitWorktreeCommand',wn:'formatCommitCountOverflow',jr:'shortenWorktreePathLabel',Tn:'classifyWorktreeOwnership',xe:'hasSameCanonicalPath',ft:'normalizeWorktreePath',En:'isPathWithinWorktreeRoot',On:'checkJobStateFilePresence'};
function make(other={}){
 const code=ast.statements.filter(n=>ts.isFunctionDeclaration(n)&&Object.values(names).includes(n.name?.text)).map(n=>n.getText(ast));
 a.equal(code.length,Object.keys(names).length);
 for(const [oldName,name] of Object.entries(names))code.push('const '+oldName+'='+name+';');
 const dep={en:{run:async(k,fn)=>fn()},zn:x=>x*2,_q:s=>/^[0-9a-f]{4}$/.test(s),
  ct:async(command,args,opts)=>({code:0,stdout:'dead\n',command,args,opts}),It:()=>'/usr/bin/git',_n:['--no-pager'],Rs:()=>({GIT_TERMINAL_PROMPT:'0'}),vr:2048,yn:5,Rn:6,et:String,
  In:v=>String(v).trim(),sp:v=>v.toLowerCase(),Pr:path.dirname,ml:v=>typeof v==='string'&&v.startsWith('/'),mt:path.isAbsolute,Yn:()=>false,gl:()=>false,Mr:path.relative,
  Pt:(e,n)=>e+'/'+n,Ar:async()=>undefined,G:e=>e.code==='ENOENT',...other};
 return vm.runInNewContext(code.join('\n')+';({'+[...Object.keys(names),...Object.values(names)].join(',')+'})',dep);
}
test('十一组短符号均指向同一具名函数',()=>{const f=make();for(const [oldName,name] of Object.entries(names))a.equal(f[oldName],f[name]);});
test('Daemon 分组错误被隔离且协调请求仍可启动',async()=>{
 const events=[],f=make({en:{run:async(k,fn)=>{events.push(k);return fn()}}});
 a.equal(await f.st('a',async()=>123),123);a.equal(await f.st('a',async()=>{throw Error('bad')}),undefined);
 a.equal(await f.Vn(4),8);a.deepEqual(events,['g:a','g:a','reconcile']);
});
test('Git 命令、环境、预期 SHA 及失败返回值保持原版契约',async()=>{
 const f=make();const r=await f.vt('/repo',['status']);a.equal(r.command,'/usr/bin/git');a.deepEqual(Array.from(r.args),['--no-pager','status']);
 a.equal(r.opts.cwd,'/repo');a.equal(await f.Rt('/repo','dead'),true);a.equal(await f.Rt('/repo','0000'),false);a.equal(await f.Rt('/repo','invalid'),false);
});
test('提交数量溢出标记和显示路径截断按阈值执行',()=>{const f=make();a.equal(f.wn({count:4}),'');a.equal(f.wn({count:5}),'+');a.equal(f.jr('123456789'),'123456');});
test('规范路径比较区分等价、不同和越界路径',()=>{
 const f=make();a.equal(f.ft(' /BASE/A '),'/base/a');a.equal(f.xe('/BASE/a','/base/A'),true);
 a.equal(f.xe('/base/a','/base/b'),false);a.equal(f.En('/base/sub/a','/base'),true);a.equal(f.En('/base2/sub','/base'),false);
});
test('归属判断不把未确权路径当成不存在',()=>{
 const f=make();a.equal(f.Tn('/base/sub','/base/sub','/other'),'match');
 a.equal(f.Tn('/else/sub','/base','/other'),'unverifiable');
 a.equal(f.Tn('relative','/base','/other'),'no_match');
 a.equal(make({Yn:()=>true}).Tn('/else/sub','/base','/other'),'no_match');
});
test('state.json 查询区分存在、不存在、不可读',async()=>{
 a.equal(await make().On('/job'),'present');
 a.equal(await make({Ar:async()=>{throw Object.assign(Error('x'),{code:'ENOENT'})}}).On('/job'),'missing');
 a.equal(await make({Ar:async()=>{throw Error('permission')}}).On('/job'),'unreadable');
});
