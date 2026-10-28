'use strict';
const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const map = require('../resources/research/v40-p0-symbol-locations.json');
const root = path.resolve(__dirname, '..');
test('v40的92条标识符属于已定位证据，不按缺失函数计数',()=>{
  assert.equal(map.version,'2.1.310');
  assert.equal(map.record_count,92);
  assert.equal(map.resolved_export_count,92);
  assert.equal(map.items.length,92);
  assert.equal(Object.keys(map.real_definition_modules).length,11);
  assert.equal(map.items.reduce((n,row)=>n+row.definitions.length,0),94);
  assert.equal(map.evidenceLevel.includes('不是函数缺失'),true);
});
test('v40全部已定位模块在111当前主程序中真实存在，源表保留多归属而非擅自合并',()=>{
  const histogram=new Map();
  for(const row of map.items){
    assert.ok(row.symbolName && row.definitions.length>0);
    for(const d of row.definitions){
      assert.match(d.module,/^chunk-[\w]+\.js$/);
      assert.ok(d.symbol);
      assert.ok(fs.existsSync(path.join(root,'core',d.module)),'缺少v40真实模块 '+d.module);
      histogram.set(d.module,(histogram.get(d.module)||0)+1);
    }
  }
  for(const [module,n] of Object.entries(map.real_definition_modules))
    assert.equal(histogram.get(module),n,'v40定义模块计数漂移 '+module);
  assert.equal(map.items.filter(x=>x.definitions.length>1).length,2);
});
