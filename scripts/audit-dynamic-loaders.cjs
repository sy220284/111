'use strict';
// Enumerate every literal and computed direct ESM/CommonJS loader call in the
// 2.1.310 main program; do not mistake computed loader sites for verified targets.
const fs=require('node:fs');
const path=require('node:path');
const assert=require('node:assert/strict');
const ts=require('typescript');
const root=path.resolve(__dirname,'..');
const src=root;

function extractLoaderSites(code,file){
  const ast=ts.createSourceFile(file,code,ts.ScriptTarget.Latest,true,ts.ScriptKind.JS);
  assert.equal(ast.parseDiagnostics.length,0,'invalid source syntax: '+file);
  const sites=[];
  const walk=n=>{
    if(ts.isCallExpression(n)){
      const e=n.expression;
      let kind;
      if(e.kind===ts.SyntaxKind.ImportKeyword)kind='import';
      else if(ts.isIdentifier(e)&&e.text==='require')kind='require';
      else if(ts.isPropertyAccessExpression(e)&&e.name.text==='require'&&
        ts.isMetaProperty(e.expression)&&e.expression.keywordToken===ts.SyntaxKind.ImportKeyword)kind='import.meta.require';
      if(kind){
        const arg=n.arguments[0];
        const literal=arg&&(ts.isStringLiteral(arg)||ts.isNoSubstitutionTemplateLiteral(arg));
        sites.push({kind,file,line:ast.getLineAndCharacterOfPosition(n.getStart(ast)).line+1,
          target:literal?arg.text:null,computed:!literal,argument:arg?arg.getText(ast).slice(0,160):'<missing>'});
      }
    }
    ts.forEachChild(n,walk);
  };
  walk(ast);
  return sites;
}
function resolveRelative(file,target){
  const direct=path.resolve(src,path.dirname(file),target);
  return [direct,direct+'.js',direct+'.mjs',direct+'.cjs',direct+'.json',direct+'.node',
    path.join(direct,'index.js'),path.join(direct,'package.json')].some(p=>fs.existsSync(p));
}
function audit(){
  const names=fs.readdirSync(path.join(src,'core')).filter(n=>n.endsWith('.js')&&
    !['chart.umd.min.js','hljsBundle.generated.min.js','mermaid.min.js'].includes(n)).sort().map(n=>'core/'+n);
  names.push('plugins/functionHooks/hooks-worker/hooks-worker.js');
  assert.equal(names.length,2551,'main source inventory changed');
  const kinds={import:0,require:0,'import.meta.require':0};
  const computed=[],unresolved=[],nonRelative=[];
  let literal=0;
  for(const file of names){
    const code=fs.readFileSync(path.join(src,file),'utf8');
    for(const site of extractLoaderSites(code,file)){
      kinds[site.kind]++;
      if(site.computed){computed.push(site);continue;}
      literal++;
      if(site.target.startsWith('.')){
        if(!resolveRelative(file,site.target))unresolved.push(site);
      }else nonRelative.push(site);
    }
  }
  const result={version:'2.1.310',sourceModules:names.length,callSites:Object.values(kinds).reduce((a,b)=>a+b,0),
    kinds,literalTargets:literal,computedTargets:computed.length,
    missingRelativeTargets:unresolved.length,
    unresolvedSamples:unresolved.slice(0,25),
    computedSamples:computed.slice(0,25),
    nonRelativeSamples:nonRelative.slice(0,12),
    status:unresolved.length?'FAIL':'PASS',
    computedTargetsVerified:false,protocolOrRuntimeVerified:false};
  return result;
}
if(require.main===module){
  const data=audit();
  console.log(JSON.stringify(data));
  if(process.argv.includes('--full')){
    fs.mkdirSync(path.join(root,'.generated'),{recursive:true});
    fs.writeFileSync(path.join(root,'.generated/dynamic-loader-audit.json'),JSON.stringify(data,null,2)+'\n');
  }
  assert.equal(data.missingRelativeTargets,0,'statically known dynamic/require target absent');
}
module.exports={extractLoaderSites,audit};
