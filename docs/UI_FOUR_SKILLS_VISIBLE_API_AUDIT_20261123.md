# 111 Android · 四技能用户可见接口审计与组件落地（2026-11-23）

权威协议来源：`android-app/tools/ui_surface_policy.json`；按同一策略生成的 Kotlin 方法目录和 `docs/UI_PROTOCOL_COMPONENT_MATRIX_V5.md` 负责接口名称、归属和实验标记。此文件记录本次 UI 结构、交互差距、代码落实和逐接口验收边界。

## 本轮使用的四项技能

- `finesse-ui v0.22.0`：信息层次、触控尺寸、共享组件、状态与视觉一致性
- `finesse-brief v0.5.0`：任务优先、业务对象归属、真实工作流及错误恢复
- `frame-smith v0.1.0`：表达状态变化的短动效及 reducedMotion；不移植视频编辑功能
- `finesse-term v0.3.0`：中文任务操作和后果，隐藏内部 RPC 与不必要的技术字段

## 审计边界与结果

| 类别 | 数量 | 面向用户的处理 |
| --- | ---: | --- |
| 直接操作 | 38 | 主操作/列表/设置中的明确入口，提交真实 RPC |
| 上下文操作 | 43 | 选中会话、文件、项目、插件后显示菜单、Sheet、确认 |
| 高级按需 | 52 | 检查运行核心可用性后，按实际业务场景显示入口 |
| 后台内部 | 60 | 运行核心或智能体处理，只有审批、错误与结果需要 UI |
| Android 不适用 | 2 | 不生成 Android UI |

**用户可见范围合计 133 项**。静态调用追踪只说明已存在源码路径，仍需要独立检查请求字段、服务端响应、失败处理和真实 ARM64 设备效果。实验能力未确认时展示原因及恢复操作，不以多一个按钮衡量功能完成。

## 发现并改造的交互缺口

| 业务问题 | 改造 | 用户检查 |
| --- | --- | --- |
| 全文检索两个页面重复实现、旧关键词结果错位 | 独立搜索路由复用 `ThreadLibraryPage`，结果与提交词及真正响应一致 | 修改关键词而未搜索时隐藏旧结果 |
| 首页、侧栏、搜索、分支或深链先导航再恢复会话 | `CodexViewModel.chooseThread(..., onOpened)` 读取/恢复成功后导航 | 失败留原任务，成功进入正确任务 |
| 运行中选择协作方式没有操作反馈 | `ModeSelectionPolicy` 统一 Sheet/页面状态；执行和加载时禁用，连接异常说明原因 | 模式有效性来自真实内核目录 |
| 技能启停与技能目录争用同一状态键 | 独立 `skillsWrite`，忙碌时禁用，成功后刷新目录 | 成功/失败反馈与目录刷新互不覆盖 |
| 插件安装/卸载与目录争用键、检索结果陈旧 | 独立 `pluginMutation`，请求中禁用操作；搜索词变化先隐藏旧结果 | 避免重复安装与串词 |
| 项目和队列修改失败后编辑草稿消失 | 只在 RPC 成功后清理草稿及关闭编辑；在当前弹层显示错误 | 失败可以原地重试 |

## 设计交付

- [四项技能交互设计板（可编辑 SVG）](ui-assets/four-skills-interaction-v6.svg)：统一检索、会话恢复、协作模式、技能/插件状态等关键组件的四列可编辑矢量参考。作为设计规格参考，不当作运行截图。
- 主界面保持典雅白、桌面式侧栏、工作流优先及无底部导航；48dp 触控目标、Material 3 本机弹层和系统减少动画选项按现有 `VisualSystem.kt`、`InteractionSystem.kt` 复用。
- Figma 原文件 `K4FEMoNdmQbcnJZCHWr8c7` 的 Starter/View MCP 已返回额度耗尽。此次直接画板写入受阻；仓库内 Compose 与可导入 SVG 是本轮可核对的实现成果。

## 133 项协议的 UI 责任归属

这里逐项标注当前策略的用户场景，具体字段和失败分支以 `PROTOCOL_ACTION_IMPLEMENTATION_LEDGER_20261122.md` 为基准继续交叉核查。列表列账不宣称每项都已实机贯通。

| 官方方法 | 展示层级 | 功能归属 | 容器选择 | 验收条件 |
| --- | --- | --- | --- | --- |
| `server/diagnostics` | 高级按需 | 诊断 | 诊断页面 | 待能力确认与场景执行 |
| `thread/start` | 直接操作 | 会话/对话 | 会话列表/当前任务上下文 | 真实业务反馈需逐项设备验收 |
| `thread/resume` | 直接操作 | 会话/对话 | 会话列表/当前任务上下文 | 真实业务反馈需逐项设备验收 |
| `thread/fork` | 上下文操作 | 会话/对话 | 会话列表/当前任务上下文 | 真实业务反馈需逐项设备验收 |
| `thread/archive` | 直接操作 | 会话/对话 | 会话列表/当前任务上下文 | 真实业务反馈需逐项设备验收 |
| `thread/delete` | 直接操作 | 会话/对话 | 会话列表/当前任务上下文 | 真实业务反馈需逐项设备验收 |
| `thread/name/set` | 直接操作 | 会话/对话 | 会话列表/当前任务上下文 | 真实业务反馈需逐项设备验收 |
| `thread/goal/set` | 上下文操作 | 会话/对话 | 会话列表/当前任务上下文 | 真实业务反馈需逐项设备验收 |
| `thread/goal/get` | 上下文操作 | 会话/对话 | 会话列表/当前任务上下文 | 真实业务反馈需逐项设备验收 |
| `thread/goal/clear` | 上下文操作 | 会话/对话 | 会话列表/当前任务上下文 | 真实业务反馈需逐项设备验收 |
| `thread/queue/add` | 上下文操作 | 会话/对话 | 会话列表/当前任务上下文 | 真实业务反馈需逐项设备验收 |
| `thread/queue/list` | 上下文操作 | 会话/对话 | 会话列表/当前任务上下文 | 真实业务反馈需逐项设备验收 |
| `thread/queue/update` | 上下文操作 | 会话/对话 | 会话列表/当前任务上下文 | 真实业务反馈需逐项设备验收 |
| `thread/queue/delete` | 上下文操作 | 会话/对话 | 会话列表/当前任务上下文 | 真实业务反馈需逐项设备验收 |
| `thread/queue/reorder` | 上下文操作 | 会话/对话 | 会话列表/当前任务上下文 | 真实业务反馈需逐项设备验收 |
| `thread/queue/start` | 高级按需 | 会话/对话 | 会话列表/当前任务上下文 | 待能力确认与场景执行 |
| `thread/attachment/add` | 上下文操作 | 会话/对话 | 会话列表/当前任务上下文 | 真实业务反馈需逐项设备验收 |
| `thread/attachment/list` | 上下文操作 | 会话/对话 | 会话列表/当前任务上下文 | 真实业务反馈需逐项设备验收 |
| `thread/attachment/remove` | 上下文操作 | 会话/对话 | 会话列表/当前任务上下文 | 真实业务反馈需逐项设备验收 |
| `thread/section/move` | 上下文操作 | 会话/对话 | 会话列表/当前任务上下文 | 真实业务反馈需逐项设备验收 |
| `thread/settings/update` | 高级按需 | 会话/对话 | 会话列表/当前任务上下文 | 待能力确认与场景执行 |
| `thread/memoryMode/set` | 高级按需 | 会话/对话 | 会话列表/当前任务上下文 | 待能力确认与场景执行 |
| `thread/memory/read` | 高级按需 | 会话/对话 | 会话列表/当前任务上下文 | 待能力确认与场景执行 |
| `memory/status` | 高级按需 | 记忆后台 | 会话高级区 | 待能力确认与场景执行 |
| `memory/reset` | 高级按需 | 记忆后台 | 会话高级区 | 待能力确认与场景执行 |
| `thread/unarchive` | 直接操作 | 会话/对话 | 会话列表/当前任务上下文 | 真实业务反馈需逐项设备验收 |
| `thread/compact/start` | 上下文操作 | 会话/对话 | 会话列表/当前任务上下文 | 真实业务反馈需逐项设备验收 |
| `thread/approveGuardianDeniedAction` | 高级按需 | 会话/对话 | 会话列表/当前任务上下文 | 待能力确认与场景执行 |
| `thread/backgroundTerminals/clean` | 高级按需 | 会话/对话 | 会话列表/当前任务上下文 | 待能力确认与场景执行 |
| `thread/backgroundTerminals/list` | 高级按需 | 会话/对话 | 会话列表/当前任务上下文 | 待能力确认与场景执行 |
| `thread/backgroundTerminals/terminate` | 高级按需 | 会话/对话 | 会话列表/当前任务上下文 | 待能力确认与场景执行 |
| `thread/revert` | 上下文操作 | 会话/对话 | 会话列表/当前任务上下文 | 真实业务反馈需逐项设备验收 |
| `thread/list` | 直接操作 | 会话/对话 | 会话列表/当前任务上下文 | 真实业务反馈需逐项设备验收 |
| `project/list` | 高级按需 | 项目管理（实验） | 按需工作区项目面板 | 待能力确认与场景执行 |
| `project/read` | 高级按需 | 项目管理（实验） | 按需工作区项目面板 | 待能力确认与场景执行 |
| `project/create` | 高级按需 | 项目管理（实验） | 按需工作区项目面板 | 待能力确认与场景执行 |
| `project/import` | 高级按需 | 项目管理（实验） | 按需工作区项目面板 | 待能力确认与场景执行 |
| `project/update` | 高级按需 | 项目管理（实验） | 按需工作区项目面板 | 待能力确认与场景执行 |
| `project/move` | 高级按需 | 项目管理（实验） | 按需工作区项目面板 | 待能力确认与场景执行 |
| `project/delete` | 高级按需 | 项目管理（实验） | 按需工作区项目面板 | 待能力确认与场景执行 |
| `threadSection/list` | 上下文操作 | 会话历史 | 历史分组菜单 | 真实业务反馈需逐项设备验收 |
| `threadSection/create` | 上下文操作 | 会话历史 | 历史分组菜单 | 真实业务反馈需逐项设备验收 |
| `threadSection/update` | 上下文操作 | 会话历史 | 历史分组菜单 | 真实业务反馈需逐项设备验收 |
| `threadSection/delete` | 上下文操作 | 会话历史 | 历史分组菜单 | 真实业务反馈需逐项设备验收 |
| `thread/search` | 直接操作 | 会话/对话 | 会话列表/当前任务上下文 | 真实业务反馈需逐项设备验收 |
| `thread/search/cancel` | 高级按需 | 会话/对话 | 会话列表/当前任务上下文 | 待能力确认与场景执行 |
| `thread/searchOccurrences` | 高级按需 | 会话/对话 | 会话列表/当前任务上下文 | 待能力确认与场景执行 |
| `thread/read` | 直接操作 | 会话/对话 | 会话列表/当前任务上下文 | 真实业务反馈需逐项设备验收 |
| `thread/turns/list` | 上下文操作 | 会话/对话 | 会话列表/当前任务上下文 | 真实业务反馈需逐项设备验收 |
| `thread/items/list` | 上下文操作 | 会话/对话 | 会话列表/当前任务上下文 | 真实业务反馈需逐项设备验收 |
| `thread/items/read` | 上下文操作 | 会话/对话 | 会话列表/当前任务上下文 | 真实业务反馈需逐项设备验收 |
| `thread/prepareApprovedPlan` | 高级按需 | 会话/对话 | 会话列表/当前任务上下文 | 待能力确认与场景执行 |
| `skills/list` | 直接操作 | 技能 | 技能列表开关 | 真实业务反馈需逐项设备验收 |
| `marketplace/add` | 高级按需 | 插件/市场 | 市场高级设置 | 待能力确认与场景执行 |
| `marketplace/remove` | 高级按需 | 插件/市场 | 市场高级设置 | 待能力确认与场景执行 |
| `marketplace/upgrade` | 高级按需 | 插件/市场 | 市场高级设置 | 待能力确认与场景执行 |
| `plugin/list` | 直接操作 | 插件 | 插件列表/详情 Sheet | 真实业务反馈需逐项设备验收 |
| `plugin/search` | 上下文操作 | 插件 | 插件列表/详情 Sheet | 真实业务反馈需逐项设备验收 |
| `plugin/installed` | 直接操作 | 插件 | 插件列表/详情 Sheet | 真实业务反馈需逐项设备验收 |
| `plugin/read` | 直接操作 | 插件 | 插件列表/详情 Sheet | 真实业务反馈需逐项设备验收 |
| `plugin/skill/read` | 上下文操作 | 插件 | 插件列表/详情 Sheet | 真实业务反馈需逐项设备验收 |
| `plugin/share/save` | 高级按需 | 插件 | 插件列表/详情 Sheet | 待能力确认与场景执行 |
| `plugin/share/updateTargets` | 高级按需 | 插件 | 插件列表/详情 Sheet | 待能力确认与场景执行 |
| `plugin/share/list` | 高级按需 | 插件 | 插件列表/详情 Sheet | 待能力确认与场景执行 |
| `plugin/share/checkout` | 高级按需 | 插件 | 插件列表/详情 Sheet | 待能力确认与场景执行 |
| `plugin/share/delete` | 高级按需 | 插件 | 插件列表/详情 Sheet | 待能力确认与场景执行 |
| `app/list` | 直接操作 | 连接应用 | 应用授权管理 | 真实业务反馈需逐项设备验收 |
| `app/installed` | 直接操作 | 连接应用 | 应用授权管理 | 真实业务反馈需逐项设备验收 |
| `fs/readFile` | 上下文操作 | 工作区文件 | 文件列表/编辑器/确认 | 真实业务反馈需逐项设备验收 |
| `fs/writeFile` | 上下文操作 | 工作区文件 | 文件列表/编辑器/确认 | 真实业务反馈需逐项设备验收 |
| `fs/createDirectory` | 上下文操作 | 工作区文件 | 文件列表/编辑器/确认 | 真实业务反馈需逐项设备验收 |
| `fs/readDirectory` | 上下文操作 | 工作区文件 | 文件列表/编辑器/确认 | 真实业务反馈需逐项设备验收 |
| `fs/remove` | 上下文操作 | 工作区文件 | 文件列表/编辑器/确认 | 真实业务反馈需逐项设备验收 |
| `fs/copy` | 上下文操作 | 工作区文件 | 文件列表/编辑器/确认 | 真实业务反馈需逐项设备验收 |
| `skills/config/write` | 直接操作 | 技能 | 技能列表开关 | 真实业务反馈需逐项设备验收 |
| `plugin/install` | 直接操作 | 插件 | 插件列表/详情 Sheet | 真实业务反馈需逐项设备验收 |
| `plugin/uninstall` | 直接操作 | 插件 | 插件列表/详情 Sheet | 真实业务反馈需逐项设备验收 |
| `turn/start` | 直接操作 | 任务对话 | 底部输入和运行状态 | 真实业务反馈需逐项设备验收 |
| `turn/steer` | 上下文操作 | 任务对话 | 底部输入和运行状态 | 真实业务反馈需逐项设备验收 |
| `turn/interrupt` | 直接操作 | 任务对话 | 底部输入和运行状态 | 真实业务反馈需逐项设备验收 |
| `thread/realtime/start` | 高级按需 | 会话/对话 | 会话列表/当前任务上下文 | 待能力确认与场景执行 |
| `thread/realtime/stop` | 高级按需 | 会话/对话 | 会话列表/当前任务上下文 | 待能力确认与场景执行 |
| `thread/timeline/list` | 上下文操作 | 会话/对话 | 会话列表/当前任务上下文 | 真实业务反馈需逐项设备验收 |
| `thread/realtime/list` | 高级按需 | 会话/对话 | 会话列表/当前任务上下文 | 待能力确认与场景执行 |
| `thread/realtime/listVoices` | 高级按需 | 会话/对话 | 会话列表/当前任务上下文 | 待能力确认与场景执行 |
| `review/start` | 直接操作 | 代码审查 | 审查任务上下文 | 真实业务反馈需逐项设备验收 |
| `model/list` | 直接操作 | 模型选择 | 模型选择 Sheet | 真实业务反馈需逐项设备验收 |
| `account/gatewayOAuth/read` | 高级按需 | 账号/额度 | 账号/用量/额度页 | 待能力确认与场景执行 |
| `account/gatewayOAuth/login` | 高级按需 | 账号/额度 | 账号/用量/额度页 | 待能力确认与场景执行 |
| `account/gatewayOAuth/cancel` | 高级按需 | 账号/额度 | 账号/用量/额度页 | 待能力确认与场景执行 |
| `modelProvider/list` | 直接操作 | 模型服务商 | 服务商配置 | 真实业务反馈需逐项设备验收 |
| `modelProvider/config/write` | 上下文操作 | 模型服务商 | 服务商配置 | 真实业务反馈需逐项设备验收 |
| `modelProvider/default/set` | 直接操作 | 模型服务商 | 服务商配置 | 真实业务反馈需逐项设备验收 |
| `modelProvider/capabilities/read` | 上下文操作 | 模型服务商 | 服务商配置 | 真实业务反馈需逐项设备验收 |
| `experimentalFeature/list` | 高级按需 | 引擎配置高级 | 高级配置 | 待能力确认与场景执行 |
| `permissionProfile/list` | 直接操作 | 权限与安全 | 权限与安全页 | 真实业务反馈需逐项设备验收 |
| `experimentalFeature/enablement/set` | 高级按需 | 引擎配置高级 | 高级配置 | 待能力确认与场景执行 |
| `remoteControl/enable` | 高级按需 | 远程控制高级 | 高级远程管理 | 待能力确认与场景执行 |
| `remoteControl/disable` | 高级按需 | 远程控制高级 | 高级远程管理 | 待能力确认与场景执行 |
| `remoteControl/status/read` | 高级按需 | 远程控制高级 | 高级远程管理 | 待能力确认与场景执行 |
| `remoteControl/pairing/start` | 高级按需 | 远程控制高级 | 高级远程管理 | 待能力确认与场景执行 |
| `remoteControl/pairing/status` | 高级按需 | 远程控制高级 | 高级远程管理 | 待能力确认与场景执行 |
| `remoteControl/client/list` | 高级按需 | 远程控制高级 | 高级远程管理 | 待能力确认与场景执行 |
| `remoteControl/client/revoke` | 高级按需 | 远程控制高级 | 高级远程管理 | 待能力确认与场景执行 |
| `collaborationMode/list` | 直接操作 | 协作模式 | 协作方式 Sheet | 真实业务反馈需逐项设备验收 |
| `environment/add` | 上下文操作 | 执行环境 | 按需执行环境 | 真实业务反馈需逐项设备验收 |
| `environment/info` | 上下文操作 | 执行环境 | 按需执行环境 | 真实业务反馈需逐项设备验收 |
| `environment/status` | 上下文操作 | 执行环境 | 按需执行环境 | 真实业务反馈需逐项设备验收 |
| `mcpServer/oauth/login` | 直接操作 | MCP 服务/审批 | MCP 授权审批 | 真实业务反馈需逐项设备验收 |
| `config/mcpServer/reload` | 高级按需 | 引擎配置/安全 | 设置表单/危险确认 | 待能力确认与场景执行 |
| `mcpServerStatus/list` | 直接操作 | MCP 服务 | MCP 状态 | 真实业务反馈需逐项设备验收 |
| `account/login/start` | 直接操作 | 账号/额度 | 账号/用量/额度页 | 真实业务反馈需逐项设备验收 |
| `account/login/cancel` | 直接操作 | 账号/额度 | 账号/用量/额度页 | 真实业务反馈需逐项设备验收 |
| `accountSession/list` | 上下文操作 | 账号高级 | 账号切换 Sheet | 真实业务反馈需逐项设备验收 |
| `accountSession/switch` | 上下文操作 | 账号高级 | 账号切换 Sheet | 真实业务反馈需逐项设备验收 |
| `account/logout` | 直接操作 | 账号/额度 | 账号/用量/额度页 | 真实业务反馈需逐项设备验收 |
| `account/securitySetup/read` | 高级按需 | 账号/额度 | 账号/用量/额度页 | 待能力确认与场景执行 |
| `account/rateLimits/read` | 直接操作 | 账号/额度 | 账号/用量/额度页 | 真实业务反馈需逐项设备验收 |
| `account/usage/read` | 直接操作 | 账号/额度 | 账号/用量/额度页 | 真实业务反馈需逐项设备验收 |
| `account/workspaceMessages/read` | 高级按需 | 账号/额度 | 账号/用量/额度页 | 待能力确认与场景执行 |
| `command/exec` | 上下文操作 | 终端 | 终端工作区 | 真实业务反馈需逐项设备验收 |
| `command/exec/write` | 上下文操作 | 终端 | 终端工作区 | 真实业务反馈需逐项设备验收 |
| `command/exec/terminate` | 上下文操作 | 终端 | 终端工作区 | 真实业务反馈需逐项设备验收 |
| `command/exec/resize` | 上下文操作 | 终端 | 终端工作区 | 真实业务反馈需逐项设备验收 |
| `config/read` | 直接操作 | 引擎配置/安全 | 设置表单/危险确认 | 真实业务反馈需逐项设备验收 |
| `externalAgentConfig/detect` | 高级按需 | 诊断与导入 | 高级诊断和配置导入 | 待能力确认与场景执行 |
| `externalAgentConfig/import` | 高级按需 | 诊断与导入 | 高级诊断和配置导入 | 待能力确认与场景执行 |
| `externalAgentConfig/import/recordHistory` | 高级按需 | 诊断与导入 | 高级诊断和配置导入 | 待能力确认与场景执行 |
| `externalAgentConfig/import/readHistories` | 高级按需 | 诊断与导入 | 高级诊断和配置导入 | 待能力确认与场景执行 |
| `config/value/write` | 直接操作 | 引擎配置/安全 | 设置表单/危险确认 | 真实业务反馈需逐项设备验收 |
| `config/batchWrite` | 直接操作 | 引擎配置/安全 | 设置表单/危险确认 | 真实业务反馈需逐项设备验收 |
| `configRequirements/read` | 直接操作 | 权限与安全 | 权限与安全页 | 真实业务反馈需逐项设备验收 |
| `account/read` | 直接操作 | 账号/额度 | 账号/用量/额度页 | 真实业务反馈需逐项设备验收 |

## CI 与实际证据

- 静态：官方 Rust 方向、DTO、协议目录、可见接口调用位置和单一策略一致性。
- 代码：Kotlin 编译、单元测试、Lint，更新后的组件状态与编辑草稿测试。
- Android 35：360/393/411dp 三种屏宽导航和必要截图；适用时自更新安装器回归。
- 独立端到端：真实登录、MCP 授权、ARM64 Codex 子进程、后台任务、远程控制和厂商机行为另需对应运行日志；静态引用和模拟状态不能替代。
