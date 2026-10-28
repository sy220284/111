# 111 开发工具链

此目录复用 777 的工具链交付方式：版本与组件清单、`plan` 缺失识别、`bootstrap` 按需安装、`check/status` 真实状态、`run` 执行、GitHub Actions Artifact 离线交付和复验。

## 与 777 的区别

111 是 **Linux x64 / Node.js 22.16.0** 源码恢复工程，不使用 777 的 Android SDK、Gradle、JDK、模拟器与 Kotlin 编译器。111 的生产依赖为 `ws@8.22.0`，测试/AST 索引依赖为 `typescript@5.9.3`；Node 内置 npm 固定为 10.9.2。完整档追加 `actionlint@1.7.12` 校验 GitHub Actions 配置。

核心组件是 `111-toolchain-node-latest` 和 `111-toolchain-npm-cache-latest`；完整档额外使用 `111-toolchain-actionlint-latest`。每个组件有 `component.env`、`payload.tar.gz`、`SHA256SUMS`。npm 缓存产物额外绑定 `package-lock.json` 的 SHA256。

## 实际安装入口

联网环境可直接使用 `bash scripts/install.sh`，按 `package-lock.json` 安装依赖后自动执行全部 `npm test`、CLI `--version` 和 `--help`。Node.js 必须满足 Linux x64、22.16.0 或更新。

已经从 GitHub Actions 下载组件时，可使用：

```bash
export DEV111_ARTIFACTS_DIR=/path/to/artifacts
bash scripts/install.sh --offline
```

离线模式复用 `tools/dev/ai-toolchain.sh run` 完成按需安装，不请求 npm registry；缺组件会明确失败并列出待下载产物。CI 还会主动删除 `node_modules` 再次离线安装，以验证安装路径可重复恢复。CLI `--help` 或 `--version` 失败会让安装失败，不能再由 CI 忽略。

## 使用方式

```bash
bash tools/dev/ai-toolchain.sh self-test
bash tools/dev/ai-toolchain.sh plan --profile full
# 在 GitHub Actions「111 开发工具链」中下载本次 run 的组件，
# 各 ZIP 按相同名称解压到 /path/to/artifacts/<Artifact名称>/
bash tools/dev/ai-toolchain.sh bootstrap --profile full --artifacts-dir /path/to/artifacts
bash tools/dev/ai-toolchain.sh check --profile full
bash tools/dev/ai-toolchain.sh run -- npm test
bash tools/dev/ai-toolchain.sh status
```

本地 `plan/check/run` 均不联网。配置 `DEV111_ARTIFACTS_DIR` 后，`run` 会在需要时自动使用已下载的 Artifact 补齐依赖。只有工作流打包阶段会从可信源取得 Node 发行版、npm 包和带固定 SHA256 的 actionlint。解压后执行 `npm ci --offline`，而非使用宿主网络安装。工具链复用已满足版本要求的本机工具与已安装依赖，不重复下载。

`111` 的 Bun/JSC 私有运行时 API、原生组件、真实在线服务等，不会因为 Node/npm 工具齐备自动获得完整兼容。工作流对 `--help` 单独报告真实结果，不能替代端到端功能验收。

工作流不含定时触发器；只对工具链或依赖清单变更自动执行，或手动执行。原有源码恢复及命名验收工作流继续独立运作。


## Bun/JSC 底层兼容诊断工具

工具分两层，以免将静态引用、Bun 参照测试和 Node 兼容性混为一谈：

1. 源码调用点审计：使用 TypeScript AST 遍历 111 的 2,551 个主源码模块（不包含 vendor / README），定位直接 Bun.spawn、Bun.spawnSync、Bun.Terminal、Bun.SQL、Bun.ant.*、bun:sqlite、bun:jsc 等引用，支持字面量方括号及 globalThis.Bun。每项输出实际文件、行号和出现次数。
2. Bun/JSC 参照运行时探针：在真实 Bun 中隔离执行 spawn/spawnSync 进程退出与 stdout、Terminal PTY 输出、SQL 内存数据库参数绑定、bun:sqlite、bun:jsc 堆信息。私有 Bun.ant 只记录未验证状态，不做敏感调用。

运行命令：

~~~bash
npm run audit:bun-jsc
npm run check:bun-jsc
node tools/dev/bun-jsc-compat.cjs report --json --bun /path/to/bun --require-bun --output /tmp/111-bun-jsc-compat.json
~~~

仅 report 命令调用真实 Bun 二进制；不存在时明确报告 unavailable，不会伪造通过；--require-bun 让 Bun 探针失败或缺失时返回非零。GitHub Actions 额外使用固定 Bun 1.4.4，生成可下载的诊断 JSON；Bun 仅供参照验证，不会自动替换 111 默认 Node 主程序，也不进入现有离线运行组件清单。

验收边界：扫描到 Bun.spawn 不表示该分支必然运行；没有扫描到 Bun.SQL 也不能断言原版 ELF 永不使用它。参照 Bun 探针通过只代表测试的 API 在该版本下可用，111 在 Node 中的行为兼容仍需另行实现并验证。诊断工具不会往全局作用域注入伪造的 Bun 对象。
