'use strict';
// AST index of the recovered 2.1.310 module/function graph.
// This enumerates evidence, it does NOT claim function semantics are verified.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const assert = require('node:assert/strict');
let ts;
try { ts = require('typescript'); }
catch { ts = require(path.join(path.dirname(path.dirname(process.execPath)), 'lib/node_modules/typescript')); }
const root = path.resolve(__dirname,'..');
const src = path.join(root,'src');
const generated = path.join(root,'.generated');
const summaryPath = path.join(root,'resources/function-summary.json');
const files = fs.readdirSync(src).filter(n=>n.endsWith('.js') && !['chart.umd.min.js','hljsBundle.generated.min.js','mermaid.min.js'].includes(n)).sort();
files.push('src/plugins/functionHooks/hooks-worker/hooks-worker.js');
assert.equal(files.length,2551);
const index = {}, detailed = {};
const summary = {version:'2.1.310',modules:files.length,functions:0,callExpressions:0,dynamicLiteralImports:0,missingRelativeImports:0,parseFailed:0};
const fingerprint = crypto.createHash('sha256');
const isFunction = n => ts.isFunctionDeclaration(n)||ts.isFunctionExpression(n)||ts.isArrowFunction(n)||ts.isMethodDeclaration(n)||ts.isConstructorDeclaration(n)||ts.isGetAccessorDeclaration(n)||ts.isSetAccessorDeclaration(n);
const name = n => ts.isIdentifier(n) ? n.text : n?.getText().slice(0,100) || '<anonymous>';
for (const file of files) {
  const code = fs.readFileSync(path.join(src,file),'utf8');
  fingerprint.update(file+'\0').update(code).update('\0');
  const ast = ts.createSourceFile(file,code,ts.ScriptTarget.Latest,true,ts.ScriptKind.JS);
  summary.parseFailed += ast.parseDiagnostics.length;
  const imports=[],exports=[],functions=[];
  let current=null,calls=0;
  for (const n of ast.statements) {
    if(ts.isImportDeclaration(n) && ts.isStringLiteral(n.moduleSpecifier)) imports.push(n.moduleSpecifier.text);
    if(ts.isExportDeclaration(n)) {
      if(n.moduleSpecifier && ts.isStringLiteral(n.moduleSpecifier)) imports.push(n.moduleSpecifier.text);
      if(n.exportClause && ts.isNamedExports(n.exportClause)) for(const e of n.exportClause.elements) exports.push({export:e.name.text,local:e.propertyName?.text||e.name.text});
    }
  }
  function visit(n) {
    const prior=current;
    if(isFunction(n)) {
      const pos=ast.getLineAndCharacterOfPosition(n.getStart(ast));
      let id=n.name?name(n.name):'<anonymous>';
      if(id==='<anonymous>' && ts.isVariableDeclaration(n.parent)) id=name(n.parent.name);
      if(id==='<anonymous>' && ts.isPropertyAssignment(n.parent)) id=name(n.parent.name);
      const fn={name:id,line:pos.line+1,column:pos.character+1,parameters:n.parameters.length,calls:[]};
      functions.push(fn); current=fn;summary.functions++;
    }
    if(ts.isCallExpression(n)||ts.isNewExpression(n)) {
      calls++; summary.callExpressions++;
      const target=n.expression?.getText(ast).slice(0,120)||'<unknown>';
      if(current) current.calls.push({name:target,line:ast.getLineAndCharacterOfPosition(n.getStart(ast)).line+1});
      if(ts.isCallExpression(n) && n.expression.kind===ts.SyntaxKind.ImportKeyword && n.arguments?.length===1 && ts.isStringLiteral(n.arguments[0])) {
        imports.push(n.arguments[0].text);summary.dynamicLiteralImports++;
      }
    }
    ts.forEachChild(n,visit);
    if(isFunction(n)) current=prior;
  }
  visit(ast);
  const missing=[];
  for(const dep of imports){if(dep.startsWith('.')&&!fs.existsSync(path.resolve(src,path.dirname(file),dep)))missing.push(dep);}
  summary.missingRelativeImports += missing.length;
  index[file]={sha256:crypto.createHash('sha256').update(code).digest('hex'),bytes:Buffer.byteLength(code),functionCount:functions.length,callCount:calls,imports:[...new Set(imports)].sort(),exports,missing};
  detailed[file]=functions;
}
summary.sourceFingerprint=fingerprint.digest('hex');
assert.equal(summary.parseFailed,0);
assert.equal(summary.missingRelativeImports,0);
// Hash the actual generated module and callsite indexes as well as the source.
// This detects changes to the indexer algorithm even when source bytes do not change.
const moduleIndexText = JSON.stringify(index);
const callsiteIndexText = JSON.stringify(detailed);
summary.moduleIndexFingerprint = crypto.createHash('sha256').update(moduleIndexText).digest('hex');
summary.functionCallsitesFingerprint = crypto.createHash('sha256').update(callsiteIndexText).digest('hex');
const text=JSON.stringify(summary,null,2)+'\n';
if(process.argv.includes('--check')) assert.equal(fs.readFileSync(summaryPath,'utf8'),text,'source index is stale');
else {
  fs.mkdirSync(generated,{recursive:true});
  fs.writeFileSync(summaryPath,text);
  fs.writeFileSync(path.join(generated,'module-index.json'),moduleIndexText);
  if(process.argv.includes('--full')) fs.writeFileSync(path.join(generated,'function-callsites.json'),callsiteIndexText);
}
