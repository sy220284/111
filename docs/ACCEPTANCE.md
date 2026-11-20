# Codex Android 验收矩阵

本表依据真实 GitHub Actions 与 Android 35 模拟器日志更新，明确区分已验证、代码已实现和仍待真机验证。

| 项目 | 验证依据 | 当前结论 |
|---|---|---|
| Codex 官方源代码快照 | `CODEX_UPSTREAM_COMMIT` = `0e8c7683c4a85f00667bd762265358a407124f2e` | 已入库，可追溯 |
| Compose 侧栏与功能页面 | 已有 28 个导航入口与专用功能页面，包含目标和队列管理 | 代码实现，部分页面尚待交互实测 |
| Android UI、协议、Lint | [分架构分支 UI 工作流](https://github.com/sy220284/111/actions/runs/44733688170) | 编译、协议单测、Lint 和 UI 设备截图通过 |
| 运行核心 Android NDK 编译 | [原生核心工作流](https://github.com/sy220284/111/actions/runs/44721202452) | ARM64、x86_64 构建成功 |
| Android 16KB 页对齐和 Bionic 链接 | 双 ABI 的 ELF / 动态链接器 / LOAD 段核查 | 通过 |
| 单架构 APK 分发 | [ABI 拆分工作流](https://github.com/sy220284/111/actions/runs/44733688136) | ARM64、x86_64 APK 各自构建通过；未混入其他 ABI |
| Android 核心版本查询 | 同上 Android 35 x86_64 仪器测试 | `--version` 成功 |
| Android RPC 初始化及账号读取 | 同上 Android 35 x86_64 仪器测试 | `initialize`、`account/read` 成功 |
| Android 本地命令执行 | 同上，调用 `command/exec` 执行 `/system/bin/sh` | 返回正确退出码和预期输出 |
| Android 主界面导航 | 同上，侧边栏进入任务对话；另有独立 UI 截图验收 | 通过 |
| Linux MUSL 可执行文件直接移植 | 历史模拟器测试 `--version` 退出 159（SIGSYS） | 已确认不适用，改用 Android NDK |
| ARM64 实机启动与 Android 10–17 兼容 | 尚未连接相应物理设备 | 待实测 |
| 账号登录、模型请求、完整会话生成 | 需要有效账号和真实网络环境 | 待端到端验收 |
| 文件修改、Diff、审批、MCP、插件、技能 | 协议与 UI 源码已接入，但完整设备场景未覆盖 | 待端到端验收 |
| 无障碍、键盘避让、宽窄屏和动效 | 当前仅有 Android 35 模拟器的主要导航截图 | 待更广泛设备验收 |
| 正式发布签名及可安装发布包 | 当前 APK 采用调试签名，保留 30 天 Actions 产物 | 待正式发布配置 |

## 运行证据

- [原生双架构编译成功](https://github.com/sy220284/111/actions/runs/44721202452)
- [合并前：完整版核心与 Shell 命令测试成功（3 项）](https://github.com/sy220284/111/actions/runs/44732591288)
- [ABI 拆包：Android 核心与界面测试再次成功（3 项）](https://github.com/sy220284/111/actions/runs/44733688136)
- [ABI 拆包：独立 Kotlin / 协议 / Lint / UI 截图验证成功](https://github.com/sy220284/111/actions/runs/44733688170)

完整交付的后续验收重点：ARM64 真机运行、账号认证与实际模型会话、文件修改和审批流程、多工具调用、正式签名与多尺寸 UI 实测。

GitHub Actions 构建结果为最终记录；源码中存在页面或协议声明本身不视为设备能力通过。
