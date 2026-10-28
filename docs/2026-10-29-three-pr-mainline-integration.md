# 111 主线三批恢复成果联合审计（2026-10-29）

本分支以 main@70c1256c7f80612ba1f67d65dd7ca51d8a2e0b0c 为可信起点，将 PR #63、#65、#66 的新增实现不改写地落入同一代码树，并额外新增跨模块真实调用链测试，保留 main 后续的 Daemon/CI 变更。

**范围**：Linux 有界子进程/剪贴板/ImageMagick、C N-API 三扩展及显式加载器、终端 CellSegmenter/bidi/wide-cell、各自真实测试以及联合测试。

**配置合并**：package.json 的已有 npm test 全部保留，额外注册 check:linux-portable、check:native-linux、check:terminal-cells、check:combined-recovery。避免直接按顺序合并 3 条 PR 时覆盖同一条 test 脚本。

**跨模块链**：runBoundedProcess 真实 Node 子进程输出 → LinuxClipboard 回退 → CellSegmenter Unicode 单元绘制；LazyImagePipeline 惰性操作顺序与幂等；N-API 显式加载器与原版二进制路径隔离。

**未接管默认业务入口**：bin/claude-310.mjs 仍进入 core/cli.js；联合用例不证明三批模块已被主 CLI 生产调用。真实硬件、Wayland、线上模型与原始原生 ABI 全等价仍不成立。

**测试**：npm run check:linux-portable && npm run check:native-linux && npm run check:terminal-cells && npm run check:combined-recovery；CI 同时保留现有 npm test、专用 Linux N-API 流程和 CLI 模型流流程。

此次为主线兼容整合候选。现有 PR #63/#65/#66 独立验收通过不能取代联合验收结果。
