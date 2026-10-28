#!/usr/bin/env bash
# 111 开发工具链：沿用 777 的 plan/bootstrap/check/status/run 及按需 Artifact 机制。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=/dev/null
source "$SCRIPT_DIR/toolchain-versions.env"
# shellcheck source=/dev/null
source "$SCRIPT_DIR/toolchain-components.env"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
REPO_ROOT="${DEV111_REPO_ROOT:-$REPO_ROOT}"
TOOLS_ROOT="${DEV111_TOOLS_ROOT:-$HOME/.local/share/111-dev}"
ENV_FILE="${DEV111_TOOLCHAIN_ENV_FILE:-$HOME/.config/111/dev-toolchain.env}"
STATE_DIR="${DEV111_STATE_DIR:-$REPO_ROOT/.111/toolchain}"
ARTIFACTS_DIR="${DEV111_ARTIFACTS_DIR:-}"
COMMAND="${1:-help}"
if [ "$#" -gt 0 ]; then shift; fi
PROFILE=build

usage() {
  cat <<'EOF'
111 开发工具链（Linux x64；使用 777 的按需 Artifact 工作方式）
  bash tools/dev/ai-toolchain.sh plan [--profile build|full]
  bash tools/dev/ai-toolchain.sh bootstrap --artifacts-dir PATH [--profile build|full]
  bash tools/dev/ai-toolchain.sh check [--profile build|full]
  bash tools/dev/ai-toolchain.sh status
  bash tools/dev/ai-toolchain.sh run -- npm test
  bash tools/dev/ai-toolchain.sh verify-artifact COMPONENT DIRECTORY
  bash tools/dev/ai-toolchain.sh self-test
build：Node 22.16.0 + npm 10.9.2 + 离线 npm 依赖（TypeScript/ws）
full：追加 actionlint 1.7.12
plan 不联网；bootstrap 仅接受 GitHub Actions 产出的已校验 Artifact；
run 仅使用已就绪环境，不进行网络安装。无 Android/Gradle/JDK 工具。
EOF
}
die() { echo "[111-toolchain] $*" >&2; exit 1; }
while [ "$#" -gt 0 ]; do
  case "$1" in
    --profile) PROFILE="$2"; shift 2 ;;
    --artifacts-dir) ARTIFACTS_DIR="$2"; shift 2 ;;
    --) shift; break ;;
    *) break ;;
  esac
done
case "$PROFILE" in build|full) ;; *) die "未知档位 $PROFILE" ;; esac

host_check() {
  [ "$(uname -s)" = Linux ] && [ "$(uname -m)" = x86_64 ] ||
    die "111 仅支持 Linux x64（WSL2 可用）"
}
selected_node() {
  if [ -x "$TOOLS_ROOT/node/bin/node" ] &&
     [ "$("$TOOLS_ROOT/node/bin/node" --version)" = "v$NODE_VERSION" ]; then
    printf '%s\n' "$TOOLS_ROOT/node/bin/node"
  elif command -v node >/dev/null 2>&1 &&
       [ "$(node --version)" = "v$NODE_VERSION" ]; then
    command -v node
  else
    return 1
  fi
}
run_npm() {
  if [ -f "$TOOLS_ROOT/node/lib/node_modules/npm/bin/npm-cli.js" ] &&
     [ -x "$TOOLS_ROOT/node/bin/node" ]; then
    "$TOOLS_ROOT/node/bin/node" "$TOOLS_ROOT/node/lib/node_modules/npm/bin/npm-cli.js" "$@"
  else
    npm "$@"
  fi
}
node_ready() {
  selected_node >/dev/null || return 1
  [ "$(run_npm --version 2>/dev/null)" = "$NPM_VERSION" ]
}
deps_ready() {
  node_ready || return 1
  [ -f "$REPO_ROOT/node_modules/typescript/package.json" ] || return 1
  [ -f "$REPO_ROOT/node_modules/ws/package.json" ] || return 1
  "$(selected_node)" -e '
    const fs=require("node:fs"), p=require("node:path");
    const [root,ts,ws]=process.argv.slice(1);
    const lock=JSON.parse(fs.readFileSync(p.join(root,"package-lock.json"),"utf8"));
    if(lock.packages["node_modules/typescript"].version!==ts ||
       lock.packages["node_modules/ws"].version!==ws ||
       JSON.parse(fs.readFileSync(p.join(root,"node_modules/typescript/package.json"),"utf8")).version!==ts ||
       JSON.parse(fs.readFileSync(p.join(root,"node_modules/ws/package.json"),"utf8")).version!==ws) process.exit(1);
  ' "$REPO_ROOT" "$TYPESCRIPT_VERSION" "$WS_VERSION" >/dev/null 2>&1
}
component_ready() {
  case "$1" in
    node) node_ready ;;
    npm-cache) deps_ready ;;
    actionlint)
      if [ -x "$TOOLS_ROOT/bin/actionlint" ]; then
        "$TOOLS_ROOT/bin/actionlint" -version 2>/dev/null | grep -Fq "$ACTIONLINT_VERSION"
      else
        command -v actionlint >/dev/null 2>&1 &&
          actionlint -version 2>/dev/null | grep -Fq "$ACTIONLINT_VERSION"
      fi
      ;;
    *) die "未知组件：$1" ;;
  esac
}
required_components() {
  printf '%s\n' $BUILD_COMPONENTS
  if [ "$PROFILE" = full ]; then printf '%s\n' $FULL_EXTRA_COMPONENTS; fi
}
missing_components() {
  local component
  while read -r component; do
    component_ready "$component" || printf '%s\n' "$component"
  done < <(required_components)
}
emit_plan() {
  local first=1 component
  printf '{"schema":1,"profile":"%s","missing_artifacts":[' "$PROFILE"
  while read -r component; do
    [ "$first" -eq 1 ] || printf ','
    printf '"111-toolchain-%s-latest"' "$component"
    first=0
  done < <(missing_components)
  printf ']}\n'
}
lock_hash() { sha256sum "$REPO_ROOT/package-lock.json" | awk '{print $1}'; }
verify_artifact() {
  local component="$1" dir="$2" top
  [ -f "$dir/component.env" ] && [ -f "$dir/SHA256SUMS" ] &&
    [ -f "$dir/payload.tar.gz" ] || die "组件文件不完整：$dir"
  grep -Fxq 'ARTIFACT_SCHEMA=1' "$dir/component.env" || die "产物格式不匹配"
  grep -Fxq "ARTIFACT_COMPONENT=$component" "$dir/component.env" || die "组件身份不匹配"
  grep -Fxq 'ARTIFACT_ARCH=x64' "$dir/component.env" || die "架构不匹配"
  grep -Fxq "ARTIFACT_NODE_VERSION=$NODE_VERSION" "$dir/component.env" || die "Node 版本不匹配"
  if [ "$component" = npm-cache ]; then
    grep -Fxq "ARTIFACT_LOCK_SHA256=$(lock_hash)" "$dir/component.env" ||
      die "npm 锁文件哈希不匹配，必须重建依赖产物"
  fi
  (cd "$dir" && sha256sum -c SHA256SUMS) || die "Artifact 校验和失败"
  case "$component" in
    node) top=node ;;
    npm-cache) top=npm-cache ;;
    actionlint) top=bin ;;
    *) die "未知组件：$component" ;;
  esac
  tar -tzf "$dir/payload.tar.gz" | awk -v top="$top" '
    BEGIN { found=0; bad=0 }
    {
      path=$0
      if (path == top || path == top "/" || index(path,top "/")==1) found=1
      else bad=1
      if (path ~ /^\// || path ~ /(^|\/)\.\.(\/|$)/) bad=1
    }
    END { if (!found || bad) exit 1 }
  ' || die "Artifact 归档路径非法"
}
install_component() {
  local component="$1" dir="$ARTIFACTS_DIR/111-toolchain-$1-latest" tmp
  [ -d "$dir" ] || die "缺少组件 Artifact：$dir"
  verify_artifact "$component" "$dir"
  mkdir -p "$TOOLS_ROOT"
  tmp="$(mktemp -d "$TOOLS_ROOT/.staging.XXXXXXXX")"
  tar -xzf "$dir/payload.tar.gz" -C "$tmp" || die "Artifact 解压失败：$component"
  case "$component" in
    node)
      [ -x "$tmp/node/bin/node" ] && [ -f "$tmp/node/lib/node_modules/npm/bin/npm-cli.js" ] ||
        die "Node/npm 载荷缺失"
      [ "$("$tmp/node/bin/node" --version)" = "v$NODE_VERSION" ] || die "Node 版本错误"
      [ "$("$tmp/node/bin/node" "$tmp/node/lib/node_modules/npm/bin/npm-cli.js" --version)" = "$NPM_VERSION" ] ||
        die "npm 版本错误"
      rm -rf "$TOOLS_ROOT/node"
      mv "$tmp/node" "$TOOLS_ROOT/node"
      ;;
    npm-cache)
      [ -d "$tmp/npm-cache/_cacache" ] || die "npm 离线缓存载荷缺失"
      rm -rf "$TOOLS_ROOT/npm-cache"
      mv "$tmp/npm-cache" "$TOOLS_ROOT/npm-cache"
      export PATH="$TOOLS_ROOT/node/bin:$PATH"
      (cd "$REPO_ROOT" && run_npm ci --offline --ignore-scripts --no-audit --no-fund --cache "$TOOLS_ROOT/npm-cache") ||
        die "npm ci --offline 失败"
      deps_ready || die "npm 离线依赖版本验收失败"
      ;;
    actionlint)
      [ -x "$tmp/bin/actionlint" ] || die "actionlint 载荷缺失"
      "$tmp/bin/actionlint" -version 2>/dev/null | grep -Fq "$ACTIONLINT_VERSION" ||
        die "actionlint 版本错误"
      mkdir -p "$TOOLS_ROOT/bin"
      mv "$tmp/bin/actionlint" "$TOOLS_ROOT/bin/actionlint"
      ;;
  esac
  rm -rf "$tmp"
  echo "[111-toolchain] 已安装：$component" >&2
}
write_env() {
  mkdir -p "$(dirname "$ENV_FILE")"
  printf 'export PATH="%s/node/bin:%s/bin:$PATH"\n' "$TOOLS_ROOT" "$TOOLS_ROOT" > "$ENV_FILE"
}
write_status() {
  local status="$1" reason="$2" code="$3"
  mkdir -p "$STATE_DIR"
  printf '{"schema":1,"status":"%s","reason":"%s","exit_code":%s,"profile":"%s","repo_root":"%s"}\n' \
    "$status" "$reason" "$code" "$PROFILE" "$REPO_ROOT" > "$STATE_DIR/status.json"
  cat "$STATE_DIR/status.json"
}
check_ready() {
  [ -z "$(missing_components)" ]
}
bootstrap() {
  local component
  host_check
  if ! check_ready; then
    [ -d "$ARTIFACTS_DIR" ] || {
      emit_plan >&2
      die "请先下载 plan 列出的 Artifact，再传入 --artifacts-dir"
    }
    while read -r component; do
      if ! component_ready "$component"; then install_component "$component"; fi
    done < <(required_components)
  fi
  check_ready || die "安装后复查失败"
  write_env
  write_status ready environment_ready 0
}
self_test() {
  host_check
  [ -f "$REPO_ROOT/package.json" ] && [ -f "$REPO_ROOT/package-lock.json" ] ||
    die "缺少 111 仓库 npm 基线"
  node -e '
    const fs=require("node:fs"),p=require("node:path"),assert=require("node:assert/strict");
    const [root,ts,ws]=process.argv.slice(1);
    const pkg=JSON.parse(fs.readFileSync(p.join(root,"package.json"),"utf8"));
    const lock=JSON.parse(fs.readFileSync(p.join(root,"package-lock.json"),"utf8"));
    assert.equal(pkg.devDependencies.typescript,ts);
    assert.equal(pkg.dependencies.ws,ws);
    assert.equal(lock.packages["node_modules/typescript"].version,ts);
    assert.equal(lock.packages["node_modules/ws"].version,ws);
    assert.equal(pkg.os[0],"linux"); assert.equal(pkg.cpu[0],"x64");
  ' "$REPO_ROOT" "$TYPESCRIPT_VERSION" "$WS_VERSION"
  echo "[111-toolchain] 组件清单与 package-lock.json 基线一致"
}

case "$COMMAND" in
  plan) host_check; emit_plan ;;
  bootstrap) bootstrap ;;
  check)
    host_check
    if check_ready; then write_status ready environment_ready 0
    else emit_plan >&2; write_status needs_bootstrap missing_dependencies 1; exit 1; fi
    ;;
  status)
    if [ -f "$STATE_DIR/status.json" ]; then cat "$STATE_DIR/status.json"
    else printf '{"schema":1,"status":"unknown","reason":"not_checked"}\n'; fi
    ;;
  env) printf '%s\n' "$ENV_FILE" ;;
  run)
    host_check
    [ "$#" -gt 0 ] || die "run -- 后需要命令"
    if ! check_ready; then
      if [ -d "$ARTIFACTS_DIR" ]; then
        bootstrap >/dev/null
      else
        emit_plan >&2
        die "工具链尚未就绪；请先下载 plan 所列 Artifact 或执行 bootstrap"
      fi
    fi
    export PATH="$TOOLS_ROOT/node/bin:$TOOLS_ROOT/bin:$PATH"
    cd "$REPO_ROOT"
    exec "$@"
    ;;
  verify-artifact)
    [ "$#" -eq 2 ] || die "用法 verify-artifact COMPONENT DIRECTORY"
    host_check; verify_artifact "$1" "$2"; echo "[111-toolchain] Artifact 校验通过：$1"
    ;;
  self-test) self_test ;;
  help|-h|--help) usage ;;
  *) usage >&2; die "未知命令：$COMMAND" ;;
esac
