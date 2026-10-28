'use strict';
const {test}=require('node:test');
const assert=require('node:assert/strict');
const modulePath='../chunk-y7venc25.js';
test('2.1.310 原命令入口识别 plugin eval 和 plugins eval，但排除无关子命令',async()=>{
 const m=await import(modulePath);
 assert.equal(m.isPluginEvalInvocation(['plugin','eval']),true);
 assert.equal(m.isPluginEvalInvocation(['plugins','eval','--json']),true);
 assert.equal(m.isPluginEvalInvocation(['--verbose','plugin','eval']),true);
 assert.equal(m.isPluginEvalInvocation(['plugin','test']),false);
 assert.equal(m.isPluginEvalInvocation(['eval','plugin']),false);
 assert.equal(m.isPluginEvalInvocation(['plugin','list','eval']),false);
});
test('MCP serve、Agent JSON 和远控命令保持原版位置语义',async()=>{
 const m=await import(modulePath);
 assert.equal(m.isMcpServeInvocation(['mcp','serve']),true);
 assert.equal(m.isMcpServeInvocation(['--verbose','mcp','serve']),true);
 assert.equal(m.isMcpServeInvocation(['mcp','list']),false);
 assert.equal(m.isMcpServeInvocation(['mcp','list','mcp','serve']),false);
 assert.equal(m.isAgentsJsonInvocation(['agents','--json']),true);
 assert.equal(m.isAgentsJsonInvocation(['--json','agents']),false);
 assert.equal(m.isRemoteControlInvocation(['rc']),true);
 assert.equal(m.isRemoteControlInvocation(['remote-control','--verbose']),true);
 assert.equal(m.isRemoteControlInvocation(['other']),false);
});
test('非 REPL 入口与无实现的 project-verb 保持原版边界',async()=>{
 const m=await import(modulePath);
 assert.deepEqual([...m.NON_REPL_SUBCOMMANDS].sort(),['doctor','forward-home-settings','update','upgrade']);
 assert.equal(m.isProjectVerbInvocation(['anything']),false);
 assert.equal(m.isProjectVerbInvocation([]),false);
});
