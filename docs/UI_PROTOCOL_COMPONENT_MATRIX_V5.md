# UI V5 接口到组件全量映射

数据源：本仓 App Server 195 项请求、V5 功能展现清单及 UI 审计。此表为实施与回归唯一基线。

分类：**直接操作 40、上下文操作 44、高级按需 70、后台内部 39、平台不适用 2**。共有 84 项直接或上下文操作应由用户可见的工作流程触发；70 项高级能力按真实服务可用性和业务上下文展示；41 项内部或平台不适用接口由后台管理或不创建 UI。分类是展示责任，接口返回需要真实证据，不能以有按钮即断言已接入。

| 方法 | 类别 | 功能归属 | 实验 |
|---|---|---|---|
| `server/diagnostics` | 后台内部 | 诊断 | 是 |
| `userVerification/status` | 后台内部 | 身份验证后台 | 是 |
| `userVerification/enroll` | 后台内部 | 身份验证后台 | 是 |
| `userVerification/delete` | 后台内部 | 身份验证后台 | 是 |
| `userVerification/verify` | 后台内部 | 身份验证后台 | 是 |
| `userVerification/cancel` | 后台内部 | 身份验证后台 | 是 |
| `thread/start` | 直接操作 | 会话/对话 | 否 |
| `thread/resume` | 直接操作 | 会话/对话 | 否 |
| `thread/fork` | 上下文操作 | 会话/对话 | 否 |
| `thread/sample` | 高级按需 | 会话/对话 | 是 |
| `thread/sample/cancel` | 高级按需 | 会话/对话 | 是 |
| `thread/archive` | 直接操作 | 会话/对话 | 否 |
| `thread/delete` | 直接操作 | 会话/对话 | 否 |
| `thread/unsubscribe` | 后台内部 | 会话/对话 | 否 |
| `thread/increment_elicitation` | 后台内部 | 会话审批计时 | 是 |
| `thread/decrement_elicitation` | 后台内部 | 会话审批计时 | 是 |
| `thread/name/set` | 直接操作 | 会话/对话 | 否 |
| `thread/prediction/request` | 高级按需 | 会话/对话 | 是 |
| `thread/goal/set` | 上下文操作 | 会话/对话 | 否 |
| `thread/goal/get` | 上下文操作 | 会话/对话 | 否 |
| `thread/goal/clear` | 上下文操作 | 会话/对话 | 否 |
| `thread/queue/add` | 上下文操作 | 会话/对话 | 是 |
| `thread/queue/list` | 上下文操作 | 会话/对话 | 是 |
| `thread/queue/update` | 上下文操作 | 会话/对话 | 是 |
| `thread/queue/delete` | 上下文操作 | 会话/对话 | 是 |
| `thread/queue/reorder` | 上下文操作 | 会话/对话 | 是 |
| `thread/queue/start` | 高级按需 | 会话/对话 | 是 |
| `thread/metadata/update` | 高级按需 | 会话/对话 | 否 |
| `thread/attachment/add` | 上下文操作 | 会话/对话 | 否 |
| `thread/attachment/list` | 上下文操作 | 会话/对话 | 否 |
| `thread/attachmentOwner/list` | 后台内部 | 会话/对话 | 否 |
| `thread/attachment/remove` | 上下文操作 | 会话/对话 | 否 |
| `thread/section/move` | 上下文操作 | 会话/对话 | 否 |
| `thread/settings/update` | 高级按需 | 会话/对话 | 是 |
| `thread/memoryMode/set` | 高级按需 | 会话/对话 | 是 |
| `thread/memory/read` | 高级按需 | 会话/对话 | 是 |
| `memory/status` | 高级按需 | 记忆后台 | 是 |
| `memory/reset` | 高级按需 | 记忆后台 | 是 |
| `rollout/compress` | 后台内部 | 历史后台 | 是 |
| `thread/unarchive` | 直接操作 | 会话/对话 | 否 |
| `thread/compact/start` | 上下文操作 | 会话/对话 | 否 |
| `thread/shellCommand` | 后台内部 | 会话/对话 | 否 |
| `thread/approveGuardianDeniedAction` | 高级按需 | 会话/对话 | 否 |
| `thread/backgroundTerminals/clean` | 高级按需 | 会话/对话 | 是 |
| `thread/backgroundTerminals/list` | 高级按需 | 会话/对话 | 是 |
| `thread/backgroundTerminals/terminate` | 高级按需 | 会话/对话 | 是 |
| `thread/revert` | 上下文操作 | 会话/对话 | 否 |
| `thread/list` | 直接操作 | 会话/对话 | 否 |
| `project/list` | 高级按需 | 项目管理（实验） | 是 |
| `project/read` | 高级按需 | 项目管理（实验） | 是 |
| `project/create` | 高级按需 | 项目管理（实验） | 是 |
| `project/import` | 高级按需 | 项目管理（实验） | 是 |
| `project/update` | 高级按需 | 项目管理（实验） | 是 |
| `project/move` | 高级按需 | 项目管理（实验） | 是 |
| `project/delete` | 高级按需 | 项目管理（实验） | 是 |
| `threadSection/list` | 上下文操作 | 会话历史 | 否 |
| `threadSection/create` | 上下文操作 | 会话历史 | 否 |
| `threadSection/update` | 上下文操作 | 会话历史 | 否 |
| `threadSection/delete` | 上下文操作 | 会话历史 | 否 |
| `thread/search` | 直接操作 | 会话/对话 | 是 |
| `thread/search/stream` | 高级按需 | 会话/对话 | 是 |
| `thread/search/cancel` | 高级按需 | 会话/对话 | 是 |
| `thread/search/stream/cancel` | 高级按需 | 会话/对话 | 是 |
| `thread/searchOccurrences` | 高级按需 | 会话/对话 | 是 |
| `thread/loaded/list` | 后台内部 | 会话/对话 | 否 |
| `thread/read` | 直接操作 | 会话/对话 | 否 |
| `thread/readState/update` | 高级按需 | 会话/对话 | 是 |
| `thread/turns/list` | 上下文操作 | 会话/对话 | 否 |
| `thread/items/list` | 上下文操作 | 会话/对话 | 否 |
| `thread/inject_items` | 后台内部 | 会话历史注入 | 否 |
| `thread/items/read` | 上下文操作 | 会话/对话 | 是 |
| `thread/guardianReview` | 高级按需 | 会话/对话 | 是 |
| `thread/prepareApprovedPlan` | 高级按需 | 会话/对话 | 是 |
| `skills/list` | 直接操作 | 技能 | 否 |
| `skills/extraRoots/set` | 后台内部 | 技能 | 否 |
| `hooks/list` | 高级按需 | 触发钩子 | 否 |
| `marketplace/add` | 高级按需 | 插件/市场 | 否 |
| `marketplace/remove` | 高级按需 | 插件/市场 | 否 |
| `marketplace/upgrade` | 高级按需 | 插件/市场 | 否 |
| `plugin/list` | 直接操作 | 插件 | 否 |
| `plugin/search` | 上下文操作 | 插件 | 是 |
| `plugin/installed` | 直接操作 | 插件 | 否 |
| `plugin/reconcile` | 高级按需 | 插件 | 否 |
| `plugin/read` | 直接操作 | 插件 | 否 |
| `plugin/skill/read` | 上下文操作 | 插件 | 否 |
| `plugin/share/save` | 高级按需 | 插件 | 否 |
| `plugin/share/updateTargets` | 高级按需 | 插件 | 否 |
| `plugin/share/list` | 高级按需 | 插件 | 否 |
| `plugin/share/checkout` | 高级按需 | 插件 | 否 |
| `plugin/share/delete` | 高级按需 | 插件 | 否 |
| `app/read` | 直接操作 | 连接应用 | 否 |
| `app/list` | 直接操作 | 连接应用 | 否 |
| `app/installed` | 直接操作 | 连接应用 | 否 |
| `fs/readFile` | 上下文操作 | 工作区文件 | 否 |
| `fs/writeFile` | 上下文操作 | 工作区文件 | 否 |
| `fs/createDirectory` | 上下文操作 | 工作区文件 | 否 |
| `fs/getMetadata` | 后台内部 | 工作区文件 | 否 |
| `fs/readDirectory` | 上下文操作 | 工作区文件 | 否 |
| `fs/remove` | 上下文操作 | 工作区文件 | 否 |
| `fs/copy` | 上下文操作 | 工作区文件 | 否 |
| `fs/watch` | 后台内部 | 工作区文件 | 否 |
| `fs/unwatch` | 后台内部 | 工作区文件 | 否 |
| `skills/config/write` | 直接操作 | 技能 | 否 |
| `plugin/install` | 直接操作 | 插件 | 否 |
| `plugin/uninstall` | 直接操作 | 插件 | 否 |
| `turn/start` | 直接操作 | 任务对话 | 否 |
| `turn/settings/update` | 后台内部 | 任务对话 | 是 |
| `turn/steer` | 上下文操作 | 任务对话 | 否 |
| `turn/interrupt` | 直接操作 | 任务对话 | 否 |
| `thread/realtime/start` | 高级按需 | 会话/对话 | 是 |
| `thread/realtime/appendAudio` | 高级按需 | 会话/对话 | 是 |
| `thread/realtime/appendText` | 高级按需 | 会话/对话 | 是 |
| `thread/realtime/appendSpeech` | 高级按需 | 会话/对话 | 是 |
| `thread/realtime/stop` | 高级按需 | 会话/对话 | 是 |
| `thread/timeline/list` | 上下文操作 | 会话/对话 | 是 |
| `thread/realtime/list` | 高级按需 | 会话/对话 | 是 |
| `thread/realtime/listVoices` | 高级按需 | 会话/对话 | 是 |
| `review/start` | 直接操作 | 代码审查 | 否 |
| `model/list` | 直接操作 | 模型选择 | 否 |
| `account/gatewayOAuth/read` | 高级按需 | 账号/额度 | 否 |
| `account/gatewayOAuth/login` | 高级按需 | 账号/额度 | 否 |
| `account/gatewayOAuth/cancel` | 高级按需 | 账号/额度 | 否 |
| `modelProvider/list` | 直接操作 | 模型服务商 | 否 |
| `modelProvider/config/write` | 上下文操作 | 模型服务商 | 否 |
| `modelProvider/default/set` | 直接操作 | 模型服务商 | 否 |
| `modelProvider/capabilities/read` | 上下文操作 | 模型服务商 | 否 |
| `experimentalFeature/list` | 高级按需 | 引擎配置高级 | 否 |
| `permissionProfile/list` | 直接操作 | 权限与安全 | 否 |
| `experimentalFeature/enablement/set` | 高级按需 | 引擎配置高级 | 否 |
| `remoteControl/enable` | 高级按需 | 远程控制高级 | 是 |
| `remoteControl/disable` | 高级按需 | 远程控制高级 | 是 |
| `remoteControl/status/read` | 高级按需 | 远程控制高级 | 是 |
| `remoteControl/pairing/start` | 高级按需 | 远程控制高级 | 是 |
| `remoteControl/pairing/status` | 高级按需 | 远程控制高级 | 是 |
| `remoteControl/client/list` | 高级按需 | 远程控制高级 | 是 |
| `remoteControl/client/revoke` | 高级按需 | 远程控制高级 | 是 |
| `collaborationMode/list` | 直接操作 | 协作模式 | 是 |
| `mock/experimentalMethod` | 后台内部 | 调试 | 是 |
| `environment/add` | 上下文操作 | 执行环境 | 是 |
| `environment/info` | 上下文操作 | 执行环境 | 是 |
| `environment/status` | 上下文操作 | 执行环境 | 是 |
| `mcpServer/oauth/login` | 直接操作 | MCP 服务/审批 | 否 |
| `config/mcpServer/reload` | 高级按需 | 引擎配置/安全 | 否 |
| `mcpServerStatus/list` | 直接操作 | MCP 服务 | 否 |
| `mcpServer/resource/read` | 直接操作 | MCP 服务/审批 | 否 |
| `mcpServer/event/stream/start` | 高级按需 | MCP 服务/审批 | 是 |
| `mcpServer/event/stream/stop` | 高级按需 | MCP 服务/审批 | 是 |
| `mcpServer/tool/call` | 上下文操作 | MCP 服务/审批 | 否 |
| `windowsSandbox/setupStart` | 平台不适用 | Windows 专用 | 否 |
| `windowsSandbox/readiness` | 平台不适用 | Windows 专用 | 否 |
| `account/login/start` | 直接操作 | 账号/额度 | 否 |
| `account/bedrock/discover` | 后台内部 | 账号/额度 | 是 |
| `account/bedrock/setup` | 后台内部 | 账号/额度 | 是 |
| `account/gcp/setup` | 后台内部 | 账号/额度 | 是 |
| `account/bedrock/checkGovCloudRequirements` | 后台内部 | 账号/额度 | 是 |
| `account/login/cancel` | 直接操作 | 账号/额度 | 否 |
| `accountSession/list` | 上下文操作 | 账号高级 | 是 |
| `accountSession/switch` | 上下文操作 | 账号高级 | 是 |
| `account/logout` | 直接操作 | 账号/额度 | 否 |
| `account/securitySetup/read` | 高级按需 | 账号/额度 | 否 |
| `account/rateLimits/read` | 直接操作 | 账号/额度 | 否 |
| `account/rateLimitResetCredit/consume` | 后台内部 | 账号/额度 | 否 |
| `account/usage/read` | 直接操作 | 账号/额度 | 否 |
| `account/workspaceMessages/read` | 高级按需 | 账号/额度 | 否 |
| `account/sendAddCreditsNudgeEmail` | 后台内部 | 账号/额度 | 否 |
| `feedback/upload` | 后台内部 | 诊断后台 | 否 |
| `feedback/save` | 后台内部 | 诊断后台 | 否 |
| `command/exec` | 上下文操作 | 终端 | 否 |
| `command/exec/write` | 上下文操作 | 终端 | 否 |
| `command/exec/terminate` | 上下文操作 | 终端 | 否 |
| `command/exec/resize` | 上下文操作 | 终端 | 否 |
| `process/spawn` | 高级按需 | 终端高级 | 是 |
| `process/writeStdin` | 高级按需 | 终端高级 | 是 |
| `process/kill` | 高级按需 | 终端高级 | 是 |
| `process/resizePty` | 高级按需 | 终端高级 | 是 |
| `config/read` | 直接操作 | 引擎配置/安全 | 否 |
| `externalAgentConfig/detect` | 高级按需 | 诊断与导入 | 否 |
| `externalAgentConfig/import` | 高级按需 | 诊断与导入 | 否 |
| `externalAgentConfig/import/recordHistory` | 高级按需 | 诊断与导入 | 否 |
| `externalAgentConfig/import/readHistories` | 高级按需 | 诊断与导入 | 否 |
| `config/value/write` | 直接操作 | 引擎配置/安全 | 否 |
| `config/batchWrite` | 直接操作 | 引擎配置/安全 | 否 |
| `configRequirements/read` | 直接操作 | 权限与安全 | 否 |
| `account/read` | 直接操作 | 账号/额度 | 否 |
| `fuzzyFileSearch/sessionStart` | 后台内部 | 检索后台 | 是 |
| `fuzzyFileSearch/sessionUpdate` | 后台内部 | 检索后台 | 是 |
| `fuzzyFileSearch/sessionStop` | 后台内部 | 检索后台 | 是 |
| `item/commandExecution/requestApproval` | 后台内部 | 审批事件后台 | 否 |
| `item/fileChange/requestApproval` | 后台内部 | 审批事件后台 | 否 |
| `item/tool/requestUserInput` | 后台内部 | 审批事件后台 | 否 |
| `mcpServer/elicitation/request` | 后台内部 | MCP 服务/审批 | 否 |
| `item/permissions/requestApproval` | 后台内部 | 审批事件后台 | 否 |
| `item/tool/call` | 后台内部 | 审批事件后台 | 否 |
| `account/chatgptAuthTokens/refresh` | 后台内部 | 账号/额度 | 否 |
| `attestation/generate` | 后台内部 | 可信证明后台 | 否 |

## 四技能执行约束

- finesse-ui：组件设计、白底层级、尺寸、状态、触控与可读性
- finesse-brief：当前用户工作场景决定页面、Sheet、确认框、行内按钮及返回
- frame-smith：按压 110ms、展开 210ms、跳转 240ms，尊重减弱动效和真实状态
- finesse-term：确认动作写明后果，失败指出下一步，中文主文案与协议字段分离

## 完整接入的验收规则

对每个直接/上下文方法按“入口→操作对象→权限或条件→真实 RPC→响应/错误/审批→返回原场景”逐项查验。
高级按需需要真实服务支持和功能开关；不应通过临时可点的虚假按钮冒充可用。
