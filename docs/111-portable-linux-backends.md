# 2.1.310 跨版本恢复项目：独立 Linux 运行时后端迁移

本目录存放前几轮本地恢复工程中已经能独立执行的高级语言实现。这里的实现是兼容后端，并非宣称与原始 Bun/JSC、Rust 内部源码等价。

## 迁移内容

- `runtime/linux/process-transport.mjs`：无 shell 执行、可配置子进程超时、stdout/stderr 输出限额，以及 Linux 进程组终止。
- `runtime/linux/clipboard-linux.mjs`：剪贴板读取按 wl-paste → xclip → xsel → 可选原生 N-API 回退；Wayland PRIMARY 写入包含代次保护。
- `runtime/linux/lazy-image-pipeline.mjs`：解码一次，惰性应用 resize/格式化操作序列。
- `runtime/linux/imagemagick-image-processor.mjs`：通过显式系统 ImageMagick 二进制执行 PNG/JPEG/WebP 编解码、缩放，并限定超时和输出内存。
- `tests/linux-portable-backends.test.mjs`：真实进程、伪服务、以及本地 ImageMagick 端到端测试。

## 使用及限制

`node --test tests/linux-portable-backends.test.mjs`

测试中关于 ImageMagick 的场景，如果系统不存在 `magick`，会被标记为 skip；本地测试环境安装有 ImageMagick，10 项均通过。

此提交不改变 111 的主入口或现有 `src/chunk-*.js`；`clipboard-linux.mjs` 不会在没有明确调用的情况下接管剪贴板。原生 N-API 回退需要调用者提供兼容的 `.node` 路径；未提供时仍可通过系统工具工作。执行外部二进制前由调用方选择可信命令和输入，子进程沙箱与权限管理不属于该模块的承诺。

## 来源说明

代码来自本次会话本地“跨语言进程与原生回退补全版”恢复工程的 TypeScript 实现，转为可直接在 111 Node 22 环境使用的 ESM。旧版 2.1.88 提供了接口/协议参考；新版 2.1.310 的现有剪贴板调用路径提供了行为约束。没有直接替换 111 已恢复的商业源代码。