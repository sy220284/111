'use strict';
const test=require('node:test'),assert=require('node:assert/strict');
const {analyze}=require('../../tools/analysis/code-graph.cjs');
const {buildReport,inspectArchive}=require('../../tools/analysis/closure-audit.cjs');
test('evidence is not promoted to runtime equivalence; missing archives stay blocked',()=>{
  const graph=analyze({'src/a.js':'function fn(){};fn()' },[{source:'src/a.js',symbol:'fn',role:'test',test:'tests/example.test.cjs',status:'targeted_behavior_test_only'}]);
  // Minimal fake fixtures exercise the report builder without pretending to scan the full repo.
  graph.summary.modules=2553;
  graph.summary.recordedSemantics=1;
  const bun={schema:1,scannedModules:2551,parseErrors:[],capabilities:[
    {api:'Bun.spawn',observed:true,occurrences:1,locations:[{file:'src/a.js',line:1}]}
  ]};
  const v40={record_count:1,resolved_export_count:1,items:[
    {symbolName:'sample',definitions:[{module:'a.js',symbol:'fn'}]}
  ]};
  const ledger={entries:[{source:'src/a.js',symbol:'fn',role:'test',test:'tests/example.test.cjs',status:'targeted_behavior_test_only'}]};
  const aliases={entries:[]};
  const summary={modules:2551,functions:1,callExpressions:1,sourceFingerprint:'test'};
  const report=buildReport({graph,bun,v40,ledger,aliases,fnSummary:summary});
  assert.deepEqual(report.problems,[]);
  assert.equal(report.status,'audited_with_open_gaps');
  assert.equal(report.statusMatrix.historical2188Comparison,'blocked_no_verified_archive');
  assert.equal(report.bunCapabilities[0].nodeBehavior,'not_proven_by_static_scan_or_bun_reference_probe');
  assert.equal(report.historicalLocations[0].status,'definition_or_export_location_only_not_semantic_equivalence');
});
test('inconsistent metrics fail rather than silently manufacture evidence',()=>{
  const graph=analyze({'src/a.js':'function fn(){}'});
  const bun={scannedModules:2551,parseErrors:[],capabilities:[]};
  const v40={record_count:0,resolved_export_count:0,items:[]};
  const report=buildReport({graph,bun,v40,ledger:{entries:[]},aliases:{entries:[]},
    fnSummary:{modules:2551,functions:20,callExpressions:0,sourceFingerprint:'fixture'}});
  assert.equal(report.status,'failed');
  assert.ok(report.problems.some(e=>e.includes('function identities')));
});

test('sha256 checksum-only file is not mistaken for full v40 research archive',()=>{
  const path=require('node:path'),os=require('node:os'),fs=require('node:fs');
  const dir=fs.mkdtempSync(path.join(os.tmpdir(),'archive-evidence-'));
  try {
    const checksum=path.join(dir,'study.tar.zst.sha256');
    fs.writeFileSync(checksum,'a'.repeat(64)+'  study.tar.zst\n');
    assert.equal(inspectArchive(checksum).exists,false);
    const archive=path.join(dir,'study.tar.zst');
    fs.writeFileSync(archive,'fixture only');
    const unverified=inspectArchive(archive);
    assert.equal(unverified.sha256Verified,false);
    assert.equal(inspectArchive(archive,unverified.sha256).sha256Verified,true);
    assert.equal(inspectArchive(archive,'0'.repeat(64)).sha256Verified,false);
  }finally{fs.rmSync(dir,{recursive:true,force:true});}
});
