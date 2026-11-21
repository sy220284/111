# 111 Android 全量 UI/协议与四技能体验审计

核查日期：2026-11-21。以本仓锁定的 `upstream/codex/codex-rs/app-server-protocol/src/protocol/common.rs`、`AppNavigation.kt` 和 Kotlin 代码为设计依据。

## 设计结论

**用户日常流程优先：开始任务、恢复会话、打开工作区、处理审批。** Android 端保持 **28 个可达页面**；底部只保留首页、对话、文件、会话、更多五槽，更多里将常用任务/开发/能力/设置按场景分组。高级功能经搜索或明确展开出现，后台 RPC 与进程调度不单独占导航位。

四技能分别落实：
- `finesse-ui`：移动端层级、单手命中、有效反馈、可达性和空/加载/失败状态；
- `finesse-brief`：按实际用户任务组织入口，列表—详情—操作最多三级；
- `frame-smith`：仅用状态驱动且可减弱的轻动效，不将视频制作能力生搬进 App；
- `finesse-term`：所有按钮使用一致的中文动词，协议及调试字段保留原始名称。

## 28 页信息架构与操作范围

| 分组 | 页面 | 用户直接可见的功能 | 后台处理 |
| --- | --- | --- | --- |
| 工作台 | 总览 | 开始/继续、审批提醒、文件/改动快捷入口 | 连接、状态与统计刷新 |
| 工作台 | 任务对话 | 发消息、附件、模式/模型、停止/补充指令 | RPC ID、流式增量、事件与运行状态 |
| 工作台 | 会话历史 | 搜索、归档/恢复、重命名/删除、分组筛选及移组 | 服务端分页与记录同步 |
| 工作台 | 全文检索 | 输入检索词并打开结果 | 索引与分页 |
| 工作台 | 任务目标 | 更新/清除目标 | 会话上下文 |
| 工作台 | 任务队列 | 查看/排序队列（实验） | 运行调度与事件 |
| 工作台 | 待审批操作 | 查看作用范围、批准/拒绝 | 精确类型的服务端审批结果 |
| 项目与代码 | 工作区文件 | 导入、编辑、创建/重命名/删除 | 本地沙箱、事务写入、清理 |
| 项目与代码 | 代码差异 | 按文件查看 diff，复制、发起审查 | diff 事件与解析 |
| 项目与代码 | 终端 | 确认命令、执行、查看实时输出、向进程输入、主动停止、读取退出码 | 独立进程 ID、stdin/stdout/stderr 流、输出限额和进程结束清理 |
| 项目与代码 | 代码审查 | 选择范围、发起审查、查看结果 | 审查任务与进度 |
| 能力与扩展 | 模型目录 | 选择可用模型、查看能力 | 目录和权限同步 |
| 能力与扩展 | 协作模式 | 直接执行/先计划 | 任务参数 |
| 能力与扩展 | 技能 | 查看与启停 | 技能扫描和变更通知 |
| 能力与扩展 | 插件 | 详情、安装/卸载 | 安装依赖与状态刷新 |
| 能力与扩展 | 连接应用 | 列表、授权与查看详情 | OAuth 状态与权限 |
| 能力与扩展 | MCP 服务 | 服务状态/资源阅读/授权 | 工具调用及服务事件 |
| 能力与扩展 | 触发钩子 | 高级只读查看 | 钩子执行 |
| 能力与扩展 | 执行环境 | 高级环境状态 | 环境生命周期 |
| 设置与账户 | 账号登录 | 设备码/浏览器登录、登出 | OAuth 凭据持久化 |
| 设置与账户 | 模型服务商 | 选择默认服务商 | 认证和配置 |
| 设置与账户 | 引擎配置 | 高级原始配置修改 | 配置层/重载 |
| 设置与账户 | 权限与安全 | 读取可用权限方案、设置审批与沙箱 | 安全校验与审批调度 |
| 设置与账户 | 使用量 | 查看实际使用记录 | 服务端统计 |
| 设置与账户 | 使用限额 | 查看重置周期和窗口 | 配额计算 |
| 设置与账户 | 外观与交互 | 材质/紧凑/降低动态效果 | 本地设置 |
| 设置与账户 | 诊断与日志 | 高级排障、原始错误 | 内核异常、日志采集 |
| 设置与账户 | 关于项目 | 版本、开源、隐私 | 包版本元信息 |

## 192 项原生 App Server 请求的 UI/后台分层

| 判断层级 | 当前方法数 | 判断标准 |
| --- | ---: | --- |
| 直接操作 | 40 | 放在主要业务页面 |
| 上下文操作 | 44 | 按当前会话、文件、审批上下文进入 |
| 高级按需 | 70 | 只在高级能力页、真实服务支持时出现 |
| 后台内部 | 36 | 仅展示事件、状态、错误与可解释结果 |
| 平台不适用 | 2 | 当前 Android 平台不设普通入口 |

协议源码中带 `#[experimental]` 标记的请求：**77** 项。实验能力的入口需依据服务端返回结果开放，绝不以有接口名称就假设已经可用。

| 官方请求 | 分层 | 归属页面或系统 | 官方标记 |
| --- | --- | --- | --- |
| `server/diagnostics` | 后台内部 | 诊断 | 实验 |
| `userVerification/status` | 后台内部 | 身份验证后台 | 实验 |
| `userVerification/enroll` | 后台内部 | 身份验证后台 | 实验 |
| `userVerification/delete` | 后台内部 | 身份验证后台 | 实验 |
| `userVerification/verify` | 后台内部 | 身份验证后台 | 实验 |
| `userVerification/cancel` | 后台内部 | 身份验证后台 | 实验 |
| `thread/start` | 直接操作 | 会话/对话 | 常规/内部 |
| `thread/resume` | 直接操作 | 会话/对话 | 常规/内部 |
| `thread/fork` | 上下文操作 | 会话/对话 | 常规/内部 |
| `thread/sample` | 高级按需 | 会话/对话 | 实验 |
| `thread/sample/cancel` | 高级按需 | 会话/对话 | 实验 |
| `thread/archive` | 直接操作 | 会话/对话 | 常规/内部 |
| `thread/delete` | 直接操作 | 会话/对话 | 常规/内部 |
| `thread/unsubscribe` | 后台内部 | 会话/对话 | 常规/内部 |
| `thread/name/set` | 直接操作 | 会话/对话 | 常规/内部 |
| `thread/prediction/request` | 高级按需 | 会话/对话 | 实验 |
| `thread/goal/set` | 上下文操作 | 会话/对话 | 常规/内部 |
| `thread/goal/get` | 上下文操作 | 会话/对话 | 常规/内部 |
| `thread/goal/clear` | 上下文操作 | 会话/对话 | 常规/内部 |
| `thread/queue/add` | 上下文操作 | 会话/对话 | 实验 |
| `thread/queue/list` | 上下文操作 | 会话/对话 | 实验 |
| `thread/queue/update` | 上下文操作 | 会话/对话 | 实验 |
| `thread/queue/delete` | 上下文操作 | 会话/对话 | 实验 |
| `thread/queue/reorder` | 上下文操作 | 会话/对话 | 实验 |
| `thread/queue/start` | 高级按需 | 会话/对话 | 实验 |
| `thread/metadata/update` | 高级按需 | 会话/对话 | 常规/内部 |
| `thread/attachment/add` | 上下文操作 | 会话/对话 | 常规/内部 |
| `thread/attachment/list` | 上下文操作 | 会话/对话 | 常规/内部 |
| `thread/attachmentOwner/list` | 后台内部 | 会话/对话 | 常规/内部 |
| `thread/attachment/remove` | 上下文操作 | 会话/对话 | 常规/内部 |
| `thread/section/move` | 上下文操作 | 会话/对话 | 常规/内部 |
| `thread/settings/update` | 高级按需 | 会话/对话 | 实验 |
| `thread/memoryMode/set` | 高级按需 | 会话/对话 | 实验 |
| `thread/memory/read` | 高级按需 | 会话/对话 | 实验 |
| `memory/status` | 高级按需 | 记忆后台 | 实验 |
| `memory/reset` | 高级按需 | 记忆后台 | 实验 |
| `rollout/compress` | 后台内部 | 历史后台 | 实验 |
| `thread/unarchive` | 直接操作 | 会话/对话 | 常规/内部 |
| `thread/compact/start` | 上下文操作 | 会话/对话 | 常规/内部 |
| `thread/shellCommand` | 后台内部 | 会话/对话 | 常规/内部 |
| `thread/approveGuardianDeniedAction` | 高级按需 | 会话/对话 | 常规/内部 |
| `thread/backgroundTerminals/clean` | 高级按需 | 会话/对话 | 实验 |
| `thread/backgroundTerminals/list` | 高级按需 | 会话/对话 | 实验 |
| `thread/backgroundTerminals/terminate` | 高级按需 | 会话/对话 | 实验 |
| `thread/revert` | 上下文操作 | 会话/对话 | 常规/内部 |
| `thread/list` | 直接操作 | 会话/对话 | 常规/内部 |
| `project/list` | 高级按需 | 项目管理（实验） | 实验 |
| `project/read` | 高级按需 | 项目管理（实验） | 实验 |
| `project/create` | 高级按需 | 项目管理（实验） | 实验 |
| `project/import` | 高级按需 | 项目管理（实验） | 实验 |
| `project/update` | 高级按需 | 项目管理（实验） | 实验 |
| `project/move` | 高级按需 | 项目管理（实验） | 实验 |
| `project/delete` | 高级按需 | 项目管理（实验） | 实验 |
| `threadSection/list` | 上下文操作 | 会话历史 | 常规/内部 |
| `threadSection/create` | 上下文操作 | 会话历史 | 常规/内部 |
| `threadSection/update` | 上下文操作 | 会话历史 | 常规/内部 |
| `threadSection/delete` | 上下文操作 | 会话历史 | 常规/内部 |
| `thread/search` | 直接操作 | 会话/对话 | 实验 |
| `thread/search/stream` | 高级按需 | 会话/对话 | 实验 |
| `thread/search/cancel` | 高级按需 | 会话/对话 | 实验 |
| `thread/search/stream/cancel` | 高级按需 | 会话/对话 | 实验 |
| `thread/searchOccurrences` | 高级按需 | 会话/对话 | 实验 |
| `thread/loaded/list` | 后台内部 | 会话/对话 | 常规/内部 |
| `thread/read` | 直接操作 | 会话/对话 | 常规/内部 |
| `thread/readState/update` | 高级按需 | 会话/对话 | 实验 |
| `thread/turns/list` | 上下文操作 | 会话/对话 | 常规/内部 |
| `thread/items/list` | 上下文操作 | 会话/对话 | 常规/内部 |
| `thread/items/read` | 上下文操作 | 会话/对话 | 实验 |
| `thread/guardianReview` | 高级按需 | 会话/对话 | 实验 |
| `thread/prepareApprovedPlan` | 高级按需 | 会话/对话 | 实验 |
| `skills/list` | 直接操作 | 技能 | 常规/内部 |
| `skills/extraRoots/set` | 后台内部 | 技能 | 常规/内部 |
| `hooks/list` | 高级按需 | 触发钩子 | 常规/内部 |
| `marketplace/add` | 高级按需 | 插件/市场 | 常规/内部 |
| `marketplace/remove` | 高级按需 | 插件/市场 | 常规/内部 |
| `marketplace/upgrade` | 高级按需 | 插件/市场 | 常规/内部 |
| `plugin/list` | 直接操作 | 插件 | 常规/内部 |
| `plugin/search` | 上下文操作 | 插件 | 实验 |
| `plugin/installed` | 直接操作 | 插件 | 常规/内部 |
| `plugin/reconcile` | 高级按需 | 插件 | 常规/内部 |
| `plugin/read` | 直接操作 | 插件 | 常规/内部 |
| `plugin/skill/read` | 上下文操作 | 插件 | 常规/内部 |
| `plugin/share/save` | 高级按需 | 插件 | 常规/内部 |
| `plugin/share/updateTargets` | 高级按需 | 插件 | 常规/内部 |
| `plugin/share/list` | 高级按需 | 插件 | 常规/内部 |
| `plugin/share/checkout` | 高级按需 | 插件 | 常规/内部 |
| `plugin/share/delete` | 高级按需 | 插件 | 常规/内部 |
| `app/read` | 直接操作 | 连接应用 | 常规/内部 |
| `app/list` | 直接操作 | 连接应用 | 常规/内部 |
| `app/installed` | 直接操作 | 连接应用 | 常规/内部 |
| `fs/readFile` | 上下文操作 | 工作区文件 | 常规/内部 |
| `fs/writeFile` | 上下文操作 | 工作区文件 | 常规/内部 |
| `fs/createDirectory` | 上下文操作 | 工作区文件 | 常规/内部 |
| `fs/getMetadata` | 后台内部 | 工作区文件 | 常规/内部 |
| `fs/readDirectory` | 上下文操作 | 工作区文件 | 常规/内部 |
| `fs/remove` | 上下文操作 | 工作区文件 | 常规/内部 |
| `fs/copy` | 上下文操作 | 工作区文件 | 常规/内部 |
| `fs/watch` | 后台内部 | 工作区文件 | 常规/内部 |
| `fs/unwatch` | 后台内部 | 工作区文件 | 常规/内部 |
| `skills/config/write` | 直接操作 | 技能 | 常规/内部 |
| `plugin/install` | 直接操作 | 插件 | 常规/内部 |
| `plugin/uninstall` | 直接操作 | 插件 | 常规/内部 |
| `turn/start` | 直接操作 | 任务对话 | 常规/内部 |
| `turn/settings/update` | 后台内部 | 任务对话 | 实验 |
| `turn/steer` | 上下文操作 | 任务对话 | 常规/内部 |
| `turn/interrupt` | 直接操作 | 任务对话 | 常规/内部 |
| `thread/realtime/start` | 高级按需 | 会话/对话 | 实验 |
| `thread/realtime/appendAudio` | 高级按需 | 会话/对话 | 实验 |
| `thread/realtime/appendText` | 高级按需 | 会话/对话 | 实验 |
| `thread/realtime/appendSpeech` | 高级按需 | 会话/对话 | 实验 |
| `thread/realtime/stop` | 高级按需 | 会话/对话 | 实验 |
| `thread/timeline/list` | 上下文操作 | 会话/对话 | 实验 |
| `thread/realtime/list` | 高级按需 | 会话/对话 | 实验 |
| `thread/realtime/listVoices` | 高级按需 | 会话/对话 | 实验 |
| `review/start` | 直接操作 | 代码审查 | 常规/内部 |
| `model/list` | 直接操作 | 模型选择 | 常规/内部 |
| `account/gatewayOAuth/read` | 高级按需 | 账号/额度 | 常规/内部 |
| `account/gatewayOAuth/login` | 高级按需 | 账号/额度 | 常规/内部 |
| `account/gatewayOAuth/cancel` | 高级按需 | 账号/额度 | 常规/内部 |
| `modelProvider/list` | 直接操作 | 模型服务商 | 常规/内部 |
| `modelProvider/config/write` | 上下文操作 | 模型服务商 | 常规/内部 |
| `modelProvider/default/set` | 直接操作 | 模型服务商 | 常规/内部 |
| `modelProvider/capabilities/read` | 上下文操作 | 模型服务商 | 常规/内部 |
| `experimentalFeature/list` | 高级按需 | 引擎配置高级 | 常规/内部 |
| `permissionProfile/list` | 直接操作 | 权限与安全 | 常规/内部 |
| `experimentalFeature/enablement/set` | 高级按需 | 引擎配置高级 | 常规/内部 |
| `remoteControl/enable` | 高级按需 | 远程控制高级 | 实验 |
| `remoteControl/disable` | 高级按需 | 远程控制高级 | 实验 |
| `remoteControl/status/read` | 高级按需 | 远程控制高级 | 实验 |
| `remoteControl/pairing/start` | 高级按需 | 远程控制高级 | 实验 |
| `remoteControl/pairing/status` | 高级按需 | 远程控制高级 | 实验 |
| `remoteControl/client/list` | 高级按需 | 远程控制高级 | 实验 |
| `remoteControl/client/revoke` | 高级按需 | 远程控制高级 | 实验 |
| `collaborationMode/list` | 直接操作 | 协作模式 | 实验 |
| `mock/experimentalMethod` | 后台内部 | 调试 | 实验 |
| `environment/add` | 上下文操作 | 执行环境 | 实验 |
| `environment/info` | 上下文操作 | 执行环境 | 实验 |
| `environment/status` | 上下文操作 | 执行环境 | 实验 |
| `mcpServer/oauth/login` | 直接操作 | MCP 服务/审批 | 常规/内部 |
| `config/mcpServer/reload` | 高级按需 | 引擎配置/安全 | 常规/内部 |
| `mcpServerStatus/list` | 直接操作 | MCP 服务 | 常规/内部 |
| `mcpServer/resource/read` | 直接操作 | MCP 服务/审批 | 常规/内部 |
| `mcpServer/event/stream/start` | 高级按需 | MCP 服务/审批 | 实验 |
| `mcpServer/event/stream/stop` | 高级按需 | MCP 服务/审批 | 实验 |
| `mcpServer/tool/call` | 上下文操作 | MCP 服务/审批 | 常规/内部 |
| `windowsSandbox/setupStart` | 平台不适用 | Windows 专用 | 常规/内部 |
| `windowsSandbox/readiness` | 平台不适用 | Windows 专用 | 常规/内部 |
| `account/login/start` | 直接操作 | 账号/额度 | 常规/内部 |
| `account/bedrock/discover` | 后台内部 | 账号/额度 | 实验 |
| `account/bedrock/setup` | 后台内部 | 账号/额度 | 实验 |
| `account/gcp/setup` | 后台内部 | 账号/额度 | 实验 |
| `account/bedrock/checkGovCloudRequirements` | 后台内部 | 账号/额度 | 实验 |
| `account/login/cancel` | 直接操作 | 账号/额度 | 常规/内部 |
| `accountSession/list` | 上下文操作 | 账号高级 | 实验 |
| `accountSession/switch` | 上下文操作 | 账号高级 | 实验 |
| `account/logout` | 直接操作 | 账号/额度 | 常规/内部 |
| `account/securitySetup/read` | 高级按需 | 账号/额度 | 常规/内部 |
| `account/rateLimits/read` | 直接操作 | 账号/额度 | 常规/内部 |
| `account/rateLimitResetCredit/consume` | 后台内部 | 账号/额度 | 常规/内部 |
| `account/usage/read` | 直接操作 | 账号/额度 | 常规/内部 |
| `account/workspaceMessages/read` | 高级按需 | 账号/额度 | 常规/内部 |
| `account/sendAddCreditsNudgeEmail` | 后台内部 | 账号/额度 | 常规/内部 |
| `feedback/upload` | 后台内部 | 诊断后台 | 常规/内部 |
| `feedback/save` | 后台内部 | 诊断后台 | 常规/内部 |
| `command/exec` | 上下文操作 | 终端 | 常规/内部 |
| `command/exec/write` | 上下文操作 | 终端 | 常规/内部 |
| `command/exec/terminate` | 上下文操作 | 终端 | 常规/内部 |
| `command/exec/resize` | 上下文操作 | 终端 | 常规/内部 |
| `process/spawn` | 高级按需 | 终端高级 | 实验 |
| `process/writeStdin` | 高级按需 | 终端高级 | 实验 |
| `process/kill` | 高级按需 | 终端高级 | 实验 |
| `process/resizePty` | 高级按需 | 终端高级 | 实验 |
| `config/read` | 直接操作 | 引擎配置/安全 | 常规/内部 |
| `externalAgentConfig/detect` | 高级按需 | 诊断与导入 | 常规/内部 |
| `externalAgentConfig/import` | 高级按需 | 诊断与导入 | 常规/内部 |
| `externalAgentConfig/import/recordHistory` | 高级按需 | 诊断与导入 | 常规/内部 |
| `externalAgentConfig/import/readHistories` | 高级按需 | 诊断与导入 | 常规/内部 |
| `config/value/write` | 直接操作 | 引擎配置/安全 | 常规/内部 |
| `config/batchWrite` | 直接操作 | 引擎配置/安全 | 常规/内部 |
| `configRequirements/read` | 直接操作 | 权限与安全 | 常规/内部 |
| `account/read` | 直接操作 | 账号/额度 | 常规/内部 |
| `fuzzyFileSearch/sessionStart` | 后台内部 | 检索后台 | 实验 |
| `fuzzyFileSearch/sessionUpdate` | 后台内部 | 检索后台 | 实验 |
| `fuzzyFileSearch/sessionStop` | 后台内部 | 检索后台 | 实验 |
| `item/commandExecution/requestApproval` | 后台内部 | 审批事件后台 | 常规/内部 |
| `item/fileChange/requestApproval` | 后台内部 | 审批事件后台 | 常规/内部 |
| `item/tool/requestUserInput` | 后台内部 | 审批事件后台 | 常规/内部 |
| `mcpServer/elicitation/request` | 后台内部 | MCP 服务/审批 | 常规/内部 |
| `item/permissions/requestApproval` | 后台内部 | 审批事件后台 | 常规/内部 |
| `item/tool/call` | 后台内部 | 审批事件后台 | 常规/内部 |
| `account/chatgptAuthTokens/refresh` | 后台内部 | 账号/额度 | 常规/内部 |
| `attestation/generate` | 后台内部 | 可信证明后台 | 常规/内部 |

## 已经实际接入与待验收边界

本轮代码接入：
1. `threadSection/list/create/update/delete` 与 `thread/section/move`：会话历史按服务端分组筛选、创建、修改、移动和删除；全部先核对 v2 JSON Schema。
2. `permissionProfile/list`：在权限与安全页展示实际可用的权限方案，组织原始约束折叠展示，避免误把后台配置充当普通设置。
3. `plugin/installed`：插件页默认显示真实已安装插件，切换“发现插件”才读取市场目录；安装/卸载后双源同步刷新。
4. 账号页直接跳转使用量、使用限额与模型服务商，减少重复回到侧栏。
5. `thread/queue/update`：任务队列可修改已有的单段文本任务；图片与混合输入不转换成文本，保护用户原始附件。
6. 导航策略单一数据源 `NavigationPolicy`：默认只显示高频功能，搜索与显式展开可访问全部 28 页。
7. 首页以任务操作和最近会话为主，诊断/统计按需展开，审批和未登录状态醒目。

其余接口优先级：
- 会话队列操作与原生分组已接入；后续对实验队列的自动开始和审查功能按协议能力单独验证。
- 终端长进程：`command/exec`、`command/exec/write`、`command/exec/terminate` 已由真实进程 ID 绑定终端页，输出流使用 `command/exec/outputDelta` 通知且最近 128KiB 有界存储；终端专用长请求不受普通 RPC 的 45 秒超时限制。`command/exec/resize` 仅在将来引入真实 PTY 网格交互时显示，继续保留后台能力。
- 历史回退：`thread/revert` 会删除指定回合及后续历史，须提供范围提示与恢复策略
- 核心项目模型：`project/*` 为实验能力，与 Android 本地工作区文件管理分离
- 插件搜索/共享、MCP 参数表单、真实多账号切换：逐条核对服务能力，不能以静态按钮代替接入
- 真实 ARM64 真机登录、MCP、文件权限、TalkBack、字体放大仍需独立设备验证

**验收**：协议名称核对、Kotlin 单测、Lint、28 路由真机模拟器跳转、360/393/411dp UI 截图、双架构核心构建。不能用页面存在或字符串引用推断真实服务已成功运行。
## 2026-11-21 交互闭环复查：终端与持续任务

- **终端输入/执行**（`finesse-brief`）：工作目录固定为本地私有工程，执行前展示明确的危险确认；工具身份、独立进程 ID 和流式通知匹配在 ViewModel/协议层实现，不增加菜单层级。
- **持续运行反馈**（`finesse-ui`、`frame-smith`）：单个终端页面内同时查看执行状态、stdout/stderr、输入命令与停止操作；长任务仅使用真实状态推动进度。输出留存最近 128KiB 并提示截断，便于持续观察最新日志。
- **命名及误操作防护**（`finesse-term`）：分离“执行命令”“发送输入”“停止”和“已完成/异常”；输入仅在服务器确认后清空，失败保留草稿。默认收起协议级进程 ID。
- **会话一致性**：运行中只能回到当前会话；跳转其他历史会话将被 ViewModel 拦截。首页最近会话按钮也同步约束；完成执行后刷新最新会话列表。
- **时间可读性**：官方 `Thread.updatedAt / createdAt` 为 Unix 秒时间戳，在本地时区转为 `yyyy-MM-dd HH:mm`，保留服务端原始记录，避免展示不可读的整数。
- **任务队列**：成功入队后才清空被提交的草稿，返回错误时保留编辑内容，避免用户重复输入。
- **设备证据**：保留 360/393/411dp 的页面导航与截图回归；原生完整 APK 的 Android 35 测试扩展真实 stdin 流和进程终止。上线完成的判据为最新提交的编译、协议、单测、三档设备导航及原生长进程测试全部通过。

仍需单独设计的上游能力：真正可调整行列数的 PTY 终端（`command/exec/resize`），历史回退的可恢复范围（`thread/revert`），以及仅有实验协议的项目和云端高级功能。它们应保持在工作情境内按需出现，不占据一级导航。
