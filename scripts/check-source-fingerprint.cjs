'use strict';
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const assert = require('node:assert/strict');
const root=path.resolve(__dirname,'..');
const src=path.join(root,'src');
const manifest=JSON.parse(fs.readFileSync(path.join(root,'resources/function-summary.json'),'utf8'));
const files=fs.readdirSync(src).filter(n=>n.endsWith('.js') &&
  !['chart.umd.min.js','hljsBundle.generated.min.js','mermaid.min.js'].includes(n)).sort();
files.push('src/plugins/functionHooks/hooks-worker/hooks-worker.js');
assert.equal(files.length,manifest.modules,'2.1.310 main module count changed');
const hash=crypto.createHash('sha256');
for(const name of files){
  const code=fs.readFileSync(path.join(src,name),'utf8');
  hash.update(name+'\0').update(code).update('\0');
}
const actual=hash.digest('hex');
const result={version:manifest.version,modules:files.length,actualFingerprint:actual,
  recordedFingerprint:manifest.sourceFingerprint,matched:actual===manifest.sourceFingerprint};
console.log(JSON.stringify(result));
if(process.argv.includes('--check'))assert.equal(actual,manifest.sourceFingerprint,
  'AST function index fingerprint stale; regenerate resources/function-summary.json using scripts/source-function-index.cjs');
