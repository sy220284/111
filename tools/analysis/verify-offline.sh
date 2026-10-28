#!/usr/bin/env bash
# Offline, no network or GitHub Actions runner required.
# This script tests the candidate/alias implementation; it does NOT assert
# Claude ELF or Bun/JSC end-to-end equivalence.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
if ! command -v node >/dev/null 2>&1; then
  echo '缺少 Node.js 22.16+：先安装111已发布的离线 Node 工具链。' >&2
  exit 3
fi
if ! command -v python3 >/dev/null 2>&1; then
  echo '缺少 Python 3：无法执行Jelly分级测试。' >&2
  exit 3
fi
if [ ! -f node_modules/typescript/package.json ]; then
  echo '缺少锁定的 TypeScript：先用111已有npm缓存执行 npm ci --offline。' >&2
  exit 3
fi
echo '== 环境 =='
node --version
python3 --version
echo '== 本地Jelly分类测试 =='
python3 -m unittest discover -s tests -p test_jelly_triage.py -v
echo '== AST调用图与Jelly隔离证据测试 =='
node --test tests/code-graph.test.cjs tests/jelly-runner.test.cjs tests/jelly-corpus.test.cjs
if [ "${1:-}" = "--full" ]; then
  echo '== 全仓静态调用图与完整源码回归 =='
  npm run check:code-graph
  npm test
elif [ -n "${1:-}" ]; then
  echo '仅支持 --full，或不带参数运行核心静态证据测试。' >&2
  exit 2
fi
echo '通过：离线静态分析测试。此结果不证明原版ELF等价或所有动态调用已确权。'
