'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const {scanText, scan, probe} = require('../tools/dev/bun-jsc-compat.cjs');

test('AST scan handles direct, bracket, globalThis, nested ant and destructured Bun APIs', () => {
  const code = [
    '// Bun.Terminal must not count in a comment',
    'const a = Bun.spawn(["echo"]);',
    'const b = Bun["SQL"];',
    'const c = globalThis.Bun.Terminal;',
    'const d = globalThis["Bun"]["spawnSync"];',
    'const e = Bun.ant.memoryPressureLevel();',
    'const {write: writer} = Bun;',
    'const x = Bun[key];'
  ].join('\n');
  const result = scanText(code, 'fixture.js');
  const apis = result.sites.map(s => s.api);
  for (const api of ['Bun.spawn','Bun.SQL','Bun.Terminal','Bun.spawnSync',
                      'Bun.ant.memoryPressureLevel','Bun.write','Bun.<dynamic>'])
    assert.ok(apis.includes(api), 'missing AST evidence for ' + api);
  assert.equal(apis.filter(x => x === 'Bun.ant').length, 0, 'nested private call must not be double-counted');
  assert.equal(result.errors.length, 0);
  assert.ok(result.sites.every(s => s.file === 'fixture.js' && s.line > 0));
});

test('AST scan identifies Bun builtin imports but not ordinary string mentions', () => {
  const code = [
    'const a = "bun:sqlite";',
    'import {Database} from "bun:sqlite";',
    'export {x} from "bun:jsc";',
    'const m = import("bun:ffi");'
  ].join('\n');
  const output = scanText(code, 'fixture.js');
  assert.deepEqual(output.sites.map(s=>s.api), ['bun:sqlite','bun:jsc','bun:ffi']);
});

test('repository audit scans main recovered modules, not README or vendor files', () => {
  const report = scan(path.resolve(__dirname, '..'));
  assert.equal(report.schema, 1);
  assert.equal(report.scannedModules, 2551);
  assert.deepEqual(report.parseErrors, []);
  const spawn = report.capabilities.find(x => x.api === 'Bun.spawn');
  const ant = report.capabilities.find(x => x.api === 'Bun.ant.setDumpable');
  assert.ok(spawn && spawn.observed && spawn.locations.some(x => x.file.startsWith('src/')));
  assert.ok(ant && ant.observed && ant.locations.length > 0);
  const sql = report.capabilities.find(x => x.api === 'Bun.SQL');
  assert.ok(sql && Array.isArray(sql.locations));
  assert.notEqual(sql.nodeStatus, 'verified_compatible');
});

test('missing reference runtime returns explicit unavailable, never success', () => {
  const result = probe('/nonexistent/111-bun-audit-fixture');
  assert.equal(result.status, 'unavailable_or_failed');
  assert.deepEqual(result.checks, []);
});


test('Node/Bun observation comparison rejects altered model request sequence and fake ELF flags',()=>{
 const {compareTraces}=require('../tools/dev/runtime-differential.cjs');
 const fixture={schema:1,kind:'111-live-model-sse',mockedLocalHTTP:true,
   originalELFCompared:false,requestCount:1,
   steps:[{model:'claude-sonnet-4-5',stream:true,
     messageRoles:['user'],userPromptObserved:true,toolsAvailable:0}],
   stdoutContainsExpectedMarker:true,cliExitCode:0};
 assert.doesNotThrow(()=>compareTraces(fixture,{...fixture,steps:fixture.steps.map(x=>({...x}))}));
 assert.throws(()=>compareTraces(fixture,{...fixture,steps:[{...fixture.steps[0],stream:false}]}),
   /diverged/);
 assert.throws(()=>compareTraces(fixture,{...fixture,originalELFCompared:true}),
   /Expected values to be strictly equal/);
});

const cp=require('node:child_process');
const fs=require('node:fs');
const os=require('node:os');
const bunProbe=cp.spawnSync('bun',['--version'],{encoding:'utf8',timeout:3000});
const bunReady=!bunProbe.error&&bunProbe.status===0;
test('real reconstructed CLI Node/Bun local SSE and MCP stdio behavioral differential',
 {skip:bunReady?false:'Bun executable unavailable: only the Bun-capable CI job performs differential parity',
  timeout:300000},()=>{
  const temporary=!process.env.DEV111_RUNTIME_DIFF_OUT;
  const dest=process.env.DEV111_RUNTIME_DIFF_OUT||
    fs.mkdtempSync(path.join(os.tmpdir(),'111-node-bun-live-differential-'));
  try{
    const result=cp.spawnSync(process.execPath,
      [path.join(__dirname,'../tools/dev/runtime-differential.cjs'),
       '--node',process.execPath,'--bun','bun','--out',dest],
      {cwd:path.resolve(__dirname,'..'),encoding:'utf8',timeout:270000,maxBuffer:1024*1024*6});
    assert.equal(result.status,0,
      'Real Node/Bun reconstructed CLI divergence: '+(result.error?.message||'')+
      '\nstdout='+String(result.stdout).slice(-5000)+
      '\nstderr='+String(result.stderr).slice(-5000));
    const r=JSON.parse(fs.readFileSync(path.join(dest,'summary.json'),'utf8'));
    assert.equal(r.status,'local_fixture_parity_passed');
    assert.equal(r.cases.length,2);
    assert.ok(r.cases.every(x=>x.passed));
    assert.equal(r.originalELFCompared,false);
    console.log(JSON.stringify({nodeBunRuntimeParity:r.status,cases:r.cases.map(x=>x.name)}));
  }finally{
    if(temporary)fs.rmSync(dest,{recursive:true,force:true});
  }
});
