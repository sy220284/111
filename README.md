# Codex Android｜原生移动开发工作台

本仓库把 [OpenAI Codex](https://github.com/openai/codex) 官方源码与自研 Android 界面分开维护。

- `upstream/codex/`：官方 Codex 的完整源码快照。根目录 `CODEX_UPSTREAM_COMMIT` 记录准确版本与导入时间。
- `android-app/`：Kotlin、Jetpack Compose 原生 Android 项目，侧边栏导航、实时会话、代码工具与账户/配置界面。
- `docs/`：信息架构、UI 材质与动效、JSON-RPC 交互约定以及测试验收。
- `.github/workflows/import-codex.yml`：手动或首次文件提交触发的上游导入机制，无定时调度。
- `.github/workflows/build-android.yml`：编译 APK、校验 ARM64 核心及上传构建产物。

## 目标

把完整 Codex 开发体验适配到手机：一条主线是会话、工作区、实时执行与文件变更；另一条主线是模型、插件、技能、MCP 与权限设置。任务列表与功能配置读取官方 `app-server` 的数据，避免凭空展示执行结果。

Android 项目最低系统版本为 Android 10（API 29），以 ARM64 设备为优先适配目标。APK 调试版产物由 `安卓版本构建与验证` 工作流提供。

## 核心来源及当前限制

2026-11-20 导入的主分支源码提交记录在根目录 `CODEX_UPSTREAM_COMMIT`。打包使用官方稳定发布 `rust-v0.171.0` 的 `codex-app-server-aarch64-unknown-linux-musl`。构建工作流通过官方发布的 SHA-256 校验文件完整性。

**重要：Linux MUSL ARM64 二进制与 Android/Bionic 的实际运行兼容性需要真机验证。** 即便 APK 构建成功且包含 ELF 文件，也不能直接推断 Android 内核可以运行全部 Codex 执行路径。为避免混淆，构建验证与 Android 端到端运行是两个独立验收项。若官方 Linux MUSL 包在设备上出现执行器、动态链接、系统调用或网络问题，需要利用本仓库已保留的源码，经 Android NDK 重编译所需的运行模块并进行真机回归。

## 使用

1. 进入 GitHub 仓库的 Actions，打开 `安卓版本构建与验证`，下载 `codex-android-debug-apk`。
2. 安装 APK，启动后检查侧栏下方的“引擎已连接”状态。失败时查看“诊断与日志”。
3. 在“账号登录”中完成 Codex 账号授权，在“任务对话”中创建会话。
4. 从“工作区文件”导入 ZIP 工程，或使用本机文件编辑器创建源文件。
5. 命令、文件变更和高级权限请求集中在“待审批操作”中逐项确认。

**私有数据**默认存在本机 Android 应用私有空间，不通过本项目建设任何云端保存服务；Codex 官方接口与用户自行接入的第三方工具仍按各自网络行为工作。可阅读 [架构](docs/ARCHITECTURE.md)、[设计系统](docs/DESIGN_SYSTEM.md)、[协议](docs/PROTOCOL_CONTRACTS.md)、[验收](docs/ACCEPTANCE.md)。

## 版本维护

保留官方 `upstream/codex/` 不改动，在 `android-app/` 内维护适配层。升级主分支源码时，先读取官方 `app-server-protocol` 与 `app-server` 的变更，再比对 `docs/PROTOCOL_CONTRACTS.md` 和 Android 请求/事件，再逐项执行编译及设备回归。稳定发布核心的版本需要和协议兼容性检查分开处理。

## 许可证

Codex 原始代码按 `upstream/codex/LICENSE` 与 `upstream/codex/NOTICE` 的许可规定使用。Android 适配代码沿用 Apache-2.0 许可证；完整许可条款见 `upstream/codex/LICENSE`。
