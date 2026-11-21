# 111 Android UI 全页面审计与 CI 截图验收索引（2026-11-21）

## 范围和证据

本次审计以 GitHub PR #26 的 Android 源码、实际 UI 测试路径和 Android 35 模拟器截图为依据，逐一覆盖功能目录中全部 29 个注册路由，以及对话、审批、插件、MCP、工作区和历史操作的核心子页面与弹层。截图通过真实 Compose 页面与系统窗口捕获；需要内核在线的数据由独立、明确标注的**协议形状测试样本**验证填充态。样本验证布局和数据呈现，业务 RPC 正确性另由协议测试、运行核心集成测试负责。

功能入口来源：`UiFeatureCatalog.kt`、`NavigationPolicy.kt`；页面分发来源：`Screens.kt`、`AppNavigation.kt`；实际截图与严格检查来源：`UiNavigationInstrumentedTest.kt`、`UiPopulatedSubpagesInstrumentedTest.kt`、`CodexOverlayInstrumentedTest.kt`、`.github/workflows/check-android-ui.yml`。

## 逐页检查与处置（共29个路由）

| 页面或路由 | CI 截图名（省略 -android35.png） | 重点检查与处理 |
| --- | --- | --- |
| 首页 home | home | 仅保留可行动的快捷入口；运行状态采用中文；新建任务在工作流优先位置 |
| 任务对话 chat | chat、chat-toolbar、attachment-sheet | 输入、模式、模型、附件和真实任务工具保持相邻；本地消息与服务端事件合并；隐藏回合 ID |
| 会话历史 threads | thread-library | 可搜索、继续、归档、重命名；名称为空时显示语义标题；本机目录显示为工作区名 |
| 待审批 approvals | audit-approvals、audit-permission-request、audit-mcp-elicitation | 区分命令、文件、权限和外部服务；按官方 AdditionalPermissionProfile 显示明确授权范围 |
| 会话搜索 search | thread-full-search | 搜索结果与会话历史归于同一入口，不单独占用底部导航 |
| 任务目标 goals | audit-goals-sheet | 隶属于当前会话，明确目标、状态、预算及保存、清除操作 |
| 任务队列 queue | audit-queue | 新增、编辑、排序、删除及分页保持业务接口；删除确认不显示原始队列 ID |
| 工作区 files | workspace-files | 相对项目路径可见；应用私有存储根目录隐藏；文件编辑与新建功能保留 |
| 差异阅读 diff | diff-reader | 按文件展示改动和审查入口，空状态明确；实际文件名仍供开发者辨认 |
| 终端 terminal | terminal-confirm | 准确显示待执行命令及风险，私有目录、内部进程标识不再展示 |
| 审查 review | audit-review-sheet | 审查范围、二次确认及结果进入任务流；不展示响应 JSON 和回合 UUID |
| 模型 models | model-sheet | 可用模型、默认选择与思考级别使用用户可读名称 |
| 协作模式 modes | mode-sheet | 仅允许已支持的模式并保留运行中约束 |
| 技能 skills | audit-skills | 名称、启停、技能说明可见；安装缓存路径与协议字段不进入列表 |
| 插件 plugins | plugins、plugin-search、audit-plugin-detail | 安装目录、发现、能力详情与技能阅读；底层 JSON 从展示界面移除 |
| 连接应用 apps | audit-apps | 区分发现、启用、已授权及可调用；缺失时可到插件管理；不直接输出 app/read |
| MCP mcp | audit-mcp、audit-mcp-tool-form、audit-mcp-resource | 服务状态、工具和资源可读；按 inputSchema 生成参数表单，复杂结构按需编辑 |
| 运行钩子 hooks | audit-hooks | 启用/停用状态、触发事件及来源；缓存路径、key 和内部信任值隐藏 |
| 执行环境 environments | audit-environments、audit-environment-editor | 执行环境连接与配置操作保留；隐藏原始响应和应用私有目录 |
| 账号 account | account、account-sessions | 登录、浏览器验证、账号切换、注销；内部会话 ID 不作为标题 |
| 服务商 providers | audit-providers、audit-provider-editor | 显示服务商名称而非内部 ID；新增、默认设置和配置入口保留 |
| 引擎配置 config | audit-config、audit-config-editor | 日常设置分流到权限、服务商、模型；专家自定义编辑收起，并保留确认与真实写入 |
| 权限与安全 security | audit-security、audit-security-profiles | 生效规则、审批级别及可用权限方案中文化；网络和文件新增授权逐条解释 |
| 使用量 usage | account-usage | 数据与费用分组显示，去除原始 account/read JSON |
| 使用限额 limits | account-limits | 额度、占比、重置时间易读，内部状态代号转为结果 |
| 外观 appearance | appearance | 外观与无障碍设置归于设置，不再通过底部栏分流 |
| 诊断 diagnostics | audit-diagnostics、engine-status | 主界面先展示连接状态和排查动作；启动日志需主动展开，私有目录隐藏 |
| 关于 about | audit-about | 应用版本、核心来源、许可信息与平台条件集中呈现 |
| 设置 settings | settings-hub、sidebar | 仅侧栏一个主导航；二级配置按功能分组，保持搜索和真实返回路径 |

## 子页面和复合交互

| 子页面/弹层 | 截图 | 关键验收 |
| --- | --- | --- |
| 会话工具 | audit-thread-context | 分支复制、上下文整理、目标、队列、历史、附件、审查入口均真实关联当前会话 |
| 回合历史 | audit-thread-history | 按回合序号及状态呈现，保留安全的回退确认 |
| 实验时间线 | audit-advanced-timeline | 按事件顺序和文字摘要呈现，后台位置与回合标识隐藏 |
| 会话关联资料 | audit-thread-attachments | 备注名称和正文、删除前确认；后台 identityKey 保留于请求参数 |
| 代码审查 | audit-review-sheet | 依托当前会话选择准确审查范围，结果回到任务流 |
| 任务目标 | audit-goals-sheet | 真实目标状态和可编辑任务信息 |
| 额外权限请求 | audit-permission-request | 权限结构精确解析，阅读/修改/联网分开，减少盲目批准 |
| MCP 动态表单 | audit-mcp-tool-form | 按 JSON Schema 渲染必填项、布尔与枚举；高级编辑折叠，敏感字段不显示值 |
| MCP 资源阅读 | audit-mcp-resource | 用户直接阅读资源正文，后台 URI 与响应 JSON 隐藏 |
| MCP 请求用户填写 | audit-mcp-elicitation | 依据服务端定义生成字段并保留用户明确提交 |
| 插件详情 | audit-plugin-detail | 安装状态、能力清单、插件技能阅读和刷新；隐藏内部 ID 与原始协议数据 |
| 服务商编辑 | audit-provider-editor | 连接地址、账号和配置操作有清晰字段与保存动作 |
| 配置专家编辑 | audit-config-editor | 收起复杂字段，只有主动展开时进行 JSON 编辑与确认 |
| 环境编辑 | audit-environment-editor | 执行服务连接、添加、移除有明确目标及说明 |
| 权限方案 | audit-security-profiles | 所有支持的策略及是否允许由运行核心反馈 |
| 关于信息 | audit-about | 单独详情，提供返回路径 |
| 全局工具/附件 | tools-sheet、attachment-sheet | 加号与工具入口汇合，弹层先关闭再切换目标，避免两张弹层互相覆盖 |
| 屏宽适配 | 全部截图以 360 / 393 / 411dp 三档分别捕获 | 无手机业务底部导航；系统原生返回手势与导航栏继续正常工作 |

## 贯穿所有页面的统一规则

1. **导航**：主导航只存在于侧栏，手机点左上角打开，宽屏常驻；任务动作在任务上下文中，长期配置在设置中。
2. **展示边界**：`PresentationPolicy` 统一处理 `null`、未知状态、私有目录、UUID、原始消息类型、权限代号。涉及安全确认的命令正文、确切文件操作仍完整呈现。
3. **弹层切换**：会话工具和工作区工具先完成收起动画，再打开下一个弹层或页面；防止旧抽屉盖住新界面。
4. **实际数据**：对话、会话、文件、插件、技能、MCP、账号等现有业务调用保持原样。无内核环境的截图属于离线空态；填充态屏幕明确使用测试样本，不能替代真实服务执行验收。
5. **自动验收**：50 张截图均纳入 CI 必需检查，涉及输入表单、子弹层、审批等 UI 自动测试；并执行 Kotlin 编译、单元测试、Lint 和本机核心构建流程。
6. **Figma**：设计文件 `K4FEMoNdmQbcnJZCHWr8c7` 已有手机 10 屏、宽屏 1 屏作为风格基线；补齐剩余页面需按本索引与实际 CI 截图同步。Figma 当前 Starter MCP 调用上限阻止了本次进一步写回，待连接恢复后可一次性按路由映射批量同步，无需重新审计。

验收链接：PR [#26](https://github.com/sy220284/111/pull/26)，其「检查」页有代码编译与设备截图报告；以最新 PR 提交的工作流结论和产物数量为最终依据。
