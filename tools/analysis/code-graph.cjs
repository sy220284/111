'use strict';
// Evidence-aware module/function graph for the recovered Claude Code JS.
// This is NOT whole-program devirtualization, execution, or semantic equivalence.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const ts = require('typescript');
const EXCLUDE = new Set(['chart.umd.min.js','hljsBundle.generated.min.js','mermaid.min.js']);
const WORKER = 'src/src/plugins/functionHooks/hooks-worker/hooks-worker.js';
const functionLike = n => ts.isFunctionDeclaration(n)||ts.isFunctionExpression(n)||
  ts.isArrowFunction(n)||ts.isMethodDeclaration(n)||ts.isConstructorDeclaration(n)||
  ts.isGetAccessorDeclaration(n)||ts.isSetAccessorDeclaration(n);
const normalize = x => x.split(path.sep).join('/');
function inputFiles(root) {
  const src = path.join(root,'src');
  const names = fs.readdirSync(src).filter(x=>x.endsWith('.js')&&!EXCLUDE.has(x)).sort();
  const files = names.map(x=>'src/'+x);
  if (fs.existsSync(path.join(root, WORKER))) files.push(WORKER);
  for (const runtime of ['runtime/assets.mjs', 'runtime/string-width.mjs'])
    if (fs.existsSync(path.join(root, runtime))) files.push(runtime);
  return files;
}
function definitionName(n) {
  if(ts.isFunctionDeclaration(n) && n.name) return n.name.text;
  if(ts.isVariableStatement(n)) return null;
  if(ts.isClassDeclaration(n) && n.name) return n.name.text;
  return null;
}
function resolve(file, source, known) {
  if(!source.startsWith('.')) return null;
  const key = path.posix.normalize(path.posix.join(path.posix.dirname(file),source));
  for(const candidate of [key,key+'.js',key+'.mjs',key+'/index.js'])
    if(known.has(candidate)) return candidate;
  return null;
}
function analyze(sources, ledger = []) {
  const known=new Set(Object.keys(sources)), parsed=new Map(), errors=[];
  const files=[...known].sort();
  for(const file of files){
    const ast=ts.createSourceFile(file,sources[file],ts.ScriptTarget.Latest,true,ts.ScriptKind.JS);
    if(ast.parseDiagnostics.length) errors.push(...ast.parseDiagnostics.map(d=>({file,offset:d.start,
      message:ts.flattenDiagnosticMessageText(d.messageText,' ')})));
    parsed.set(file,ast);
  }
  const modules=new Map(), links=[], external=new Map(), functions=new Map();
  const stats={calls:0,resolvedCalls:0,unresolvedCalls:0,dynamicImports:0};
  for(const file of files) {
    const ast=parsed.get(file), importBindings=new Map(), deps=new Set(), exports=new Map(),
      defs=new Map(), exportAll=[];
    function addDependency(spec,kind,at){
      const target=resolve(file,spec,known);
      if (target) deps.add(target);
      else if(spec.startsWith('.')) errors.push({file,offset:at,message:'unresolved relative '+spec});
      else external.set(spec,(external.get(spec)||0)+1);
      links.push({from:file,to:target||spec,kind,resolveStatus:target?'resolved':'external_or_unresolved'});
      return target;
    }
    for(const stmt of ast.statements) {
      const top=definitionName(stmt);
      if(top) defs.set(top,{file,symbol:top});
      if(ts.isVariableStatement(stmt)) for(const d of stmt.declarationList.declarations)
        if(ts.isIdentifier(d.name) && d.initializer && functionLike(d.initializer))
          defs.set(d.name.text,{file,symbol:d.name.text});
      if(ts.isImportDeclaration(stmt) && ts.isStringLiteralLike(stmt.moduleSpecifier)){
        const target=addDependency(stmt.moduleSpecifier.text,'import',stmt.getStart(ast));
        const clause=stmt.importClause;
        if (clause?.name) importBindings.set(clause.name.text,{target,export:'default'});
        if (clause?.namedBindings && ts.isNamedImports(clause.namedBindings))
          for(const e of clause.namedBindings.elements)
            importBindings.set(e.name.text,{target,export:e.propertyName?.text||e.name.text});
        if (clause?.namedBindings && ts.isNamespaceImport(clause.namedBindings))
          importBindings.set(clause.namedBindings.name.text,{target,namespace:true});
      }
      if(ts.isExportDeclaration(stmt)){
        const spec=stmt.moduleSpecifier && ts.isStringLiteralLike(stmt.moduleSpecifier) ?
          stmt.moduleSpecifier.text : null;
        const target=spec?addDependency(spec,'re-export',stmt.getStart(ast)):null;
        if(stmt.exportClause && ts.isNamedExports(stmt.exportClause))
          for(const e of stmt.exportClause.elements)
            exports.set(e.name.text,{file:target||file,local:e.propertyName?.text||e.name.text});
        else if(target) exportAll.push(target);
      }
    }
    modules.set(file,{file,imports:importBindings,deps,defs,exports,exportAll});
  }
  function exportTarget(file,symbol,seen=new Set()){
    if(!file || seen.has(file+'#'+symbol)) return null;
    seen.add(file+'#'+symbol);
    const m=modules.get(file);
    if(!m) return null;
    const out=m.exports.get(symbol);
    if(out) {
      if(out.file!==file) return exportTarget(out.file,out.local,seen);
      if(m.defs.has(out.local))return {file,symbol:out.local};
      const binding=m.imports.get(out.local);
      if(binding?.target) return exportTarget(binding.target,binding.export,seen);
    }
    if(m.defs.has(symbol)) return {file,symbol};
    for(const other of m.exportAll) {
      const found=exportTarget(other,symbol,seen);
      if(found)return found;
    }
    return null;
  }
  const edges=new Map();
  function addEdge(from,to,kind,file,ast,node){
    const targetKey=to?to.file+'#'+to.symbol:'<unknown>';
    const key=from+' -> '+targetKey+' ['+kind+']';
    const p=ast.getLineAndCharacterOfPosition(node.getStart(ast));
    let e=edges.get(key);
    if(!e){e={from,to:to||null,kind,count:0,evidence:[]};edges.set(key,e);}
    e.count++;
    if(e.evidence.length<3)e.evidence.push({file,line:p.line+1,column:p.character+1});
  }
  for(const file of files){
    const ast=parsed.get(file), m=modules.get(file);
    let current=null;
    function visit(node) {
      const before=current;
      if(functionLike(node)) {
        let label=ts.isIdentifier(node.name)?node.name.text:null;
        if(!label && ts.isVariableDeclaration(node.parent) &&
           ts.isIdentifier(node.parent.name))label=node.parent.name.text;
        if(!label && ts.isPropertyAssignment(node.parent))label=node.parent.name.getText(ast);
        const p=ast.getLineAndCharacterOfPosition(node.getStart(ast));
        current={file,symbol:label||'<anonymous>@'+(p.line+1),line:p.line+1};
        functions.set(file+'#'+current.symbol+'@'+current.line,current);
      }
      if(ts.isCallExpression(node)||ts.isNewExpression(node)) {
        stats.calls++;
        const exp=node.expression, owner=current ?
          file+'#'+current.symbol+'@'+current.line :file+'#<module>';
        let target=null,kind='dynamic_or_property';
        if(ts.isIdentifier(exp)){
          const binding=m.imports.get(exp.text);
          if(binding?.target && !binding.namespace) {
            target=exportTarget(binding.target,binding.export);
            if(target)kind='import_binding_candidate';
          }else if(m.defs.has(exp.text)){
            target=m.defs.get(exp.text);
            kind='same_module_candidate';
          }
        }else if(ts.isPropertyAccessExpression(exp)&&ts.isIdentifier(exp.expression)){
          const ns=m.imports.get(exp.expression.text);
          if(ns?.target && ns.namespace){
            target=exportTarget(ns.target,exp.name.text);
            if(target)kind='namespace_import_candidate';
          }
        }
        if(target)stats.resolvedCalls++;else stats.unresolvedCalls++;
        // Keep unresolved call counts without fabricating a callee.
        if(target)addEdge(owner,target,kind,file,ast,node);
        if(ts.isCallExpression(node) && exp.kind===ts.SyntaxKind.ImportKeyword)stats.dynamicImports++;
      }
      ts.forEachChild(node,visit);
      current=before;
    }
    visit(ast);
  }
  const uniqueEdges=[...edges.values()].sort((a,b)=>a.from.localeCompare(b.from)||a.kind.localeCompare(b.kind));
  const moduleDeps=[...modules.values()].map(m=>({file:m.file,dependsOn:[...m.deps].sort()}));
  const reverse=new Map(files.map(f=>[f,[]]));
  for(const entry of moduleDeps)for(const d of entry.dependsOn)reverse.get(d)?.push(entry.file);
  for(const r of reverse.values())r.sort();
  // Tarjan strongly connected components; cycles are facts about static modules, not executions.
  let counter=0;
  const stack=[],indices=new Map(),low=new Map(),onStack=new Set(),cycles=[];
  function strong(v){
    indices.set(v,counter);low.set(v,counter++);stack.push(v);onStack.add(v);
    for(const w of modules.get(v).deps){
      if(!indices.has(w)){strong(w);low.set(v,Math.min(low.get(v),low.get(w)));}
      else if(onStack.has(w))low.set(v,Math.min(low.get(v),indices.get(w)));
    }
    if(low.get(v)===indices.get(v)){
      const s=[];let w;do{w=stack.pop();onStack.delete(w);s.push(w)}while(w!==v);
      if(s.length>1 || modules.get(v).deps.has(v))cycles.push(s.sort());
    }
  }
  for(const f of files)if(!indices.has(f))strong(f);
  const semantics=ledger.map(e=>({
    file:e.source,symbol:e.symbol,role:e.role,test:e.test,status:e.status,
    evidence:'targeted_test_ledger_only'
  }));
  return {
    schema:1,kind:'111-evidence-code-graph',errors,
    summary:{modules:files.length,moduleDependencies:moduleDeps.reduce((a,b)=>a+b.dependsOn.length,0),
      externalSpecifiers:[...external.keys()].sort(),cyclicGroups:cycles.length,
      functionEntities:functions.size,callExpressions:stats.calls,
      candidateResolvedCalls:stats.resolvedCalls,unresolvedCallExpressions:stats.unresolvedCalls,
      uniqueCandidateEdges:uniqueEdges.length,recordedSemantics:semantics.length,
      warning:'Calls are syntax/lexical/import candidates, NOT proven runtime call targets. Ledger roles are targeted-test evidence, NOT full semantic restoration.'},
    modules:moduleDeps, cycles, callEdges:uniqueEdges, semantics
  };
}
function impact(graph,file){
  const reverse=new Map(graph.modules.map(m=>[m.file,[]]));
  for(const m of graph.modules)for(const dep of m.dependsOn)reverse.get(dep)?.push(m.file);
  if(!reverse.has(file))throw Error('unknown source module: '+file);
  const queue=[file],visited=new Set([file]);
  for(let i=0;i<queue.length;i++)for(const p of reverse.get(queue[i])||[])
    if(!visited.has(p)){visited.add(p);queue.push(p);}
  return {entry:file,affectedModuleCount:visited.size-1,reverseDependents:queue.slice(1).sort(),
    evidence:'static-import-graph-only'};
}
function trace(graph,file,symbol) {
  if(!graph.modules.some(m=>m.file===file))throw Error('unknown source module: '+file);
  const prefix=file+'#'+symbol+'@';
  const outbound=graph.callEdges.filter(e=>e.from.startsWith(prefix));
  const inbound=graph.callEdges.filter(e=>e.to?.file===file&&e.to.symbol===symbol);
  return {entry:{file,symbol},outbound,inbound,
    semantics:graph.semantics.filter(e=>e.file===file&&e.symbol===symbol),
    note:'Candidate call edges need runtime / type-flow verification; not fully resolved dynamic dispatch.'};
}
function repoGraph(root) {
  const sources={};
  for(const file of inputFiles(root))sources[file]=fs.readFileSync(path.join(root,file),'utf8');
  const file=path.join(root,'resources/function-verification-ledger.json');
  const ledger=fs.existsSync(file)?JSON.parse(fs.readFileSync(file,'utf8')).entries:[];
  return analyze(sources,ledger);
}
function main(argv) {
  let command=argv.shift()||'scan',file=null,symbol=null,output=null,detail=false;
  if(!['scan','trace','impact'].includes(command))throw Error('Usage: code-graph.cjs scan|trace|impact [--file src/path.js] [--symbol fn] [--output path] [--full]');
  while(argv.length){
    let x=argv.shift();
    if(x==='--file')file=argv.shift();
    else if(x==='--symbol')symbol=argv.shift();
    else if(x==='--output')output=argv.shift();
    else if(x==='--full')detail=true;
    else throw Error('Unknown option '+x);
  }
  const root=path.resolve(__dirname,'../..'),graph=repoGraph(root);
  if(graph.errors.length)process.exitCode=1;
  let value=command==='scan'?(detail?graph:{schema:graph.schema,kind:graph.kind,summary:graph.summary,
    parseErrors:graph.errors,cycles:graph.cycles.slice(0,10)}):
    command==='trace'?(file&&symbol?trace(graph,file,symbol):(()=>{throw Error('trace requires --file and --symbol')})()):
    file?impact(graph,file):(()=>{throw Error('impact requires --file')})();
  const result=JSON.stringify(value,null,2)+'\n';
  if(output){const abs=path.resolve(output);fs.mkdirSync(path.dirname(abs),{recursive:true});fs.writeFileSync(abs,result);}
  process.stdout.write(result);
}
if(require.main===module){try{main(process.argv.slice(2));}catch(e){console.error(e.stack||e);process.exitCode=1;}}
module.exports={analyze,impact,trace,repoGraph,inputFiles};
