'use strict';
const assert = require('node:assert/strict');
const { execFileSync } = require('node:child_process');
const path = require('node:path');
const stdout = execFileSync(process.execPath, [path.join(__dirname, '../core/cli.js'), '--version'], { encoding:'utf8', timeout:12000 });
assert.match(stdout, /^2\.1\.310 \(Claude Code\)/m);
console.log('PASS: 2.1.310 CLI --version');
