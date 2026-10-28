'use strict';
const fs=require('node:fs'),path=require('node:path'),cp=require('node:child_process'),crypto=require('node:crypto'),ts=require('typescript');
const PIN='3c64d1669174e825fb0fbae088c5ec95edaa7339';
const excluded=new Set(['mermaid.min.js','chart.umd.min.js','hljsBundle.generated.min.js']);
const worker='plugins/functionHooks/hooks-worker/hooks-worker.js';
const digest=s=>crypto.createHash('sha256').update(s).digest('hex');
const git=(cwd,...args)=>cp.execFileSync('git',args,{cwd,encoding:'utf8',maxBuffer:64*1024*1024});
const isModule=p=>(/^(?:src\/|core\/)?[^/]+\.js$/.test(p)&&!excluded.has(path.posix.basename(p)))||p===worker||p==='src/src/plugins/functionHooks/hooks-worker/hooks-worker.js';
function blobs(root,ref){
 const out=new Map();
 for(const row of git(root,'ls-tree','-r','-z','--full-tree',ref).split('\0')){
  const m=/^[0-7]{6} blob ([a-f0-9]{40})\t(.+)$/.exec(row);
  if(m&&isModule(m[2]))out.set(m[2],m[1]);
 }
 return out;
}
function functionBodies(code,file){
 const ast=ts.createSourceFile(file,code,ts.ScriptTarget.Latest,true,ts.ScriptKind.JS);
 if(ast.parseDiagnostics.length)throw Error('AST parse failed: '+file);
 const out=[];
 function walk(node){
  if(ts.isFunctionLike(node)&&node.body){
   const pos=ast.getLineAndCharacterOfPosition(node.getStart(ast));
   const name=node.name&&ts.isIdentifier(node.name)?node.name.text:null;
   out.push({name,line:pos.line+1,column:pos.character+1,sha256:digest(node.body.getText(ast))});
  }
  ts.forEachChild(node,walk);
 }
 walk(ast);
 return out;
}
function preserve(old,now){
 const counts=new Map();
 for(const f of now)counts.set(f.sha256,(counts.get(f.sha256)||0)+1);
 const unmatched=[];let same=0;
 for(const f of old){
  const n=counts.get(f.sha256)||0;
  if(n){same++;counts.set(f.sha256,n-1)}
  else unmatched.push(f);
 }
 return {baselineFunctions:old.length,currentFunctions:now.length,identicalBodies:same,
  changedOrMissingBodies:unmatched.length,unmatched:unmatched.slice(0,20)};
}
function audit({root=process.cwd(),base=PIN,head='HEAD',review={},expected=2551}={}){
 root=path.resolve(root);
 const baseSha=git(root,'rev-parse',base).trim(),headSha=git(root,'rev-parse',head).trim();
 const normalize=p=>p.startsWith('src/src/plugins/')?p.slice(8):
    p.startsWith('src/')?'core/'+p.slice(4):p;
 const legacy=blobs(root,baseSha),modern=blobs(root,headSha);
 const oldPaths=new Map([...legacy.keys()].map(p=>[normalize(p),p]));
 const currentPaths=new Map([...modern.keys()].map(p=>[normalize(p),p]));
 const old=new Map([...legacy].map(([p,sha])=>[normalize(p),sha]));
 const current=new Map([...modern].map(([p,sha])=>[normalize(p),sha]));
 const names=[...new Set([...old.keys(),...current.keys()])].sort();
 const missing=names.filter(n=>!current.has(n)),added=names.filter(n=>!old.has(n));
 const changed=names.filter(n=>old.has(n)&&current.has(n)&&old.get(n)!==current.get(n));
 const changes=changed.map(file=>{
  const before=functionBodies(git(root,'show',baseSha+':'+oldPaths.get(file)),file);
  const after=functionBodies(git(root,'show',headSha+':'+currentPaths.get(file)),file);
  const policy=review[oldPaths.get(file)]||review[file]||{};
   const tests=policy.tests||[];
  const evidence=tests.map(t=>({test:t,exists:fs.existsSync(path.join(root,t))}));
  return {file,baselineBlob:old.get(file),currentBlob:current.get(file),
    ...preserve(before,after),classification:policy.classification||'unreviewed',
    tests:evidence,status:tests.length&&evidence.every(e=>e.exists)?
     'targeted_tests_linked_not_full_equivalence':'unreviewed'};
 });
 const failures=[];
 if(old.size!==expected||current.size!==expected)failures.push('source module count mismatch');
 if(missing.length||added.length)failures.push('source modules deleted or added');
 if(changes.some(x=>x.status==='unreviewed'))failures.push('unreviewed changed files');
 return {schema:1,kind:'111-v37-git-blob-and-function-body-evidence',
  reference:{commit:baseSha,source:'pinned Git history; not v37 external archive'},
  current:{commit:headSha},
  metrics:{baselineModules:old.size,currentModules:current.size,
    identical:old.size-changed.length-missing.length,changed:changed.length,
    missing:missing.length,added:added.length,
    reviewed:changes.filter(x=>x.status!=='unreviewed').length,
    changedOrMissingBodies:changes.reduce((a,b)=>a+b.changedOrMissingBodies,0)},
  missing,added,changes,failures,
  limitations:['Function body identity is not full runtime equivalence.',
    'External v37 .tar.zst archive was not available for byte-level verification.',
    'Changed or missing bodies are candidates for manual behavioral verification; no automatic old-code copying.']};
}
function main(argv){
 let base=PIN,head='HEAD',root=path.resolve(__dirname,'..'),output=null;
 while(argv.length){
  const flag=argv.shift(),v=argv.shift();if(!v)throw Error('missing '+flag);
  if(flag==='--base')base=v;else if(flag==='--head')head=v;
  else if(flag==='--root')root=v;else if(flag==='--output')output=v;
  else throw Error('unknown '+flag);
 }
 const policy=JSON.parse(fs.readFileSync(path.join(root,'resources/research/v37-reviewed.json'),'utf8'));
 if(base===PIN&&policy.baselineCommit!==PIN)throw Error('unexpected pinned v37 ref');
 const report=audit({root,base,head,review:policy.changes});
 if(output){fs.mkdirSync(path.dirname(path.resolve(output)),{recursive:true});
   fs.writeFileSync(output,JSON.stringify(report,null,2)+'\n')}
 process.stdout.write(JSON.stringify({reference:report.reference,metrics:report.metrics,
   changes:report.changes.map(x=>({file:x.file,status:x.status,changedOrMissingBodies:x.changedOrMissingBodies})),
   failures:report.failures,report:output},null,2)+'\n');
 if(report.failures.length)process.exitCode=1;
}
if(require.main===module){try{main(process.argv.slice(2))}catch(e){console.error(e.stack||e);process.exitCode=1}}
module.exports={PIN,isModule,blobs,functionBodies,preserve,audit};
