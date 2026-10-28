'use strict';
// 2.1.88 -> 2.1.310 只读候选匹配器。匹配不构成函数等价证明，也不会改写源码。
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const ts = require('typescript');

const hash = x => crypto.createHash('sha256').update(x).digest('hex');
const isFn = n => ts.isFunctionLike(n) && !!n.body;
const nameOf = n => {
  if (n.name && ts.isIdentifier(n.name)) return n.name.text;
  const p = n.parent;
  if (ts.isVariableDeclaration(p) && ts.isIdentifier(p.name)) return p.name.text;
  if (ts.isPropertyAssignment(p) && ts.isIdentifier(p.name)) return p.name.text;
  return null;
};
function features(node, ast, file) {
  const scanner = ts.createScanner(ts.ScriptTarget.Latest, true, ts.LanguageVariant.Standard, node.body.getText(ast));
  const tokens = [];
  for (let kind = scanner.scan(); kind !== ts.SyntaxKind.EndOfFileToken; kind = scanner.scan()) {
    if (kind === ts.SyntaxKind.Identifier || kind === ts.SyntaxKind.PrivateIdentifier) tokens.push('IDENT');
    else if (kind === ts.SyntaxKind.StringLiteral || kind === ts.SyntaxKind.NoSubstitutionTemplateLiteral)
      tokens.push('STRING:' + scanner.getTokenText().slice(1, -1));
    else if (kind === ts.SyntaxKind.NumericLiteral) tokens.push('NUMBER:' + scanner.getTokenText());
    else tokens.push(ts.SyntaxKind[kind] || String(kind));
  }
  const literals = [];
  function walk(n) {
    if (ts.isStringLiteral(n) || ts.isNoSubstitutionTemplateLiteral(n)) {
      const s = n.text;
      if (s.length >= 8 && s.length <= 240) literals.push(s);
    }
    ts.forEachChild(n, walk);
  }
  walk(node.body);
  return {
    file, name: nameOf(node), line: ast.getLineAndCharacterOfPosition(node.getStart(ast)).line + 1,
    params: node.parameters ? node.parameters.length : 0,
    tokens: tokens.length, shape: hash(tokens.join('\x1f')),
    literals: [...new Set(literals)].sort()
  };
}
function collect(file, relative) {
  const code = fs.readFileSync(file, 'utf8');
  const ast = ts.createSourceFile(relative, code, ts.ScriptTarget.Latest, true,
    /\.tsx$|\.jsx$/.test(file) ? ts.ScriptKind.TSX : /\.tsx?$/.test(file) ? ts.ScriptKind.TS : ts.ScriptKind.JS);
  if (ast.parseDiagnostics.length) throw new Error('语法解析失败: ' + relative);
  const list = [];
  function visit(n) {
    if (isFn(n)) {
      const f = features(n, ast, relative);
      if (f.name && f.tokens >= 55) list.push(f);
    }
    ts.forEachChild(n, visit);
  }
  visit(ast);
  return list;
}
function sourceFiles(root, {old = false, focus = []} = {}) {
  const all = [];
  function visit(dir) {
    for (const e of fs.readdirSync(dir, {withFileTypes:true}).sort((a,b)=>a.name.localeCompare(b.name))) {
      if (e.name === 'node_modules' || e.name.startsWith('.')) continue;
      const full = path.join(dir, e.name);
      if (e.isDirectory()) { if (old) visit(full); continue; }
      if (!e.isFile()) continue;
      if (old ? /\.(ts|tsx|js|jsx)$/.test(e.name) && !/\.d\.ts$/.test(e.name)
              : /^chunk-.*\.js$|^cli\.js$|^audio-capture\.js$|^clipboard-napi\.js$/.test(e.name)) {
        const relative = path.relative(root, full).replaceAll(path.sep, '/');
        if (!old && focus.length && !focus.some(f => relative === f || relative.startsWith(f + '/'))) continue;
        all.push({full, relative});
      }
    }
  }
  visit(root);
  if (!old) {
    // The 2.1.310 inventory counts the Hook Worker and both native wrappers.
    // Avoid scanning unrelated plugin-testing-kit/vendor children here.
    const worker='src/plugins/functionHooks/hooks-worker/hooks-worker.js';
    const full=path.join(root,worker);
    if (fs.existsSync(full) && (!focus.length || focus.some(f => worker === f || worker.startsWith(f + '/'))))
      all.push({full,relative:worker});
  }
  return all;
}
function indexOld(functions) {
  const shape = new Map(), literal = new Map();
  for (const f of functions) {
    if (!shape.has(f.shape)) shape.set(f.shape, []);
    shape.get(f.shape).push(f);
    for (const s of f.literals) {
      if (!literal.has(s)) literal.set(s, []);
      literal.get(s).push(f);
    }
  }
  return {shape,literal};
}
function overlap(a,b) {
  const aa = new Set(a), bb = new Set(b);
  const intersection = [...aa].filter(x=>bb.has(x));
  return {intersection,ratio:intersection.length / Math.max(1, aa.size + bb.size - intersection.length)};
}
function matchOne(f,idx) {
  const same = idx.shape.get(f.shape) || [];
  if (same.length === 1 && f.tokens >= 55 && same[0].name.length >= 4)
    return {old:same[0], confidence:'高', reason:'唯一结构指纹', score:1, shared:[]};
  const pool = new Map();
  for (const literal of f.literals) {
    const peers = idx.literal.get(literal) || [];
    if (peers.length > 80) continue;
    for (const p of peers) pool.set(p.file + ':' + p.line, p);
  }
  const ranked = [];
  for (const p of pool.values()) {
    if (p.name.length < 4) continue;
    const o = overlap(f.literals,p.literals);
    if (o.intersection.length < 2) continue;
    const length = Math.min(f.tokens,p.tokens)/Math.max(f.tokens,p.tokens);
    const score = 0.80*o.ratio + 0.20*length;
    if (score >= 0.72) ranked.push({old:p,score,shared:o.intersection});
  }
  ranked.sort((a,b)=>b.score-a.score || a.old.file.localeCompare(b.old.file) || a.old.line-b.old.line);
  if (!ranked.length) return null;
  if (ranked[1] && ranked[0].score-ranked[1].score < 0.08)
    return {confidence:'歧义',score:ranked[0].score,choices:ranked.slice(0,3).map(x=>({file:x.old.file,name:x.old.name,line:x.old.line,score:+x.score.toFixed(3)}))};
  return {...ranked[0],confidence:'待复核',reason:'长字符串证据与函数规模相近'};
}
function plan({oldRoot,newRoot,focus=[],max=200,aliasPath,v40Map,reference='2.1.88'}) {
  const oldFiles = sourceFiles(oldRoot,{old:true});
  const newFiles = sourceFiles(newRoot,{focus});
  const old = oldFiles.flatMap(f=>collect(f.full,f.relative));
  const idx=indexOld(old);
  let aliases=[];
  if (aliasPath && fs.existsSync(aliasPath)) aliases=JSON.parse(fs.readFileSync(aliasPath,'utf8')).entries||[];
  const done = new Set(aliases.map(x => x.source.replace(/^src\//,'') + '::' + x.legacy));
  let v40Items=[];
  if (v40Map && fs.existsSync(v40Map)) v40Items=JSON.parse(fs.readFileSync(v40Map,'utf8')).items||[];
  const v40BySymbol=new Map();
  for (const item of v40Items) for (const d of item.definitions||[]) {
    const key=d.module+'::'+d.symbol;
    if (!v40BySymbol.has(key)) v40BySymbol.set(key,[]);
    v40BySymbol.get(key).push(item.symbolName);
  }
  const matches=[], review=[], ambiguous=[];
  let functions=0, skippedRecovered=0;
  for (const f of newFiles.flatMap(x=>collect(x.full,x.relative))) {
    functions++;
    if (done.has(f.file+'::'+f.name)) {skippedRecovered++;continue;}
    const m=matchOne(f,idx);
    if (!m) continue;
    const next={new:{file:f.file,name:f.name,line:f.line,tokens:f.tokens},confidence:m.confidence,score:+m.score.toFixed(3)};
    const v40Names=v40BySymbol.get(f.file+'::'+f.name);
    if(v40Names)next.v40LocatedNames=v40Names;
    if (m.confidence==='歧义') {next.choices=m.choices;ambiguous.push(next);continue;}
    next.old={file:m.old.file,name:m.old.name,line:m.old.line};
    next.evidence={reason:m.reason,sharedLiterals:m.shared.slice(0,8)};
    if (m.confidence==='高') matches.push(next); else review.push(next);
  }
  const sort=(a,b)=>a.new.file.localeCompare(b.new.file)||a.new.line-b.new.line;
  matches.sort(sort);review.sort(sort);ambiguous.sort(sort);
  return {
    schema:1,baseline:'2.1.310',reference,mode:'只读候选，未执行源码替换',
    v40LocationEvidence:{available:v40Items.length>0,records:v40Items.length,recognizedDefinitions:v40BySymbol.size,meaning:'已定位符号，不代表缺失函数'},
    scanned:{oldFiles:oldFiles.length,oldFunctions:old.length,newFiles:newFiles.length,newFunctions:functions,alreadyRestored:skippedRecovered},
    counts:{high:matches.length,review:review.length,ambiguous:ambiguous.length},
    high:matches.slice(0,max),review:review.slice(0,max),ambiguous:ambiguous.slice(0,max)
  };
}
function main(argv) {
  const option=k=>{const i=argv.indexOf(k);return i<0?null:argv[i+1]};
  const oldRoot=option('--old'),newRoot=option('--new')||path.resolve(__dirname,'../src');
  if(!oldRoot) throw Error('必须指定 --old <2.1.88/source/src 路径>');
  const focus=argv.includes('--all')?[]:(option('--focus')||'chunk-tqjdehqd.js,chunk-r4g83g7p.js,chunk-w9w4r9w1.js').split(',').filter(Boolean);
  const max=Number(option('--max')||200);
  if(!Number.isInteger(max)||max<1||max>5000)throw Error('--max 必须为 1 到 5000 的整数');
  const root=path.resolve(__dirname,'..');
  const out=option('--out')||path.join(root,'.generated','batch-restoration-candidates.json');
  const result=plan({oldRoot:path.resolve(oldRoot),newRoot:path.resolve(newRoot),focus,max,reference:option('--reference')||'2.1.88',v40Map:option('--v40-map')||path.join(root,'resources/research/v40-p0-symbol-locations.json'),aliasPath:option('--aliases')||path.join(root,'resources/restored-symbol-aliases.json')});
  fs.mkdirSync(path.dirname(out),{recursive:true});
  fs.writeFileSync(out,JSON.stringify(result,null,2)+'\n');
  process.stdout.write(JSON.stringify({file:out,scanned:result.scanned,counts:result.counts})+'\n');
}
if(require.main===module){try{main(process.argv.slice(2))}catch(e){console.error(e.message);process.exitCode=1}}
module.exports={features,collect,indexOld,matchOne,plan};
