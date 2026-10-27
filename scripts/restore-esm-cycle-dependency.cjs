'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const file = path.resolve(__dirname, '../src/chunk-j0tbz6ra.js');
const old = 'BZ = import.meta.require("./chunk-81tmxf49.js").default';
const directImport = 'import __cc310RecapCommand from "./chunk-81tmxf49.js";\n';
const code = fs.readFileSync(file, 'utf8');
assert.equal(code.split(old).length - 1, 1,
  '原始 Recap 同步加载锚点必须唯一，禁止猜测替换');
assert.ok(!code.includes(directImport.trim()),
  '目标修复已经存在，不得重复插入');
fs.writeFileSync(file, directImport + code.replace(old, 'BZ = __cc310RecapCommand'));
console.log('已将 Recap 的同步 require 改为等价 ES 模块导入，保持导出对象引用不变');
