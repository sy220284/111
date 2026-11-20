# Codex App Server 协议和 Android 页面契约

读取依据：`upstream/codex/codex-rs/app-server-protocol/src/protocol/common.rs`、对应 `v2` 类型以及 `upstream/codex/codex-rs/app-server/README.md`。版本以根目录 `CODEX_UPSTREAM_COMMIT` 为准。源码主分支与稳定发布核心可能存在接口差异。

## 连接与生命周期

`ProcessBuilder(<native library>/libcodex_server.so, "--listen", "stdio://")` 启动本地核心。JSON-RPC 2.0 风格消息采用每行一个 JSON，App Server 不需要强制 `jsonrpc` 属性：

```json
{"method":"initialize","id":1,"params":{"clientInfo":{"name":"codex_android","title":"Codex Android","version":"0.1.0"},"capabilities":{"experimentalApi":true}}}
```

完成 `initialize` 后通知 `initialized`，再开始用户请求。请求 ID 唯一、写入互斥、逐一匹配响应。服务端主动请求必须回传与其 ID 对应的 `result`；通知无 `id`。

## 页面与方法矩阵

| 模块 | 读取 / 创建 / 修改方法 | 事件 | UI |
|---|---|---|---|
| 初始化 | `initialize`, `initialized` | — | 引擎连接状态 |
| 会话 | `thread/start`, `thread/list`, `thread/read`, `thread/resume`, `thread/archive`, `thread/delete` | `thread/started` | 总览、历史和对话 |
| 全文检索 | `thread/search` | `thread/search/partial` (实验) | 检索 |
| 线程目标 | `thread/goal/get`, `thread/goal/set`, `thread/goal/clear` | — | 目标 |
| 任务队列 | `thread/queue/list` 等 | 相关队列事件 | 队列（实验） |
| 会话执行 | `turn/start`, `turn/steer`, `turn/interrupt` | `turn/started`, `turn/completed` | 对话与停止 |
| 内容流 | — | `item/started`, `item/completed`, `item/agentMessage/delta` | 实时任务流 |
| 文件差异 | — | `turn/diff/updated` | 差异 |
| 模型 | `model/list`, `modelProvider/list` | 目录更新 | 模型和服务商 |
| 技能 | `skills/list`, `skills/config/write` | 技能更新 | 技能 |
| 插件 | `plugin/list`, `plugin/install`, `plugin/uninstall` | 安装结果 | 插件 |
| Apps | `app/list`, `app/read` | 连接状态 | 连接应用 |
| MCP | `mcpServerStatus/list`, `mcpServer/resource/read` | 工具调用 | MCP |
| Hooks | `hooks/list` | — | 触发钩子 |
| 协作 | `collaborationMode/list` | — | 协作 |
| 执行 | `command/exec` | 命令输出 | 终端 |
| 审查 | `review/start` | 审查结果 | 代码审查 |
| 登录 | `account/read`, `account/login/start`, `account/logout` | `account/login/completed` | 账号 |
| 额度 | `account/usage/read`, `account/rateLimits/read` | — | 使用量、限额 |
| 配置 | `config/read`, `config/value/write`, `config/batchWrite` | — | 引擎配置 |
| 权限要求 | `configRequirements/read` | — | 安全策略 |
| 诊断 | `server/diagnostics` (实验) | — | 诊断 |

## 服务端主动请求

`item/commandExecution/requestApproval`、`item/fileChange/requestApproval`、`item/permissions/requestApproval`、`item/tool/requestUserInput`、`mcpServer/elicitation/request` 使用各自定义的结果类型，不能把所有种类的请求都简单转换成 `accept`。当前 Android 界面的命令/文件 approval 使用 `decision=accept/decline`；人类确认标记的命令必须每次审批。其他类型需要逐个对照官方 v2 DTO 验证后启用，避免误发授权。

## 兼容性守则

- `initialize.capabilities.experimentalApi = true` 是实验 API 的必要条件，但并不保证功能在当前核心版本中实现。
- UI 不应把 `thread/search/stream`、`thread/queue/list` 等实验功能包装为稳定能力。
- 平台未配置的供应商、沙箱和插件会返回明确错误，UI 应展示真实错误信息。
- App Server 以事件及完整的 `thread/read` 作为会话真实状态来源，不依赖本地伪造线程记录。
- 包装层和官方 `v2` 类型不一致时，应以锁定的可运行核心版本对照修订。
