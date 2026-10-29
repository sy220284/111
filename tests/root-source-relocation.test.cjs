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
      // Explicitly audited second exception: preserve the original historical
      // Blob while pinning the exact tested runtime source Blob. Never
      // generalize this to arbitrary changes under core/.
      assert.equal(item.behaviorAdjusted,'cron-durable-integrity');
      assert.deepEqual(item.behaviorTests,['tests/cron-durable-integrity.test.mjs']);
      assert.equal(item.originalGitBlob,'fa39f70fb8da18d24b73cfde1b26d247cf8603fc');
      assert.equal(item.currentGitBlob,'e46094ed1be4b158f85360761ceae3b24b5c781d');
      assert.ok(fs.existsSync(path.join(ROOT,item.behaviorTests[0])));
      const method=bytes.toString('utf8');
      assert.ok(method.includes('await Ecn(s,!0)'),'持久化写入未执行严格校验');
      assert.ok(method.includes('refusing to overwrite malformed'),'任务文件损坏未阻止写入');
    }else if(item.current==='core/chunk-kj2kfe1q.js'){
      assert.equal(item.behaviorAdjusted,'worker-state-replay-guard');
      assert.equal(item.originalGitBlob,'06bfe55d5d50f36920a1e8c62d19e0e07312f4c3');
      assert.equal(item.currentGitBlob,'eec14ef82b2de5e94346993bfaa98189de7b1b5b');
      assert.deepEqual(item.behaviorTests,[
        'tests/daemon-worker-state-guard.test.cjs',
        'tests/cli-live-daemon-worker-recovery.test.cjs'
      ]);
      for(const name of item.behaviorTests)assert.ok(fs.existsSync(path.join(ROOT,name)));
      const method=bytes.toString('utf8');
      assert.ok(method.includes('await HOo(_o(this.dispatch.short),this.storageV5)'),
        'Worker 恢复未区分状态不存在与状态损坏');
      assert.ok(method.includes('reason:b("unreadable_state")'),
        '无法验证状态时必须禁止自动重放');
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
 assert.equal(runtime.length,51,'真实引用 runtime 的模块必须完整保留');
 for(const item of runtime){
  const code=fs.readFileSync(path.join(ROOT,item.current),'utf8');
  assert.ok(!code.includes("from './runtime/"),item.current+' 存在错误的平级适配导入');
  for(const match of code.matchAll(/['"]\.\.\/runtime\/([^'"]+)['"]/g)){
   assert.ok(fs.existsSync(path.resolve(ROOT,'core','../runtime',match[1])),item.current+' -> '+match[1]);
  }
 }
 assert.ok(fs.existsSync(path.join(ROOT,'core/cli.js')));
});
