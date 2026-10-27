'use strict';
// Precise static call-site evidence for the 2.1.310 main query generator.
// This reports topology only; it does not claim to execute the whole agent loop.
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const source = fs.readFileSync(path.resolve(__dirname,'../src/chunk-tqjdehqd.js'),'utf8');
const required = {
  keepBridge: 'function Da(e, o)',
  queryEntrypoint: 'async function* pN(e)',
  engineLoop: 'async function* Ou(',
  perIteration: 'async function* Du()',
  turnGeneratorCall: 'yield* Du()',
  terminalUserIndex: 'function findLastInteractiveUserIndex(e)',
  queuedPromptGate: 'function Pu(e)',
  queuedPromptRule: 'function Qf(e, o)'
};
const offsets = Object.fromEntries(Object.entries(required).map(([name,anchor]) => {
  const pos=source.indexOf(anchor);
  assert.ok(pos>=0, 'missing exact original function/call anchor: ' + name);
  assert.equal(source.indexOf(anchor,pos+anchor.length),-1,'ambiguous anchor: '+name);
  return [name,pos];
}));
assert.ok(offsets.keepBridge < offsets.queryEntrypoint);
assert.ok(offsets.queryEntrypoint < offsets.engineLoop);
assert.ok(offsets.engineLoop < offsets.perIteration);
assert.ok(offsets.perIteration < offsets.turnGeneratorCall);
const wrapper=source.slice(offsets.queryEntrypoint,offsets.engineLoop);
const loop=source.slice(offsets.engineLoop,offsets.turnGeneratorCall+100);
assert.ok(wrapper.includes('Da(e.toolUseContext, e.messages)'), 'query entry must install session append bridge');
assert.ok(wrapper.includes('s.through(Ou(e,'), 'query entry must stream the engine through the keep bridge');
assert.ok(loop.includes('resumeIncompleteThinking'), 'iteration must carry thinking continuation');
assert.ok(loop.includes('pendingToolUseSummary'), 'iteration must carry pending tools');
assert.ok(loop.includes('PostToolBatch'), 'iteration must handle post-batch lifecycle');
assert.ok(loop.includes('toolUseContext'), 'iteration must retain tool execution context');
const output = {
  version:'2.1.310',
  module:'src/chunk-tqjdehqd.js',
  evidence:'static-original-source-only',
  entrypoint:'pN',
  wrapper:'pN installs Da then drives Ou through Da.through',
  engine:'Ou owns nested Du; Du is yielded per iteration',
  scopes:{...offsets},
  provenLinks:[
    'pN -> Da (install session.append bridge)',
    'pN -> Ou (drive streamed query)',
    'Ou -> Du (per-iteration generator)',
    'Du -> PostToolBatch branch (lifecycle)',
    'Du -> resumeIncompleteThinking state'
  ],
  semanticOrEndToEndParity:false
};
console.log(JSON.stringify(output));
