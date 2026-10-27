'use strict';
const assert=require('node:assert/strict');

// Extract the closing declaration cluster before the module's last export statement.
// The export list is allowed to grow, reorder names or change whitespace. We still
// require the requested source symbol to exist, so meaningful source removals fail.
function fragmentToModuleExports(source, anchor) {
 assert.equal(typeof source,'string');
 assert.equal(typeof anchor,'string');
 const start=source.indexOf(anchor);
 const exports=[...source.matchAll(/(?:^|\n)export\s*\{/g)];
 const finalExport=exports.at(-1);
 assert.ok(start>=0 && finalExport && finalExport.index>start,'source fragment missing or no trailing module export: '+anchor);
 return source.slice(start,finalExport.index);
}
module.exports={fragmentToModuleExports};
