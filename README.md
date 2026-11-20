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

将官方源码与平台适配分离；编译和运行问题直接在 Android 适配层及构建流程处理。**只有设备端通过启动与初始化测试的 APK 才可视为可运行候选版。**

## GitHub Actions 工作流

| 工作流 | 目的 | 触发 |
|---|---|---|
| `一次性导入官方 Codex 源码` | 手动引入最新官方源码快照，记录提交版本 | 手动 |
| `编译 Android 原生 Codex 核心` | 锁定 Rust / NDK，分别编译两个 ABI 的 Bionic ELF | 修改原生构建配置、手动 |
| `安卓版本构建与验证` | 从最近成功的原生核心构建取回双架构产物，制作 APK 并执行 Android 模拟器测试 | UI 更新、原生核心成功、手动 |
| `安卓界面与协议独立验收` | Kotlin 编译、审批协议单元测试、Android Lint | UI 更新、手动 |

原生核心编译尚未成功时，APK 流水线会明确停止，不再改用已确认存在 Android 兼容性问题的 Linux 静态包。

## 安装与验证

1. 进入 [Actions](https://github.com/sy220284/111/actions)，查看 `编译 Android 原生 Codex 核心` 是否成功。
2. 再查看 `安卓版本构建与验证` 的 `build` 和 `android-emulator` 是否均为成功。
3. 两项均成功后，下载其上传的 `codex-android-native-apks` 调试包，安装到 Android。
4. 从侧栏“诊断与日志”检查本机 Codex 进程与协议；登录账号后试运行会话、命令与文件修改。
5. APK 下载物为 GitHub Actions 暂存产物，并非已签名的正式应用发布版。

## 功能

侧栏按工作台、开发工具、Codex 生态和设置分组，支持搜索。包含流式对话、会话历史、任务模式、文件导入/编辑、Diff、终端、代码审查、模型、技能、插件、应用、MCP、Hooks、执行环境、账号登录、服务商、配置、安全、审批、使用量、限额、外观和诊断等页面。支持小屏抽屉和大屏常驻侧栏，采用“石墨·暖铜”视觉系统。

## 关键原则

- 使用官方 `app-server` 接口及真实事件；功能真实可用以服务器返回和设备验证为准。
- 高风险命令、文件更改、额外权限按官方协议单独审批。
- 不建设自有云端数据存储或 AI 服务；所有默认项目文件只在应用私有目录。
- 官方源码保留并可追溯版本，升级时逐一比对协议变化。

请查看 [架构](docs/ARCHITECTURE.md)、[视觉设计](docs/DESIGN_SYSTEM.md)、[接口契约](docs/PROTOCOL_CONTRACTS.md)、[验收矩阵](docs/ACCEPTANCE.md)。

上游 Codex 授权见 `upstream/codex/LICENSE` 与相关 NOTICE。
