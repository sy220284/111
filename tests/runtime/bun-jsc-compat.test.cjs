'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const {scanText, scan, probe} = require('../../tools/dev/bun-jsc-compat.cjs');

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
  const report = scan(path.resolve(__dirname, '../..'));
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


test('Bun 探针返回非零状态时仍保留具体失败 API 和错误详情',()=>{
  const fs=require('node:fs'),os=require('node:os');
  const dir=fs.mkdtempSync(path.join(os.tmpdir(),'111-bun-probe-fixture-'));
  const executable=path.join(dir,'mock-bun');
  try{
    fs.writeFileSync(executable, '#!/usr/bin/env node\n'+
      'process.stdout.write(JSON.stringify({schema:1,kind:"bun-runtime-probe",status:"failed",checks:[{api:"Bun.Terminal",status:"failed",error:"PTY mismatch"}]})+"\\n");process.exitCode=1;\n');
    fs.chmodSync(executable,0o755);
    const result=probe(executable);
    assert.equal(result.status,'failed');
    assert.deepEqual(result.checks,[{api:'Bun.Terminal',status:'failed',error:'PTY mismatch'}]);
  }finally{fs.rmSync(dir,{recursive:true,force:true})}
});
