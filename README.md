# Codex Android｜原生移动开发工作台

目标：将官方 Codex 运行核心与 Kotlin / Jetpack Compose 安卓界面结合，在手机上完整呈现任务对话、开发工具、技能、插件、MCP、账号与权限设置。

## 项目结构

| 目录 | 作用 |
|---|---|
| `upstream/codex/` | OpenAI Codex 官方源码快照（保持原貌） |
| `CODEX_UPSTREAM_COMMIT` | 对应上游提交、日期与来源 |
| `android-app/` | Compose 原生 Android APP，28 个侧栏入口及功能页面 |
| `docs/设计系统.md` | 审美、材质、布局、动效、交互规范 |
| `docs/协议与页面契约.md` | Codex App Server JSON-RPC 页面契约 |
| `docs/验收矩阵.md` | 依据真实构建和设备日志核验的进度 |

**平台最低 Android 10（API 29）**，优先 ARM64，自动化测试额外使用 x86_64 模拟器。用户项目、配置与会话数据留在应用私有目录；外部 AI 模型、插件和用户明确选择的远端执行环境仍可能产生网络通信。

## Android 核心兼容性

曾经尝试在 APK 内直接打包官方 `unknown-linux-musl` 发布文件。GitHub Actions 可编译 APK，但 Android 35 模拟器实测：

- 官方 Linux ARM64/x86_64 静态 ELF 与 Android 运行限制不兼容；
- x86_64 核心执行 `--version` 退出码为 159（SIGSYS）；
- `initialize` 无法成功握手。

当前采用 **Android NDK / Bionic 原生构建**：从锁定的 Codex Rust 源码编译 `aarch64-linux-android`，仅发布 ARM64 手机原生内核；x86_64 模拟器使用独立 UI 验收 APK。Rust 版本固定为 `upstream/codex/codex-rs/rust-toolchain.toml` 所要求的 1.99.1，NDK 为 27.2.12479018，API 29。

将官方源码与平台适配分离；编译和运行问题在 Android 适配层及构建流程处理。**CI 持续验证 ARM64 手机包的关键原生组件，并在 Android 35 模拟器验证 360、393、411dp 界面。真机原生交互仍需要设备实测。**

## GitHub Actions 工作流（Android 专用）

根目录保留 **7 条实际交付工作流**；上游快照中的全平台 Rust、桌面发布、Python SDK 和社区管理工作流不会作为 111 的 CI 直接执行。

| 工作流 | 触发条件 | 验收职责 |
|---|---|---|
| `手动同步官方 Codex 源码并创建审核 PR` | 仅手动，可指定上游 SHA | 生成中文 PR，升级经 Android 验收后合并 |
| `编译 Android 原生 Codex 核心` | Android Rust 源码、Cargo 配置、编译脚本更新；手动 | 编译并验证 ARM64 核心及依赖工具 |
| `安卓界面与协议独立验收` | Android UI、协议及 CI 校验规则更新；手动 | Kotlin 单测、协议、Lint、3 档模拟器和截图 |
| `安卓版本构建与验证` | 手机打包输入变更或原生核心完成；手动 | 自动排除同提交的重复构建，验证 ARM64 APK 与完整性 |
| `安卓原生上游升级预演` | MXC、Code Mode 的上游 Rust 构建输入更新；手动 | Android 平台升级兼容性 |
| `111 Android 正式发布` | 发布相关 PR、主分支两项验收完成；手动 | 同一提交通过两项验收后签名与发布 |
| `清理 111 旧正式版本` | 正式发布成功后由发布工作流直接调用；支持手动 | 保留最新三个正式版本及全部 Git 标签 |

原生核心缓存按 **Android Rust 源码树、父级 Cargo 配置、Android 适配脚本和编译配方** 比较。上游无关文档、其他平台 SDK 或历史 CI 文件的变化不会使已验证的 ARM64 核心失效。必要编译输入变更或旧产物过期时重新编译。

UI 单元测试由独立 UI CI 集中执行，APK 构建和签名阶段复用这项验收结论。 正式发布比较实际 APK 与原生编译输入，只修改 CI、测试和文档时不增加版本号。根目录工作流触发边界及发布条件由 `.github/scripts/test_ci_scope.py` 自动核查。 主分支同时涉及 Android 与原生修改时，APK 等待原生核心验收后只构建一次；发布门禁必须看到本提交实际成功的 APK 二次核验和三档模拟器测试。

## 安装与验证

1. 进入 [Actions](https://github.com/sy220284/111/actions)，查看 `编译 Android 原生 Codex 核心` 是否成功。
2. 再查看 `安卓版本构建与验证` 的 `build`、`verify-apk` 和独立 UI 验收的模拟器矩阵是否均为成功。
3. 两项均成功后，可从构建产物 `codex-android-arm64-apk` 下载 ARM64 手机调试 APK；正式版仅在 Releases 提供 ARM64 手机 APK。
4. 从侧栏“诊断与日志”检查本机 Codex 进程与协议；登录账号后试运行会话、命令与文件修改。
5. 以上 Actions 工作流产物是**调试签名 APK**。正式签名版经「111 Android 正式发布」工作流发布至 [Releases](https://github.com/sy220284/111/releases)，签名 Secret 配置详见 [发布说明](docs/正式发布流程.md)。首次 Android 35 x86_64 设备验收的日志见 [成功记录](https://github.com/sy220284/111/actions/runs/44732591288)，分架构版的验证见 [对应记录](https://github.com/sy220284/111/actions/runs/44733688136)。

## 最新集成与下载

2026-11-20：四项技能驱动的工作台重设计已通过 [PR #5](https://github.com/sy220284/111/pull/5) 合并；[PR #8](https://github.com/sy220284/111/pull/8) 已整合官方会话请求、计划模式和 ChatGPT 设备码/浏览器登录。

- [Android 35 UI 验收](https://github.com/sy220284/111/actions/runs/44743790233)：**8 项设备测试通过**，包含账号首屏、侧栏导航、外观、协作模式、终端确认及执行依据弹层。
- [原生核心与 APK 验收](https://github.com/sy220284/111/actions/runs/44743790342)：**11 项设备测试通过**，涵盖 `initialize`、`account/read`、Shell 执行和离线会话创建/读取。
- 手机调试请下载最新成功构建的 `codex-android-arm64-apk` 并解压安装；正式版从 Releases 获取单独的 ARM64 手机 APK。模拟器 APK 仅用于内部验收。
- 历史 Actions 构建使用调试签名；正式发布流程已加入，需按 [发布说明](docs/正式发布流程.md) 配置签名并通过实际 Release 构建。ARM64 物理机、真实 ChatGPT OAuth 和云端模型长任务仍需分别实机验收。

## 全部页面导航回归

[PR #10](https://github.com/sy220284/111/pull/10) 已加入 Android 35 窄屏 **28 个侧栏入口逐页验收**，每次代码更新都能检查路由及实际页面标题；[UI 工作流](https://github.com/sy220284/111/actions/runs/44746032933) 为 **9 项成功**，[含原生核心的设备工作流](https://github.com/sy220284/111/actions/runs/44746033005) 为 **12 项成功**。页面内涉及登录授权、云端推理、文件与网络权限的业务链路仍需分别实测。

## 手机屏宽回归配置

Android 35 UI 验收采用 **360、393、411dp** 三档并行模拟器测试，分别保存设备截图与测试日志；完整 Codex 原生核心使用 **393dp** 做设备回归。已取消 320dp 的持续集成检测档位，实际执行结果以 GitHub Actions 为准。

## 功能

侧栏按工作台、开发工具、Codex 生态和设置分组，支持搜索。包含流式对话、会话历史、任务模式、文件导入/编辑、Diff、终端、代码审查、模型、技能、插件、应用、MCP、Hooks、执行环境、账号登录（设备码和浏览器）、服务商、配置、安全、审批、使用量、限额、外观和诊断等页面。支持小屏抽屉和大屏常驻侧栏，采用“典雅白”视觉系统，页面、卡片与强调背景统一白色，以暖铜文字、图标和细边框表达层级。

## 关键原则

- 使用官方 `app-server` 接口及真实事件；功能真实可用以服务器返回和设备验证为准。
- 高风险命令、文件更改、额外权限按官方协议单独审批。
- 不建设自有云端数据存储或 AI 服务；所有默认项目文件只在应用私有目录。
- 官方源码保留并可追溯版本，升级时逐一比对协议变化。

请查看 [正式发布流程](docs/正式发布流程.md)、[架构](docs/系统架构.md)、[视觉设计](docs/设计系统.md)、[接口契约](docs/协议与页面契约.md)、[验收矩阵](docs/验收矩阵.md)。

上游 Codex 授权见 `upstream/codex/LICENSE` 与相关 NOTICE。
