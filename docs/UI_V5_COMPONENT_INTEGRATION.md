# 111 Android V5｜全接口 UI 组件与功能接入说明

版本基线：2026-11-21。协议条目详见同仓 `UI_PROTOCOL_COMPONENT_MATRIX_V5.md`，UI 元信息在 `UiFeatureCatalog.kt`，协议到可视层归属在 `UiProtocolMatrix.kt`。本文件包含可离线执行的设计与验收约束。

## 一、架构原则

**接口是能力，组件是操作方式，页面只承载持续任务。** 对每个接口按“业务场景 → 操作对象 → 用户可见动作 → 服务能力判断 → 真实 RPC 或本地操作 → 加载/成功/错误/审批 → 返回原位置”串联。严禁仅把方法名称改成一个按钮，也不以有接口就判断服务实际可用。

| 展现类别 | 判定 | 执行组件 | 用户看到 |
|---|---|---|---|
| 常驻页面 | 需要长期阅读、多条记录、持续输入 | `PageContent` / 对话 / 文件 / 终端 / Diff / 队列 | 固定导航与上下文 |
| 底部选择层 | 单次选择、短时上下文 | `CodexChoiceSheet` / 模型 / 模式 / 状态 / 目标 / 审查 | 取消、选项、描述、当前状态 |
| 风险确认 | 可能更改远端权限、记录或文件 | `CodexConfirmDialog` | 精确对象、影响范围、确认及取消 |
| 行内设置 | 开关、配额、版本、短状态 | `AppearanceInlineControls` / `AppUpdateCard` / `AccountMetricsPage` | 状态在原位更新 |
| 长文本阅读 | 资源、日志、原始输出、长 JSON | `FullTextReaderDialog` | 完整文本、搜索、复制、返回 |
| 直接动作 | 确切结果且无需附加设置 | Button/ActionTile | 禁用前提、加载、真实结果/错误 |

## 二、四项技能落地

1. **finesse-ui（审美与可用性）**：所有页面/卡片/强调背景纯白 `#FFFFFF`，深灰文字 `#263139`，次要文案 `#56646D`，边框 `#D8DEE1`；暖铜 `#805A3F`、青绿 `#246F54`、红 `#AC363A` 只用于字、图标、描边和进度；重要触控盒不小于 48dp。
2. **finesse-brief（工作情境）**：侧栏六个常用入口；设置四组；会话特定操作从会话 ··· 触发；工作区工具不混入附件选择；保持原有 28 个路由用于旧链接与能力核查。
3. **frame-smith（动效）**：卡片按压/边框反馈 110ms，短内容展开 210ms，页面切换 240ms；`UiPreferences.reducedMotion` 让自定义位移与缩放即时结束，仍保留静态按压反馈；进度严格来自真实数据，无伪造完成动画。
4. **finesse-term（文案）**：按钮文字写操作后果，例如“检查并下载更新”“确认调用工具”“确认删除资料”，危险确认披露范围；错误说明修复方向；JSON 字段保留在阅读器或专业模式中。

## 三、落地组件与真实功能

| 组件 / Kotlin 文件 | 对应方法 / 数据源 | 业务时机与状态 |
|---|---|---|
| `EngineStatusSheet`（UiSharedSurfaces） | `bridge.status`、`terminalSession`、`vm.connect` | 顶栏/侧栏状态固定打开面板；连接、诊断、返回终端分别选择 |
| `ModelChoiceSurface` | `model/list` + `modelId` | 对话/默认模型选择共享；成功后更改后续会话 |
| `ModeChoiceSurface` | `collaborationMode/list` + `selectedMode` | 仅显示当前内核支持的计划选项，执行中不切换 |
| `AttachmentActionsSheet` | `LocalImageAttachments.importFrom` | 输入框“+”仅管理三张以内本机图片，不与工具菜单混合 |
| `WorkspaceActionsSheet` | 真实工作区与会话路由 | 文件、Diff、终端、审查、目标、队列；缺少会话先跳会话历史 |
| `ReviewScopePanel` / `ReviewScopeSheet` | `review/start` | 选择审查范围、确认风险、提交 RPC；结果回到任务流 |
| `ThreadGoalSheet` | `thread/goal/get/set/clear` | 当前会话 ··· 操作；目标编辑和清除沿用真实服务调用 |
| `ThreadQueuePage` | `thread/queue/list/add/update/delete/reorder` | 复杂列表保留页，实验能力失败状态保留 |
| `ThreadHistorySheet` | `thread/turns/list`、`thread/items/list`、`thread/revert` | 游标分页、完整条目、明确“从此回合及之后删除”的确认；不会恢复本地文件 |
| `ThreadAttachmentsSheet` | `thread/attachment/list/add/remove` | 当前会话资料与文本备注；准确身份键删除、结果确认后刷新 |
| `ThreadLibraryPage` | `thread/list` / `thread/search` | 同一页标题/全文搜索；分组与会话管理保留 |
| `AccountMetricsPage` | `account/usage/read`、`account/rateLimits/read` | 用量/额度双标签和真实配额信息 |
| `AppUpdateCard` | `AppUpdateManager` | 设置行折叠；真实检查下载、增量校验、进度、取消、系统安装 |
| `McpToolCallSheet` | `mcpServer/tool/call` | 指定服务/工具/会话和 JSON 参数，显式确认后执行 |
| `McpResourceSheet` + `FullTextReaderDialog` | `mcpServer/resource/read` | 短资源预览，长资源全文检索与复制 |
| `PluginDetailSheet` | `plugin/read`、`plugin/skill/read` | 插件元数据及按需技能正文 |
| `PluginSearchContract` + `PluginsPage` | `plugin/search`（实验） | 插件发现页按关键字搜索，携来源安装与详情，真实游标分页、错误提示 |
| `AccountSessionsSheet` | `accountSession/list`、`accountSession/switch`（实验） | 账号面板列出可切换工作区，准确会话/工作区标识确认切换，按需外部 SSO 授权 |
| `ProvidersPage` | `modelProvider/list/default/set/config/write/capabilities/read` | 切默认、保存 customGateway 补丁、查询真实能力 |
| `AboutInfoSheet` | 本机版本、内核状态 | 设置内底部信息层，保留旧页面路由 |

## 四、195 项协议的曝光边界

| 来源类别 | 数量 | 设计结果 |
|---|---:|---|
| 直接操作 | 40 | 由用户明示的业务入口触发 |
| 上下文操作 | 44 | 需要当前会话/文件/服务或审批对象 |
| 高级按需 | 71 | 按当前内核支持能力开放；无服务支持不显示危险执行入口 |
| 后台内部 | 38 | 只展示可读状态、错误或审批反馈 |
| 平台不适用 | 2 | 不出普通 Android 操作入口 |

以下属于不应简单新增执行按钮的场景：`fs/readFile/writeFile/createDirectory/readDirectory/remove/copy` 对应当前 Android 工作区的真实本地文件操作；`thread/items/read` 与 `thread/timeline/list` 为高级按条件读取，已有条目分页和全文阅读能力；`command/exec/resize` 已通过真实 PTY 行列设置进行调用，按运行状态和尺寸校验控制入口；`plugin/search`、`accountSession/list/switch` 等实验能力需完成正式协议字段和当前服务可用性确认后启用。全部保留在 195 项分类中，并未伪装成已支持按钮。

## 五、真实验收清单

- Kotlin 编译、协议检查、UnitTest、Lint、APK 构建与 Android 35 360/393/411dp 全量 UI Test；
- 28 个原业务路由仍存在，30 个功能标识唯一；所有按钮有明确操作结果；
- 登录、模型切换、模式选择、权限、审查、MCP、附件增删和升级均只依赖真实状态；
- 选中会话前禁止直接执行回退、审查、目标与队列；禁用/错误保留解释和返回入口；
- 360/393/411dp：键盘弹出不遮挡发送；Modal 关闭后可返回原上下文；长 JSON 显示全文；
- 系统大字、TalkBack、弱动效、系统安装授权和真实设备登录另外保存独立设备证据。

设计规则保存在本仓内容中，交付不依赖外部设计技能网页或未归档链接。
