#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

usage() {
  echo '用法：bash scripts/install.sh [--offline]' >&2
  echo '联网模式使用 npm ci；--offline 复用 111 工具链及已下载的 GitHub Actions Artifact。' >&2
}

if [ "$#" -gt 1 ]; then usage; exit 2; fi
case "${1:-}" in
  '') MODE=online ;;
  --offline) MODE=offline ;;
  *) usage; exit 2 ;;
esac

check_host() {
  node -e '
    const [major,minor]=process.versions.node.split(".").map(Number);
    if(process.platform!=="linux" || process.arch!=="x64" ||
       major<22 || (major===22 && minor<16)) {
      console.error("需要 Linux x64、Node.js >= 22.16.0");
      process.exit(1);
    }
  '
}

run_command() {
  if [ "$MODE" = offline ]; then
    bash tools/dev/ai-toolchain.sh run -- "$@"
  else
    "$@"
  fi
}

if [ "$MODE" = online ]; then
  check_host
  echo '使用 package-lock.json 安装固定版本依赖…'
  npm ci --ignore-scripts --no-audit --no-fund
else
  echo '通过 111 Artifact 工具链离线安装并复用固定版本依赖…'
  run_command node -e '
    const [major,minor]=process.versions.node.split(".").map(Number);
    if(process.platform!=="linux" || process.arch!=="x64" ||
       major<22 || (major===22 && minor<16)) process.exit(1);
  '
fi

echo '执行源码、内嵌资源、原生组件和函数证据完整验收…'
run_command npm test

echo '验证真实主程序版本及帮助入口…'
run_command node bin/claude-310.mjs --version
run_command node bin/claude-310.mjs --help >/dev/null

echo '基础安装及 CLI 验收通过。完整代理与在线服务仍需独立业务验收。'
