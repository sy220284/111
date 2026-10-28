'use strict';
// 111 Bun/JSC gap audit: static evidence, never a compatibility shim.
const fs = require('node:fs');
const path = require('node:path');
const cp = require('node:child_process');
const ts = require('typescript');
const ROOT = path.resolve(__dirname, '../..');
const OMIT = new Set(['chart.umd.min.js', 'hljsBundle.generated.min.js', 'mermaid.min.js']);
const CORE = ['Bun.spawn', 'Bun.spawnSync', 'Bun.Terminal', 'Bun.SQL', 'Bun.ant',
              'bun:sqlite', 'bun:jsc'];
function isGlobal(expr, name) {
  if (ts.isIdentifier(expr) && expr.text === name) return true;
  if (name !== 'Bun') return false;
  return (ts.isPropertyAccessExpression(expr) && expr.name.text === 'Bun' &&
          ts.isIdentifier(expr.expression) && expr.expression.text === 'globalThis') ||
         (ts.isElementAccessExpression(expr) &&
          ts.isIdentifier(expr.expression) && expr.expression.text === 'globalThis' &&
          ts.isStringLiteralLike(expr.argumentExpression) &&
          expr.argumentExpression.text === 'Bun');
}
function property(expr) {
  if (ts.isPropertyAccessExpression(expr)) return expr.name.text;
  if (ts.isElementAccessExpression(expr))
    return ts.isStringLiteralLike(expr.argumentExpression) ? expr.argumentExpression.text : '<dynamic>';
  return null;
}
function directBunMember(expr) {
  return (ts.isPropertyAccessExpression(expr) || ts.isElementAccessExpression(expr)) &&
    isGlobal(expr.expression, 'Bun') ? property(expr) : null;
}
function scanText(code, filename) {
  const ast = ts.createSourceFile(filename, code, ts.ScriptTarget.Latest, true, ts.ScriptKind.JS);
  const errors = ast.parseDiagnostics.map(d => ({
    file: filename, offset: d.start, message: ts.flattenDiagnosticMessageText(d.messageText, ' ')
  }));
  const sites = [];
  function record(api, n, via) {
    const pos = ast.getLineAndCharacterOfPosition(n.getStart(ast));
    sites.push({ api, file: filename, line: pos.line + 1, column: pos.character + 1, via });
  }
  function visit(n) {
    if ((ts.isPropertyAccessExpression(n) || ts.isElementAccessExpression(n))) {
      const direct = directBunMember(n);
      if (direct !== null) {
        // A Bun.ant.method expression counts as one precise API site.
        if (direct === 'ant' && n.parent &&
            (ts.isPropertyAccessExpression(n.parent) || ts.isElementAccessExpression(n.parent)) &&
            n.parent.expression === n) {
          record('Bun.ant.' + property(n.parent), n.parent, 'member');
        } else if (direct !== 'ant' || !(n.parent &&
                  (ts.isPropertyAccessExpression(n.parent) || ts.isElementAccessExpression(n.parent)) &&
                  n.parent.expression === n)) {
          record('Bun.' + direct, n, 'member');
        }
      }
    }
    if (ts.isVariableDeclaration(n) && ts.isObjectBindingPattern(n.name) &&
        n.initializer && isGlobal(n.initializer, 'Bun')) {
      for (const e of n.name.elements) {
        const key = e.propertyName ? property(e.propertyName) || e.propertyName.getText(ast)
                                  : e.name.getText(ast);
        record('Bun.' + key, e, 'destructure');
      }
    }
    if ((ts.isImportDeclaration(n) || ts.isExportDeclaration(n)) &&
        n.moduleSpecifier && ts.isStringLiteralLike(n.moduleSpecifier) &&
        /^(bun:|bun$)/.test(n.moduleSpecifier.text)) {
      record(n.moduleSpecifier.text, n, 'module');
    }
    if (ts.isCallExpression(n) && n.expression.kind === ts.SyntaxKind.ImportKeyword &&
        n.arguments.length === 1 && ts.isStringLiteralLike(n.arguments[0]) &&
        /^(bun:|bun$)/.test(n.arguments[0].text)) {
      record(n.arguments[0].text, n, 'dynamic-import');
    }
    ts.forEachChild(n, visit);
  }
  visit(ast);
  return { errors, sites };
}
function scan(root = ROOT) {
  const base = path.resolve(root);
  const src = path.join(base,'core');
  const names = fs.readdirSync(src).filter(x => x.endsWith('.js') && !OMIT.has(x)).sort();
  const worker = 'plugins/functionHooks/hooks-worker/hooks-worker.js';
  const paths = names.map(x => 'core/' + x);
  if (fs.existsSync(path.join(base, worker))) paths.push(worker);
  const entries = [], errors = [];
  for (const filename of paths) {
    const output = scanText(fs.readFileSync(path.join(base, filename), 'utf8'), filename);
    entries.push(...output.sites);
    errors.push(...output.errors);
  }
  entries.sort((a,b) => a.file.localeCompare(b.file) || a.line - b.line || a.column - b.column);
  const APIs = [...new Set([...CORE, ...entries.map(s=>s.api)])].sort();
  const capabilities = APIs.map(api => {
    const sites = entries.filter(s => s.api === api);
    return {
      api, observed: sites.length > 0, occurrences: sites.length,
      nodeStatus: sites.length ? 'requires_behavior_verification' : 'not_observed_in_scanned_source',
      locations: sites
    };
  });
  return { schema: 1, kind: 'source-audit', scope: '2551 main source modules (not embedded vendor/library code)',
    scannedModules: paths.length, parseErrors: errors, capabilities,
    limitations: [
      'Static AST discovery only; runtime aliases or computed Bun properties can escape detection.',
      'A Bun reference does not establish whether that branch executes on Node.',
      'An absent API is not proof that the original ELF never used it.',
      'Bun reference probes do not establish parity for the Claude Code call chain.'
    ] };
}
function probe(bun = 'bun') {
  const script = path.join(ROOT, 'tools/dev/bun-jsc-probe.mjs');
  try {
    const result = cp.execFileSync(bun, [script], {
      cwd: ROOT, encoding:'utf8', timeout: 25000, maxBuffer: 1024*1024,
      env: { ...process.env, CI: 'true' }
    });
    const parsed = JSON.parse(result.trim());
    if (parsed.schema !== 1 || parsed.kind !== 'bun-runtime-probe' || !Array.isArray(parsed.checks))
      throw new Error('Invalid probe schema');
    return parsed;
  } catch (error) {
    return { schema: 1, kind: 'bun-runtime-probe', status: 'unavailable_or_failed',
      message: String(error.message || error).slice(0, 1200),
      checks: [] };
  }
}
function main(args) {
  const command = args.shift() || 'scan';
  if (!['scan', 'report'].includes(command)) throw Error('Usage: bun-jsc-compat.cjs scan|report [--json] [--output PATH] [--bun PATH] [--require-bun]');
  let output = null, bun = 'bun', requireBun = false;
  while (args.length) {
    const flag = args.shift();
    if (flag === '--json') continue;
    if (flag === '--output') { output = args.shift(); if (!output) throw Error('Missing --output value'); continue; }
    if (flag === '--bun') { bun = args.shift(); if (!bun) throw Error('Missing --bun value'); continue; }
    if (flag === '--require-bun') { requireBun = true; continue; }
    throw Error('Unknown option ' + flag);
  }
  const result = scan();
  if (command === 'report') {
    result.runtimeProbe = probe(bun);
    const checks = result.runtimeProbe.checks || [];
    if (requireBun && (result.runtimeProbe.status !== 'passed' ||
         !checks.length || checks.some(x => x.status !== 'passed' && x.status !== 'not_verified'))) {
      process.exitCode = 1;
    }
  } else if (requireBun) throw Error('--require-bun needs report');
  if (result.parseErrors.length) process.exitCode = 1;
  const text = JSON.stringify(result, null, 2) + '\n';
  if (output) { fs.mkdirSync(path.dirname(path.resolve(output)), {recursive:true}); fs.writeFileSync(output, text); }
  process.stdout.write(text);
}
if (require.main === module) {
  try { main(process.argv.slice(2)); }
  catch (error) { console.error(error.stack || String(error)); process.exitCode = 1; }
}
module.exports = { scanText, scan, probe };
