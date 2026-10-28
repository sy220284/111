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
  // Recovered minified code often uses var. Follow var/let only when the
  // identifier and its visible properties have no explicit write anywhere in
  // the module; file-wide name checking is intentionally conservative.
  const unsafeByFile=new Map(),propertyMutationsByFile=new Map();
  function assignmentRoot(node){
    while(node&&(ts.isParenthesizedExpression(node)||
      ts.isPropertyAccessExpression(node)||ts.isElementAccessExpression(node)))
      node=node.expression;
    return node&&ts.isIdentifier(node)?node.text:null;
  }
  for(const file of files){
    const unsafe=new Set(),propertyMutations=new Set(),ast=parsed.get(file);
    function mark(node){
      const name=assignmentRoot(node);
      if(name){
        unsafe.add(name);
        // Rebinding `alias=other` does not mutate the object held by an
        // independent alias. Only `alias.member=...`, delete and updates
        // propagate invalidation through the object identity group.
        while(ts.isParenthesizedExpression(node))node=node.expression;
        if(ts.isPropertyAccessExpression(node)||ts.isElementAccessExpression(node))
          propertyMutations.add(name);
      }
    }
    function findWrites(node){
      if(ts.isBinaryExpression(node)&&
         node.operatorToken.kind>=ts.SyntaxKind.FirstAssignment&&
         node.operatorToken.kind<=ts.SyntaxKind.LastAssignment)
        mark(node.left);
      if((ts.isPrefixUnaryExpression(node)||ts.isPostfixUnaryExpression(node))&&
         (node.operator===ts.SyntaxKind.PlusPlusToken||
          node.operator===ts.SyntaxKind.MinusMinusToken))
        mark(node.operand);
      if(ts.isDeleteExpression(node))mark(node.expression);
      if((ts.isForOfStatement(node)||ts.isForInStatement(node))&&
         !ts.isVariableDeclarationList(node.initializer))
        mark(node.initializer);
      ts.forEachChild(node,findWrites);
    }
    findWrites(ast);
    // Propagate *property mutations*, not standalone binding reassignments.
    // Module-local aliases can share object identity, but `b=unknown` does
    // not mutate `a`. Nested lexical scopes remain separate below.
    const neighbors=new Map();
    for(const stmt of ast.statements)if(ts.isVariableStatement(stmt))
      for(const d of stmt.declarationList.declarations)
        if(ts.isIdentifier(d.name)&&d.initializer&&ts.isIdentifier(d.initializer)){
          const a=d.name.text,b=d.initializer.text;
          if(!neighbors.has(a))neighbors.set(a,new Set());
          if(!neighbors.has(b))neighbors.set(b,new Set());
          neighbors.get(a).add(b);neighbors.get(b).add(a);
        }
    const queue=[...propertyMutations];
    for(let i=0;i<queue.length;i++)
      for(const other of neighbors.get(queue[i])||[])
        if(!propertyMutations.has(other)){
          propertyMutations.add(other);queue.push(other);
        }
    for(const name of propertyMutations)unsafe.add(name);
    unsafeByFile.set(file,unsafe);
    propertyMutationsByFile.set(file,propertyMutations);
  }
  const modules=new Map(), links=[], external=new Map(), functions=new Map();
  const unresolvedReasons=Object.create(null),unresolvedCallsites=[];
  const stats={calls:0,resolvedCalls:0,unresolvedCalls:0,dynamicImports:0};
  for(const file of files) {
    const ast=parsed.get(file), importBindings=new Map(), deps=new Set(), exports=new Map(),
      defs=new Map(), exportAll=[], constAliases=[];
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
      const isExported=!!stmt.modifiers?.some(x=>x.kind===ts.SyntaxKind.ExportKeyword);
      const isDefault=!!stmt.modifiers?.some(x=>x.kind===ts.SyntaxKind.DefaultKeyword);
      if(top && isExported) exports.set(isDefault?'default':top,{file,local:top});
      if(ts.isVariableStatement(stmt)) for(const d of stmt.declarationList.declarations){
        if(!ts.isIdentifier(d.name)||!d.initializer)continue;
        if(functionLike(d.initializer)){
          defs.set(d.name.text,{file,symbol:d.name.text});
          if(isExported) exports.set(d.name.text,{file,local:d.name.text});
        }else if(ts.isObjectLiteralExpression(d.initializer)){
          // An exported object is not itself callable, but can expose callable members.
          if(isExported)exports.set(d.name.text,{file,local:d.name.text});
        }else if((stmt.declarationList.flags&ts.NodeFlags.Const)!==0 &&
          ts.isIdentifier(d.initializer)){
          // An immutable function alias is the same callable, not a new implementation.
          // Never follow let/var reassignment or property/reflective aliases.
          constAliases.push({alias:d.name.text,pointTo:d.initializer.text});
          if(isExported)exports.set(d.name.text,{file,local:d.name.text});
        }
      }
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
    // Resolve chains of immutable aliases only when the target is a known local
    // top-level function. Do not invent cross-module/dynamic callable targets.
    for(let pass=0;pass<constAliases.length;pass++){
      let resolved=0;
      for(const {alias,pointTo} of constAliases)if(!defs.has(alias)&&defs.has(pointTo)){
        defs.set(alias,defs.get(pointTo));resolved++;
      }
      if(!resolved)break;
    }
    modules.set(file,{file,imports:importBindings,deps,staticDeps:new Set(deps),defs,exports,exportAll});
  }
  function exportTarget(file,symbol,seen=new Set()){
    if(!file || seen.has(file+'#'+symbol)) return null;
    seen.add(file+'#'+symbol);
    const m=modules.get(file);
    if(!m) return null;
    const out=m.exports.get(symbol);
    if(out) {
      if(out.file!==file) return exportTarget(out.file,out.local,seen);
      // ESM exports are live bindings. Reassigning an exported function
      // invalidates the old implementation as a static callee candidate.
      if(m.defs.has(out.local) && !unsafeByFile.get(file)?.has(out.local))
        return m.defs.get(out.local);
      const binding=m.imports.get(out.local);
      if(binding?.target) return exportTarget(binding.target,binding.export,seen);
    }
    for(const other of m.exportAll) {
      const found=exportTarget(other,symbol,new Set(seen));
      if(found)return found;
    }
    return null;
  }
  // A name+line key collides for compressed JS with several functions on one line.
  // Use AST start/end offsets as the identity; keep the previous trace prefix readable.
  function collectNames(pattern,out) {
    if(!pattern)return;
    if(ts.isIdentifier(pattern)){out.add(pattern.text);return;}
    if(ts.isObjectBindingPattern(pattern)||ts.isArrayBindingPattern(pattern))
      for(const element of pattern.elements){
        if(ts.isBindingElement(element))collectNames(element.name,out);
      }
  }
  function localBindings(fn){
    const names=new Set();
    for(const param of fn.parameters||[])collectNames(param.name,names);
    function walk(node){
      if(node!==fn.body && functionLike(node)){
        if(ts.isFunctionDeclaration(node)&&node.name)names.add(node.name.text);
        return;
      }
      if(ts.isVariableDeclaration(node))collectNames(node.name,names);
      if(ts.isCatchClause(node)&&node.variableDeclaration)
        collectNames(node.variableDeclaration.name,names);
      if(ts.isClassDeclaration(node)&&node.name)names.add(node.name.text);
      ts.forEachChild(node,walk);
    }
    if(fn.body)walk(fn.body);
    return names;
  }
  // Resolve only directly declared lexical callables. Parameter/catch/branch bindings
  // still mask outer names; a same-named top-level function cannot leak past them.
  // Targets keep AST identity so same-name nested functions are distinguishable.
  function nodeTarget(node,file,ast,symbol){
    const p=ast.getLineAndCharacterOfPosition(node.getStart(ast));
    const name=symbol||'<anonymous>@'+(p.line+1)+':'+(p.character+1);
    return {file,symbol:name,line:p.line+1,column:p.character+1,
      id:file+'#'+name+'@'+(p.line+1)+':'+(p.character+1)};
  }
  function staticPropertyName(name){
    return name&&(ts.isIdentifier(name)||ts.isStringLiteralLike(name)||
      ts.isNumericLiteral(name))?name.text:null;
  }
  // obj['method'] and namespace['export'] have the same static member identity
  // as obj.method; computed or variable keys must remain unresolved.
  function literalMemberName(node){
    if(ts.isPropertyAccessExpression(node))return node.name.text;
    if(ts.isElementAccessExpression(node)&&node.argumentExpression&&
       (ts.isStringLiteralLike(node.argumentExpression)||
        ts.isNumericLiteral(node.argumentExpression)))return node.argumentExpression.text;
    return null;
  }
  // Nested object-literal values form an explicit tree. Only leaf functions
  // are callable. A spread or unknown computed property can replace any key,
  // so reject the entire object's methods rather than inventing bindings.
  function knownCallable(name,file,localCallables,localNames){
    if(unsafeByFile.get(file)?.has(name))return null;
    if(localCallables?.has(name))return localCallables.get(name);
    if(localNames?.has(name))return null;
    const m=modules.get(file);
    if(m?.defs.has(name))return m.defs.get(name);
    const imported=m?.imports.get(name);
    if(imported?.target&&!imported.namespace)
      return exportTarget(imported.target,imported.export);
    return null;
  }
  function objectMethods(expr,file,ast,depth=0,localCallables=null,localNames=null){
    const result=new Map();
    if(!expr||!ts.isObjectLiteralExpression(expr)||depth>12)return result;
    for(const property of expr.properties){
      if(ts.isSpreadAssignment(property)||
        (property.name&&ts.isComputedPropertyName(property.name)&&
         !ts.isStringLiteralLike(property.name.expression)&&
         !ts.isNumericLiteral(property.name.expression)))return new Map();
      const name=ts.isComputedPropertyName(property.name)?
        staticPropertyName(property.name.expression):staticPropertyName(property.name);
      if(name===null||name===undefined)continue;
      if(ts.isMethodDeclaration(property)){
        result.set(name,nodeTarget(property,file,ast,name));
      }else if(ts.isPropertyAssignment(property)){
        const fn=property.initializer;
        if(functionLike(fn))result.set(name,nodeTarget(fn,file,ast,fn.name?.text||name));
        else if(ts.isObjectLiteralExpression(fn))
          result.set(name,objectMethods(fn,file,ast,depth+1,localCallables,localNames));
        else if(ts.isIdentifier(fn)){
          const target=knownCallable(fn.text,file,localCallables,localNames);
          if(target)result.set(name,target);
          else result.delete(name);
        }else result.delete(name);
      }else if(ts.isShorthandPropertyAssignment(property)){
        const target=knownCallable(property.name.text,file,localCallables,localNames);
        if(target)result.set(name,target);
        else result.delete(name);
      }else if(ts.isGetAccessorDeclaration(property)||ts.isSetAccessorDeclaration(property)){
        result.delete(name);
      }
    }
    return result;
  }
  function lookupObjectMethod(object,keys){
    let entry=object;
    for(const k of keys){
      if(!(entry instanceof Map))return null;
      entry=entry.get(k);
    }
    return entry instanceof Map?null:(entry||null);
  }
  // Only a literal key or fixed syntactic property can be followed.
  // Computed identifiers, optional chains, indexing by data, and expressions
  // whose root is not an identifier stay unresolved.
  function staticMemberChain(exp,keyLookup){
    const members=[];
    let cur=exp;
    while(ts.isPropertyAccessExpression(cur)||ts.isElementAccessExpression(cur)){
      if(cur.questionDotToken)return null;
      let part=literalMemberName(cur);
      if(part===null&&ts.isElementAccessExpression(cur)&&
         ts.isIdentifier(cur.argumentExpression))
        part=keyLookup(cur.argumentExpression.text);
      if(part===null||part===undefined)return null;
      members.unshift(part);
      cur=cur.expression;
    }
    return ts.isIdentifier(cur)&&members.length?{root:cur.text,members}:null;
  }
  function scopedBindings(fn,file,ast,localNames){
    const callables=new Map(),objects=new Map(),keys=new Map(),aliases=[];
    if(!fn.body||!ts.isBlock(fn.body))return {callables,objects,keys};
    // A mutation through any direct lexical alias invalidates all aliases
    // of the same local object. Unlike module-scoped names, process only
    // this function body to avoid conflating minified names across functions.
    const localPoison=new Set(unsafeByFile.get(file)||[]);
    const contagious=new Set(propertyMutationsByFile.get(file)||[]);
    const pairs=[];
    for(const stmt of fn.body.statements)if(ts.isVariableStatement(stmt))
      for(const d of stmt.declarationList.declarations)
        if(ts.isIdentifier(d.name)&&d.initializer&&ts.isIdentifier(d.initializer))
          pairs.push([d.name.text,d.initializer.text]);
    for(let pass=0;pass<pairs.length;pass++){
      let changed=false;
      for(const [alias,original] of pairs)
        if(contagious.has(alias)||contagious.has(original)){
          if(!contagious.has(alias)){contagious.add(alias);changed=true;}
          if(!contagious.has(original)){contagious.add(original);changed=true;}
          localPoison.add(alias);localPoison.add(original);
        }
      if(!changed)break;
    }
    for(const stmt of fn.body.statements){
      if(ts.isFunctionDeclaration(stmt)&&stmt.name)
        callables.set(stmt.name.text,nodeTarget(stmt,file,ast,stmt.name.text));
      if(!ts.isVariableStatement(stmt))continue;
      for(const d of stmt.declarationList.declarations){
        if(!ts.isIdentifier(d.name)||!d.initializer||
           localPoison.has(d.name.text))continue;
        if(functionLike(d.initializer))
          callables.set(d.name.text,nodeTarget(d.initializer,file,ast,
            d.initializer.name?.text||d.name.text));
        else if(ts.isIdentifier(d.initializer))
          aliases.push([d.name.text,d.initializer.text]);
        else if(ts.isObjectLiteralExpression(d.initializer))
          objects.set(d.name.text,objectMethods(d.initializer,file,ast,0,callables,localNames));
        else if(ts.isStringLiteralLike(d.initializer)||ts.isNumericLiteral(d.initializer))
          keys.set(d.name.text,d.initializer.text);
      }
    }
    // Immutable aliases inside this same function body. Never follow parameters,
    // let/var reassignment, a property access, or an unknown cross-scope symbol.
    for(let pass=0;pass<aliases.length;pass++){
      let changed=false;
      for(const [alias,original] of aliases){
        if(localPoison.has(alias)||localPoison.has(original))continue;
        if(!callables.has(alias)&&callables.has(original)){
          callables.set(alias,callables.get(original));changed=true;
        }
        if(!objects.has(alias)&&objects.has(original)){
          objects.set(alias,objects.get(original));changed=true;
        }
        if(!keys.has(alias)&&keys.has(original)){
          keys.set(alias,keys.get(original));changed=true;
        }
      }
      if(!changed)break;
    }
    // A destructured function member from a fixed local object can be tracked
    // as a callable binding, but not destructuring from parameters/dynamic data.
    for(const stmt of fn.body.statements){
      if(!ts.isVariableStatement(stmt))continue;
      for(const d of stmt.declarationList.declarations){
        if(!ts.isObjectBindingPattern(d.name)||!ts.isIdentifier(d.initializer))continue;
        const obj=objects.get(d.initializer.text);
        if(!obj)continue;
        for(const element of d.name.elements){
          if(!ts.isBindingElement(element)||!ts.isIdentifier(element.name))continue;
          const property=element.propertyName?
            staticPropertyName(element.propertyName):element.name.text;
          const target=lookupObjectMethod(obj,[property]);
          if(target)callables.set(element.name.text,target);
        }
      }
    }
    return {callables,objects,keys};
  }
  const moduleObjects=new Map(),moduleKeys=new Map();
  for(const file of files){
    const map=new Map(),keys=new Map(),aliases=[],ast=parsed.get(file);
    for(const stmt of ast.statements){
      if(!ts.isVariableStatement(stmt))continue;
      for(const d of stmt.declarationList.declarations){
        if(!ts.isIdentifier(d.name)||!d.initializer||
           unsafeByFile.get(file).has(d.name.text))continue;
        if(ts.isObjectLiteralExpression(d.initializer))
          map.set(d.name.text,objectMethods(d.initializer,file,ast));
        else if(ts.isIdentifier(d.initializer))
          aliases.push([d.name.text,d.initializer.text]);
        else if(ts.isStringLiteralLike(d.initializer)||ts.isNumericLiteral(d.initializer))
          keys.set(d.name.text,d.initializer.text);
      }
    }
    for(let pass=0;pass<aliases.length;pass++){
      let changed=false;
      for(const [alias,original] of aliases){
        if(!map.has(alias)&&map.has(original)){
          map.set(alias,map.get(original));changed=true;
        }
        if(!keys.has(alias)&&keys.has(original)){
          keys.set(alias,keys.get(original));changed=true;
        }
      }
      if(!changed)break;
    }
    moduleObjects.set(file,map);
    moduleKeys.set(file,keys);
  }
  const objectExportCache=new Map();
  function exportObject(file,symbol,seen=new Set()){
    if(!file||seen.has(file+'#'+symbol))return null;
    const key=file+'#'+symbol;
    if(objectExportCache.has(key))return objectExportCache.get(key);
    seen.add(key);
    const m=modules.get(file);
    if(!m)return null;
    const out=m.exports.get(symbol);
    if(out){
      if(out.file!==file)return exportObject(out.file,out.local,seen);
      const obj=moduleObjects.get(file)?.get(out.local);
      if(obj){objectExportCache.set(key,obj);return obj;}
      const binding=m.imports.get(out.local);
      if(binding?.target&&!binding.namespace)
        return exportObject(binding.target,binding.export,seen);
    }
    for(const other of m.exportAll){
      const obj=exportObject(other,symbol,new Set(seen));
      if(obj){objectExportCache.set(key,obj);return obj;}
    }
    return null;
  }
  const edges=new Map();
  function addEdge(from,to,kind,file,ast,node){
    const targetKey=to?(to.id||to.file+'#'+to.symbol):'<unknown>';
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
    const activeScopes=[],activeDefinitions=[];
    function visit(node) {
      const before=current;
      const isFunction=functionLike(node);
      if(isFunction) {
        const names=localBindings(node);
        activeScopes.push(names);
        activeDefinitions.push(scopedBindings(node,file,ast,names));
        let label=node.name && ts.isIdentifier(node.name)?node.name.text:null;
        if(!label && ts.isVariableDeclaration(node.parent) &&
           ts.isIdentifier(node.parent.name))label=node.parent.name.text;
        if(!label && ts.isPropertyAssignment(node.parent))label=node.parent.name.getText(ast);
        const p=ast.getLineAndCharacterOfPosition(node.getStart(ast));
        const start=node.getStart(ast),end=node.getEnd();
        current={file,symbol:label||'<anonymous>@'+(p.line+1)+':'+(p.character+1),
          line:p.line+1,column:p.character+1,start,end};
        current.id=file+'#'+current.symbol+'@'+current.line+':'+current.column;
        functions.set(current.id,current);
      }
      if(ts.isCallExpression(node)||ts.isNewExpression(node)) {
        stats.calls++;
        const exp=node.expression, owner=current ? current.id :file+'#<module>';
        let callee=exp;
        while(ts.isParenthesizedExpression(callee))callee=callee.expression;
        let target=null,kind='dynamic_or_property',reason='complex_callable_expression';
        if(ts.isIdentifier(callee)){
          // Walk lexical scopes inside-out; an unproved inner binding blocks
          // a same-named outer or imported implementation.
          let masked=false;
          for(let i=activeScopes.length-1;i>=0;i--)
            if(activeScopes[i].has(callee.text)){
              masked=true;
              target=activeDefinitions[i].callables.get(callee.text)||null;
              if(target)kind='lexical_function_candidate';
              else reason='local_binding_or_parameter';
              break;
            }
          if(!masked){
            const binding=m.imports.get(callee.text);
            if(binding?.target && !binding.namespace) {
              target=exportTarget(binding.target,binding.export);
              if(target)kind='import_binding_candidate';
              else reason='import_without_proven_export';
            }else if(m.defs.has(callee.text) && !unsafeByFile.get(file).has(callee.text)){
              target=m.defs.get(callee.text);
              kind='same_module_candidate';
            }else reason='unknown_identifier';
          }
        }else if(ts.isPropertyAccessExpression(callee)||ts.isElementAccessExpression(callee)){
          const candidateChain=staticMemberChain(callee,name=>{
            for(let i=activeScopes.length-1;i>=0;i--)
              if(activeScopes[i].has(name))
                return activeDefinitions[i].keys.get(name)||null;
            return moduleKeys.get(file)?.get(name)||null;
          });
          if(candidateChain){
          const {root:ownerName,members}=candidateChain;
          let masked=false;
          for(let i=activeScopes.length-1;i>=0;i--)
            if(activeScopes[i].has(ownerName)){
              masked=true;
              target=lookupObjectMethod(activeDefinitions[i].objects.get(ownerName),members);
              if(target)kind='lexical_object_method_candidate';
              break;
            }
          if(!masked){
            const ns=m.imports.get(ownerName);
            if(ns?.target&&ns.namespace){
              if(members.length===1){
                target=exportTarget(ns.target,members[0]);
                if(target)kind='namespace_import_candidate';
              }else{
                target=lookupObjectMethod(exportObject(ns.target,members[0]),members.slice(1));
                if(target)kind='namespace_object_member_candidate';
              }
            }else if(ns?.target){
              target=lookupObjectMethod(exportObject(ns.target,ns.export),members);
              if(target)kind='imported_object_method_candidate';
            }
            if(!target){
              target=lookupObjectMethod(moduleObjects.get(file)?.get(ownerName),members);
              if(target)kind='module_object_method_candidate';
            }
          }
          if(!target)reason='property_or_namespace_unresolved';
          }else reason='dynamic_property';
        }else if(ts.isFunctionExpression(callee)||ts.isArrowFunction(callee)){
          target=nodeTarget(callee,file,ast,callee.name?.text||null);
          kind='immediate_function_candidate';
        }
        if(ts.isCallExpression(node) && exp.kind===ts.SyntaxKind.ImportKeyword){
          stats.dynamicImports++;
          reason='dynamic_import';
          if(node.arguments?.length===1&&ts.isStringLiteralLike(node.arguments[0])){
            const spec=node.arguments[0].text;
            const dep=resolve(file,spec,known);
            if(dep)m.deps.add(dep);
            else if(spec.startsWith('.'))errors.push({file,offset:node.getStart(ast),
              message:'unresolved literal dynamic import '+spec});
          }
        }
        if(target)stats.resolvedCalls++;
        else {
          stats.unresolvedCalls++;
          unresolvedReasons[reason]=(unresolvedReasons[reason]||0)+1;
          const p=ast.getLineAndCharacterOfPosition(node.getStart(ast));
          unresolvedCallsites.push({file,owner,line:p.line+1,column:p.character+1,
            reason,callee:exp.getText(ast).slice(0,240)});
        }
        // Never fabricate dynamic call targets or treat a candidate as executed.
        if(target)addEdge(owner,target,kind,file,ast,node);
      }
      ts.forEachChild(node,visit);
      if(isFunction){activeScopes.pop();activeDefinitions.pop();}
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
    for(const w of modules.get(v).staticDeps){
      if(!indices.has(w)){strong(w);low.set(v,Math.min(low.get(v),low.get(w)));}
      else if(onStack.has(w))low.set(v,Math.min(low.get(v),indices.get(w)));
    }
    if(low.get(v)===indices.get(v)){
      const s=[];let w;do{w=stack.pop();onStack.delete(w);s.push(w)}while(w!==v);
      if(s.length>1 || modules.get(v).staticDeps.has(v))cycles.push(s.sort());
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
      staticModuleDependencies:[...modules.values()].reduce((a,m)=>a+m.staticDeps.size,0),
      cyclesScope:'static-import-export-only',
      externalSpecifiers:[...external.keys()].sort(),cyclicGroups:cycles.length,
      functionEntities:functions.size,callExpressions:stats.calls,
      candidateResolvedCalls:stats.resolvedCalls,unresolvedCallExpressions:stats.unresolvedCalls,
      uniqueCandidateEdges:uniqueEdges.length,recordedSemantics:semantics.length,
      unresolvedCallsiteInventory:unresolvedCallsites.length,
      unresolvedReasons:Object.fromEntries(Object.entries(unresolvedReasons).sort()),
      functionIdentity:'file+lexical-symbol+AST start line/column; no same-line collisions',
      warning:'Calls are syntax/lexical/import candidates, NOT proven runtime call targets. Ledger roles are targeted-test evidence, NOT full semantic restoration.'},
    modules:moduleDeps, cycles, callEdges:uniqueEdges, unresolvedCallsites, semantics
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
