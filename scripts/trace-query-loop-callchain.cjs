'use strict';
// AST-level callsite proof for the recovered 2.1.310 query loop.
// This proves source topology, not successful tool execution or protocol parity.
const fs=require('node:fs');
const path=require('node:path');
const assert=require('node:assert/strict');
const ts=require('typescript');
const sourcePath=path.resolve(__dirname,'../src/chunk-tqjdehqd.js');

function analyzeQueryCallgraph(source){
  const ast=ts.createSourceFile('chunk-tqjdehqd.js',source,ts.ScriptTarget.Latest,true,ts.ScriptKind.JS);
  assert.equal(ast.parseDiagnostics.length,0,'query source must parse');
  const byName=new Map();
  const visit=n=>{
    if(ts.isFunctionDeclaration(n)&&n.name){
      if(!byName.has(n.name.text))byName.set(n.name.text,[]);
      byName.get(n.name.text).push(n);
    }
    ts.forEachChild(n,visit);
  };
  visit(ast);
  const only=name=>{
    const choices=byName.get(name)||[];
    assert.equal(choices.length,1,'expected one AST function '+name);
    return choices[0];
  };
  const pN=only('pN'),Ou=only('Ou'),Du=only('Du'),Fu=only('Fu'),Da=only('Da');
  const line=n=>ast.getLineAndCharacterOfPosition(n.getStart(ast)).line+1;
  const parentFunction=n=>{
    for(let p=n.parent;p;p=p.parent)if(ts.isFunctionLike(p))return p;
  };
  assert.equal(parentFunction(Du),Ou,'Du must be nested in Ou');
  assert.equal(parentFunction(Fu),Ou,'Fu must be nested in Ou');
  const calls=(from,target)=>{
    const found=[];
    const walk=n=>{
      if(n!==from.body&&ts.isFunctionLike(n))return;
      if(ts.isCallExpression(n)&&ts.isIdentifier(n.expression)&&n.expression.text===target)found.push(n);
      ts.forEachChild(n,walk);
    };
    walk(from.body);
    return found;
  };
  const edges=[
    {from:pN,to:Da,kind:'install-session-append-bridge'},
    {from:pN,to:Ou,kind:'drive-engine-generator'},
    {from:Ou,to:Fu,kind:'run-next-round-through-error-recorder'},
    {from:Fu,to:Du,kind:'yield-one-query-round'}
  ];
  const links=edges.map(({from,to,kind})=>{
    const sites=calls(from,to.name.text);
    assert.ok(sites.length>0,from.name.text+' -> '+to.name.text+' missing AST call');
    return {from:from.name.text,to:to.name.text,kind,callerLine:line(from),calleeLine:line(to),callLines:sites.map(line)};
  });
  const hasIdentifier=(fn,text)=>{
    let matched=false;
    const walk=n=>{
      if(ts.isIdentifier(n)&&n.text===text)matched=true;
      ts.forEachChild(n,walk);
    };
    walk(fn.body);
    return matched;
  };
  for(const id of ['resumeIncompleteThinking','pendingToolUseSummary','toolUseContext'])
    assert.ok(hasIdentifier(Du,id),'query round missing AST state/branch identifier '+id);
  let postToolBatchEventFound=false;
  const findEvent=n=>{
    if(ts.isStringLiteral(n)&&n.text==='PostToolBatch')postToolBatchEventFound=true;
    ts.forEachChild(n,findEvent);
  };
  findEvent(Du.body);
  assert.ok(postToolBatchEventFound,'query round missing AST PostToolBatch hook event');
  for(const name of ['Gr','Pu','Qf','getCancelledMainTurnMessageId','findLatestTrailingUserMessageUuid'])only(name);
  // The recording wrapper, previously omitted from the source-callchain report,
  // deliberately records project errors and then the engine repeats Fu().
  assert.ok(hasIdentifier(Fu,'recordProjectsReplyTurnError'),'Fu must record project reply outcomes');
  return {version:'2.1.310',module:'src/chunk-tqjdehqd.js',
    evidence:'static-AST-callsites-not-end-to-end-execution',
    scopes:{Da:line(Da),pN:line(pN),Ou:line(Ou),Du:line(Du),Fu:line(Fu)},
    links,conditionalBranchesStillRequireExecution:true,semanticOrEndToEndParity:false};
}
if(require.main===module)console.log(JSON.stringify(analyzeQueryCallgraph(fs.readFileSync(sourcePath,'utf8'))));
module.exports={analyzeQueryCallgraph};
