'use strict';
/**
 * 111 的恢复交付门禁。
 * 自动检查 PR 的源码/测试差异与交付声明；不把静态检查冒充运行时语义等价。
 * 使用方式：GitHub pull_request 工作流检出 PR head（完整历史）后执行。
 */
const fs = require('node:fs');
const {execFileSync} = require('node:child_process');
const ts = require('typescript');

const CATEGORIES = new Set(['功能恢复', '缺陷修复', '独立测试', '工具维护', '文档维护', '治理配置']);
const FUNCTIONAL = new Set(['功能恢复', '缺陷修复']);
const FIELDS = ['业务问题', '实际代码改动', '调用链', '验证命令', '验证结果', '验收级别', '未验收范围'];
const PLACEHOLDER = /^(?:待填写|待补充|待测试|TODO|TBD|未执行|无内容|占位|\.{2,}|[-—])$/i;

function field(body, name) {
  const lines = String(body || '').split(/\r?\n/);
  const matches = lines
    .map(line => line.match(new RegExp('^\\s*(?:[-*]\\s*)?' + name + '[：:]\\s*(.*)$')))
    .filter(Boolean);
  if (matches.length !== 1) return '';
  return matches[0][1].trim();
}
function businessPath(path) {
  return /^(?:src|runtime|bin)\//.test(path) || /^resources\/(?:bunfs|data)\//.test(path);
}
function runnableSource(path) {
  return /\.(?:cjs|mjs|js|jsx|ts|tsx)$/.test(path);
}
function testPath(path) {
  return /^tests\/.*\.(?:test|spec)\.(?:cjs|mjs|js|ts)$/.test(path) ||
    /\.(?:test|spec)\.(?:cjs|mjs|js|ts)$/.test(path);
}
function lex(source, identifiersOnly = false) {
  const scanner = ts.createScanner(ts.ScriptTarget.Latest, true, ts.LanguageVariant.Standard, source);
  const tokens = [];
  for (let kind = scanner.scan(); kind !== ts.SyntaxKind.EndOfFileToken; kind = scanner.scan()) {
    const value = identifiersOnly && kind === ts.SyntaxKind.Identifier ? '标识符' : scanner.getTokenText();
    tokens.push(kind + ':' + value);
  }
  return tokens.join('\u0000');
}
function classifyCodeChange(change) {
  if (!runnableSource(change.path)) {
    return change.before !== change.after ? 'effective' : 'none';
  }
  if (change.before === null || change.after === null) return 'effective';
  const previous = lex(change.before);
  const current = lex(change.after);
  if (previous === current) return 'cosmetic';
  if (lex(change.before, true) === lex(change.after, true)) return 'identifier_only';
  return 'effective';
}
function auditDelivery({title = '', body = '', changed = [], sourceChanges = []}) {
  const errors = [];
  const category = field(body, '交付类别');
  if (!CATEGORIES.has(category)) errors.push('必须以“交付类别：功能恢复/缺陷修复/独立测试/工具维护/文档维护/治理配置”声明唯一类别');
  const implementation = changed.filter(businessPath);
  const tests = changed.filter(testPath);
  const isFunction = FUNCTIONAL.has(category);
  if (isFunction) {
    if (!implementation.length) errors.push('功能恢复/缺陷修复缺少实际业务源码或运行资源变更，不能用台账、报告、工具或测试替代');
    if (!tests.length) errors.push('功能恢复/缺陷修复缺少对应测试文件的实际变更');
    for (const name of FIELDS) {
      const answer = field(body, name);
      if (!answer || PLACEHOLDER.test(answer)) errors.push('功能恢复/缺陷修复必须填写有效字段：' + name);
    }
    if (field(body, '验收级别') && !['局部行为已验证', '业务链已验证', '部分完成'].includes(field(body, '验收级别'))) {
      errors.push('验收级别只能为：局部行为已验证、业务链已验证、部分完成');
    }
    if (implementation.length) {
      const inspected = sourceChanges.filter(item => implementation.includes(item.path));
      if (inspected.length !== implementation.length) errors.push('有业务代码未完成实际差异分析，禁止跳过');
      if (inspected.length && inspected.every(item => classifyCodeChange(item) !== 'effective')) {
        errors.push('业务代码只有注释/格式/标识符改名差异，没有可识别的实际行为变更；不能记为功能恢复');
      }
      if (inspected.some(item => classifyCodeChange(item) === 'identifier_only') && !inspected.some(item => classifyCodeChange(item) === 'effective')) {
        errors.push('只改短符号或标识符名称不能证明恢复了业务语义');
      }
    }
  } else if (CATEGORIES.has(category)) {
    if (!body.includes('不计入功能恢复')) errors.push('非恢复类 PR 必须明确声明“不计入功能恢复”');
    if (implementation.length) errors.push('实际修改业务源码的 PR 必须按功能恢复/缺陷修复类提交，并补齐真实行为测试');
    if (/(?:完成|实现|修复|恢复).*(?:完整业务|全部功能|真实业务恢复|功能等价)/.test(title)) {
      errors.push('非恢复类 PR 标题不得宣称已完成业务恢复或功能等价');
    }
  }
  return {ok: errors.length === 0, category, implementation, tests, errors};
}
function runGit(args) {
  return execFileSync('git', args, {encoding:'utf8', maxBuffer:64 * 1024 * 1024});
}
function gitAt(commit, path) {
  try { return runGit(['show', commit + ':' + path]); }
  catch { return null; } // 新增文件在 merge-base 不存在
}
function main() {
  if (!process.env.GITHUB_EVENT_PATH) throw Error('只能在带有 pull_request 事件的环境运行；单元测试请直接调用 auditDelivery');
  const event = JSON.parse(fs.readFileSync(process.env.GITHUB_EVENT_PATH, 'utf8'));
  if (!event.pull_request) throw Error('恢复交付门禁必须收到 pull_request 事件');
  const base = event.pull_request.base.sha;
  const head = event.pull_request.head.sha;
  const ancestor = runGit(['merge-base', base, head]).trim();
  const changed = runGit(['diff', '--name-only', '-z', ancestor, head])
    .split('\u0000').filter(Boolean);
  const sourceChanges = changed.filter(businessPath).map(path => {
    const before = gitAt(ancestor, path);
    const after = fs.existsSync(path) ? fs.readFileSync(path, 'utf8') : null;
    return {path, before, after};
  });
  const report = auditDelivery({
    title: event.pull_request.title,
    body: event.pull_request.body,
    changed,
    sourceChanges
  });
  const lines = [
    '## 111 源码恢复交付门禁',
    '- 类别：' + (report.category || '未声明'),
    '- 变更文件：' + changed.length,
    '- 运行时代码/资源：' + report.implementation.length,
    '- 测试文件：' + report.tests.length,
    '- 结论：' + (report.ok ? '基础交付约束通过（仍需真实测试及人工审查）' : '拒绝：交付约束不满足'),
    ...report.errors.map(e => '- 违规：' + e),
    '',
    '注意：本门禁不会自动证明函数语义、消费者有效性或完整端到端行为；这些必须由真实测试和代码审查确认。'
  ];
  if (process.env.GITHUB_STEP_SUMMARY) fs.appendFileSync(process.env.GITHUB_STEP_SUMMARY, lines.join('\n') + '\n');
  console.log(lines.join('\n'));
  if (!report.ok) process.exitCode = 1;
}
if (require.main === module) main();
module.exports = {field, businessPath, testPath, classifyCodeChange, auditDelivery};
