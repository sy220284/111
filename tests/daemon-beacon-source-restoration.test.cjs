'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');

const filename = path.resolve(__dirname, '../chunk-t2adf9g4.js');
const source = fs.readFileSync(filename,'utf8');
const ast = ts.createSourceFile(filename,source,ts.ScriptTarget.Latest,true,ts.ScriptKind.JS);
const mapping = [
  ['$e','isDaemonRecordExpired'],
  ['tn','updateOwnedDaemonBeaconAttempt'],
  ['oxt','mergeOwnedDaemonBeaconProgress']
];

function fixture(){
  assert.equal(ast.parseDiagnostics.length,0);
  const declarations=ast.statements.filter(n=>ts.isFunctionDeclaration(n)&&mapping.some(([,name])=>name===n.name?.text));
  assert.equal(declarations.length,3);
  const code=declarations.map(n=>n.getText(ast)).join('\n')+
    '\n'+mapping.map(([alias,name])=>'const '+alias+'='+name+';').join('\n')+
    '\n({'+mapping.flat().join(',')+'})';
  const ownedBeacons=new Map(), writes=[], jobs=[], storageWrites=[];
  let enabled=true;
  const state={
    wt:86400000,
    yF:()=>({ownedBeacons}),
    st:(id,callback)=>{jobs.push({id,callback})},
    vb:async()=>9001,
    Ee:async()=>enabled,
    kt:async(storage,id,record)=>storageWrites.push({storage,id,record}),
    Dn:async(filename,json,mode)=>writes.push({filename,json,mode}),
    at:id=>'/beacons/'+id+'.json',
    _:JSON.stringify,
    Number
  };
  const f=vm.runInNewContext(code,state);
  return { f,ownedBeacons,writes,jobs,storageWrites,setEnabled:value=>enabled=value,
    flush:async()=>{while(jobs.length){const task=jobs.shift();await task.callback()}} };
}

test('原短符号和具名函数指向相同实现',()=>{
  const {f}=fixture();
  for(const [short,readable] of mapping)assert.equal(f[short],f[readable]);
});
test('Daemon beacon只在超过一天或未来偏移超过一分钟时过期',()=>{
  const {f}=fixture(),now=1000000000;
  assert.equal(f.$e(now-86400000,now),true);
  assert.equal(f.$e(now-86399999,now),false);
  assert.equal(f.$e(now+60000,now),false);
  assert.equal(f.$e(now+60001,now),true);
});
test('Daemon尝试次数必须为单调递增整数；合法更新只排队一次',async()=>{
  const c=fixture(),r={attempt:1},f=c.f;c.ownedBeacons.set('job',r);
  f.tn('missing',2);f.tn('job',1);f.tn('job',0);f.tn('job',1.5);
  assert.equal(c.jobs.length,0);assert.equal(r.attempt,1);
  f.tn('job',2);
  assert.equal(r.attempt,2);assert.equal(c.jobs.length,1);
  await c.flush();
  assert.equal(r.procStart,9001);assert.equal(c.writes.length,1);
  assert.equal(c.writes[0].filename,'/beacons/job.json');
  assert.equal(JSON.parse(c.writes[0].json).attempt,2);
  assert.equal(c.writes[0].mode,384);
});
test('持久化回调拒绝写入已失去所有权的旧beacon',async()=>{
  const c=fixture(),original={attempt:0};
  c.ownedBeacons.set('job',original);
  c.f.tn('job',1);
  c.ownedBeacons.set('job',{attempt:9});
  await c.flush();
  assert.equal(c.writes.length,0);
});
test('Daemon进度状态保留真实值和首次attach信息，确认后不降级',async()=>{
  const c=fixture(),r={attempt:0};c.ownedBeacons.set('job',r);
  c.f.oxt('job',{marksExpected:true,daemonBooted:true,attachMs:12,msgsLoaded:0,attachCold:true,via:'apc'});
  c.f.oxt('job',{marksExpected:false,daemonBooted:false,attachMs:88,msgsLoaded:4,interactiveReached:true,attachCold:false,via:'tty'});
  assert.equal(r.marksExpected,true);
  assert.equal(r.daemonBooted,true);
  assert.equal(r.interactiveReached,true);
  assert.equal(r.attachMs,12);
  assert.equal(r.msgsLoaded,0);
  assert.equal(r.attachCold,true);
  assert.equal(r.via,'apc');
  await c.flush();
  assert.equal(c.writes.length,2);
});
test('存储V5分支委托已有的持有者写入；原地文件写入需要可用目录',async()=>{
  const c=fixture(),r={attempt:0};c.ownedBeacons.set('job',r);
  const storage={write(){}};
  c.f.tn('job',1,storage);
  c.f.oxt('job',{msgsInJsonl:3,msgsRenderedAtFirstPaint:2},storage);
  await c.flush();
  assert.equal(c.storageWrites.length,2);
  assert.ok(c.storageWrites.every(x=>x.storage===storage&&x.id==='job'));
  assert.equal(c.writes.length,0);
  c.setEnabled(false);
  c.f.oxt('job',{msgsInJsonl:4});
  await c.flush();
  assert.equal(c.writes.length,0);
});
