'use strict';
// Rewrite only eager CJS require(ESM) calls whose target statically imports this
// module. Node refuses this sync cycle; static ESM namespace imports preserve exports.
const ts = require('typescript');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const dir = path.resolve(__dirname, '../src');
const file = path.join(dir, 'chunk-j0tbz6ra.js');
const code = fs.readFileSync(file, 'utf8');
const ast = ts.createSourceFile(file, code, ts.ScriptTarget.Latest, true, ts.ScriptKind.JS);
assert.equal(ast.parseDiagnostics.length, 0);
const importers = new Map();
function targetImportsBack(filename) {
  if (importers.has(filename)) return importers.get(filename);
  const name = path.basename(filename);
  const src = fs.readFileSync(path.join(dir, name), 'utf8');
  const source = ts.createSourceFile(name, src, ts.ScriptTarget.Latest, true, ts.ScriptKind.JS);
  const back = source.statements.some(n =>
    (ts.isImportDeclaration(n) || ts.isExportDeclaration(n)) &&
    n.moduleSpecifier && ts.isStringLiteral(n.moduleSpecifier) &&
    n.moduleSpecifier.text === './chunk-j0tbz6ra.js');
  importers.set(filename, back);
  return back;
}
const replacements = [];
const cyclic = new Map();
function eager(n) {
  for (let parent = n.parent; parent && parent !== ast; parent = parent.parent) {
    if (ts.isFunctionLike(parent)) return false;
  }
  return true;
}
function visit(n) {
  if (ts.isCallExpression(n) && n.arguments.length === 1 &&
      ts.isStringLiteral(n.arguments[0]) &&
      n.expression.getText(ast) === 'import.meta.require' &&
      eager(n)) {
    const target = n.arguments[0].text;
    if (/^\.\/chunk-[a-z0-9]+\.js$/.test(target) && targetImportsBack(target)) {
      if (!cyclic.has(target)) cyclic.set(target, '__cc310EsmCycle' + cyclic.size);
      replacements.push({ start: n.getStart(ast), end: n.getEnd(), alias: cyclic.get(target), target });
    }
  }
  ts.forEachChild(n, visit);
}
visit(ast);
assert.ok(replacements.length > 0, '未找到已确认的 eager require 循环依赖');
let output = code;
for (const item of replacements.sort((a,b) => b.start - a.start)) {
  assert.match(output.slice(item.start, item.end), /^import\.meta\.require\(/);
  output = output.slice(0, item.start) + item.alias + output.slice(item.end);
}
const imports = [...cyclic].map(([target, alias]) => 'import * as ' + alias + ' from "' + target + '";').join('\n') + '\n';
fs.writeFileSync(file, imports + output);
console.log(JSON.stringify({convertedCalls:replacements.length,modules:[...cyclic.keys()],sourceBytes:Buffer.byteLength(imports + output)}));
