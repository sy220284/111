'use strict';
// Strict pinned-v37 inventory and *syntax* audit for the 652 non-main files.
// This is NOT a full behavior/parity test for bundled tools, native modules, or vendor bundles.
const fs=require('node:fs');
const path=require('node:path');
const cp=require('node:child_process');
const crypto=require('node:crypto');
const ts=require('typescript');
const {isModule}=require('./audit-v37-source.cjs');
const ROOT=path.resolve(__dirname,'..');
const PIN='3c64d1669174e825fb0fbae088c5ec95edaa7339';
const vendor=new Set(['src/chart.umd.min.js','src/hljsBundle.generated.min.js','src/mermaid.min.js']);
const blobHash=b=>crypto.createHash('sha1').update('blob '+b.length+'\0').update(b).digest('hex');
const sha256=b=>crypto.createHash('sha256').update(b).digest('hex');
const checksum=(root,ref)=>{
 const o=cp.execFileSync('git',['ls-tree','-r','-z','--full-tree',ref],{cwd:root,encoding:'utf8',maxBuffer:128*1024*1024});
 const files=new Map();
 for(const line of o.split('\0')){
  const m=/^[0-7]{6} blob ([a-f0-9]{40})\t(.+)$/.exec(line);
  if(m)files.set(m[2],m[1]);
 }
 return files;
};
function category(file){
 if(file.startsWith('src/plugin-testing-kit/'))return 'plugin_testing_kit';
 if(file.startsWith('src/embedded/'))return 'independent_embedded_source';
 if(vendor.has(file))return 'prebuilt_vendor_bundle';
 if(file.startsWith('resources/tools/'))return 'bundled_tool_sources';
 if(file.startsWith('resources/'))return 'other_embedded_resources';
 if(file.startsWith('tests/'))return 'historic_tests';
 if(file.startsWith('scripts/'))return 'historic_build_scripts';
 if(file.startsWith('.github/'))return 'historic_ci';
 return 'historic_metadata_and_entrypoints';
}
function astMetrics(source,filename){
 const ast=ts.createSourceFile(filename,source,ts.ScriptTarget.Latest,true,ts.ScriptKind.JS);
 if(ast.parseDiagnostics.length)throw Error(filename+' AST parse failed: '+ts.flattenDiagnosticMessageText(ast.parseDiagnostics[0].messageText,' '));
 const imports=[],exportNames=new Set(),calls={dynamicImports:0,callExpressions:0};
 let functions=0;
 function visit(n){
  if(ts.isFunctionLike(n)&&n.body)functions++;
  if(ts.isCallExpression(n)){calls.callExpressions++;if(n.expression.kind===ts.SyntaxKind.ImportKeyword)calls.dynamicImports++;}
  ts.forEachChild(n,visit);
 }
 for(const s of ast.statements){
  if(ts.isImportDeclaration(s)&&ts.isStringLiteralLike(s.moduleSpecifier))imports.push(s.moduleSpecifier.text);
  if(ts.isExportDeclaration(s)){
   if(s.moduleSpecifier&&ts.isStringLiteralLike(s.moduleSpecifier))imports.push(s.moduleSpecifier.text);
   if(s.exportClause&&ts.isNamedExports(s.exportClause))for(const e of s.exportClause.elements)exportNames.add(e.name.text);
  }
  if(s.modifiers?.some(m=>m.kind===ts.SyntaxKind.ExportKeyword)){
   if(s.name&&ts.isIdentifier(s.name))exportNames.add(s.name.text);
   if(ts.isVariableStatement(s))for(const d of s.declarationList.declarations)if(ts.isIdentifier(d.name))exportNames.add(d.name.text);
  }
 }
 visit(ast);
 return {functions,imports:imports.sort(),exports:[...exportNames].sort(),...calls};
}
function pythonAst(files,root){
 if(!files.length)return {};
 const py=`import ast,json,sys,pathlib
root=pathlib.Path(sys.argv[1])
files=json.loads(sys.argv[2])
out={}
for name in files:
    src=(root/name).read_text(encoding="utf-8")
    tree=ast.parse(src,filename=name)
    fun=sum(isinstance(n,(ast.FunctionDef,ast.AsyncFunctionDef)) for n in ast.walk(tree))
    cls=sum(isinstance(n,ast.ClassDef) for n in ast.walk(tree))
    imp=sorted(set(a.name.split(".")[0] for n in ast.walk(tree) for a in (n.names if isinstance(n,ast.Import) else []) ))
    out[name]={"functions":fun,"classes":cls,"imports":imp}
print(json.dumps(out))
`;
 return JSON.parse(cp.execFileSync('python3',['-c',py,root,JSON.stringify(files)],{cwd:root,encoding:'utf8',maxBuffer:16*1024*1024}));
}
function build({root=ROOT,reference=PIN,verify=true}={}){
 root=path.resolve(root);
 const index=JSON.parse(fs.readFileSync(path.join(root,'resources/research/v37-nonmain-pinned-blobs.json'),'utf8'));
 if(index.commit!==reference||index.files.length!==652)throw Error('pinned v37 nonmain manifest mismatch');
 const orig=new Map(index.files.map(x=>[x.path,x.blob]));
 const problem=[],groups={},entries=[],code=[],python=[];
 if(verify){
  const gitReference=checksum(root,reference);
  const real=[...gitReference].filter(([p])=>!isModule(p));
  if(real.length!==index.files.length)problem.push('historical Git tree mismatch with pinned nonmain index');
  for(const [file,sha] of real)if(orig.get(file)!==sha)problem.push('pinned file provenance mismatch: '+file);
 }
 const relocations=new Map(JSON.parse(fs.readFileSync(path.join(root,'resources/layout/relocations.json'),'utf8')).files.map(x=>[x.old,x.current]));
 const rest=[...orig].sort(([a],[b])=>a.localeCompare(b));
 for(const [name,sha] of rest){
  const file=path.join(root,relocations.get(name)||(name.startsWith('src/')?'core/'+name.slice(4):name));
  let b=null;try{b=fs.readFileSync(file);}catch(e){problem.push('missing original file '+name+': '+e.code);continue;}
  const type=category(name),actual=blobHash(b),same=actual===sha;
  if(!groups[type])groups[type]={files:0,bytes:0,unchangedFromV37:0,modifiedFromV37:0};
  groups[type].files++;groups[type].bytes+=b.length;
  if(same)groups[type].unchangedFromV37++;else groups[type].modifiedFromV37++;
  const row={file:name,category:type,size:b.length,v37Blob:verify?sha:null,currentBlob:actual,identicalToPinnedV37:verify?same:null};
  if(type==='bundled_tool_sources' && /\.(?:mjs|js|cjs)$/.test(name)){
   try{row.ast=astMetrics(b.toString('utf8'),name);code.push(row);}
   catch(e){problem.push(String(e.message));}
  }
  if(type==='bundled_tool_sources' && name.endsWith('.py'))python.push(name);
  if(type==='prebuilt_vendor_bundle'&&!same)problem.push('vendor byte drift: '+name);
  if(['plugin_testing_kit','independent_embedded_source','bundled_tool_sources'].includes(type)&&verify&&!same)
   problem.push('v37 extracted source changed: '+name);
  if(name.endsWith('.node')){
   row.nativeFormat=b.subarray(0,4).equals(Buffer.from([127,69,76,70]))?'ELF':b.subarray(0,2).toString('hex')==='4d5a'?'PE':'unknown';
  }
  entries.push(row);
 }
 if(rest.length!==652)problem.push('pinned v37 non-main inventory changed: '+rest.length);
 for(const [kind,count] of Object.entries({plugin_testing_kit:294,independent_embedded_source:24,prebuilt_vendor_bundle:3,bundled_tool_sources:56,other_embedded_resources:212,historic_tests:44,historic_build_scripts:8,historic_ci:2,historic_metadata_and_entrypoints:9}))
  if(groups[kind]?.files!==count)problem.push(kind+' expected '+count+' got '+groups[kind]?.files);
 if(code.length!==47)problem.push('bundled executable JS/MJS/CJS expected 47 got '+code.length);
 if(python.length!==3)problem.push('bundled Python code expected 3 got '+python.length);
 let py={};
 try{py=pythonAst(python.map(p=>relocations.get(p)||p),root);}catch(e){problem.push('Python AST verification failed: '+e.message);}
 const native=entries.filter(x=>x.file.endsWith('.node'));
 if(native.length!==2||native.some(x=>x.nativeFormat!=='ELF'))problem.push('two original ELF .node modules not found');
 const metrics={
  pinnedReference:reference,nonMainFiles:rest.length,independentlyRecoveredSourceFiles:318,
  remainingOriginalEntitiesOutsideKitAndEmbedded:rest.length-318,
  bundledJsFiles:code.length,bundledPythonFiles:python.length,vendorBundles:3,nativeELFModules:native.length,
  toolSyntaxFunctions:code.reduce((n,x)=>n+x.ast.functions,0),
  toolStaticImports:code.reduce((n,x)=>n+x.ast.imports.length,0),
  toolDynamicImports:code.reduce((n,x)=>n+x.ast.dynamicImports,0),
  toolCallExpressions:code.reduce((n,x)=>n+x.ast.callExpressions,0),
  pythonFunctions:Object.values(py).reduce((n,x)=>n+x.functions,0),
  pythonClasses:Object.values(py).reduce((n,x)=>n+x.classes,0),
  unchangedPinnedToolFiles:groups.bundled_tool_sources?.unchangedFromV37||0,
  unchangedVendorBundles:groups.prebuilt_vendor_bundle?.unchangedFromV37||0,
  verifiedOriginalPluginAndEmbedded:groups.plugin_testing_kit?.unchangedFromV37+groups.independent_embedded_source?.unchangedFromV37
 };
 return {schema:1,reference:{commit:reference,verified:verify},metrics,groups,
  bundledTools:code.map(x=>({file:x.file,size:x.size,v37Blob:x.v37Blob,ast:x.ast})),
  python:py,native: native.map(x=>({file:x.file,size:x.size,format:x.nativeFormat,identicalToPinnedV37:x.identicalToPinnedV37})),
  vendor:entries.filter(x=>x.category==='prebuilt_vendor_bundle'),
  allFiles:entries,problems:problem,limits:[
   'AST functions are syntax entities, not completed semantic recoveries.',
   'Native ELF identity does not demonstrate Node/Bun ABI safety or successful device interaction.',
   'Static import analysis does not verify optional browser packages, authentication, network, or visual grading.',
   'Python scripts are AST parsed only; no paid API calls or network requests are sent.',
   'v37 pinned Git history is not proof of unprovided external tar.zst archive bytes.'
  ]};
}
function main(args){
 const opts={};
 for(let i=0;i<args.length;i+=2){const flag=args[i],v=args[i+1];if(!v)throw Error('missing '+flag);if(flag==='--output')opts.output=v;else if(flag==='--root')opts.root=v;else if(flag==='--reference')opts.reference=v;else throw Error('unknown '+flag);}
 const result=build(opts);
 if(opts.output){fs.mkdirSync(path.dirname(path.resolve(opts.output)),{recursive:true});fs.writeFileSync(opts.output,JSON.stringify(result,null,2)+'\n');}
 console.log(JSON.stringify({metrics:result.metrics,groups:result.groups,problems:result.problems,report:opts.output},null,2));
 if(result.problems.length)process.exitCode=1;
}
if(require.main===module)try{main(process.argv.slice(2))}catch(e){console.error(e.stack||String(e));process.exitCode=1}
module.exports={category,astMetrics,blobHash,build};
