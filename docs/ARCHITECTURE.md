# Codex Android 系统架构

## 组成与职责

```text
Android UI / Compose
├── 侧边导航与页面状态
│   ├── 工作台（总览 / 对话 / 历史 / 搜索 / 目标 / 队列）
│   ├── 开发工具（文件 / 差异 / 终端 / 审查）
│   ├── Codex 生态（模型 / 协作 / 技能 / 插件 / Apps / MCP / Hooks / 环境）
│   └── 设置（账号 / 服务商 / 配置 / 权限 / 审批 / 统计 / 外观 / 诊断）
├── CodexViewModel
│   ├── 线程、轮次、工具事件归并与页面状态
│   ├── 需要用户决策的请求
│   └── 统一服务数据与请求错误
└── CodexConnection
    ├── JSON-RPC 请求 ID / 回复匹配
    ├── 标准输入输出双向消息
    ├── 服务器主动审批与事件广播
    └── Codex app-server 本机子进程
        ├── Codex core
        ├── 执行器 / 沙箱
        ├── 认证 / 模型 / 工具 / MCP
        └── Android 私有工作目录
```

## 文件隔离与核心升级

`upstream/codex/` 是完整上游源码快照，不混杂 Android 特定的接口补丁。
Android 应用源代码与部署脚本位于 `android-app/`。

构建流程从 Codex 官方 GitHub release 拉取 ARM64 MUSL 文件，核对发布哈希后，将官方核心装入 APK 的 `lib/arm64-v8a/libcodex_server.so` 路径。Android 在 `nativeLibraryDir` 解压后以本机子进程方式启动。

这样 UI 可独立迭代，上游每次升级只需在版本标记与协议映射层审查变化。官方发布产物的 Android 适配尚需真机验证，尤其 `app-server` 调用的助手工具和与系统交互部分。

## 数据路径

- Compose UI 用户输入 -> ViewModel -> Connection -> app-server 的 `thread/start`、`turn/start`。
- app-server 以 `item/...` 通知方式持续推送文本、命令、文件变更、用量等，ViewModel 维护当前会话状态。
- 原生交互必须回复 server-initiated requests：CommandApproval、FileChangeApproval、PermissionsApproval、ToolRequestUserInput。UI 将其收纳于审批页。
- 文件只读/编辑通过系统文件选择器导入私有工程目录；Codex 的 `cwd` 指向同一目录。
- 配置与认证由上游 Codex 自身管理，UI 不存储 API Key。

## 安全关键点

- 实际权限以 Android 应用沙箱与 Codex 自身限制为准，不能仅凭界面开关声称网络或命令被系统强制隔离。
- 前端对执行与配置写入执行人工确认，不静默通过命令或权限请求。
- ZIP 文件解压执行路径穿越核查和 120MB 解包限额；实际导入仍需扩展文件类型和格式验证。
- 发生协议错误时直接在页面展示失败原因，不注入伪造成功数据。

## 工作流

- `一次性导入官方 Codex 源码`：在初始化时获取官方主分支快照；未来仅可手动触发，不做定时更新，避免未知版本自动覆盖。
- `安卓版本构建与验证`：每次 Android 路径变更时生成 APK 与产物 hash，并检查 ARM64 核心文件已包含。
- 最终上线还需设备真实登录、会话、工具执行、文件写入与重启恢复的端到端测试。
