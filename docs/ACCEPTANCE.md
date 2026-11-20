# Codex Android 验收矩阵

本表根据真实提交、GitHub Actions 和 Android 模拟器证据更新。验证不通过的部分继续视为未完成。

| 项目 | 依据与结果 | 验收 |
|---|---|---|
| 官方源代码快照 | `CODEX_UPSTREAM_COMMIT` = `0e8c7683c4a85f00667bd762265358a407124f2e`；已入库 | 已验证 |
| Compose 工程与侧栏页面 | GitHub 已包含原生工程、28 个导航入口和专用功能页 | 源码已写入 |
| UI 基础构建 | 既有 Gradle APK 构建通过；新增独立编译与 Lint 工作流 | 等待最新独立验收 |
| 审批协议单元测试 | 5 项协议测试曾随 APK 构建成功 | 已通过此前单元测试 |
| Linux MUSL 在 Android 上运行 | Android 35 模拟器 `--version` 退出 159，握手失败 | 已证实不兼容，废弃该路径 |
| Android Bionic 原生 Rust 编译 | 初次因工具链与目标标准库版本不匹配 E0463；已改用 1.99.1 并重新编译 | 进行中 |
| ARM64 / x86_64 ELF 打包 | 新 APK 工作流只接收成功的 Android 原生构建产物 | 等待原生编译 |
| Android 模拟器启动 `--version` | 设备 instrumentation 测试 | 等待原生 ELF |
| Android `initialize` 与 `account/read` | 设备 instrumentation 测试 | 等待核心通过 |
| 账号登录、模型与真实任务、命令、Diff | 需 Android 真机端到端验证 | 未通过 |
| 文件导入编辑、审批接受与拒绝 | 设备级行为验收和权限测试 | 未通过 |
| 多设备布局、动效、TalkBack 与安全状态 | Android 实机交互、截图和辅助功能测试 | 未通过 |

构建进度以 [GitHub Actions](https://github.com/sy220284/111/actions) 的最新运行结果为最终依据。

## 必须验证的端到端路径

1. NDK 原生核心在 Android 10–17 设备执行、可被创建子进程并完成初始化。
2. 登录、加载模型、新建会话和连续任务；关闭再启动后可读取实际历史。
3. 工程 ZIP 导入、文本编辑、只读命令、修改文件、展示 Diff、审查结果。
4. 命令、文件与请求额外权限时逐项批准或拒绝；批准范围和作用时间正确。
5. MCP 服务、Skills、插件、Apps 数据来自服务端，错误和未授权反馈准确。
6. 对窄屏和宽屏的导航、输入键盘避让、动效减弱、最低触摸区域进行真机核验。
