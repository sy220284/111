# 111 开发工具链

此目录复用 777 的工具链交付方式：版本与组件清单、`plan` 缺失识别、`bootstrap` 按需安装、`check/status` 真实状态、`run` 执行、GitHub Actions Artifact 离线交付和复验。

## 与 777 的区别

111 是 **Linux x64 / Node.js 22.16.0** 源码恢复工程，不使用 777 的 Android SDK、Gradle、JDK、模拟器与 Kotlin 编译器。111 的生产依赖为 `ws@8.22.0`，测试/AST 索引依赖为 `typescript@5.9.3`；Node 内置 npm 固定为 10.9.2。完整档追加 `actionlint@1.7.12` 校验 GitHub Actions 配置。

核心组件是 `111-toolchain-node-latest` 和 `111-toolchain-npm-cache-latest`；完整档额外使用 `111-toolchain-actionlint-latest`。每个组件有 `component.env`、`payload.tar.gz`、`SHA256SUMS`。npm 缓存产物额外绑定 `package-lock.json` 的 SHA256。

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

本地 `plan/check/run` 均不联网。只有工作流打包阶段会从可信源取得 Node 发行版、npm 包和带固定 SHA256 的 actionlint。解压后执行 `npm ci --offline`，而非使用宿主网络安装。工具链复用已满足版本要求的本机工具与已安装依赖，不重复下载。

`111` 的 Bun/JSC 私有运行时 API、原生组件、真实在线服务等，不会因为 Node/npm 工具齐备自动获得完整兼容。工作流对 `--help` 单独报告真实结果，不能替代端到端功能验收。

工作流不含定时触发器；只对工具链或依赖清单变更自动执行，或手动执行。原有源码恢复及命名验收工作流继续独立运作。
