'use strict';
const {test}=require('node:test'),a=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),ts=require('typescript'),vm=require('node:vm');
const p=path.resolve(__dirname,'../../src/chunk-bwt94x0y.js'),ast=ts.createSourceFile(p,fs.readFileSync(p,'utf8'),ts.ScriptTarget.Latest,true,ts.ScriptKind.JS);
const entries={F6:'isFrameLiveAdoptable',xwe:'collectAdoptableFrameSlugs',Vxt:'countAlreadyAdoptedWork',qxt:'countOutstandingAdoptWork',Q:'attachAdoptionErrorCode',ke:'parseAdoptionJsonOrNull',Cno:'formatAdoptionProcessIdentity',je:'resolveAdoptedAgentParent'};
function fixture(extra={}){
 const required=[...Object.values(entries),'isTaskMarkedAdoptable','isCronAdoptable'];
 const source=ast.statements.filter(n=>ts.isFunctionDeclaration(n)&&required.includes(n.name?.text)).map(n=>n.getText(ast));
 a.equal(source.length,required.length);
 for(const [oldName,name] of Object.entries(entries))source.push('const '+oldName+'='+name+';');
 source.push('const u6e=isTaskMarkedAdoptable;const lUe=isCronAdoptable;');
 const deps={SIe:()=>true,ime:x=>x.kind==='agent',Bct:()=>['boot'],sBe:()=>['supervisor'],FX:()=>new Map(),F:(xs,fn)=>xs.filter(fn).length,Om:()=>[],JB:()=>({count:3}),te:JSON.parse,Plt:(p,t,s)=>[p,t,s].join('|'),Tn:x=>x?.kind==='agent',Br:x=>'parent:'+x,tt:()=> 'root',...extra};
 return vm.runInNewContext(source.join('\n')+';({'+[...Object.keys(entries),...Object.values(entries)].join(',')+'})',deps);
}
test('原有八个短符号均保留可调用身份',()=>{
 const f=fixture();for(const [oldName,name] of Object.entries(entries))a.equal(f[oldName],f[name],oldName);
});
test('Frame 接管需要功能启用、合法 Agent 及 frameLive',()=>{
 const x={kind:'agent',frameLive:{slug:'x'}};
 a.equal(fixture().F6(x),true);
 a.equal(fixture({SIe:()=>false}).F6(x),false);
 a.equal(fixture().F6({kind:'shell',frameLive:{slug:'x'}}),false);
 a.equal(fixture().F6({kind:'agent'}),false);
});
test('Frame 收集去重，并汇合监管器和启动记录',()=>{
 const f=fixture(),result=f.xwe({a:{kind:'agent',frameLive:{slug:'x'}},b:{kind:'agent',frameLive:{slug:'x'}}});
 a.deepEqual([...result].sort(),['boot','supervisor','x']);
});
test('已接管与待接管活动的计数保留任务、Cron 和 Frame 规则',()=>{
 const f=fixture({Bct:()=>[],sBe:()=>[],JB:()=>({count:4}),Om:()=>[{agentId:undefined}]});
 const rows={a:{kind:'agent',id:'a',frameLive:{slug:'x'}},b:{kind:'shell',id:'b'}};
 a.equal(f.Vxt(rows,new Map([['a',true]])),3);
 a.equal(f.qxt(rows,new Map([['a',true]])),1);
});
test('错误码标记保留对象引用，非法 JSON 安全返回 null',()=>{
 const f=fixture(),err=Error('failure');
 a.equal(f.Q(err,'EIO'),err);a.equal(err.code,'EIO');
 a.deepEqual(JSON.parse(JSON.stringify(f.ke('{"ok":true}'))),{ok:true});
 a.equal(f.ke('{invalid}'),null);
});
test('进程身份传递三项字段，Agent 缺少父引用时回落根句柄',()=>{
 const f=fixture();a.equal(f.Cno({pid:3,startTimeTicks:4,procStart:5}),'3|4|5');
 a.equal(f.je({parentAgentId:'p'},new Map([['p',{kind:'agent'}]])),'parent:p');
 a.equal(f.je({parentAgentId:'p'},new Map()),'root');
});
