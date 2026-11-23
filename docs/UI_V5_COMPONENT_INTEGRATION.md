# 111 Android V5｜全接口 UI 组件与功能接入说明

版本基线：2026-11-21。协议条目详见同仓 `UI_PROTOCOL_COMPONENT_MATRIX_V5.md`，UI 元信息在 `UiFeatureCatalog.kt`，协议到可视层归属在 `UiProtocolMatrix.kt`。本文件包含可离线执行的设计与验收约束。

## 一、架构原则

**接口是能力，组件是操作方式，页面只承载持续任务。** 对每个接口按“业务场景 → 操作对象 → 用户可见动作 → 服务能力判断 → 真实 RPC 或本地操作 → 加载/成功/错误/审批 → 返回原位置”串联。严禁仅把方法名称改成一个按钮，也不以有接口就判断服务实际可用。

| 展现类别 | 判定 | 执行组件 | 用户看到 |
|---|---|---|---|
| 常驻页面 | 需要长期阅读、多条记录、持续输入 | `PageContent` / 对话 / 文件 / 终端 / Diff | 固定导航与上下文 |
| 底部选择层 | 单次选择、短时上下文 | `CodexChoiceSheet` / 模型 / 模式 / 状态 / 目标 / 审查 | 取消、选项、描述、当前状态 |
| 风险确认 | 可能更改远端权限、记录或文件 | `CodexConfirmDialog` | 精确对象、影响范围、确认及取消 |
| 行内设置 | 开关、配额、版本、短状态 | `AppearanceInlineControls` / `AppUpdateCard` / `AccountMetricsPage` | 状态在原位更新 |
| 长文本阅读 | 资源、日志、原始输出、长 JSON | `FullTextReaderDialog` | 完整文本、搜索、复制、返回 |
| 直接动作 | 确切结果且无需附加设置 | Button/ActionTile | 禁用前提、加载、真实结果/错误 |

## 二、四项技能落地

1. **finesse-ui（审美与可用性）**：所有页面/卡片/强调背景纯白 `#FFFFFF`，深灰文字 `#263139`，次要文案 `#56646D`，边框 `#D8DEE1`；暖铜 `#805A3F`、青绿 `#246F54`、红 `#AC363A` 只用于字、图标、描边和进度；重要触控盒不小于 48dp。
2. **finesse-brief（工作情境）**：侧栏以当前项目和项目内会话为主，设置四组；任务目标、队列、审查在工作流内按需显示；文件、终端属于项目上下文；保持原有 28 个路由用于旧链接与能力核查。
3. **frame-smith（动效）**：卡片按压/边框反馈 110ms，短内容展开 210ms，页面切换 240ms；`UiPreferences.reducedMotion` 让自定义位移与缩放即时结束，仍保留静态按压反馈；进度严格来自真实数据，无伪造完成动画。
4. **finesse-term（文案）**：按钮文字写操作后果，例如“检查并下载更新”“确认授权服务”“确认删除资料”，危险确认披露范围；错误说明修复方向；JSON 字段保留在阅读器或专业模式中。

## 三、落地组件与真实功能

| 组件 / Kotlin 文件 | 对应方法 / 数据源 | 业务时机与状态 |
|---|---|---|
| `EngineStatusSheet`（UiSharedSurfaces） | `bridge.status`、`terminalSession`、`vm.connect` | 顶栏/侧栏状态固定打开面板；连接、诊断、返回终端分别选择 |
| `ModelChoiceSurface` | `model/list` + `modelId` | 对话/默认模型选择共享；成功后更改后续会话 |
| `ModeChoiceSurface` | `collaborationMode/list` + `selectedMode` | 仅显示当前内核支持的计划选项，执行中不切换 |
| `AttachmentActionsSheet` | `LocalImageAttachments.importFrom` | 输入框“+”仅管理三张以内本机图片，不与工具菜单混合 |
| `Sidebar` + `SidebarSessions` | `activeWorkspace` + `thread/list` | 侧栏显示当前项目，并按工作目录聚合会话；项目与会话自动衔接 |
| `ConversationPage` + `ConversationComposer` | `thread/goal/get`、`thread/queue/add/list`、`turn/steer` | 对话显示目标、改动摘要及排队条数；执行中的输入栏切换「完成后执行 / 立即纠偏」 |
| `TerminalPage` + 对话终端抽屉 | `command/exec`、`command/exec/write`、`command/exec/terminate` | 从对话或项目入口直接打开终端，保持当前工作目录；无需先选择历史会话 |
| `DiffReaderPage` + `ThreadDiffHistory` | `turn/diff/updated`、`thread/read` 的 `fileChange` | 当前执行变更实时显示；历史会话恢复逐文件 Diff，并在同一阅读器触发审查 |
| `ReviewScopePanel` / `ReviewScopeSheet` | `review/start` | 选择审查范围、确认风险、提交 RPC；结果回到任务流 |
| `ThreadGoalSheet` | `thread/goal/get/set/clear` | 当前会话 ··· 操作；目标编辑和清除沿用真实服务调用 |
| `ThreadQueuePage` | `thread/queue/list/add/update/delete/reorder` | 复杂列表保留页，实验能力失败状态保留 |
| `ThreadHistorySheet` | `thread/turns/list`、`thread/items/list`、`thread/revert` | 游标分页、完整条目、明确“从此回合及之后删除”的确认；不会恢复本地文件 |
| `ThreadAttachmentsSheet` | `thread/attachment/list/add/remove` | 当前会话资料与文本备注；准确身份键删除、结果确认后刷新 |
| `ThreadLibraryPage` | `thread/list` / `thread/search` | 同一页标题/全文搜索；分组与会话管理保留 |
| `AccountMetricsPage` | `account/usage/read`、`account/rateLimits/read` | 用量/额度双标签和真实配额信息 |
| `AppUpdateCard` | `AppUpdateManager` | 设置行折叠；真实检查下载、增量校验、进度、取消、系统安装 |
| `McpServiceStatusCard` | `mcpServerStatus/list`、`mcpServer/oauth/login` | 用户只管理 MCP 服务的连接状态和授权；智能体自主调用工具 |
| MCP 工具调用与资源读取 | `mcpServer/tool/call`、`mcpServer/resource/read` | 只保留 Android RPC 能力；智能体自动使用，无手动工具表单、资源浏览入口 |
| `PluginDetailSheet` | `plugin/read`、`plugin/skill/read` | 插件元数据及按需技能正文 |
| `PluginSearchContract` + `PluginsPage` | `plugin/search`（实验） | 插件发现页按关键字搜索，携来源安装与详情，真实游标分页、错误提示 |
| `AccountSessionsSheet` | `accountSession/list`、`accountSession/switch`（实验） | 账号面板列出可切换工作区，准确会话/工作区标识确认切换，按需外部 SSO 授权 |
| `ProvidersPage` | `modelProvider/list/default/set/config/write/capabilities/read` | 切默认、保存 customGateway 补丁、查询真实能力 |
| `AboutInfoSheet` | 本机版本、内核状态 | 设置内底部信息层，保留旧页面路由 |

## 四、195 项协议的曝光边界

以 `android-app/tools/ui_surface_policy.json` 为唯一分类来源，当前各类数量以自动生成的 `UI_PROTOCOL_COMPONENT_MATRIX_V5.md` 为准。该文档由 CI 校验，避免在多个实施说明中维护容易过时的相同数字。

| 来源类别 | UI 处理原则 |
|---|---|
| 直接操作 | 用户主动进入页面并执行业务动作，必须有可追踪 RPC 或验证过的本地操作 |
| 上下文操作 | 针对已选会话、文件或服务的菜单、Sheet、行内操作 |
| 高级按需 | 探测到内核支持能力且满足授权条件后开放 |
| 后台内部 | 由智能体和运行核心维护，涉及用户决策时在任务内弹出审批 |
| 平台不适用 | Android 环境不生成用户操作入口 |

以下属于不应简单新增执行按钮的场景：`fs/readFile/writeFile/createDirectory/readDirectory/remove/copy` 对应当前 Android 工作区的真实本地文件操作；`thread/items/read` 与 `thread/timeline/list` 为高级按条件读取，已有条目分页和全文阅读能力；`command/exec/resize` 已通过真实 PTY 行列设置进行调用，按运行状态和尺寸校验控制入口；`plugin/search`、`accountSession/list/switch` 等实验能力需完成正式协议字段和当前服务可用性确认后启用。全部保留在 195 项分类中，并未伪装成已支持按钮。

## 五、真实验收清单

- Kotlin 编译、协议检查、UnitTest、Lint、APK 构建与 Android 35 360/393/411dp 全量 UI Test；
- 28 个原业务路由仍存在；功能目录与页面目录通过 `UiFeatureCatalog.kt` 和自动审计核对；所有按钮具备明确操作结果；
- 登录、模型切换、模式选择、权限、审查、MCP、附件增删和升级均只依赖真实状态；
- 回退、目标与队列按当前会话 ID 操作；文件与终端按当前项目目录直接打开；审查从已绑定的差异和任务中发起，错误状态可见；
- 360/393/411dp：键盘弹出不遮挡发送；Modal 关闭后可返回原上下文；长 JSON 显示全文；
- 系统大字、TalkBack、弱动效、系统安装授权和真实设备登录另外保存独立设备证据。

设计规则保存在本仓内容中，交付不依赖外部设计技能网页或未归档链接。

> 2026-11-22：运行钩子列表、进程传输、实时语音分片、MCP 事件流等归为智能体/内核后台调用；不在设置导航中按协议方法自动生成页面。用户需要确认的权限仍由任务审批卡承担。

> 连接应用页只提供服务状态、授权和启用/停用操作，内部工具清单由智能体按需使用；不再在普通设置页面暴露 `app/read.includeTools` 的工具名称。
