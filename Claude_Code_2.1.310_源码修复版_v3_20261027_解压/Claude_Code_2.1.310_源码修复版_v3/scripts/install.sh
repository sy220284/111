#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
node -e 'if(Number(process.versions.node.split(".")[0])<22 || process.platform!=="linux" || process.arch!=="x64") {console.error("需要 Node.js >=22，平台 Linux x64");process.exit(1)}'
echo '安装固定版本依赖（需要访问 npm registry）…'
npm ci --ignore-scripts --no-audit --no-fund
echo '验证源码、内嵌资源和原生组件…'
npm test
echo '验证真实主程序 --help 入口…'
node bin/claude-310.mjs --help >/dev/null
echo '基础安装验收通过。完整代理和在线功能仍需进一步功能验收。'
