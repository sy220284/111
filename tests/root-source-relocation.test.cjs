'use strict';
const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const crypto=require('node:crypto');
const cp=require('node:child_process');
const ROOT=path.resolve(__dirname,'..');
const manifest=JSON.parse(fs.readFileSync(path.join(ROOT,'resources/layout/root-source-relocations.json'),'utf8'));
const blob=b=>crypto.createHash('sha1').update('blob '+b.length+'\0').update(b).digest('hex');
test('2554 个文件分类并验证 Hook Worker 130 个导入路径变更可逆，原件字节可还原',()=>{
 assert.equal(manifest.rootFiles,2554);
 assert.equal(manifest.files.length,2554);
 assert.equal(manifest.importAdjusted,130);
 assert.equal(fs.existsSync(path.join(ROOT,'src')),false,'src 旧目录仍存在');
 assert.equal(fs.readdirSync(ROOT).filter(x=>x.endsWith('.js')).length,0,'根目录散落 JavaScript');
 const actual=fs.readdirSync(path.join(ROOT,'core')).filter(x=>x.endsWith('.js')).sort();
 assert.equal(actual.length,2553);
 const seen=new Set();
 for(const item of manifest.files){
  assert.ok(item.old.startsWith('src/'),'非法源路径 '+item.old);
  assert.ok(item.current.startsWith('core/')||item.current.startsWith('plugins/'),'归类错误 '+item.current);
  assert.ok(!seen.has(item.current),'重复目标 '+item.current);
  seen.add(item.current);
  assert.equal(fs.existsSync(path.join(ROOT,item.old)),false,'旧文件残留 '+item.old);
  const target=path.join(ROOT,item.current);
  assert.ok(fs.existsSync(target),'目标缺失 '+item.current);
  const bytes=fs.readFileSync(target);
  assert.equal(blob(bytes),item.currentGitBlob,'迁移清单实际字节不一致 '+item.current);
  if(item.importAdjusted){
    assert.equal(item.current,'plugins/functionHooks/hooks-worker/hooks-worker.js');
    const current=bytes.toString('utf8');
    const matches=current.match(/\.\.\/\.\.\/\.\.\/core\/chunk-/g)||[];
    assert.equal(matches.length,130,'Hook Worker 应恰好修复 130 条相对导入');
    const original=current.replaceAll('../../../core/chunk-','../../../../chunk-');
    assert.equal(blob(Buffer.from(original)),item.originalGitBlob,'Hook Worker 反向还原后与原 Git Blob 不一致');
  }else if(item.behaviorAdjusted){
    if(item.current==='core/chunk-fmf16p94.js'){
      assert.equal(item.behaviorAdjusted,true,'原 MCP 修复标记不得改变');
      assert.equal(item.currentGitBlob,'78f0fd359e4df812d4bbba9cf0ebc019a6d15585','当前修复必须保留与原基线一致的清理代码');
      const method=bytes.toString('utf8');
      assert.ok(method.includes('this._requestResolvers.delete(d),this._cleanupTimeout(d)'),'MCP取消回调必须清理关联任务resolver');
    }else if(item.current==='core/chunk-hp7h93my.js'){
      assert.equal(item.behaviorAdjusted,'cron-durable-integrity-and-atomic-process-lock-recovery');
      assert.deepEqual(item.behaviorTests,[
        'tests/cron-durable-integrity.test.mjs',
        'tests/cron-multiprocess-recovery.test.mjs'
      ]);
      assert.equal(item.originalGitBlob,'fa39f70fb8da18d24b73cfde1b26d247cf8603fc');
      assert.equal(item.currentGitBlob,'36b8ae6d4655a593fc1f520d1719aa119bd58a4e');
      for(const name of item.behaviorTests)assert.ok(fs.existsSync(path.join(ROOT,name)));
      const method=bytes.toString('utf8');
      assert.ok(method.includes('await Ecn(s,!0)'),'持久化写入未执行严格校验');
      assert.ok(method.includes('await Qcn(tHe(s)'),'定时任务写入没有进程间互斥');
      assert.ok(method.includes('async function withCronStoreLock'),'实际互斥函数未进入受审计的原业务模块');
      assert.ok(method.includes('await link(staging,path)'),'锁发布必须使用原子硬链接，避免半写入锁');
      assert.ok(method.includes('async function tryReapAbandonedReaper'),'回收者崩溃恢复函数未接入');
      assert.ok(method.includes('await Qcn(tHe()'),'定时任务创建没有进程间互斥');
      assert.ok(method.includes('async function WTe(e,s)'),'定时任务删除原入口不再存在');
    }else if(item.current==='core/chunk-t2adf9g4.js'){
      assert.equal(item.behaviorAdjusted,'daemon-roster-presence-fallback');
      assert.deepEqual(item.behaviorTests,['tests/daemon-job-contract.test.cjs']);
      assert.equal(item.originalGitBlob,'6f7809a7ec9daa1b11090200489ab657c86ff5fa');
      assert.equal(item.currentGitBlob,'0f2b28232e834c476685b8ef91740417b0664ad6');
      assert.ok(fs.existsSync(path.join(ROOT,item.behaviorTests[0])));
      assert.ok(bytes.toString('utf8').includes('let n=await probeDaemonJob(e);return n.present'),
        'Daemon 断线未进行带 PID 启动指纹的 roster 回退');
    }else{
      assert.fail('未经审计的运行态源码变更：'+item.current);
    }
  }else{
    assert.equal(blob(bytes),item.originalGitBlob,'未调整源码字节被更改 '+item.current);
  }
 }
 assert.equal([...seen].filter(x=>x.startsWith('core/')).length,2553);
});
test('core 的运行时相对导入恢复为 ../runtime 并能定位到真实文件',()=>{
 const modules=manifest.files.filter(x=>x.current.startsWith('core/'));
 const runtime=modules.filter(x=>fs.readFileSync(path.join(ROOT,x.current),'utf8').includes('../runtime/'));
 assert.equal(runtime.length,51,'原有51个运行时适配消费者应完整保留');
 for(const item of runtime){
  const code=fs.readFileSync(path.join(ROOT,item.current),'utf8');
  assert.ok(!code.includes("from './runtime/"),item.current+' 存在错误的平级适配导入');
  for(const match of code.matchAll(/['"]\.\.\/runtime\/([^'"]+)['"]/g)){
   assert.ok(fs.existsSync(path.resolve(ROOT,'core','../runtime',match[1])),item.current+' -> '+match[1]);
  }
 }
 assert.ok(fs.existsSync(path.join(ROOT,'core/cli.js')));
});
