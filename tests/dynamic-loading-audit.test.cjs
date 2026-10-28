'use strict';
const {test}=require('node:test');
const assert=require('node:assert/strict');
const {extractLoaderSites}=require('../scripts/audit-dynamic-loaders.cjs');
test('动态导入及 Bun 兼容 require 分别索引字面量与表达式',()=>{
 const code=[
  "import('./chunk-a.js');",
  "const x=import(somePath);",
  "import.meta.require('./chunk-b.js');",
  "require('./chunk-c.js');",
  "require('node:fs');",
  "const text=\"import('./fake.js')\";",
  "// require('./comment.js')"
 ].join('\n');
 const items=extractLoaderSites(code,'fixture.js');
 assert.deepEqual(items.map(x=>[x.kind,x.target,x.computed]),[
  ['import','./chunk-a.js',false],['import',null,true],
  ['import.meta.require','./chunk-b.js',false],
  ['require','./chunk-c.js',false],['require','node:fs',false]
 ]);
});
test('无插值模板字符串是可证实路径，插值模板保留未验证标识',()=>{
 const sites=extractLoaderSites("import(\`./static.js\`);import(\`./\${name}.js\`);",'fixture.js');
 assert.deepEqual(sites.map(x=>[x.target,x.computed]),[['./static.js',false],[null,true]]);
});
