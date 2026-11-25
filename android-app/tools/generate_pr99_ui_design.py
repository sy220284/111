#!/usr/bin/env python3
"""Generate editable PR99 phone design source, component specimens and a clickable review prototype.
Run from any cwd; input is the existing V8 tokens.json. All values are design examples, not live receipts.
"""
from pathlib import Path
import json, html, re
from collections import OrderedDict

ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'docs/ui-assets/pr99'
T=json.loads((OUT.parent/'tokens.json').read_text())
OUT.mkdir(parents=True, exist_ok=True)
E=lambda s:html.escape(str(s), quote=True)
W,H=393,852
FONT='Noto Sans CJK SC, Microsoft YaHei, sans-serif'
ICONS={
 'back':'M15 18l-6-6 6-6M9 12h12','close':'M6 6l12 12M18 6L6 18','menu':'M4 5h16v14H4zM9 5v14','refresh':'M20 7v5h-5M20 12a8 8 0 1 1-2-5','chevron':'M9 18l6-6-6-6','check':'M5 12l4 4L19 6','search':'M15.5 15.5L21 21M17 10a7 7 0 1 1-14 0 7 7 0 0 1 14 0','plus':'M12 5v14M5 12h14','send':'M12 19V5M6 11l6-6 6 6','more':'M5 12h.01M12 12h.01M19 12h.01','stop':'M7 7h10v10H7z','info':'M12 11v6M12 7h.01M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0','warning':'M12 3l10 18H2zM12 9v5M12 17h.01','file':'M6 3h8l4 4v14H6zM14 3v5h4M9 12h6M9 16h6','image':'M4 4h16v16H4zM4 17l5-5 4 4 3-3 4 4M15 8h.01','mic':'M9 5a3 3 0 0 1 6 0v7a3 3 0 0 1-6 0zM5 11v1a7 7 0 0 0 14 0v-1M12 19v3','sort':'M4 6h16M4 12h11M4 18h6','globe':'M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0M3 12h18M12 3c4 4 4 14 0 18-4-4-4-14 0-18','shield':'M12 3l8 3v6c0 4-3 7-8 9-5-2-8-5-8-9V6zM8 12l3 3 5-6','lock':'M6 10h12v10H6zM9 10V7a3 3 0 0 1 6 0v3','wave':'M3 12h2l2-6 3 12 4-14 3 11 2-3h2','clock':'M12 7v5l4 2M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0','settings':'M4 6h16M4 12h16M4 18h16M8 3v6M16 9v6M10 15v6','expand':'M4 10V4h6M14 4h6v6M20 14v6h-6M10 20H4v-6','minus':'M5 12h14','eye':'M2 12s4-6 10-6 10 6 10 6-4 6-10 6-10-6-10-6M15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0'
}
def svg_header(w,h,label):return f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" viewBox="0 0 {w} {h}" role="img" aria-label="{E(label)}">'
def color(c):return c
class Canvas:
 def __init__(self,mode='light',width=W,height=H):
  self.w=width; self.h=height; self.c=T['modes'][mode]; self.mode=mode; self.a=[];self.hot=[]
 def add(self,s): self.a.append(s)
 def rect(self,x,y,w,h,r=0,fill='panel',stroke=None,sw=1,opacity=1):
  if w<=0 or h<=0:raise ValueError((x,y,w,h))
  c=self.c.get(fill,fill);st=self.c.get(stroke,stroke) if stroke else 'none'
  self.add(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{r}" fill="{c}" stroke="{st}" stroke-width="{sw}" opacity="{opacity}"/>')
 def line(self,x,y,x2,y2,stroke='border',sw=1):self.add(f'<path d="M{x} {y}L{x2} {y2}" stroke="{self.c.get(stroke,stroke)}" stroke-width="{sw}" fill="none"/>')
 def text(self,x,y,s,size=14,fill='parchment',weight=400,anchor='start'):
  self.add(f'<text x="{x}" y="{y}" font-family="{FONT}" font-size="{size}" font-weight="{weight}" fill="{self.c.get(fill,fill)}" text-anchor="{anchor}">{E(s)}</text>')
 def lines(self,x,y,ss,size=13,fill='muted',step=21,weight=400):
  for i,s in enumerate(ss):self.text(x,y+i*step,s,size,fill,weight)
 def icon(self,name,x,y,fill='muted',size=24):
  self.add(f'<g transform="translate({x} {y}) scale({size/24})" fill="none" stroke="{self.c.get(fill,fill)}" stroke-width="1.8" stroke-linejoin="round" stroke-linecap="round"><path d="{ICONS[name]}"/></g>')
 def circle(self,x,y,r,fill='parchment',stroke=None):
  self.add(f'<circle cx="{x}" cy="{y}" r="{r}" fill="{self.c.get(fill,fill)}" stroke="{self.c.get(stroke,stroke) if stroke else "none"}"/>')
 def target(self,x,y,w,h,to,label):
  if to:
   if w<48 or h<48:raise ValueError('touch '+label)
   self.hot.append(dict(x=x,y=y,w=w,h=h,target=to,label=label))
 def header(self,title,sub='',back='home',right=None):
  self.rect(0,0,self.w,self.h,0,'canvas')
  self.text(20,25,'9:41',12,weight=600)
  self.line(self.w-72,20,self.w-64,20,'parchment',2);self.line(self.w-60,18,self.w-60,23,'parchment',2);self.rect(self.w-46,15,20,10,2,'parchment');self.rect(self.w-24,18,2,4,0,'parchment')
  self.icon('back' if back else 'menu',18,48)
  self.target(6,36,48,48,back or 'tasks','返回' if back else '打开侧栏')
  self.text(64,65,title,18,weight=700)
  if sub:self.text(64,84,sub,11,'muted')
  if right:
   icon,to,label=right;self.icon(icon,self.w-42,48);self.target(self.w-54,36,48,48,to,label)
  self.line(16,98,self.w-16,98)
  self.rect(self.w/2-60,self.h-16,120,4,2,'parchment')
 def section(self,y,s):self.text(20,y,s,12,'muted',600)
 def pill(self,x,y,s,tone='muted'):
  # status labels are small visual badges, not interactive controls
  w=max(50,len(s)*12+18)
  self.rect(x,y,w,24,12,'recessed',tone)
  self.text(x+w/2,y+16,s,11,tone,600,'middle');return w
 def row(self,y,title,sub='',icon=None,tail='chevron',tone='muted',target=None,selected=False,h=66,margin=16):
  x=margin; w=self.w-2*x
  if selected:self.rect(x,y,w,h,13,'recessed','border')
  if icon:
   self.rect(x+8,y+12,36,36,13,'recessed'); self.icon(icon,x+14,y+18,tone)
  tx=x+54 if icon else x+12
  self.text(tx,y+24,title,15,'parchment',600)
  if sub:self.text(tx,y+46,sub,12,'muted')
  if tail:self.icon(tail,x+w-32,y+(h-24)/2,'jade' if tail=='check' else tone,20)
  if not selected:self.line(tx,y+h,x+w-8,y+h)
  self.target(x,y,w,h,target,title);return y+h
 def btn(self,y,label,to=None,kind='primary',x=16,w=None):
  w=w if w is not None else self.w-32
  fill='parchment' if kind=='primary' else 'red' if kind=='danger' else 'recessed' if kind=='disabled' else 'panel'
  text='canvas' if kind in ('primary','danger') else 'muted' if kind=='disabled' else 'parchment'
  self.rect(x,y,w,48,13,fill,'border' if kind=='secondary' else None)
  self.text(x+w/2,y+30,label,14,text,600,'middle')
  self.target(x,y,w,48,to,label)
 def field(self,y,label,val='',icon=None):
  self.text(20,y,label,12,'muted',600);self.rect(16,y+10,self.w-32,52,13,'panel','controlBorder')
  self.text(30,y+42,val,14,'parchment' if val else 'muted')
  if icon:self.icon(icon,self.w-46,y+24)
 def notice(self,y,title,lines,tone='warning',icon='warning'):
  h=62+max(0,len(lines)-1)*20;self.rect(16,y,self.w-32,h,16,'recessed','border')
  self.icon(icon,28,y+14,tone,20);self.text(56,y+28,title,14,tone,600);self.lines(28,y+50,lines,12,'muted',20)
  return y+h
 def progress(self,y,stages,current,detail):
  for i,stage in enumerate(stages):
   cy=y+22+i*66
   if i<len(stages)-1:self.line(36,cy+12,36,cy+54,'border',2)
   self.circle(36,cy,12,'recessed','jade' if i<current else 'coral' if i==current else 'border')
   if i<current:self.icon('check',28,cy-8,'jade',16)
   elif i==current:self.circle(36,cy,4,'coral')
   self.text(60,cy+2,stage,15,'parchment' if i<=current else 'muted',600 if i==current else 400)
   self.text(60,cy+22,detail[i],12,'muted')
 def composer(self,label='遵循默认模型',to='model',msg='让 Codex 完成任务…'):
  y=self.h-163;self.rect(16,y,self.w-32,94,22,'panel','controlBorder')
  self.text(30,y+28,msg,14,'muted');self.icon('plus',30,y+57)
  self.text(62,y+74,label,13,weight=600);self.icon('chevron',185,y+57,size=18)
  self.target(60,y+46,158,48,to,'选择模型')
  self.icon('mic',self.w-104,y+57);self.circle(self.w-48,y+70,22,'parchment');self.icon('send',self.w-60,y+58,'canvas')
  self.text(25,self.h-44,'权限  ·  执行',12,'muted');self.icon('more',self.w-43,self.h-58,size=18)
 def result(self):return ''.join(self.a)
SCREENS=OrderedDict()
def screen(id,module,title,fn,desc='',mode='light',width=W):
 c=Canvas(mode,width);fn(c);SCREENS[id]={'id':id,'module':module,'title':title,'desc':desc,'mode':mode,'width':width,'height':H,'svg':c.result(),'hotspots':c.hot}
def chat(c):
 c.header('Codex','当前工作区 · 111',None,('plus','new-session','新建任务'))
 c.text(20,150,'检查项目并整理待办',20,weight=700)
 c.rect(76,184,c.w-92,58,18,'recessed');c.text(92,218,'先检查现有代码和测试结果',14)
 c.text(20,280,'Codex',14,weight=600);c.lines(20,310,['已完成基础检查。接下来核对','相关页面与组件的运行状态。'],15,'parchment',25)
 c.row(378,'执行检查','查看本次检查的过程与回执','shield','chevron',target='hook-run')
 c.row(446,'设计说明.md','任务建议预览 · 只读','file','chevron',target='doc')
 c.row(514,'生成图片','打开图片及生成信息','image','chevron',target='image-result')
 c.composer()
screen('home','入口','会话工作台',chat,'唯一常驻工作区')
def new_session(c):
 c.header('新建任务','当前工作区 · 111',None,('plus','new-session','新建任务'))
 c.text(c.w/2,324,'要完成什么任务？',22,weight=700,anchor='middle')
 c.text(c.w/2,357,'选择模型与服务商后开始',14,'muted',anchor='middle')
 c.composer(to='model-new-session')
screen('new-session','入口','新建任务',new_session,'新任务可使用独立服务商')
def model(c,state='ready',new=False):
 (new_session(c) if new else chat(c));c.hot.clear();c.add('<g class="motion-menu">');x=c.w-296;y=278 if state=='ready' else 360
 c.rect(x,y,280,412 if state=='ready' else 330,24,'elevated','border')
 c.text(x+16,y+30,'选择运行模型',17,weight=700);c.icon('close',x+250,y+12,size=18);c.target(x+232,y,48,48,'new-session' if new else 'home','关闭模型菜单')
 c.text(x+16,y+54,'当前服务商 · ChatGPT',12,'muted')
 if state=='ready':
  c.rect(x+12,y+70,256,48,13,'panel','border');c.icon('search',x+24,y+82,size=20);c.text(x+53,y+100,'搜索可用模型',13,'muted')
  c.text(x+16,y+142,'当前可用 · 示例目录',11,'muted',600)
  for i,(name,sub,selected) in enumerate([('遵循默认模型','由当前服务决定',False),('示例模型 A','思考强度可选',True),('示例模型 B','以实际目录为准',False)]):
   yy=y+154+i*58
   if selected:c.rect(x+12,yy,256,56,13,'recessed','border')
   c.text(x+25,yy+22,name,14,weight=600);c.text(x+25,yy+42,sub,11,'muted')
   if selected:c.icon('check',x+234,yy+18,'jade',20)
   c.target(x+12,yy,256,56,(['model-default-new','model-selected-new','model-selected-b-new'] if new else ['model-default','model-selected','model-selected-b'])[i],name)
  c.line(x+16,y+335,x+264,y+335)
  c.icon('refresh',x+20,y+350,size=20);c.text(x+49,y+365,'刷新目录',13,weight=600);c.target(x+8,y+339,128,48,'model-loading','刷新目录')
  c.text(x+145,y+365,'服务商  ›' if new else '服务商已固定',13,'parchment' if new else 'muted',600);c.target(x+136,y+339,136,48,'model-provider' if new else None,'服务商')
  c.text(x+16,y+397,'新会话可以更换服务商' if new else '历史会话保留所属服务商',11,'muted')
 else:
  c.rect(x+12,y+75,256,95,13,'recessed','border');c.icon('refresh' if state=='loading' else 'warning',x+22,y+90,'coral' if state=='loading' else 'warning',20)
  c.text(x+50,y+105,'正在读取模型目录' if state=='loading' else '暂无可用模型' if state=='empty' else '模型目录读取失败',14,weight=600)
  c.lines(x+22,y+132,['保留原选择，等待真实目录。'] if state=='loading' else ['连接恢复后可重新读取。','原有草稿和选择保持不变。'],11,'muted',18)
  c.text(x+18,y+201,'已保存的选择',12,'muted');c.text(x+18,y+225,'遵循默认模型',15,weight=600)
  c.rect(x+12,y+250,256,48,13,'parchment');c.text(x+140,y+280,'正在读取…' if state=='loading' else '重新读取',14,'canvas',600,'middle');c.target(x+12,y+250,256,48,None if state=='loading' else 'model-loading','重新读取')
 c.add('</g>')
screen('model','模型','模型选择 · 可用目录',lambda c:model(c),'按钮锚定菜单；目录只列真实可选项')
screen('model-new-session','模型','新会话模型菜单',lambda c:model(c,new=True),'服务商可切换；当前目录隔离')
screen('model-loading','模型','刷新中',lambda c:model(c,'loading'),'等待真实回执，选择锁定')
screen('model-error','模型','刷新失败',lambda c:model(c,'error'),'保持旧选择，提供恢复')
screen('model-empty','模型','有效空目录',lambda c:model(c,'empty'),'连接正常但没有真实可选模型')
def model_selected(c,label='示例模型 A',new=False):
 (new_session(c) if new else chat(c));c.rect(16,644,c.w-32,42,12,'recessed','border');c.icon('check',28,655,'jade',20);c.text(54,670,label+' · 后续任务生效',12,'jade',600);c.composer(label,to='model-new-session' if new else 'model')
screen('model-selected','模型','选择反馈',model_selected,'即时更新本会话草稿，运行中锁定')
screen('model-selected-b','模型','切换另一模型',lambda c:model_selected(c,'示例模型 B'),'即时更新当前选择；后续任务生效')
screen('model-default','模型','恢复默认模型',lambda c:model_selected(c,'遵循默认模型'),'清除显式选择，遵循内核默认')
for sid,label in [('model-default-new','遵循默认模型'),('model-selected-new','示例模型 A'),('model-selected-b-new','示例模型 B')]:
 screen(sid,'模型','新会话 · '+label,lambda c,label=label:model_selected(c,label,True),'新会话草稿保持模型与服务商的独立归属')
 SCREENS[sid]['prototypeOnly']=True

def model_provider(c):
 c.header('选择新会话服务商','模型目录独立授权','model-new-session')
 c.notice(118,'仅适用于新会话',['历史会话会继续使用原服务商。'],'muted','info')
 c.section(220,'服务商')
 c.row(234,'遵循默认服务商','使用全局设置','globe','check',selected=True,target='model-new-session')
 c.row(304,'ChatGPT','当前账号目录','globe',target='model-new-session')
 c.row(374,'国内模型','查看已配置服务商','settings',target='provider-list')
 c.btn(746,'管理服务商','provider-list','secondary')
screen('model-provider','模型','服务商选择',model_provider,'新会话可切换；历史会话只读所属服务商')
def providers(c):
 c.header('模型服务商','设置 · 模型','home',('refresh','provider-list','刷新'))
 c.rect(16,118,c.w-32,102,16,'recessed','border');c.text(30,145,'默认服务商',12,'muted');c.text(30,175,'ChatGPT',22,weight=700);c.pill(c.w-114,153,'已选择','jade');c.text(30,199,'后续新会话使用此服务商',12,'muted')
 c.section(258,'国内模型预置')
 for i,(name,sub) in enumerate([('DeepSeek','开放平台 API · 待配置'),('GLM 智谱','Coding Plan · 待配置'),('MiniMax','开放平台 · 待配置'),('Kimi','开放平台 API · 待配置')]):c.row(272+i*66,name,sub,'globe',target='provider-form')
 c.section(570,'其他服务商')
 c.row(582,'添加自定义服务商','连接地址与密钥引用','plus',target='provider-form')
 c.notice(678,'预置不等于授权',['保存凭据后仍需核验真实模型。'],'muted','info')
screen('provider-list','国内模型','服务商列表',providers,'按授权与配置状态区分')
def provider_form(c,state='form'):
 c.header('配置国内模型','DeepSeek · 官方接口','provider-list',('close','provider-list','关闭'))
 c.field(135,'服务商名称','DeepSeek')
 c.field(216,'连接地址','官方预置地址')
 c.field(297,'API 密钥','••••••••••••••••','eye')
 c.lines(24,384,['密钥保存在本机加密记录中。','连接与模型能力将在保存后分别核实。'],12)
 c.section(440,'配置影响')
 c.row(450,'新会话可选择','既有会话保留原来的服务商','info',None)
 if state=='form':c.btn(746,'检查并保存','provider-save-confirm')
 else:
  c.notice(539,'正在保存并重新连接',['等待旧内核退出；保留会话和草稿。'],'coral','refresh')
  c.btn(746,'正在保存…',None,'disabled')
screen('provider-form','国内模型','授权表单',provider_form,'密钥受保护；提交前说明变更范围')
screen('provider-saving','国内模型','保存与连接',lambda c:provider_form(c,'saving'),'锁定重复提交，等待内核重新连接')
def provider_save_confirm(c):
 provider_form(c);c.hot.clear();c.rect(0,0,c.w,H,0,'#111A22',opacity=.42);c.rect(18,274,c.w-36,304,24,'panel')
 c.text(38,314,'保存模型服务商',20,weight=700)
 c.lines(38,353,['凭据写入本机加密记录。','保存后需要重新连接本机内核。','检查当前任务，空闲时才允许继续。'],13,'muted',24)
 c.btn(456,'确认保存','provider-saving',x=38,w=c.w-76);c.btn(516,'取消','provider-form','secondary',x=38,w=c.w-76)
screen('provider-save-confirm','国内模型','保存确认',provider_save_confirm,'全局占用检查后允许保存')

def catalog(c,state='ready'):
 c.header('模型目录','DeepSeek · 只读候选或账号目录','provider-list',('refresh','provider-catalog','刷新目录'))
 c.rect(16,118,c.w-32,116,16,'recessed','border');c.text(30,148,'连接 / 授权状态',12,'muted');c.text(30,177,'已配置 · 能力待验证',19,weight=700)
 c.lines(30,200,['目录来源以当前服务商返回为准。','候选模型尚未准入当前会话。'],12,'muted',18)
 c.section(270,'候选模型 · 示例')
 c.row(282,'示例模型 A','候选 · 授权与能力待核验','globe',None)
 c.btn(351,'验证模型工具调用','provider-confirm','secondary')
 if state=='verified':
  c.notice(426,'双轮验证通过',['实际模型身份已确认；以真实回执为准。'],'jade','check')
  c.btn(746,'返回服务商','provider-list')
 elif state=='failed':
  c.notice(426,'模型验证未通过',['查看原因并调整配置后重新验证。'],'red','warning')
  c.btn(746,'重新验证','provider-confirm')
 else:
  c.notice(426,'验证说明',['每轮限制输出量，可能产生模型费用。','不上传项目文件或对话。'],'muted','info')
  c.btn(746,'返回服务商','provider-list','secondary')
screen('provider-catalog','国内模型','目录与验证',catalog,'区分目录授权、候选与任务准入')
def provider_confirm(c):
 catalog(c);c.hot.clear();c.rect(0,0,c.w,H,0,'#111A22',opacity=.42);x=18;y=277
 c.rect(x,y,c.w-36,334,24,'panel');c.text(x+20,y+35,'真实模型能力验证',20,weight=700)
 c.lines(x+20,y+74,['最多发送两轮固定测试推理请求。','每轮限制输出量，可能产生模型费用。','不会上传对话或项目文件，不执行真实工具。'],13,'muted',25)
 c.line(x+20,y+158,c.w-x-20,y+158)
 c.text(x+20,y+187,'待验证：示例模型 A',14,weight=600)
 c.btn(y+218,'开始验证','provider-running',x=x+20,w=c.w-2*x-40)
 c.btn(y+270,'取消','provider-catalog','secondary',x=x+20,w=c.w-2*x-40)
screen('provider-confirm','国内模型','验证确认',provider_confirm,'在产生费用前确认')
def provider_running(c):
 c.header('正在验证模型','示例模型 A','provider-catalog')
 c.notice(118,'正在执行固定测试',['请等待服务商返回真实结果。'],'coral','refresh')
 c.progress(220,['第一轮推理','工具结果回传','确认模型身份'],1,['收到首轮结果','等待第二轮回传','等待真实确认'])
 c.btn(746,'正在验证…',None,'disabled')
screen('provider-running','国内模型','双轮验证过程',provider_running,'阶段按回执推进，无伪造百分比')
screen('provider-verified','国内模型','真实验证回执',lambda c:catalog(c,'verified'),'身份和工具回传均核实后准入')
screen('provider-failed','国内模型','验证失败',lambda c:catalog(c,'failed'),'保留候选，明确重试')
def voice(c,state='ready'):
 c.header('账号默认声音','账号设置 · ChatGPT','home',('refresh','voice','刷新'))
 c.rect(16,118,c.w-32,115,16,'recessed','border');c.icon('wave',30,140,'coral',30);c.text(75,155,'账号默认声音',18,weight=700);c.text(75,184,'当前：示例声音 B' if state=='saved' else '当前：示例声音 A',14,'muted')
 c.text(30,214,'后续实时语音使用账号设置',12,'muted')
 c.section(265,'账号返回的声音选项')
 c.row(280,'示例声音 A','可选声音' if state=='saved' else '当前选择','wave','chevron' if state=='saved' else 'check',selected=state!='saved',target='voice' if state!='saving' else None)
 c.row(350,'示例声音 B','当前选择' if state=='saved' else '选择后同步账号','wave','check' if state=='saved' else 'chevron',selected=state=='saved',target='voice-saving' if state!='saving' else None)
 c.row(420,'示例声音 C','选择后同步账号','wave',target='voice-saving' if state!='saving' else None)
 if state=='saving':c.notice(505,'正在同步账号声音',['等待写入与重新读取一致。'],'coral','refresh');c.btn(746,'正在同步…',None,'disabled')
 elif state=='saved':c.notice(505,'账号默认声音已保存',['已重新读取并核实选择。'],'jade','check');c.btn(746,'打开实时语音','realtime')
 else:c.btn(746,'打开实时语音','realtime','secondary')
screen('voice','声音','账号默认声音',voice,'只陈列当前账号实际返回的选项')
screen('voice-saving','声音','写入与回读',lambda c:voice(c,'saving'),'保存中不提前勾选')
screen('voice-saved','声音','账号保存回执',lambda c:voice(c,'saved'),'成功以账号回读为准')
def realtime(c):
 c.header('实时语音','当前会话','home',('close','home','关闭'))
 c.rect(16,138,c.w-32,230,24,'recessed','border')
 c.circle(c.w/2,228,58,'panel','border')
 c.icon('wave',c.w/2-26,202,'coral',52)
 c.text(c.w/2,324,'准备开始实时语音',18,weight=600,anchor='middle')
 c.text(c.w/2,348,'声音由当前服务端决定',12,'muted',anchor='middle')
 c.row(405,'账号默认声音','管理后续语音的默认选项','wave',target='voice')
 c.btn(746,'开始实时语音',None)
screen('realtime','声音','实时语音入口',realtime,'仅链接账号默认声音设置，无会话声音覆盖')
def mcp_list(c):
 c.header('MCP 服务','设置 · 扩展能力','home',('refresh','mcp-list','刷新可用服务'))
 c.text(20,136,'当前可用服务',20,weight=700);c.text(c.w-20,136,'2 项示例',12,'muted',anchor='end')
 for i,n in enumerate(['示例检索服务','示例文件服务']):
  y=162+i*86;c.rect(16,y,c.w-32,74,16,'panel','border');c.icon('globe',30,y+22);c.text(64,y+28,n,15,weight=600);c.pill(64,y+38,'可用','jade');c.icon('chevron',c.w-44,y+26,size=20);c.target(16,y,c.w-32,74,'mcp-detail','查看服务')
 c.notice(354,'服务由智能体管理',['需要接入、更新或重新授权时，','在任务中告诉 Codex。'],'muted','info')
 c.row(460,'最近连接诊断','查看启动回执与失败原因','clock',target='mcp-diagnostic')
screen('mcp-list','MCP','只读可用目录',mcp_list,'只有已连接且确实可用的服务进入列表')
def mcp_detail(c):
 c.header('示例检索服务','MCP · 服务详情','mcp-list',('close','mcp-list','关闭'))
 c.rect(16,118,c.w-32,150,16,'recessed','border');c.pill(30,136,'可用','jade');c.text(30,192,'240 ms',32,weight=700);c.text(30,218,'最近启动耗时 · 示例回执',12,'muted');c.text(30,244,'当前会话或全局范围以回执为准',12,'muted')
 c.section(305,'能力概览');c.row(316,'工具 1 项 · 资源 1 项','由智能体在当前任务使用','globe',None)
 c.row(392,'最近连接诊断','展开启动状态与连接范围','clock',target='mcp-diagnostic')
 c.notice(484,'已就绪服务',['授权、配置与工具调用由智能体管理。'],'muted','info')
screen('mcp-detail','MCP','服务详情与耗时',mcp_detail,'耗时仅在真实 durationMs 存在时显示')
def mcp_diag(c):
 c.header('MCP 连接诊断','专业诊断 · 最近一次回执','mcp-list')
 c.section(135,'当前连接记录')
 c.row(150,'示例检索服务','已就绪 · 240 毫秒','check',None,'jade')
 c.row(220,'示例文件服务','正在启动 · 耗时待返回','refresh',None,'coral')
 c.row(290,'示例授权服务','启动失败 · 需要重新授权','warning',None,'red')
 c.row(360,'示例取消服务','已取消 · 本次启动结束','stop',None,'muted')
 c.notice(454,'需要重新授权',['返回相关任务，请智能体继续处理授权。','未就绪的服务不会加入可用目录。'],'warning','lock')
 c.btn(746,'返回任务','home')
screen('mcp-diagnostic','MCP','连接诊断',mcp_diag,'真实启动记录留在诊断，失败服务不进可用目录')
def hook_settings(c):
 c.header('执行检查','高级设置 · 用户规则','home')
 c.notice(118,'管理已有用户检查',['项目和插件检查由原来源管理。','仅显示可编辑的用户规则。'],'muted','info')
 c.section(236,'用户配置')
 c.rect(16,250,c.w-32,176,16,'panel','border');c.text(30,280,'任务执行前检查',17,weight=600);c.text(30,304,'失败时阻断',12,'warning')
 for i,(label,sel) in enumerate([('继续执行',False),('阻断操作',True)]):
  yy=322+i*48;c.circle(40,yy+22,10,'panel','controlBorder')
  if sel:c.circle(40,yy+22,5,'parchment')
  c.text(60,yy+27,label,14);c.target(24,yy,c.w-48,48,'hook-confirm',label)
 c.rect(16,442,c.w-32,100,16,'panel','border');c.text(30,472,'异步结果检查',16,weight=600);c.text(30,500,'仅支持继续执行',13,'muted')
 c.row(562,'查看本次执行检查','时间线 · 实际状态与依据','shield',target='hook-run')
screen('hook-settings','Hook','用户检查策略',hook_settings,'展示既有用户规则；异步检查不提供阻断选择')
def hook_confirm(c):
 hook_settings(c);c.hot.clear();c.rect(0,0,c.w,H,0,'#111A22',opacity=.42);c.rect(18,294,c.w-36,272,24,'panel')
 c.text(38,334,'保存检查策略',19,weight=700)
 c.lines(38,372,['更改将影响后续执行。','规则可能需要重新信任；','已运行任务的结果保持原样。'],13,'muted',23)
 c.btn(452,'确认保存','hook-settings',x=38,w=c.w-76);c.btn(508,'取消','hook-settings','secondary',x=38,w=c.w-76)
screen('hook-confirm','Hook','策略保存确认',hook_confirm,'规则变更后重新读取配置')
def hook_run(c,state='running'):
 c.header('会话时间线','本次任务 · 执行检查','home')
 c.text(20,136,'执行检查',20,weight=700);c.text(20,160,'任务开始前',12,'muted')
 status={'running':('执行检查中','coral','refresh'),'completed':('执行检查完成','jade','check'),'failed':('执行检查失败','red','warning'),'blocked':('执行检查已阻断操作','red','shield'),'stopped':('执行检查已停止','muted','stop')}[state]
 c.rect(16,184,c.w-32,232,16,'panel','border');c.icon(status[2],30,205,status[1]);c.text(66,224,status[0],16,status[1],600)
 c.line(30,247,c.w-30,247)
 if state=='running':c.lines(30,280,['正在等待检查结果。','检查结束后将显示实际执行结果。'],14,'muted',24)
 else:
  c.text(30,280,'执行耗时 · 860 毫秒（示例）',14,weight=600);c.lines(30,310,['结果由内核通知确定。','原因与详情见本次执行记录。'],13,'muted',22)
  if state in ['failed','blocked']:c.text(30,372,'示例原因：检查规则未通过',13,'red')
 c.row(440,'执行依据','查看事件、耗时和返回原因','file',None)
 if state=='running':
  c.btn(746,'返回任务','home','secondary')
 else:c.btn(746,'返回任务','home','secondary')
for sid,s in [('hook-run','running'),('hook-completed','completed'),('hook-failed','failed'),('hook-blocked','blocked'),('hook-stopped','stopped')]:screen(sid,'Hook',{'running':'执行中','completed':'检查完成','failed':'检查失败','blocked':'操作被阻断','stopped':'检查停止'}[s],lambda c,s=s:hook_run(c,s),'真实 hook status 决定状态')
def runtime(c,state='waiting'):
 c.header('运行状态','本机内核 · 自动恢复','home',('close','home','关闭'))
 cur={'waiting':0,'retiring':1,'starting':2,'connected':3,'failed':0}[state]
 labels=['等待自动恢复','正在回收旧内核','正在初始化内核','本机内核已连接']
 details=['本次等待间隔 5 秒（示例）','等待旧进程实际退出','启动并完成协议握手','收到有效连接回执']
 c.rect(16,118,c.w-32,124,16,'recessed','border');c.icon('check' if state=='connected' else 'warning' if state=='failed' else 'refresh',30,138,'jade' if state=='connected' else 'red' if state=='failed' else 'coral',28)
 c.text(74,157,'内核连接异常' if state=='failed' else labels[cur],19,weight=700)
 c.lines(30,195,['历史与草稿已保留。','未完成指令不会自动重发。'],13,'muted',21)
 c.section(280,'恢复阶段 · 以下为状态示意')
 if state=='failed':c.notice(296,'自动恢复未完成',['查看诊断和日志，确认后重新连接。'],'red','warning')
 else:c.progress(296,labels,cur,details)
 c.row(594,'查看诊断与日志','实际原因与恢复操作','info',target='runtime-failed')
 nextid={'waiting':'runtime-retiring','retiring':'runtime-starting','starting':'runtime-connected','connected':'home','failed':'runtime-waiting'}[state]
 c.btn(746,'继续任务' if state=='connected' else '重新连接' if state=='failed' else '恢复中…',nextid if state in {'connected','failed'} else None,'primary' if state in {'connected','failed'} else 'disabled')
for sid,s in [('runtime-waiting','waiting'),('runtime-retiring','retiring'),('runtime-starting','starting'),('runtime-connected','connected'),('runtime-failed','failed')]:screen(sid,'内核恢复',{'waiting':'等待恢复','retiring':'回收旧内核','starting':'初始化连接','connected':'恢复完成','failed':'恢复失败'}[s],lambda c,s=s:runtime(c,s),'真实阶段，避免伪造进度和自动重发')
def tasks(c,filter='active',order='最近活动'):
 full=c.w;c.rect(0,0,full,H,0,'#111A22',opacity=.42);c.w=min(336,full-32)
 c.header('会话与任务','当前工作区 · 111','home',('plus','new-session','新建任务'))
 c.rect(16,117,c.w-32,48,13,'recessed','border');c.icon('search',29,129,size=20);c.text(60,146,'搜索会话',14,'muted')
 c.rect(16,183,c.w-32,84,16,'panel','border');c.text(30,210,'进行中 · '+order,13,'muted');c.text(30,243,'3 项',24,weight=700);c.pill(120,220,'待处理 2','warning');c.icon('sort',c.w-46,202);c.target(c.w-64,184,48,72,'sort'+{'最近活动':'','更新时间':'-updated','创建时间':'-created'}[order]+{'active':'','waiting':'-waiting','all':'-all','running':'-running','failed':'-failed'}[filter],'排序与筛选')
 c.section(303,'当前筛选 · '+{'waiting':'待处理','active':'进行中','all':'全部','running':'执行中','failed':'异常'}[filter])
 data=[('检查 Android 界面','执行中 · 最近活动','coral','stop'),('等待确认配置变更','待审批 · 需要你处理','warning','chevron'),('整理接口清单','等待回答 · 当前任务','warning','chevron')]
 if filter=='waiting':data=data[1:]
 if filter=='running':data=data[:1]
 if filter=='failed':data=[('同步接口目录','异常 · 查看执行原因','red','chevron')]
 if filter=='all':data=data+[('已完成的界面校对','已完成 · 昨天','jade','chevron')]
 if order=='更新时间':data=sorted(data,key=lambda v: {'等待确认配置变更':0,'检查 Android 界面':1,'整理接口清单':2}.get(v[0],3))
 if order=='创建时间':data=sorted(data,key=lambda v: {'整理接口清单':0,'检查 Android 界面':1,'等待确认配置变更':2}.get(v[0],3))
 for i,(title,sub,tone,icon) in enumerate(data):
  yy=318+i*85;c.rect(16,yy,c.w-32,73,16,'panel','border');c.circle(34,yy+20,4,tone);c.text(48,yy+25,title,15,weight=600);c.text(48,yy+51,sub,12,tone)
  c.icon(icon,c.w-46,yy+25,tone);c.target(16,yy,c.w-76,73,'approval' if tone=='warning' else 'home','定位任务');c.target(c.w-64,yy,48,73,'stop-confirm' if icon=='stop' else 'approval' if tone=='warning' else 'home','停止或查看任务')
 c.btn(746,'排序与筛选','sort'+{'最近活动':'','更新时间':'-updated','创建时间':'-created'}[order]+{'active':'','waiting':'-waiting','all':'-all','running':'-running','failed':'-failed'}[filter],'secondary');c.w=full
screen('tasks','多任务','侧栏进行中任务',tasks,'抽屉内统一管理，待审批与停止可直接定位')
screen('tasks-waiting','多任务','待处理任务',lambda c:tasks(c,'waiting'),'筛选仅改变列表，不改变任务状态')
for sid,filt,title in [('tasks-all','all','全部会话'),('tasks-running','running','执行中任务'),('tasks-failed','failed','异常任务')]:
 screen(sid,'多任务',title,lambda c,filt=filt:tasks(c,filt),'切换筛选不修改原始会话状态')
ORDER_IDS={'最近活动':'','更新时间':'-updated','创建时间':'-created'}
FILTER_IDS={'进行中':'','全部':'-all','执行中':'-running','待处理':'-waiting','异常':'-failed'}
FILTER_KEYS={'进行中':'active','全部':'all','执行中':'running','待处理':'waiting','异常':'failed'}
def sort_id(order,filter):return 'sort'+ORDER_IDS[order]+FILTER_IDS[filter]
def tasks_id(order,filter):return 'tasks'+ORDER_IDS[order]+FILTER_IDS[filter]
def sort(c,order='最近活动',filter='进行中'):
 tasks(c,FILTER_KEYS[filter],order);c.hot.clear();c.rect(0,0,c.w,H,0,'#111A22',opacity=.42)
 c.rect(12,178,c.w-24,H-198,24,'panel');c.rect(c.w/2-18,188,36,4,2,'border')
 c.text(28,228,'会话排序与筛选',20,weight=700);c.icon('close',c.w-48,207);c.target(c.w-60,195,48,48,tasks_id(order,filter),'关闭')
 c.section(270,'排序方式 · 单选')
 for i,s in enumerate(['最近活动','更新时间','创建时间']):
  selected=s==order
  yy=283+i*48;c.text(32,yy+29,s,14);c.circle(c.w-42,yy+24,10,'panel','controlBorder')
  if selected:c.circle(c.w-42,yy+24,5,'parchment')
  c.target(24,yy,c.w-48,48,sort_id(s,filter),'应用排序 '+s)
 c.section(459,'任务状态')
 for i,s in enumerate(['全部','进行中','执行中','待处理','异常']):
  selected=s==filter
  yy=468+i*48;c.text(32,yy+29,s,14);c.circle(c.w-42,yy+24,10,'panel','controlBorder')
  if selected:c.circle(c.w-42,yy+24,5,'parchment')
  c.target(24,yy,c.w-48,48,sort_id(order,s),'筛选'+s)
 c.btn(756,'完成',tasks_id(order,filter))
screen('sort','会话排序','排序与状态筛选',sort,'即时应用排他选择，完成关闭后保持滚动位置')
for sid,order,filter in [('sort-updated','更新时间','进行中'),('sort-created','创建时间','进行中'),('sort-all','最近活动','全部'),('sort-running','最近活动','执行中'),('sort-waiting','最近活动','待处理'),('sort-failed','最近活动','异常')]:
 screen(sid,'会话排序',order+' · '+filter,lambda c,order=order,filter=filter:sort(c,order,filter),'单选保留在弹层，完成后返回侧栏')
def approval(c):
 c.header('待处理审批','等待确认配置变更','tasks')
 c.notice(118,'等待你的操作',['确认任务、权限范围与实际影响。'],'warning','shield')
 c.rect(16,218,c.w-32,186,16,'panel','border');c.text(30,252,'任务请求本轮权限',17,weight=600)
 c.lines(30,285,['仅对当前任务轮次生效。','拒绝不会新增权限。','完整请求可在当前任务查看。'],13,'muted',24)
 c.btn(432,'返回当前任务','home');c.btn(488,'返回任务列表','tasks','secondary')
screen('approval','多任务','审批定位',approval,'复用现有审批卡；此示意不模拟实际授权')
def stop(c):
 tasks(c);c.hot.clear();c.rect(0,0,c.w,H,0,'#111A22',opacity=.42);c.rect(18,306,c.w-36,250,24,'panel')
 c.text(38,346,'停止这项任务？',19,weight=700);c.lines(38,384,['只停止「检查 Android 界面」的当前执行。','其他会话继续运行，已收到内容保留。'],13,'muted',23)
 c.btn(450,'停止任务','tasks-waiting','danger',38,c.w-76);c.btn(504,'取消','tasks','secondary',38,c.w-76)
screen('stop-confirm','多任务','停止确认',stop,'停止按会话/回合执行，独立于其他任务')
def doc(c,state='read'):
 c.header('设计说明.md','只读预览 · 定位第 3 行','home',('search','doc-search','全文检索'))
 c.rect(16,118,c.w-32,48,13,'recessed');c.text(30,148,'复制全文',14,weight=600);c.text(c.w-28,148,'全文检索  ›',14,weight=600,anchor='end');c.target(c.w-145,118,129,48,'doc-search','全文检索')
 c.text(20,213,'设计说明',25,weight=700)
 c.lines(20,252,['任务工作台以当前会话为中心。','工具结果随任务归档。'],15,'parchment',26)
 c.rect(16,315,c.w-32,50,10,'recessed');c.rect(16,315,3,50,1,'coral');c.text(28,345,'3  ·  保留真实回执与状态',14,weight=600)
 c.text(20,409,'实施要求',18,weight=700);c.lines(20,443,['1. 使用统一主题与交互组件。','2. 错误时明确原因和恢复操作。','3. 先确认回执，再更新完成状态。'],14,'muted',28)
 if state=='search':
  c.rect(16,116,c.w-32,62,13,'panel','controlBorder');c.icon('search',26,137,size=20);c.text(55,151,'回执',15,weight=600);c.text(c.w-114,151,'1 / 2',12,'muted');c.icon('close',c.w-44,137,size=20)
  c.rect(16,315,c.w-32,50,10,'recessed','warning');c.text(28,345,'3  ·  保留真实回执与状态',14,'warning',600)
  c.btn(746,'完成搜索','doc','secondary')
 c.row(680,'文件读取说明','文件仅在本机读取，不回传给模型','lock',None)
screen('doc','文件预览','文档全屏阅读',doc,'回到任务保留阅读位置；文件仅本机读取')
screen('doc-search','文件预览','全文搜索定位',lambda c:doc(c,'search'),'匹配项、结果计数、关闭后保留定位')
def preview(c,zoom=False,error=False,index=1):
 c.header('图片 '+str(index)+' / 2','本机附件 · 全屏预览','image-result')
 c.rect(16,120,c.w-32,48,13,'recessed');c.text(32,150,'‹ 上一张',13,'muted' if index==1 else 'parchment');c.text(c.w-30,150,'下一张 ›',13,'muted' if index==2 else 'parchment',600,anchor='end');c.target(16,120,120,48,'preview' if index==2 else None,'上一张');c.target(c.w-136,120,120,48,'preview-next' if index==1 else None,'下一张')
 if error:c.notice(300,'图片读取失败',['附件可能已被移动或损坏。'],'red','warning');c.btn(746,'重新读取','preview')
 else:
  # editable abstract vector specimen of a local image, intentionally illustrative
  x=16;y=184;w=c.w-32;h=440
  c.rect(x,y,w,h,16,'recessed','border')
  c.rect(x+12,y+12,w-24,h-24,12,'#E0D6CC' if index==2 else '#DFE3E2')
  clip='zoom-specimen' if zoom else 'preview-specimen-'+str(index)
  c.add(f'<defs><clipPath id="{clip}"><rect x="{x+12}" y="{y+12}" width="{w-24}" height="{h-24}" rx="12"/></clipPath></defs><g clip-path="url(#{clip})"><g'+(f' transform="translate({-c.w/4} {-y/2-80}) scale(1.5)"' if zoom else '')+'>')
  c.add(f'<path d="M{x+12} {y+330} Q{x+w*.3} {y+110} {x+w*.56} {y+240} T{x+w-12} {y+260} V{y+h-12} H{x+12}Z" fill="#849C94"/>')
  c.add(f'<path d="M{x+12} {y+370} Q{x+w*.34} {y+260} {x+w*.55} {y+350} T{x+w-12} {y+316} V{y+h-12} H{x+12}Z" fill="#344D4B"/>')
  c.circle(x+w*.72,y+112,34,'#E8D1AC' if index==1 else '#C58A70');c.add('</g></g>')
  c.text(c.w/2,653,'双指缩放 · 拖动查看 · 双击复位',12,'muted',anchor='middle')
  c.btn(680,'恢复原始大小','preview','secondary')
  c.btn(746,'缩小','preview','secondary',16,104);c.text(c.w/2,775,'150%' if zoom else '100%',15,weight=600,anchor='middle');c.btn(746,'放大','preview-zoom','secondary',c.w-120,104)
screen('preview','文件预览','图片全屏查看',preview,'缩放、拖动、翻页与按钮辅助')
screen('preview-zoom','文件预览','图片缩放状态',lambda c:preview(c,True),'220ms 缩放；减少动效直接更新')
screen('preview-next','文件预览','下一张图片',lambda c:preview(c,index=2),'换图后复位；不改变原始文件')
screen('preview-error','文件预览','读取失败',lambda c:preview(c,error=True),'明确重试，保留来源任务')
def image_result(c,detail=False):
 c.header('生成图片','当前任务 · 图片产物','home',('more','image-meta','查看执行详情'))
 c.text(20,142,'图片已生成',20,weight=700)
 c.rect(16,165,c.w-32,345,16,'recessed','border')
 # 12dp checker motif only inside art, conveying transparency; actual image replaces sample
 for yy in range(177,493,24):
  for xx in range(28,c.w-28,24):
   if ((xx-28)//24+(yy-177)//24)%2==0:c.rect(xx,yy,min(12,c.w-28-xx),12,0,'border')
 c.circle(c.w/2,333,112,'#B9D6CA');c.circle(c.w/2+26,310,76,'#749D91');c.circle(c.w/2-25,360,54,'#E0C0A2')
 c.icon('expand',c.w-48,475,'parchment',22);c.target(16,165,c.w-32,345,'preview','打开图片')
 c.text(28,536,'透明背景 · 生成信息 2 项（示例）',13,'muted')
 c.row(556,'执行详情','查看原始生成信息','info',target='image-meta')
 c.btn(746,'打开原图','preview','secondary')
 if detail:
  c.hot.clear();c.rect(0,0,c.w,H,0,'#111A22',opacity=.42);c.rect(12,444,c.w-24,390,24,'panel');c.rect(c.w/2-18,454,36,4,2,'border')
  c.text(28,492,'图片生成信息',20,weight=700);c.icon('close',c.w-48,470);c.target(c.w-60,458,48,48,'image-result','关闭')
  c.row(515,'背景类型','透明背景 · 原生字段明确返回','image',None)
  c.row(583,'生成信息','2 项 · 原样保留在执行记录','file',None)
  c.notice(660,'未知字段按原始记录呈现',['不猜测尺寸、模型、种子或生成耗时。'],'muted','info')
  c.btn(766,'查看原图','preview','secondary',26,c.w-52)
screen('image-result','图片生成','图片产物卡片',image_result,'元数据由真实字段驱动，减少正文噪声')
screen('image-meta','图片生成','生成元数据详情',lambda c:image_result(c,True),'透明状态和元数据按实际回执呈现')

# Dark and width verification artboards reuse exactly the same layout logic with live token modes.
for sid,module,title,fn in [
 ('dark-model','适配','深色 · 模型选择',lambda c:model(c)),
 ('dark-tasks','适配','深色 · 任务侧栏',lambda c:tasks(c)),
 ('dark-runtime','适配','深色 · 内核恢复',lambda c:runtime(c)),
 ('dark-doc','适配','深色 · 文档阅读',lambda c:doc(c)),
 ('dark-provider','适配','深色 · 服务商',lambda c:providers(c)),
 ('dark-mcp','适配','深色 · MCP 诊断',lambda c:mcp_diag(c)),
 ('dark-voice','适配','深色 · 默认声音',lambda c:voice(c)),
 ('dark-hook','适配','深色 · 执行检查',lambda c:hook_run(c,'blocked')),
 ('dark-sort','适配','深色 · 会话筛选',lambda c:sort(c)),
 ('dark-image','适配','深色 · 图片产物',lambda c:image_result(c)),
]:screen(sid,module,title,fn,'同一组 Ink 暗色令牌',mode='dark')
for width in (360,411):
 for suffix,fn in [('model',lambda c:model(c)),('tasks',lambda c:tasks(c)),('doc',lambda c:doc(c))]:
  screen(f'w{width}-{suffix}','适配',f'{width}dp · '+{'model':'模型选择','tasks':'任务抽屉','doc':'文档阅读'}[suffix],fn,'原生手机宽度布局抽样',width=width)

# 200% typography exemplars are separate responsive compositions, never a scaled bitmap.
def large_font(c,state):
 c.header('无障碍示例','系统字号 200%','home')
 if state=='model':
  c.text(20,147,'选择运行模型',30,weight=700);c.lines(20,193,['当前服务商','ChatGPT'],24,'muted',34)
  c.rect(16,265,c.w-32,120,18,'recessed','border');c.icon('warning',30,281,'warning',28)
  c.lines(30,333,['目录读取失败','保留原模型选择'],24,'parchment',31,600)
  c.text(20,431,'已保存的选择',24,'muted');c.lines(20,474,['遵循默认模型'],28,'parchment',34,600)
  c.rect(16,718,c.w-32,88,18,'parchment');c.text(c.w/2,772,'重新读取',28,'canvas',600,'middle');c.target(16,718,c.w-32,88,'model-loading','重新读取')
 elif state=='tasks':
  c.text(20,147,'进行中的任务',30,weight=700)
  for i,(title,sub,tone) in enumerate([('检查界面','执行中','coral'),('配置变更','待审批','warning'),('整理接口','待回答','warning')]):
   y=184+i*144;c.rect(16,y,c.w-32,128,18,'panel','border');c.text(32,y+42,title,28,weight=600);c.text(32,y+78,sub,24,tone);c.icon('chevron',c.w-48,y+51,tone);c.target(16,y,c.w-32,128,'approval' if tone=='warning' else 'home','定位任务')
  c.rect(16,718,c.w-32,88,18,'parchment');c.text(c.w/2,772,'排序与筛选',28,'canvas',600,'middle');c.target(16,718,c.w-32,88,'sort','排序与筛选')
 else:
  c.text(20,150,'正在恢复连接',30,weight=700)
  c.rect(16,188,c.w-32,240,20,'recessed','border');c.lines(30,238,['等待旧内核退出','历史与草稿保留','未完成指令','不会自动重发'],24,'parchment',43)
  c.lines(20,480,['恢复状态由真实','内核事件驱动。'],26,'muted',37)
  c.rect(16,718,c.w-32,88,18,'panel','controlBorder');c.text(c.w/2,772,'查看诊断',28,'parchment',600,'middle');c.target(16,718,c.w-32,88,'runtime-failed','查看诊断')
for sid,st,title in [('large-model','model','200% · 模型恢复'),('large-tasks','tasks','200% · 任务列表'),('large-runtime','runtime','200% · 运行状态')]:
 screen(sid,'适配',title,lambda c,st=st:large_font(c,st),'示例：内容可滚动、关键操作自然增高',width=360)
# Additional combinations are interactive-only, so the state machine stays complete without
# cluttering the design canvas with mechanically repeated layouts.
for order in ORDER_IDS:
 for filt in FILTER_IDS:
  sid=sort_id(order,filt)
  if sid not in SCREENS:
   screen(sid,'会话排序',order+' · '+filt,lambda c,o=order,f=filt:sort(c,o,f),'排序和筛选分别保存')
   SCREENS[sid]['prototypeOnly']=True
  tid=tasks_id(order,filt)
  if tid not in SCREENS:
   screen(tid,'多任务',order+' · '+filt,lambda c,o=order,f=filt:tasks(c,FILTER_KEYS[f],o),'选定筛选、排序和会话状态保持')
   SCREENS[tid]['prototypeOnly']=True
# Artboards: a clean frame per screen, with editable vector/text layers and labels outside UI.
def board(module,items,filename):
 cols=min(3,len(items)); cellw=445;cellh=934
 w=64+cols*cellw;h=148+((len(items)+cols-1)//cols)*cellh
 c=Canvas('light',w,h);c.rect(0,0,w,h,0,'recessed')
 c.text(32,47,'111  /  PR99  ·  '+module,30,weight=700)
 c.text(32,80,'手机竖屏设计 · UI V8 主题 · 设计示例（非实际运行回执）',14,'muted')
 c.line(32,105,w-32,105)
 for i,s in enumerate(items):
  x=32+(i%cols)*cellw;y=136+(i//cols)*cellh
  c.text(x,y+20,f'{i+1:02d}  {s["title"]}',18,weight=700)
  c.text(x,y+44,s['desc'],11,'muted')
  bx=x+(393-s['width'])/2;by=y+60
  c.rect(bx-1,by-1,s['width']+2,H+2,20,'panel','border')
  c.add(f'<g id="{s["id"]}" transform="translate({bx} {by})">{s["svg"]}</g>')
  c.text(x,y+924,f'{s["width"]} × {s["height"]} dp  ·  '+('深色' if s['mode']=='dark' else '浅色'),11,'muted')
 (OUT/filename).write_text(svg_header(w,h,module)+c.result()+'</svg>')
modules=OrderedDict((k,[v for v in SCREENS.values() if v['module']==k and not v.get('prototypeOnly')]) for k in dict.fromkeys(v['module'] for v in SCREENS.values()))
filemap={'入口':'01-workbench','模型':'02-model-catalog','国内模型':'03-domestic-provider','声音':'04-account-voice','MCP':'05-mcp-diagnostics','Hook':'06-hook-states','内核恢复':'07-runtime-recovery','多任务':'08-multitask','会话排序':'09-session-sort','文件预览':'10-file-preview','图片生成':'11-image-generation','适配':'12-adaptation'}
for old in ('02-model-catalog.svg','08-multitask.svg','09-session-sort.svg'):
 (OUT/old).unlink(missing_ok=True)
for mod,items in modules.items():
 # 4 artboards per adaptation file for manageable Figma import.
 limit=6 if mod in ('适配','国内模型','Hook','模型','多任务','会话排序','文件预览') else 9
 for part,start in enumerate(range(0,len(items),limit),1):
  suffix=f'-{part}' if len(items)>limit else ''
  board(mod,items[start:start+limit],filemap[mod]+suffix+'.svg')

# Component state specimens: reusable display patterns, not duplicated screen layouts.
def component_board():
 c=Canvas('light',1468,1240);c.rect(0,0,1468,1240,0,'recessed')
 c.text(32,49,'111  /  PR99  ·  状态与组件体系',30,weight=700)
 c.text(32,82,'复用 Ink · 48dp 点击区 · 统一业务状态 · 全部组件对应真实数据来源',14,'muted')
 groups=[
  ('目录状态','CodexChoiceSheet / CodexFloatingMenu',[('已读取','可选模型 · 当前服务商','jade','check'),('正在读取','保留已选，等待目录','coral','refresh'),('无可用模型','检查账号或服务商','muted','info'),('读取失败','重新读取目录','red','warning')]),
  ('服务商授权 / 验证','DomesticCatalogSheet / CapabilityFeedback',[('待授权','候选模型，尚未准入','warning','lock'),('验证中','等待第二轮真实回传','coral','refresh'),('验证通过','身份与工具回传已确认','jade','check'),('验证失败','查看原因并重试','red','warning')]),
  ('会话任务状态','SidebarSessionRow / ThreadActivity',[('执行中','允许停止当前任务','coral','stop'),('待审批','定位至对应审批','warning','shield'),('待回答','进入任务补充回答','warning','chevron'),('异常','进入任务查看实际错误','red','warning')]),
  ('MCP 启动回执','McpStartupReceipt / McpServiceStatusCard',[('已就绪','启动耗时 240 毫秒','jade','check'),('正在启动','耗时待返回','coral','refresh'),('启动失败','需要重新授权','red','warning'),('已取消','本次启动结束','muted','stop')]),
  ('检查执行状态','HookRunPresentation / TimelineItem',[('检查中','显示正在执行的事件','coral','refresh'),('检查完成','显示实际耗时','jade','check'),('失败 / 阻断','原因与是否阻断分开','red','warning'),('已停止','收到停止回执','muted','stop')]),
  ('内核恢复状态','RuntimeStatusPresentation / EngineStatusSheet',[('等待恢复','本次等待间隔由核心提供','warning','clock'),('回收旧内核','等待旧进程实际退出','coral','refresh'),('正在初始化','启动与协议握手','coral','refresh'),('恢复完成','连接回执有效后继续','jade','check')]),
  ('全屏预览状态','AttachmentPreviewDialog / ModelDocumentPane',[('读取中','真实本机读取','coral','refresh'),('只读文件','可复制、全文检索','muted','file'),('图片缩放','缩放比例由真实手势决定','jade','image'),('读取失败','保留任务与重试入口','red','warning')]),
  ('账号与产物回执','AccountVoiceSettings / ImageGenerationPresentation',[('当前声音','当前账号实际选项','jade','wave'),('同步声音','保存并回读后确认','coral','refresh'),('透明背景','字段明确为 true','jade','image'),('生成信息','未知字段按原始证据展开','muted','info')]),
 ]
 for i,(title,code,states) in enumerate(groups):
  x=32+(i%4)*354;y=130+(i//4)*512
  c.rect(x,y,342,482,20,'panel','border');c.text(x+18,y+35,title,18,weight=700)
  # Kotlin class names are handoff metadata, allow second line
  bits=code.split(' / ');c.lines(x+18,y+60,bits,10,'muted',15)
  for j,(label,sub,tone,icon) in enumerate(states):
   yy=y+103+j*83;c.rect(x+16,yy,310,70,13,'recessed','border')
   c.icon(icon,x+31,yy+17,tone,24);c.text(x+68,yy+29,label,15,tone,600);c.text(x+68,yy+51,sub,12,'muted')
  c.text(x+18,y+462,'状态文本 + 图标 · 颜色为辅助',11,'muted')
 (OUT/'13-component-states.svg').write_text(svg_header(c.w,c.h,'新增功能组件状态')+c.result()+'</svg>')
component_board()

FLOWS=[
 ('模型目录',['打开模型菜单','读取当前服务商','目录可选 / 无可用项','选择模型 → 后续任务'],['读取失败 → 重试，保留原选择','旧会话服务商固定；新会话可单独选择'],['model','model-loading','model','model-selected']),
 ('国内模型',['预置 / 自定义服务商','核对凭据并确认保存','读取目录 / 固定能力验证','回执通过 → 准入'],['密钥安全写入；全局占用门禁','验证失败 → 看原因、重新验证'],['provider-list','provider-form','provider-confirm','provider-verified']),
 ('账号声音',['账号设置','读取当前账号声音','选择并写入','回读一致 → 成功'],['账号/连接切换时丢弃旧回执','实时语音入口仅跳回账号设置'],['voice','voice','voice-saving','voice-saved']),
 ('MCP 服务',['可用目录','已就绪服务详情','查看连接诊断','返回任务处理授权'],['启动失败、取消、未授权仅诊断可见','固定启动目录属核心行为，不增加独立页面'],['mcp-list','mcp-detail','mcp-diagnostic','home']),
 ('Hook 检查',['已有用户检查策略','选择并确认保存','任务时间线收到通知','完成 / 失败 / 阻断 / 停止'],['异步不允许阻断；结果严格取自内核','失败与阻断分别表达，不按策略猜测结果'],['hook-settings','hook-confirm','hook-run','hook-blocked']),
 ('内核恢复',['等待自动恢复','回收旧内核','初始化与协议握手','真实连接成功'],['异常 → 查看诊断 → 可重试','草稿和历史保留，不重发未完成指令'],['runtime-waiting','runtime-retiring','runtime-starting','runtime-connected']),
 ('会话排序',['打开侧栏','打开排序筛选','选择排序及状态','完成并回到列表'],['筛选保留查询与滚动位置','排序依据真实创建、更新或最近活动时间'],['tasks','sort','sort-waiting','tasks-waiting']),
 ('多任务',['抽屉进行中','识别待审批与待回答','定位原任务','停止当前任务（确认）'],['保留输入与会话状态','停止按回合执行，其他会话继续'],['tasks','tasks-waiting','approval','stop-confirm']),
 ('文件与图片',['任务建议预览','本机全屏读取','全文检索 / 图片缩放','关闭恢复原任务'],['读取失败 → 重新读取；定位无效提示','文件内容不经预览工具回传模型'],['home','doc','doc-search','preview-zoom']),
 ('图片产物',['时间线图片卡片','显示已返回元数据','展开执行详情','查看原图'],['透明字段缺失则不显示；未知元数据只读','文件丢失 → 提示、保留原始执行记录'],['image-result','image-result','image-meta','preview']),
]
def interaction_board():
 c=Canvas('light',1468,117+len(FLOWS)*148+32);c.rect(0,0,c.w,c.h,0,'recessed')
 c.text(32,48,'111  /  PR99  ·  手机交互图谱',30,weight=700)
 c.text(32,82,'一次动作对应真实状态；所有切换与失败都保留返回路径。',14,'muted')
 for i,(title,steps,notes,_) in enumerate(FLOWS):
  x=32;y=117+i*148;c.rect(x,y,c.w-64,134,16,'panel','border')
  c.text(x+18,y+30,title,16,weight=700)
  for j,step in enumerate(steps):
   xx=x+18+j*342;yy=y+43;c.rect(xx,yy,310,42,12,'recessed','border')
   c.text(xx+155,yy+26,step,13,'parchment',600,'middle')
   if j<3:c.icon('chevron',xx+316,yy+9,'muted',18)
  c.text(x+18,y+108,notes[0],12,'muted');c.text(x+704,y+108,notes[1],12,'muted')
 (OUT/'14-interaction-atlas.svg').write_text(svg_header(c.w,c.h,'新增功能手机交互图谱')+c.result()+'</svg>')
interaction_board()

# Prototype contains the same vector geometry; simulated server events are OUTSIDE the phone.
EVENTS={
 'model-loading':[('目录读取成功','model'),('目录返回空列表','model-empty'),('目录读取失败','model-error')],
 'provider-saving':[('写入并重新连接成功','provider-catalog'),('保存未成功','provider-form')],
 'provider-running':[('身份与双轮回传验证成功','provider-verified'),('真实验证失败','provider-failed')],
 'voice-saving':[('写入并回读一致','voice-saved'),('同步失败，保留旧声音','voice')],
 'hook-run':[('收到完成事件','hook-completed'),('收到失败事件','hook-failed'),('收到阻断事件','hook-blocked'),('收到停止事件','hook-stopped')],
 'runtime-waiting':[('旧进程开始退出','runtime-retiring'),('自动恢复失败','runtime-failed')],
 'runtime-retiring':[('旧进程已退出','runtime-starting'),('旧进程退出异常','runtime-failed')],
 'runtime-starting':[('握手成功','runtime-connected'),('握手失败','runtime-failed')],
}
manifest={'baseline':'b6134d7eef99fe49b85689d8d46fc8d112cc9edc','tokensVersion':T['version'],'screens':[{k:v for k,v in s.items() if k!='svg'} for s in SCREENS.values()],'flows':[{'module':f[0],'steps':f[1],'notes':f[2],'screens':f[3]} for f in FLOWS],'events':EVENTS}
(OUT/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
# Build a compact prototype data map and accessible hotspot layer in an editable HTML file.
payload=json.dumps({'screens':SCREENS,'events':EVENTS},ensure_ascii=False,separators=(',',':')).replace('</','<\\/')
page='''<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>111 · PR99 手机交互设计</title>
<style>
:root{font-family:"Noto Sans CJK SC","Microsoft YaHei",system-ui,sans-serif;color:#263139;background:#f6f7f8;color-scheme:light}*{box-sizing:border-box}body{margin:0}header{padding:24px 26px;border-bottom:1px solid #d8dee1;background:white}h1{font-size:22px;margin:0 0 8px}p{margin:8px 0;color:#56646d;line-height:1.6;font-size:14px}.layout{display:grid;grid-template-columns:minmax(225px,300px) minmax(0,1fr) minmax(225px,300px);gap:24px;max-width:1310px;margin:0 auto;padding:24px}.pane{background:white;border:1px solid #d8dee1;border-radius:20px;padding:16px;align-self:start}.center{display:flex;flex-direction:column;align-items:center;min-width:0}.phone{width:min(100%,393px);position:relative;border-radius:20px;overflow:hidden;box-shadow:0 18px 55px #26313920;border:1px solid #d8dee1;background:white;transition:width .24s ease}.phone svg{width:100%;height:auto;display:block}.hot{fill:transparent;stroke:transparent;cursor:pointer;outline:none}.hot:focus-visible{stroke:#c8483e;stroke-width:2;fill:#c8483e12}.hot:active{fill:#2631390c}.controls{display:flex;flex-direction:column;gap:6px}h2{font-size:16px;margin:0 0 12px}h3{font-size:12px;color:#56646d;margin:15px 0 6px}button{font:inherit;font-size:13px;border:1px solid #d8dee1;border-radius:12px;min-height:48px;padding:10px 12px;color:#263139;background:white;text-align:left;cursor:pointer}button:hover{background:#f6f7f8}button.active{background:#263139;color:white;border-color:#263139}button:focus-visible{outline:2px solid #c8483e;outline-offset:2px}button:active{transform:scale(.985);transition:transform .1s}.bar{display:flex;gap:8px;width:100%;margin-bottom:12px;max-width:490px}.bar button{flex:1}.caption{max-width:393px;min-height:65px;text-align:center}.events{display:grid;gap:8px}.in .motion-menu{transform-box:fill-box;transform-origin:bottom right;animation:menu .2s cubic-bezier(.2,.7,.2,1)}.page-in{animation:enter .28s cubic-bezier(.2,.7,.2,1)}.sheet-in{animation:enter .32s cubic-bezier(.2,.7,.2,1)}.success-in{animation:success .45s ease-out}.zoom-in{animation:enter .22s ease-out}@keyframes enter{from{opacity:.82;transform:translateY(5px)}to{opacity:1;transform:none}}@keyframes menu{from{opacity:0;transform:translateY(3px) scale(.96)}to{opacity:1;transform:none}}@keyframes success{from{opacity:.75}to{opacity:1}}@media(prefers-reduced-motion:reduce){*,*::before,*::after{animation:none!important;transition:none!important}}body.reduce *,body.reduce *::before,body.reduce *::after{animation:none!important;transition:none!important}@media(max-width:900px){.layout{display:flex;flex-direction:column;gap:14px;padding:12px}.center{order:-1}.pane{width:100%}.controls{display:grid;grid-template-columns:repeat(auto-fit,minmax(145px,1fr))}header{padding:18px}.phone{max-width:100%}}
</style></head><body><header><h1>111 / PR99 · 手机功能增量设计</h1><p>点击手机上的按钮查看交互；右侧仅用于设计评审模拟内核或账号回执。示例数据不对应真实账号或实际运行结果。</p></header>
<main class="layout"><nav class="pane"><h2>页面与状态</h2><div class="controls" id="nav"></div></nav><section class="center"><div class="bar"><button id="back">返回上一状态</button><button id="motion" aria-pressed="false">减弱动效：关闭</button></div><div id="phone" class="phone"></div><div id="caption" class="caption" aria-live="polite"></div></section><aside class="pane"><h2>状态事件</h2><p>以下控制仅存在于原型评审台，不属于 Android 页面。</p><div class="events" id="events"></div><h3>动效规则</h3><p>按压 100ms · 释放 140ms · 选择 180ms · 菜单 200ms · 展开 240ms · 页面 280ms · 图片缩放 220ms · Sheet 320ms。系统减弱动效时立即切换。</p></aside></main>
<script id="data" type="application/json">'''+payload+'''</script><script>
const {screens,events}=JSON.parse(document.querySelector('#data').textContent), nav=document.querySelector('#nav'), phone=document.querySelector('#phone'), ev=document.querySelector('#events'), cap=document.querySelector('#caption');let current='home',history=[];
function button(label,fn,cls=''){let b=document.createElement('button');b.textContent=label;b.className=cls;b.addEventListener('click',fn);return b}
function show(id,push=true){if(!screens[id])return;if(push&&id!==current)history.push(current);current=id;let s=screens[id];phone.style.width=s.width+'px';phone.innerHTML='<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 '+s.width+' '+s.height+'" aria-label="'+s.title+'">'+s.svg+'</svg>';
let svg=phone.firstChild;for(let h of s.hotspots){let r=document.createElementNS('http://www.w3.org/2000/svg','rect');for(let k of ['x','y','w','h'])r.setAttribute(k==='w'?'width':k==='h'?'height':k,h[k]);r.setAttribute('class','hot');r.setAttribute('role','button');r.setAttribute('tabindex','0');r.setAttribute('aria-label',h.label);r.addEventListener('click',()=>show(h.target));r.addEventListener('keydown',e=>{if(e.key==='Enter'||e.key===' '){e.preventDefault();show(h.target)}});svg.append(r)}
phone.classList.remove('in','page-in','sheet-in','zoom-in','success-in');void phone.offsetWidth;let anim=id.startsWith('model')?'in':id.startsWith('sort')||id==='image-meta'?'sheet-in':id==='preview-zoom'?'zoom-in':id==='voice-saved'||id==='provider-verified'||id==='runtime-connected'?'success-in':'page-in';phone.classList.add(anim);cap.textContent=s.title+' · '+s.desc+' · '+s.width+'dp';ev.replaceChildren();let list=events[id]||[];if(!list.length){let p=document.createElement('p');p.textContent='当前画面无待接收的模拟事件。使用手机中的操作或左侧状态目录。';ev.append(p)}else for(let [t,n] of list)ev.append(button(t,()=>show(n)));document.querySelectorAll('#nav button').forEach(b=>{b.classList.toggle('active',b.dataset.id===id);b.setAttribute('aria-current',b.dataset.id===id?'page':'false')});}
let prev='';for(let s of Object.values(screens)){if(s.prototypeOnly)continue;if(s.module!==prev){let h=document.createElement('h3');h.textContent=s.module;nav.append(h);prev=s.module}let b=button(s.title,()=>show(s.id));b.dataset.id=s.id;nav.append(b)}
document.querySelector('#back').onclick=()=>{if(history.length)show(history.pop(),false)};document.querySelector('#motion').onclick=e=>{let v=document.body.classList.toggle('reduce');e.target.textContent='减弱动效：'+(v?'开启':'关闭');e.target.setAttribute('aria-pressed',String(v))};show('home',false)
</script></body></html>'''
(OUT/'prototype.html').write_text(page)

# Deterministic structural and semantic verification. Every clickable control must have a legal destination and fit its physical screen.
for s in SCREENS.values():
 for h in s['hotspots']:
  assert h['target'] in SCREENS,(s['id'],h)
  assert h['x']>=0 and h['y']>=0 and h['x']+h['w']<=s['width'] and h['y']+h['h']<=s['height'],(s['id'],h)
  assert h['w']>=48 and h['h']>=48,(s['id'],h)
for f in FLOWS:
 assert all(k in SCREENS for k in f[3])
for sid,v in EVENTS.items():
 assert sid in SCREENS and all(k in SCREENS for _,k in v)
assert len([x for x in modules if x not in ('适配','入口')])==10
print(f'{len(SCREENS)} screens · {sum(len(x["hotspots"]) for x in SCREENS.values())} hotspots · {len(FLOWS)} flows · {sum(len(x) for x in EVENTS.values())} scenario events')
