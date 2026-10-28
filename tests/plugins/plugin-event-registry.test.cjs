'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root=path.resolve(__dirname,'../..');
const source=fs.readFileSync(path.join(root,'src/chunk-pbffp8rf.js'),'utf8');
const guide=fs.readFileSync(path.join(root,'resources/docs/plugin-authoring/reference.md'),'utf8');
const schema=fs.readFileSync(path.join(root,'resources/docs/plugin-authoring/component-schemas.md'),'utf8');
const pluginTypes=fs.readFileSync(path.join(root,'resources/tools/plugin-api/types/claude-code.d.ts'),'utf8');
function registry(){
  const a=source.indexOf('var Ge = UKr.map(');
  const b=source.indexOf('var XZs =',a);
  assert.ok(a>0 && b>a,'real built-in event and noun namespace registry exists');
  return vm.runInNewContext(source.slice(a,b)+';({Oer,ZLe,uSi,e0e,BVo,BKr,xyn,Xzt})',{
    UKr:['PreToolUse','PostToolUse','SessionStart','SessionEnd','Notification','PermissionRequest']
  });
}
test('原版 Function Hooks 事件表包含插件、会话、工具、Agent 和 MCP 核心入口',()=>{
 const r=registry();
 for (const event of ['session.append','session.start','session.end','tool.call','tool.check',
   'tool.register','agent.register','agent.spawn','plugin.register','mcp.call',
   'turn.step','turn.complete','engine.create']) {
   assert.equal(r.e0e(event),true,event);
 }
 for(const item of ['mcp.call','session.messages','agent.list','model.complete']) {
   assert.equal(r.BVo(item),true,item);
 }
 assert.equal(r.e0e('session.nonexistent'),false);
 assert.equal(r.xyn('turn.step'),true);
 assert.equal(r.xyn('process.spawn'),true);
 assert.equal(r.xyn('session.append'),false);
 assert.ok(r.uSi.every(item=>item!=='classic.PreToolUse'));
});
test('插件自定义事件名称需是独立的合法命名空间，不得伪造内置域',()=>{
 const {Xzt}=registry();
 assert.equal(Xzt('widget.changed'),true);
 assert.equal(Xzt('tool.forget'),false);
 assert.equal(Xzt('session.fake'),false);
 assert.equal(Xzt('model.extra'),false);
 assert.equal(Xzt('bad key.event'),false);
 assert.equal(Xzt('no_dot'),false);
});
test('配置与插件声明文档交叉佐证现有注册入口，但不将外部 Schema 误作实现',()=>{
 for(const s of ['tool.register','agent.register','session.append'])assert.ok(guide.includes(s),s);
 for(const s of ['Frontmatter Fields','Available Events','MCP Servers'])assert.ok(schema.includes(s),s);
 assert.match(pluginTypes,/export type EventName = keyof EventOf/);
 assert.match(pluginTypes,/export type Register = \(on: On/);
});
