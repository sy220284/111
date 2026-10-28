'use strict';
// Check that every targeted behavior-test ledger anchor is a real JS function
// signature, rather than a matching comment/string or an unrelated text fragment.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ts = require('typescript');
const root = path.resolve(__dirname, '..');
const ledger = JSON.parse(fs.readFileSync(path.join(root, 'resources/function-verification-ledger.json'), 'utf8'));
const summary = JSON.parse(fs.readFileSync(path.join(root, 'resources/function-summary.json'), 'utf8'));
const sourceCache = new Map();
const testCache = new Map();

function readAst(relative) {
  const code = fs.readFileSync(path.join(root, relative), 'utf8');
  const ast = ts.createSourceFile(relative, code, ts.ScriptTarget.Latest, true, ts.ScriptKind.JS);
  assert.equal(ast.parseDiagnostics.length, 0, 'JS syntax error in ' + relative);
  return { code, ast };
}
function isFunction(n) {
  return ts.isFunctionDeclaration(n) || ts.isFunctionExpression(n) ||
    ts.isArrowFunction(n) || ts.isMethodDeclaration(n) ||
    ts.isConstructorDeclaration(n) || ts.isGetAccessorDeclaration(n) ||
    ts.isSetAccessorDeclaration(n);
}
function inspectSource(relative) {
  if (sourceCache.has(relative)) return sourceCache.get(relative);
  const { code, ast } = readAst(relative);
  const signatures = [];
  const identifiers = new Set();
  function visit(n) {
    if (ts.isIdentifier(n)) identifiers.add(n.text);
    if (isFunction(n) && n.body) {
      signatures.push([n.getStart(ast), n.body.getStart(ast)]);
    }
    if (ts.isVariableDeclaration(n) && n.initializer && isFunction(n.initializer)) {
      const declaration = ts.isVariableDeclarationList(n.parent) &&
        n.parent.declarations[0] === n ? n.parent : n;
      signatures.push([declaration.getStart(ast), n.initializer.body.getStart(ast)]);
    }
    ts.forEachChild(n, visit);
  }
  visit(ast);
  const result = { code, signatures, identifiers };
  sourceCache.set(relative, result);
  return result;
}
function inspectTest(relative) {
  if (testCache.has(relative)) return testCache.get(relative);
  const { code, ast } = readAst(relative);
  let actualTestCalls = 0;
  function visit(n) {
    if (ts.isCallExpression(n) &&
      ((ts.isIdentifier(n.expression) && n.expression.text === 'test') ||
       (ts.isPropertyAccessExpression(n.expression) && n.expression.name.text === 'test'))) {
      actualTestCalls++;
    }
    ts.forEachChild(n, visit);
  }
  visit(ast);
  const result = { code, actualTestCalls };
  testCache.set(relative, result);
  return result;
}
function matchingSignatures(relative, anchor) {
  const { code, signatures } = inspectSource(relative);
  const matching = [];
  for (let at = code.indexOf(anchor); at !== -1; at = code.indexOf(anchor, at + 1)) {
    if (signatures.some(([start, end]) => at >= start && at < end)) matching.push(at);
  }
  return matching;
}
function verifyLedger() {
  assert.equal(ledger.version, summary.version);
  assert.equal(summary.modules, 2551);
  assert.ok(summary.functions > ledger.entries.length);
  const seen = new Set();
  for (const row of ledger.entries) {
    assert.equal(row.status, 'targeted_behavior_test_only');
    assert.ok(!row.source.startsWith('src/') && row.test.startsWith('tests/'));
    const key = row.source + '::' + row.symbol;
    assert.ok(!seen.has(key), 'duplicate evidence: ' + key);
    seen.add(key);
    const source = inspectSource(row.source);
    const symbol = row.symbol.split('.').at(-1);
    assert.ok(source.identifiers.has(symbol), 'missing AST identifier: ' + key);
    assert.equal(matchingSignatures(row.source, row.anchor).length, 1,
      'anchor must identify exactly one executable function signature: ' + key);
    const test = inspectTest(row.test);
    assert.ok(test.code.includes(path.basename(row.source)),
      'test does not identify original source: ' + row.test);
    assert.ok(test.actualTestCalls > 0, 'no parsed test() invocation: ' + row.test);
  }
  return { result: 'PASS', version: ledger.version, sourceModules: summary.modules,
    astFunctionEntities: summary.functions, astLocatedFunctionContracts: seen.size,
    sourceFilesParsed: sourceCache.size, testFilesParsed: testCache.size,
    sourceReimplementationsVerifiedByThisLedger: 0, wholeBusinessFlowsVerifiedByThisLedger: 0 };
}
if (require.main === module) console.log(JSON.stringify(verifyLedger()));
module.exports = { matchingSignatures, verifyLedger };
