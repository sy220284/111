'use strict';
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const root=path.resolve(__dirname,'..');
const aliases=JSON.parse(fs.readFileSync(path.join(root,'resources/restored-symbol-aliases.json'),'utf8'));
const ledger=JSON.parse(fs.readFileSync(path.join(root,'resources/function-verification-ledger.json'),'utf8'));
assert.equal(aliases.version,ledger.version);
assert.ok(aliases.entries.length>0);
const valid=new Set(['exportAlias','constAlias','inlineVar']);
const seen=new Set(),coveredFiles=new Set();
const escapeRegExp=txt=>txt.replace(/[.*+?^${}()|[\]\\]/g,'\\$&');
for(const {source,legacy,readable,style} of aliases.entries){
 assert.ok(valid.has(style),'unsupported alias style '+style);
 const id=source+'::'+legacy;
 assert.ok(!seen.has(id),'duplicate alias claim '+id);
 seen.add(id);
 const text=fs.readFileSync(path.join(root,source),'utf8');
 const pattern=style==='exportAlias'
  ? readable+' as '+legacy
  : style==='constAlias'?'const '+legacy+'='+readable
  :'var '+legacy+'=function '+readable;
 assert.ok(text.includes(pattern),'restored alias overwritten: '+id+' expected '+pattern);
 assert.ok(text.includes('function '+readable+'('),
   'readable implementation missing: '+id);
 const evidence=ledger.entries.find(x=>x.source===source&&x.symbol===legacy);
 assert.ok(evidence && evidence.status==='targeted_behavior_test_only',
   'function evidence missing: '+id);
 const test=fs.readFileSync(path.join(root,evidence.test),'utf8');
 assert.ok(test.includes(path.basename(source)),'test no longer points to original module: '+id);
 coveredFiles.add(source);
}
console.log(JSON.stringify({
 result:'PASS',version:aliases.version,guardedLegacyAliases:seen.size,sourceModules:coveredFiles.size,
 testedOriginalFunctions:ledger.entries.length,completeSemanticRestorationsCertified:0
}));
