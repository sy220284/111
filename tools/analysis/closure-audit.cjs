'use strict';
// Consolidate independently sourced evidence without promoting candidates to verified semantics.
// No runtime calls, network access, third-party downloads, or source mutation.
const fs=require('node:fs');
const path=require('node:path');
const crypto=require('node:crypto');
const {scan:scanBun}=require('../dev/bun-jsc-compat.cjs');
const {repoGraph}=require('./code-graph.cjs');
const ROOT=path.resolve(__dirname,'../..');
const sha=x=>crypto.createHash('sha256').update(x).digest('hex');
function hashLargeFile(file){
  const hash=crypto.createHash('sha256'),buffer=Buffer.allocUnsafe(1024*1024);
  const fd=fs.openSync(file,'r');
  try {while(true){const n=fs.readSync(fd,buffer,0,buffer.length,null);
    if(!n)break;hash.update(buffer.subarray(0,n));}}
  finally {fs.closeSync(fd);}
  return hash.digest('hex');
}
function inspectArchive(file,expected){
  if(!file)return {exists:false,kind:'missing_full_2.1.88_or_v40_archive'};
  const exists=fs.existsSync(file) && fs.statSync(file).isFile() &&
    /\.(?:tar\.zst|tar\.gz|tar|zip)$/.test(file);
  if(!exists)return {exists:false,kind:'not_an_available_archive',file:path.basename(file)};
  const sha256=hashLargeFile(file);
  const verified=!!expected && /^[a-f0-9]{64}$/i.test(expected) &&
    sha256===expected.toLowerCase();
  return {exists:true,file:path.basename(file),sha256,sha256Verified:verified,
    evidence:verified?'archive_digest_verified_only':'archive_present_digest_unverified'};
}
function loadJSON(file){return JSON.parse(fs.readFileSync(file,'utf8'));}
function validate(graph,bun,v40,ledger,aliases,fnSummary){
  const problems=[];
  if(graph.errors?.length)problems.push('graph parsing/dependency errors: '+graph.errors.length);
  if(bun.parseErrors?.length)problems.push('bun AST parse errors: '+bun.parseErrors.length);
  if(graph.summary.modules!==2553)problems.push('missing 2551 main and 2 runtime modules');
  if(bun.scannedModules!==2551)problems.push('bun scanner main module count mismatch');
  if(fnSummary.modules!==2551)problems.push('function index main module count mismatch');
  if(graph.summary.callExpressions<fnSummary.callExpressions)
    problems.push('graph unexpectedly has fewer call expressions than main-module index');
  if(graph.summary.functionEntities<fnSummary.functions)
    problems.push('graph missed function identities from main-module AST inventory');
  if(graph.summary.recordedSemantics!==ledger.entries.length)problems.push('ledger count inconsistent');
  if(v40.record_count!==v40.items.length || v40.resolved_export_count!==v40.items.length)
    problems.push('v40 location map size inconsistent');
  if(!Array.isArray(aliases.entries))problems.push('alias manifest lacks entries');
  return problems;
}
function buildReport({graph,bun,v40,ledger,aliases,fnSummary,referenceArchive=null,probe=null}){
  const problems=validate(graph,bun,v40,ledger,aliases,fnSummary);
  const capabilities=bun.capabilities.map(c=>({
    api:c.api,sourceOccurrences:c.occurrences,observed:c.observed,
    locations:c.locations,
    bunReferenceProbe:probe?.checks?.find(p=>p.api===c.api)?.status||'not_tested_in_this_report',
    nodeBehavior:'not_proven_by_static_scan_or_bun_reference_probe'
  }));
  const historical=v40.items.map(entry=>({
    symbolName:entry.symbolName,source:'v40-p0-export-location-map',
    status:'definition_or_export_location_only_not_semantic_equivalence',
    definitions:(entry.definitions||[]).map(d=>({file:'src/'+d.module,symbol:d.symbol,
      observedModule:graph.modules.some(m=>m.file==='src/'+d.module)}))
  }));
  const status={
    codeGraph:'static_candidates_only',
    semanticLedger:'targeted_behavior_tests_not_end_to_end',
    bunRuntime:probe?.status||'not_run_in_this_report',
    nodeBunParity:'unverified',
    historical2188Comparison:referenceArchive?.sha256Verified?'archive_verified_still_requires_real_function_matching':
      referenceArchive?.exists?'blocked_archive_digest_unverified':'blocked_no_verified_archive',
    fullAgentDaemonBusinessFlows:'not_proven_by_these_reports'
  };
  return {schema:1,kind:'111-code-semantic-compat-closure-audit',
    status:problems.length?'failed':'audited_with_open_gaps',
    problems,version:'2.1.310',functionSourceFingerprint:fnSummary.sourceFingerprint,
    evidenceInputs:{
      codeGraphSchema:graph.schema, bunScanSchema:bun.schema,
      v40SymbolMapSha256:sha(JSON.stringify(v40)),
      ledgerEntries:ledger.entries.length, restoredAliasEntries:aliases.entries.length,
      referenceArchive:referenceArchive||{exists:false,kind:'missing_full_2.1.88_or_v40_archive'}
    },
    metrics:{...graph.summary,bunApiKinds:capabilities.length,
      bunObservedKinds:capabilities.filter(c=>c.observed).length,
      bunSourceOccurrences:capabilities.reduce((n,c)=>n+c.sourceOccurrences,0),
      historicalLocatedRecords:historical.length},
    statusMatrix:status,
    verifiedSemanticSample:ledger.entries.slice(0,25).map(e=>({
      source:e.source,symbol:e.symbol,role:e.role,test:e.test,status:e.status
    })),
    historicalLocations:historical,
    bunCapabilities:capabilities,
    remainingEvidence:{
      unverifiedCalls:graph.summary.unresolvedCallExpressions,
      semanticRecordsWithoutFullE2E:ledger.entries.length,
      nodeAdapterParityVerified:false,
      historicalFullMatchVerified:false,
      dynamicRuntimeCallEdgesVerified:false,
      detailedFlags:['needs real 2.1.88 tree for batch matcher',
        'needs complete v40 archive to verify hash and ELF evidence',
        'needs Bun/Node business-chain behavioral differential tests',
        'needs opt-in execution harness for external services and credentials',
        'needs licensed CodeQL/Joern and additional analyzers before claiming their results']
    }
  };
}
function main(argv){
  let graphFile=null,bunFile=null,output=null,probeFile=null,archive=null,archiveExpected=null;
  while(argv.length){
    const flag=argv.shift(),val=argv.shift();
    if(!val)throw Error('missing value for '+flag);
    if(flag==='--graph')graphFile=val;
    else if(flag==='--bun-report')bunFile=val;
    else if(flag==='--probe')probeFile=val;
    else if(flag==='--reference-archive')archive=val;
    else if(flag==='--reference-sha256')archiveExpected=val;
    else if(flag==='--output')output=val;
    else throw Error('unknown option '+flag);
  }
  const graph=graphFile?loadJSON(graphFile):repoGraph(ROOT);
  const bun=bunFile?loadJSON(bunFile):scanBun(ROOT);
  const v40=loadJSON(path.join(ROOT,'resources/research/v40-p0-symbol-locations.json'));
  const ledger=loadJSON(path.join(ROOT,'resources/function-verification-ledger.json'));
  const aliases=loadJSON(path.join(ROOT,'resources/restored-symbol-aliases.json'));
  const fnSummary=loadJSON(path.join(ROOT,'resources/function-summary.json'));
  const probe=probeFile?loadJSON(probeFile):null;
  const referenceArchive=inspectArchive(archive,archiveExpected);
  const report=buildReport({graph,bun,v40,ledger,aliases,fnSummary,referenceArchive,probe});
  const result=JSON.stringify(report,null,2)+'\n';
  if(output){fs.mkdirSync(path.dirname(path.resolve(output)),{recursive:true});fs.writeFileSync(output,result);}
  process.stdout.write(JSON.stringify({status:report.status,problems:report.problems,metrics:report.metrics,
    statusMatrix:report.statusMatrix,fullReport:output||null},null,2)+'\n');
  if(report.problems.length)process.exitCode=1;
}
if(require.main===module){try{main(process.argv.slice(2));}catch(e){console.error(e.stack||e);process.exitCode=1;}}
module.exports={validate,buildReport,inspectArchive,hashLargeFile};
