'use strict';
const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');
const source=fs.readFileSync(path.resolve(__dirname,'../src/chunk-wapyem7a.js'),'utf8');

function fixture({entries,swap=false,unlinkError,readError}={}){
  const start=source.indexOf('async function pruneExpiredLegacyDaemonLogDirs(e, r = $e())');
  const end=source.indexOf('var Ns =',start);
  assert.ok(start>=0&&end>start,'legacy daemon cleanup implementation missing');
  const root='/run/user/1001',now=new Date(2026,0,5),older=new Date(2025,0,1);
  const dirs=entries??[
    {name:'cc-daemon-pidA',mtime:older,files:['stderr.log']},
    {name:'cc-daemon-1001',mtime:older,files:['stderr.log']},
    {name:'cc-daemon-12345',mtime:older,files:['stderr.log']},
    {name:'cc-daemon-0123456789abcdef',mtime:older,files:['stderr.log']},
    {name:'cc-daemon-live',mtime:now,files:['stderr.log']},
    {name:'cc-daemon-mixed',mtime:older,files:['stderr.log','other']},
    {name:'unrelated',mtime:older,files:['stderr.log']}
  ];
  let statCalls=0;
  const removed=[],directories=[];
  const filesys={
    readdir:async name=>{
      if(name===root){
        if(readError)throw Object.assign(new Error(readError),{code:readError});
        return dirs.map(v=>({name:v.name}));
      }
      return dirs.find(v=>name===path.join(root,v.name))?.files?.map(name=>({name}))??[];
    },
    lstat:async name=>{
      const item=dirs.find(v=>name===path.join(root,v.name,'stderr.log'));
      if(!item)throw Object.assign(Error('ENOENT'),{code:'ENOENT'});
      return {isFile:()=>true,mtime:item.mtime};
    },
    unlink:async name=>{
      if(unlinkError)throw Object.assign(Error(unlinkError),{code:unlinkError});
      removed.push(name);
    }
  };
  const originalStats={};
  const m=vm.runInNewContext(source.slice(start,end)+';({Zt,pruneExpiredLegacyDaemonLogDirs})',{
    E:()=>({messages:0,filesRetainedFresh:0,filesPastCutoff:0,errors:0}),
    ie:()=>filesys,
    process:{getuid:()=>1001},
    G:error=>error?.code==='ENOENT',
    h:path.join,
    $e:()=>root,
    eAe:async dir=>{
      if(!dirs.some(x=>path.join(root,x.name)===dir))return null;
      statCalls++;
      return{dev:9,ino:swap&&statCalls%2===0?199:99};
    },
    T:async dir=>directories.push(dir)
  });
  return {m,filesys,root,now,older,removed,directories};
}
test('只清理过期 stderr-only 历史目录，跳过正在使用的 UID/PID/哈希目录',async()=>{
 const f=fixture();
 const outcome=await f.m.Zt(f.now,f.root);
 assert.deepEqual(f.removed,[path.join(f.root,'cc-daemon-pidA','stderr.log')]);
 assert.deepEqual(f.directories,[path.join(f.root,'cc-daemon-pidA')]);
 assert.equal(outcome.messages,1);
 assert.equal(outcome.filesRetainedFresh,1);
 assert.equal(outcome.errors,0);
 assert.equal(f.m.Zt,f.m.pruneExpiredLegacyDaemonLogDirs);
});
test('历史目录文件不唯一时禁止清理，普通路径不在清理范围',async()=>{
 const f=fixture({entries:[
   {name:'cc-daemon-multi',mtime:new Date(2020,1,1),files:['stderr.log','state.json']},
   {name:'other-folder',mtime:new Date(2020,1,1),files:['stderr.log']}
 ]});
 const out=await f.m.Zt(f.now,f.root);
 assert.equal(out.messages,0);
 assert.equal(f.removed.length,0);
});
test('删除前目录 inode 发生替换时跳过，不对竞态中新目录做清理',async()=>{
 const f=fixture({entries:[{name:'cc-daemon-oldname',mtime:new Date(2020,1,1),files:['stderr.log']}],swap:true});
 const out=await f.m.Zt(f.now,f.root);
 assert.equal(out.messages,0);
 assert.equal(f.removed.length,0);
});
test('删除失败区分 ENOENT 与访问拒绝，不冒充已清理目录',async()=>{
 const entries=[{name:'cc-daemon-oldname',mtime:new Date(2020,1,1),files:['stderr.log']}];
 const gone=fixture({entries,unlinkError:'ENOENT'});
 const nofile=await gone.m.Zt(gone.now,gone.root);
 assert.equal(nofile.errors,0);
 assert.equal(nofile.messages,0);
 const denied=fixture({entries,unlinkError:'EACCES'});
 const bad=await denied.m.Zt(denied.now,denied.root);
 assert.equal(bad.messages,0);
 assert.equal(bad.errors,1);
 assert.equal(bad.filesPastCutoff,1);
});
test('目录扫描失败安全退出并记录真实错误，不尝试删除文件',async()=>{
 const missing=fixture({readError:'ENOENT'});
 const gone=await missing.m.Zt(missing.now,missing.root);
 assert.equal(gone.errors,0);
 assert.equal(missing.removed.length,0);
 const denied=fixture({readError:'EACCES'});
 const failed=await denied.m.Zt(denied.now,denied.root);
 assert.equal(failed.errors,1);
 assert.equal(denied.removed.length,0);
});
