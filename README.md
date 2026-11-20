# Codex Android｜原生移动开发工作台

目标：将官方 Codex 运行核心与 Kotlin / Jetpack Compose 安卓界面结合，在手机上完整呈现任务对话、开发工具、技能、插件、MCP、账号与权限设置。

## 项目结构

| 目录 | 作用 |
|---|---|
| `upstream/codex/` | OpenAI Codex 官方源码快照（保持原貌） |
| `CODEX_UPSTREAM_COMMIT` | 对应上游提交、日期与来源 |
| `android-app/` | Compose 原生 Android APP，28 个侧栏入口及功能页面 |
| `docs/DESIGN_SYSTEM.md` | 审美、材质、布局、动效、交互规范 |
| `docs/PROTOCOL_CONTRACTS.md` | Codex App Server JSON-RPC 页面契约 |
| `docs/ACCEPTANCE.md` | 依据真实构建和设备日志核验的进度 |

**平台最低 Android 10（API 29）**，优先 ARM64，自动化测试额外使用 x86_64 模拟器。用户项目、配置与会话数据留在应用私有目录；外部 AI 模型、插件和用户明确选择的远端执行环境仍可能产生网络通信。

## Android 核心兼容性

曾经尝试在 APK 内直接打包官方 `unknown-linux-musl` 发布文件。GitHub Actions 可编译 APK，但 Android 35 模拟器实测：

- 官方 Linux ARM64/x86_64 静态 ELF 与 Android 运行限制不兼容；
- x86_64 核心执行 `--version` 退出码为 159（SIGSYS）；
- `initialize` 无法成功握手。

当前采用 **Android NDK / Bionic 原生构建**：直接从包内锁定的 Codex Rust 源码分别编译 `aarch64-linux-android` 与 `x86_64-linux-android`。Rust 版本固定为 `upstream/codex/codex-rs/rust-toolchain.toml` 所要求的 1.99.1，NDK 为 27.2.12479018，API 29。

将官方源码与平台适配分离；编译和运行问题在 Android 适配层及构建流程处理。**x86_64 模拟器版已经完成原生启动、协议握手与命令执行测试；ARM64 手机版已完成编译和 APK 结构核验，仍需真机实测。**

## GitHub Actions 工作流

| 工作流 | 目的 | 触发 |
|---|---|---|
| `一次性导入官方 Codex 源码` | 手动引入最新官方源码快照，记录提交版本 | 手动 |
| `编译 Android 原生 Codex 核心` | 锁定 Rust / NDK，分别编译两个 ABI 的 Bionic ELF | 修改原生构建配置、手动 |
| `安卓版本构建与验证` | 从原生核心构建取回双架构产物，分别制作手机与模拟器 APK，并完成设备测试 | UI 更新、原生核心成功、手动 |
| `安卓界面与协议独立验收` | Kotlin 编译、审批协议单元测试、Android Lint | UI 更新、手动 |

Android NDK 的 ARM64 与 x86_64 内核已经通过编译。完整 APK 在 Android 35 x86_64 模拟器通过 `--version`、`initialize`、`account/read`、真实 Shell 命令执行以及侧边栏导航测试。构建遇到缺少原生核心时明确失败，不再使用 Linux 静态包。

## 安装与验证

1. 进入 [Actions](https://github.com/sy220284/111/actions)，查看 `编译 Android 原生 Codex 核心` 是否成功。
2. 再查看 `安卓版本构建与验证` 的 `build` 和 `android-emulator` 是否均为成功。
3. 两项均成功后，在工作流产物中下载 `codex-android-arm64-apk`（ARM64 手机）或 `codex-android-x86_64-apk`（x86_64 模拟器），解压 ZIP 后安装其中的 APK。两者已按 ABI 分离，无需下载其他架构的运行核心。
4. 从侧栏“诊断与日志”检查本机 Codex 进程与协议；登录账号后试运行会话、命令与文件修改。
5. 这些是 GitHub Actions 生成的**调试签名 APK**，可以用于验证与安装，但尚未配置独立正式发布签名。首次 Android 35 x86_64 设备验收的日志见 [成功记录](https://github.com/sy220284/111/actions/runs/44732591288)，分架构版的验证见 [对应记录](https://github.com/sy220284/111/actions/runs/44733688136)。

## 最新集成与下载

2026-11-20：四项技能驱动的工作台重设计已通过 [PR #5](https://github.com/sy220284/111/pull/5) 合并；[PR #8](https://github.com/sy220284/111/pull/8) 已整合官方会话请求、计划模式和 ChatGPT 设备码/浏览器登录。

- [Android 35 UI 验收](https://github.com/sy220284/111/actions/runs/44743790233)：**8 项设备测试通过**，包含账号首屏、侧栏导航、外观、协作模式、终端确认及执行依据弹层。
- [原生核心与 APK 验收](https://github.com/sy220284/111/actions/runs/44743790342)：**11 项设备测试通过**，涵盖 `initialize`、`account/read`、Shell 执行和离线会话创建/读取。
- 下载时选择最新成功构建下的 `codex-android-arm64-apk`（ARM64 手机）或 `codex-android-x86_64-apk`（x86_64 模拟器），解压 GitHub Actions ZIP 后安装 APK。
- 现有安装包使用调试签名，ARM64 物理机、真实 ChatGPT OAuth、云端模型长任务和正式发布签名尚未完成实机验收。

## 功能

侧栏按工作台、开发工具、Codex 生态和设置分组，支持搜索。包含流式对话、会话历史、任务模式、文件导入/编辑、Diff、终端、代码审查、模型、技能、插件、应用、MCP、Hooks、执行环境、账号登录（设备码和浏览器）、服务商、配置、安全、审批、使用量、限额、外观和诊断等页面。支持小屏抽屉和大屏常驻侧栏，采用“石墨·暖铜”视觉系统。

## 关键原则

- 使用官方 `app-server` 接口及真实事件；功能真实可用以服务器返回和设备验证为准。
- 高风险命令、文件更改、额外权限按官方协议单独审批。
- 不建设自有云端数据存储或 AI 服务；所有默认项目文件只在应用私有目录。
- 官方源码保留并可追溯版本，升级时逐一比对协议变化。

请查看 [架构](docs/ARCHITECTURE.md)、[视觉设计](docs/DESIGN_SYSTEM.md)、[接口契约](docs/PROTOCOL_CONTRACTS.md)、[验收矩阵](docs/ACCEPTANCE.md)。

上游 Codex 授权见 `upstream/codex/LICENSE` 与相关 NOTICE。
