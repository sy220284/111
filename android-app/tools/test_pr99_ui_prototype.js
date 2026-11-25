const fs=require('fs'), vm=require('vm'), assert=require('node:assert/strict');
const path=require('node:path');
const source=fs.readFileSync(path.join(__dirname,'../../docs/ui-assets/pr99/prototype.html'),'utf8');
const scripts=[...source.matchAll(/<script(?: [^>]+)?>([\s\S]*?)<\/script>/g)].map(x=>x[1]);
class E{
 constructor(tag){this.tag=tag;this.attrs={};this.listeners={};this.children=[];this.dataset={};this.style={};this.set=new Set;this.classList={add:(...s)=>s.forEach(x=>this.set.add(x)),remove:(...s)=>s.forEach(x=>this.set.delete(x)),toggle:(s,b)=>{let v=b??!this.set.has(s);if(v)this.set.add(s);else this.set.delete(s);return v}}}
 setAttribute(k,v){this.attrs[k]=String(v)} append(x){this.children.push(x)} replaceChildren(){this.children=[]} addEventListener(k,v){this.listeners[k]=v} set innerHTML(v){this._html=v;this.children=[new E('svg')]} get firstChild(){return this.children[0]}
}
const elems=Object.fromEntries(['data','nav','phone','events','caption','back','motion'].map(x=>['#'+x,new E('div')]));elems['#data'].textContent=scripts[0];
let doc={body:new E('body'),querySelector:s=>elems[s],querySelectorAll:s=>elems['#nav'].children.filter(x=>x.tag==='button'),createElement:s=>new E(s),createElementNS:(_,s)=>new E(s)};
let ctx=vm.createContext({document:doc});vm.runInContext(scripts[1],ctx);
const go=id=>vm.runInContext('show('+JSON.stringify(id)+')',ctx), cur=()=>vm.runInContext('current',ctx);
const hot=(label)=>{let matches=elems['#phone'].firstChild.children.filter(x=>x.attrs['aria-label']===label);assert(matches.length,'missing hotspot '+label+' on '+cur());matches.at(-1).listeners.click()};
const event=(text)=>{let b=elems['#events'].children.find(x=>x.textContent===text);assert(b,'missing event '+text);b.listeners.click()};
const target=id=>assert.equal(cur(),id);
hot('选择模型');target('model');hot('刷新目录');target('model-loading');event('目录读取失败');target('model-error');hot('重新读取');event('目录返回空列表');target('model-empty');
go('new-session');hot('选择模型');target('model-new-session');hot('服务商');target('model-provider');hot('国内模型');target('provider-list');
go('provider-form');hot('检查并保存');hot('确认保存');event('写入并重新连接成功');hot('验证模型工具调用');hot('开始验证');event('身份与双轮回传验证成功');target('provider-verified');
for(const [label,key] of [['GLM 智谱','-glm'],['MiniMax','-minimax'],['Kimi 开放平台','-kimi'],['Kimi Code 国内订阅','-kimi-code'],['通义千问','-qwen']]){
 go('provider-list');hot(label);target('provider-form'+key);hot('检查并保存');target('provider-save-confirm'+key);hot('确认保存');event('写入并重新连接成功');target('provider-catalog'+key);hot('验证模型工具调用');hot('开始验证');event('真实验证失败');target('provider-failed'+key);hot('重新验证');hot('开始验证');event('身份与双轮回传验证成功');target('provider-verified'+key)
}
go('provider-list');hot('添加自定义服务商');hot('检查并保存');hot('确认保存');event('配置回读成功');target('provider-configured');hot('服务商能力');target('provider-capabilities');go('provider-configured');hot('设为默认服务商');hot('确认切换');event('默认配置回读成功');target('provider-list');go('provider-configured');hot('移除本机密钥');hot('移除本机密钥');event('凭据移除并回读成功');target('provider-missing');
go('voice');hot('示例声音 B');event('写入并回读一致');target('voice-saved');hot('打开实时语音');target('realtime');
go('mcp-list');hot('查看服务');hot('最近连接诊断');target('mcp-diagnostic');
go('hook-run');event('收到阻断事件');target('hook-blocked');
go('runtime-waiting');event('旧进程开始退出');event('旧进程已退出');event('握手成功');target('runtime-connected');
go('tasks');hot('排序与筛选');hot('应用排序 更新时间');hot('筛选待处理');target('sort-updated-waiting');hot('完成');target('tasks-updated-waiting');hot('排序与筛选');target('sort-updated-waiting');
go('tasks');let stopControl=elems['#phone'].firstChild.children.find(x=>x.attrs['aria-label']==='停止或查看任务');assert(stopControl);stopControl.listeners.click();target('stop-confirm');hot('停止任务');target('tasks-waiting');
go('doc');hot('全文检索');target('doc-search');hot('完成搜索');target('doc');
go('preview');hot('放大');target('preview-zoom');hot('恢复原始大小');hot('下一张');target('preview-next');hot('上一张');target('preview');
go('image-result');hot('执行详情');target('image-meta');hot('关闭');target('image-result');
elems['#motion'].onclick({target:elems['#motion']});assert.equal(elems['#motion'].attrs['aria-pressed'],'true');
console.log('Prototype JavaScript state-transition tests: model, provider, voice, MCP, Hook, runtime, sorting, multitask, files, generated image, reduced motion passed.');
