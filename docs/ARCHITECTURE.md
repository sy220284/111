# Codex Android 系统架构

## 分层结构

```text
Android Kotlin / Jetpack Compose
├─ 侧栏 / 搜索 / 响应式布局 / 材质与动效
├─ 任务总览、会话、文件、差异、终端、代码审查
├─ 模型、模式、技能、插件、MCP、Apps、Hooks、环境
└─ 账号、配置、授权审批、外观、诊断
         │
         ▼
CodexViewModel
├─ activeThread / activeTurn / 任务进度
├─ UI 数据和加载/错误状态
└─ 服务器主动请求审批调度
         │
         ▼
CodexConnection
├─ stdio JSON-RPC 请求 ID / 事件 / 审批应答
├─ 进程退出监控与等待者即时失败
└─ 本机 private directory / 凭据 / 运行日志
         │
         ▼
Codex app-server (Android Bionic ELF)
├─ Codex Rust core、App Server 协议、身份认证
├─ 执行器、文件修改、模型与工具链
└─ 插件、技能、MCP
```

## 源码与构建关系

1. `upstream/codex` 固定官方来源；版本标记为 `CODEX_UPSTREAM_COMMIT`。
2. `build-native-codex.yml` 在 GitHub Actions 的 Android NDK 27.2 内编译两个 Rust Android 目标，分别上传 ELF 产物；执行头需包含 `/system/bin/linker64`。
3. `build-android.yml` 从成功的原生编译记录获取产物，复制为 APK 的 `lib/<ABI>/libcodex_server.so`；Compose 中的 `ProcessBuilder` 从安装后的原生库目录执行独立进程，采用标准输入输出 JSON-RPC。
4. 真正的成功标准为 Android 模拟器内 `--version` 及 `initialize`、`account/read` 均通过。APK 编译通过只验证安装包结构。
5. `check-android-ui.yml` 独立验证 UI，不必重新编译成本高的 Codex Rust 核心。

## 已知兼容问题及解决路线

官方 Linux MUSL 静态发布包在 Android 35 x86_64 模拟器测试中执行 `--version` 返回 159（SIGSYS），初始化等待超时。已永久从新版 APK 构建流程移除 Linux 发布包。

Android 原生核心的代码兼容性由 Rust + NDK 编译日志裁定；若存在上游针对 Linux-only 的 API，优先基于精确的 Android 平台条件处理根因，最终编译与模拟器回归必须通过。真正可运行之后还要验证沙箱、网络请求、认证、MCP 工具以及多进程执行行为。

## 安全与状态

- 应用私有目录中的 `projects/` 保存工程，`codex-home/` 保存 Codex 自己的配置，用户输入与 API Key 不写入应用日志。
- 编排层不假造成功状态。JSON-RPC 服务端未识别的主动请求明确返回失败响应，避免阻塞整轮任务。
- 审批单独匹配命令、文件修改、额外权限、多问题输入和 MCP 请求，权限批准只返回请求中包含的范围。
- 子进程退出、输出 EOF 与握手失败会直接唤醒所有待处理请求，设备诊断保存最近 16 行错误（仅设备本地）。
- UI 里的权限策略由真实 `config/read` 决定，用户确认后仅写入修改项。
