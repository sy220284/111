'use strict';
// Real Storybook CLI/Vite production build from a minimal synthetic React
// component, served to a real Chromium process. This tests the framework/tool
// bridge, not an authenticated DS remote commit or human visual score.
const fs=require('node:fs'),os=require('node:os'),path=require('node:path');
const assert=require('node:assert/strict'),cp=require('node:child_process');
const {createHash}=require('node:crypto');
const {pathToFileURL}=require('node:url');
const root=path.resolve(__dirname,'..');
const out=process.env.V37_REPORT_DIR||fs.mkdtempSync(path.join(os.tmpdir(),'111-storybook-evidence-'));
fs.mkdirSync(out,{recursive:true});
const tmp=fs.mkdtempSync(path.join(os.tmpdir(),'111-storybook-live-'));
function put(p,text){const f=path.join(tmp,p);fs.mkdirSync(path.dirname(f),{recursive:true});fs.writeFileSync(f,text);return f}
async function main(){
 const binary=path.join(root,'node_modules','.bin','storybook');
 assert.ok(fs.existsSync(binary),'real Storybook CLI is required, not a fake static fixture');
 put('package.json',JSON.stringify({name:'v37-storybook-real-test',private:true,type:'module',
   dependencies:{'@storybook/react-vite':'9.1.10',react:'18.3.1','react-dom':'18.3.1'}}));
 fs.symlinkSync(path.join(root,'node_modules'),path.join(tmp,'node_modules'),'dir');
 put('.storybook/main.mjs',"export default {stories:['../src/**/*.stories.jsx'],addons:[],framework:{name:'@storybook/react-vite',options:{}}};\n");
 put('.storybook/preview.mjs',"export default {parameters:{controls:{expanded:true}}};\n");
 put('src/Card.jsx',"import React from 'react';export const Card=({label='default'})=>React.createElement('button',{id:'storybook-v37-card'},label);\n");
 put('src/Card.stories.jsx',"import {Card} from './Card.jsx';export default {title:'General/Card',component:Card};export const Primary={args:{label:'REAL_V37_STORYBOOK_RENDER'}};\n");
 const dst=path.join(tmp,'storybook-static');
 const build=cp.spawnSync(binary,['build','--config-dir',path.join(tmp,'.storybook'),
   '--output-dir',dst,'--disable-telemetry'],{
  cwd:tmp,encoding:'utf8',timeout:180000,env:{...process.env,CI:'true',STORYBOOK_DISABLE_TELEMETRY:'1'},
  maxBuffer:8*1024*1024});
 assert.equal(build.status,0,'actual Storybook production build failed\n'+
   String(build.stdout).slice(-2500)+'\n'+String(build.stderr).slice(-5000));
 const index=JSON.parse(fs.readFileSync(path.join(dst,'index.json'),'utf8'));
 const story=Object.entries(index.entries||{}).find(([key,x])=>x.type==='story'&&
   (key.includes('card')||x.title?.includes('Card')));
 assert.ok(story,'actual Storybook Vite build did not emit test story');
 const {serveDir}=await import(pathToFileURL(path.join(root,
   'resources/tools/design-sync/storybook/http-serve.mjs')).href);
 const {srv,port}=await serveDir(dst);
 const {chromium}=require('playwright');
 let browser;try{
  const opts={headless:true,args:['--disable-dev-shm-usage']};
  if(process.env.V37_CHROMIUM_PATH)opts.executablePath=process.env.V37_CHROMIUM_PATH;
  browser=await chromium.launch(opts);
  const page=await browser.newPage({viewport:{width:900,height:620}});
  const errors=[];page.on('pageerror',e=>errors.push(e.message));
  const uri='http://127.0.0.1:'+port+'/iframe.html?id='+encodeURIComponent(story[0])+'&viewMode=story';
  const response=await page.goto(uri,{waitUntil:'networkidle',timeout:40000});
  assert.equal(response.status(),200);
  await page.getByText('REAL_V37_STORYBOOK_RENDER').waitFor({timeout:20000});
  assert.deepEqual(errors,[]);
  const png=await page.screenshot({fullPage:true});
  assert.equal(png.subarray(0,8).toString('hex'),'89504e470d0a1a0a');
  assert.ok(png.length>2500);
  fs.writeFileSync(path.join(out,'storybook-real-chromium.png'),png);
  const report={status:'pass',kind:'real-v37-storybook-production-build',
   cli:'storybook 9.1.10 with react-vite',storyId:story[0],
   screenshotBytes:png.length,screenshotSha256:createHash('sha256').update(png).digest('hex'),
   realBrowser:true,renderedMarker:true,
   limitations:['Isolated synthetic component, not a production DS project.',
    'No authenticated remote upload or human visual quality grading.']};
  fs.writeFileSync(path.join(out,'storybook-real-build.json'),JSON.stringify(report,null,2)+'\n');
  console.log(JSON.stringify(report,null,2));
 }finally{await browser?.close();await new Promise(r=>srv.close(r))}
}
main().catch(e=>{console.error(e.stack||e);process.exitCode=1}).finally(()=>{
 fs.rmSync(tmp,{recursive:true,force:true});
});
