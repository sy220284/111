'use strict';
const {test}=require('node:test'),a=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),ts=require('typescript');
const p=path.resolve(__dirname,'../core/chunk-t2adf9g4.js'),ast=ts.createSourceFile(p,fs.readFileSync(p,'utf8'),ts.ScriptTarget.Latest,true,ts.ScriptKind.JS);
const entries={kt:'persistOwnedDaemonBeacon',Kn:'readDaemonGestureFromStorage',He:'parseTerminalKittyKeyEscape',pn:'scanTerminalInterruptSequence',qxn:'hasTerminalInterruptSequence',kn:'verifyDaemonJobOwnership',Heo:'retryDaemonJobUpdate',vn:'formatDaemonConnectionRetryPrompt',Ge:'invokeDaemonInteractiveRunner'};
function fixture(other={}){
 const src=ast.statements.filter(n=>ts.isFunctionDeclaration(n)&&Object.values(entries).includes(n.name?.text)).map(n=>n.getText(ast));
 a.equal(src.length,Object.keys(entries).length);
 for(const [oldName,name] of Object.entries(entries))src.push('const '+oldName+'='+name+';');
 const ownedBeacons=new Map();
 const deps={Ee:async()=>true,yF:()=>({ownedBeacons}),yt:x=>'beacon:'+x,_:JSON.stringify,Ue:x=>'storage:'+x,ot:1024,nn:x=>{try{return JSON.parse(x)}catch{return null}},Buffer,
  ln:93,cn:3,sr:4,fn:27,mn:Buffer.from('CTRL_C'),dr:9,cr:Buffer.from('CTRL_D'),Et:27,un:99,dt:Buffer.from('ARM'),Te:(buf,pos,pat)=>Buffer.from(buf).subarray(pos,pos+pat.length).equals(pat),
  Dp:()=>{},lo:async()=>({id:'job'}),WFe:()=>false,Vat:()=>undefined,Mie:async()=>true,xr:async(e,n,opts)=>({e,n,opts}),Spr:async(e,o)=>({e,o}),Qp:()=> 'Daemon',...other};
 const names=[...Object.keys(entries),...Object.values(entries)].join(',');
 return {...vm.runInNewContext(src.join('\n')+';({'+names+'})',deps),ownedBeacons};
}
test('八项原版短符号保持同一个可读函数实现',()=>{
 const f=fixture();for(const [oldName,name] of Object.entries(entries))a.equal(f[oldName],f[name]);
});
test('持有 beacon 的进程才写入，不具备目录条件不执行',async()=>{
 const f=fixture(),calls=[],storage={write:async(...a)=>calls.push(a)},record={attempt:1};f.ownedBeacons.set('j',record);
 a.equal(await f.kt(storage,'j',record),true);a.equal(calls.length,1);
 a.equal(await f.kt(storage,'j',{attempt:1}),true);a.equal(calls.length,1);
 a.equal(await fixture({Ee:async()=>false}).kt(storage,'j',record),false);
});
test('存储手势记录必须满足体积及存在性检查',async()=>{
 const f=fixture(),v=JSON.stringify({gestureId:'g'}),row={found:true,totalBytes:v.length,value:Buffer.from(v),version:'v1',mtimeMs:1};
 const storage={statMeta:async()=>({ok:true,value:{size:v.length}}),read:async()=>({ok:true,value:{items:[row]}})};
 a.equal((await f.Kn(storage,'x')).entry.gestureId,'g');
 a.equal(await f.Kn({statMeta:async()=>({ok:false})},'x'),null);
 a.equal(await f.Kn({statMeta:async()=>({ok:true,value:{size:20000}})},'x'),null);
});
test('Kitty 键盘转义必须携带目标键码',()=>{
 const f=fixture(),b=Buffer.from('\x1b[27;5u','binary');
 a.equal(f.He(b,0,27),b.length);a.equal(f.He(b,0,28),0);a.equal(f.He(Buffer.from('hi'),0,27),0);
});
test('中断扫描识别 Ctrl-C 与跨分片逃逸前缀',()=>{
 const f=fixture();
 a.equal(f.qxn(Buffer.from([3])),true);a.equal(f.qxn(Buffer.from([65])),false);
 const state=f.pn(Buffer.from([27]),false);
 a.equal(state.matched,false);a.equal(state.prefixArmed,true);
 a.equal(f.pn(Buffer.from([93]),true).matched,true);
});
test('作业身份校验拒绝非当前拥有者',async()=>{
 a.equal(await fixture({WFe:()=>true}).kn('job','owner'),false);
 a.equal(await fixture({Vat:()=> 'owner'}).kn('job','owner'),true);
 a.equal(await fixture({Vat:()=> 'owner'}).kn('job','other'),false);
 a.equal(await fixture({lo:async()=>null}).kn('job','owner'),false);
});
test('强制更新重试选项以及 Daemon 连接提示保留原版',async()=>{
 const f=fixture(),r=await f.Heo('job',{attempt:1});
 a.deepEqual(JSON.parse(JSON.stringify(r.opts)),{overStream:false,onlyToRetry:false});
 a.match(f.vn(),/Couldn.t reach the Daemon/);a.match(f.vn(),/Press Enter to retry/);
});

test('交互式执行桥接准确转发业务选项，执行错误不得静默消失',async()=>{
 const f=fixture(),opts={tag:'work'},r=await f.Ge('daemon','unused',opts);
 a.equal(r.e,'daemon');a.equal(r.o,opts);
 await a.rejects(fixture({Spr:async()=>{throw Error('runner failed')}}).Ge('daemon','unused',opts),/runner failed/);
});
