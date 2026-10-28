'use strict';
const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');
const source=fs.readFileSync(path.resolve(__dirname,'../../src/chunk-bwt94x0y.js'),'utf8');
function api(){
 const start=source.indexOf('function hasPendingAdoptableWork(e,n=FX(e))'),end=source.indexOf('function k8t(e)',start);
 assert.ok(start>=0&&end>start);
 return vm.runInNewContext(source.slice(start,end)+';({Mno,Yxt,Dno,oIn,hasPendingAdoptableWork,countUnadoptedFrames,hasAdoptionPayloadTasks,summarizeAdoptedPayloadCounts})',{
  FX:()=>new Map(),qxt:row=>row.pending??0,xwe:row=>new Set(row.frames??[]),f6e:row=>({running:row.running??0})
 });
}
test('四个接管状态统计函数保留旧符号同一入口',()=>{
 const f=api();for(const [oldName,newName] of [['Mno','hasPendingAdoptableWork'],['Yxt','countUnadoptedFrames'],['Dno','hasAdoptionPayloadTasks'],['oIn','summarizeAdoptedPayloadCounts']])assert.equal(f[oldName],f[newName]);
});
test('存在未完成任务、活动 Frame 或运行中流程时保留待接管状态',()=>{
 const {Mno}=api();assert.equal(Mno({pending:0,frames:[],running:0}),false);
 assert.equal(Mno({pending:1}),true);assert.equal(Mno({frames:['live']}),true);assert.equal(Mno({running:1}),true);
});
test('Frame 统计仅计算未在接管集合中的标记',()=>{
 const {Yxt}=api();assert.equal(Yxt({frames:['a','b','c']},new Map([['a',true],['c',true]])),1);
 assert.equal(Yxt({frames:[]},new Map()),0);
});
test('接管载荷的任务判断不混入独立 Frame 计数',()=>{
 const {Dno}=api();assert.equal(Dno({shells:[],agents:[],workflows:[],cron:[],frameLive:['live']}),false);
 for(const kind of ['shells','agents','workflows','cron']){
  const record={shells:[],agents:[],workflows:[],cron:[]};record[kind]=['task'];assert.equal(Dno(record),true,kind);
 }
});
test('接管计数完整保留五类数量，并允许空载荷',()=>{
 const {oIn}=api();assert.deepEqual({...oIn(null)},{adopted_shells:0,adopted_agents:0,adopted_workflows:0,adopted_cron:0,adopted_frame_live:0});
 assert.deepEqual({...oIn({shells:[1],agents:[1,2],workflows:[],cron:[1,2,3],frameLive:[1]})},{adopted_shells:1,adopted_agents:2,adopted_workflows:0,adopted_cron:3,adopted_frame_live:1});
});
