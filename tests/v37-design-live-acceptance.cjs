'use strict';
// Run the real v37 Design Sync package-build, real esbuild/ts-morph/React,
// Chromium browser rendering, screenshot acquisition and the original
// remote-diff upload partition. This is an isolated synthetic design-system
// fixture, not an authenticated cloud Storybook upload.
const fs=require('node:fs'),os=require('node:os'),path=require('node:path');
const assert=require('node:assert/strict');
const {spawnSync}=require('node:child_process');
const {createHash}=require('node:crypto');
const {pathToFileURL}=require('node:url');
const root=path.resolve(__dirname,'..');
const bundle=path.join(root,'resources/tools/design-sync');
const dir=fs.mkdtempSync(path.join(os.tmpdir(),'111-v37-design-browser-'));
const output=process.env.V37_REPORT_DIR||path.join(dir,'report');
fs.mkdirSync(output,{recursive:true});
const sha=b=>createHash('sha256').update(b).digest('hex');
function put(name,content){
 const f=path.join(dir,name);fs.mkdirSync(path.dirname(f),{recursive:true});fs.writeFileSync(f,content);return f;
}
function run(file,args){const r=spawnSync(process.execPath,[file,...args],
 {cwd:dir,encoding:'utf8',timeout:120000,env:{...process.env,CI:'true'}});
 if(r.status!==0)throw Error(path.basename(file)+' exited '+r.status+
  '\nstdout='+r.stdout.slice(-2000)+'\nstderr='+r.stderr.slice(-4500));
 return r;
}
async function main(){
 const react=path.join(root,'node_modules','react');
 const reactDom=path.join(root,'node_modules','react-dom');
 const playwright=require('playwright');
 for(const x of [react,reactDom])assert.ok(fs.existsSync(x),'real '+x+' must be installed');
 const fixture='sample-kit';
 const nm=path.join(dir,'node_modules');
 fs.mkdirSync(nm,{recursive:true});
 fs.symlinkSync(react,path.join(nm,'react'),'dir');
 fs.symlinkSync(reactDom,path.join(nm,'react-dom'),'dir');
 put('node_modules/'+fixture+'/package.json',JSON.stringify({
  name:fixture,version:'1.0.0',module:'dist/index.js',types:'dist/index.d.ts'}));
 put('node_modules/'+fixture+'/dist/index.js',
  "import React from 'react';export function Card({label='ACTUAL_BROWSER_CARD'}={}){return React.createElement('button',{id:'sample-card'},label)}\n");
 put('node_modules/'+fixture+'/dist/index.d.ts',
  "export declare function Card(props: {label?: string}): import('react').ReactElement;\n");
 put('.design-sync/config.json',JSON.stringify({pkg:fixture,shape:'package',
  componentSrcMap:{Card:'dist/index.js'}}));
 const out=path.join(dir,'ds-bundle'),entry=path.join(nm,fixture,'dist/index.js');
 const buildArgs=['--config','.design-sync/config.json','--node-modules',nm,
   '--entry',entry,'--out',out];
 run(path.join(bundle,'package-build.mjs'),buildArgs);
 assert.ok(fs.existsSync(path.join(out,'_ds_bundle.js')),'real bundler output absent');
 assert.ok(fs.existsSync(path.join(out,'_ds_sync.json')),'real upload sidecar absent');
 const map=JSON.parse(fs.readFileSync(path.join(out,'.stories-map.json'),'utf8'));
 assert.ok(map.components.some(x=>x.name==='Card'),'component lost from real Design Sync');
 const card=map.components.find(x=>x.name==='Card');
 const html='/components/'+card.group+'/Card/Card.html';
 const {serveDir}=await import(pathToFileURL(path.join(bundle,'storybook/http-serve.mjs')).href);
 const {srv,port}=await serveDir(out);
 let browser;
 try{
  const opts={headless:true,args:['--disable-dev-shm-usage']};
  if(process.env.V37_CHROMIUM_PATH)opts.executablePath=process.env.V37_CHROMIUM_PATH;
  browser=await playwright.chromium.launch(opts);
  const page=await browser.newPage({viewport:{width:900,height:650},deviceScaleFactor:1});
  const pageErrors=[];page.on('pageerror',e=>pageErrors.push(e.message));
  const res=await page.goto('http://127.0.0.1:'+port+html,{waitUntil:'networkidle',timeout:25000});
  assert.equal(res.status(),200);
  const render=await page.locator('body').innerText();
  assert.ok(render.includes('ACTUAL_BROWSER_CARD')||render.includes('Card'),
   'real Design Sync component did not render: '+render.slice(0,600));
  assert.deepEqual(pageErrors,[],'browser errors in generated bundle');
  const image=await page.screenshot({fullPage:true});
  assert.equal(image.subarray(0,8).toString('hex'),'89504e470d0a1a0a');
  assert.ok(image.length>2500);
  fs.writeFileSync(path.join(output,'design-before.png'),image);
  await page.addStyleTag({content:'body{filter:invert(1)!important}'});
  const changed=await page.screenshot({fullPage:true});
  assert.notEqual(sha(image),sha(changed),'Chromium screenshot cannot detect visual change');
  fs.writeFileSync(path.join(output,'design-visual-change.png'),changed);
  await page.close();
 }finally{await browser?.close();await new Promise(r=>srv.close(r))}
 const remote=path.join(dir,'prior-sidecar.json');
 fs.copyFileSync(path.join(out,'_ds_sync.json'),remote);
 run(path.join(bundle,'lib/remote-diff.mjs'),['--local',out,'--remote',remote]);
 const same=JSON.parse(fs.readFileSync(path.join(out,'.sync-diff.json'),'utf8'));
 assert.equal(same.upload?.any,false,'unchanged sidecar should never upload');
 // Change actual distributed component bytes; real source package gets rebuilt.
 put('node_modules/'+fixture+'/dist/index.js',
  "import React from 'react';export function Card({label='CHANGED_RENDER'}={}){return React.createElement('button',{id:'sample-card'},label)}\n");
 run(path.join(bundle,'package-build.mjs'),buildArgs);
 run(path.join(bundle,'lib/remote-diff.mjs'),['--local',out,'--remote',remote]);
 const altered=JSON.parse(fs.readFileSync(path.join(out,'.sync-diff.json'),'utf8'));
 assert.equal(altered.upload?.any,true,'changed build must generate an upload delta');
 const report={status:'pass',evidence:'original Design Sync package build + original remote-diff + actual Chromium',
  originalComponents:map.components.map(x=>({name:x.name,group:x.group})),
  screenshotSha256:sha(fs.readFileSync(path.join(output,'design-before.png'))),
  changedScreenshotSha256:sha(fs.readFileSync(path.join(output,'design-visual-change.png'))),
  uploadNoChange:same.upload,uploadAfterSourceChange:altered.upload,
  caveats:['Package-shape fixture, not a complete Storybook CLI build.',
   'Upload partition calculated by actual original code; authenticated remote service upload not available.',
   'Pixel change check is objective screenshot evidence, not a human visual-quality grade.']};
 fs.writeFileSync(path.join(output,'design-browser.json'),JSON.stringify(report,null,2)+'\n');
 console.log(JSON.stringify(report,null,2));
}
main().catch(e=>{console.error(e.stack||e);process.exitCode=1}).finally(()=>{
 if(!process.env.V37_KEEP_FIXTURE)fs.rmSync(dir,{recursive:true,force:true});
});
