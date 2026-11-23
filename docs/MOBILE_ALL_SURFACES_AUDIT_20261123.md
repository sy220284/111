# 安卓全部页面与子页面交互归属审计（2026-11-23）

## 审计范围与依据

对照代码中 29 个 `UiFeatureCatalog` 功能、Shell / 设置 / 会话路由、入口容器及 Android 35 真实模拟器截图。查看了 CI 运行 [45101142808](https://github.com/sy220284/111/actions/runs/45101142808) 保存的 60 张 393dp 截图（包含主页面、弹层、编辑器、审批、RTL 和放大字体），并检查 360dp、411dp 回归记录。原 PR #55 的会话管理、审批、终端布局作为基线。

### 统一判定原则

- **常驻工作区页面**：聊天、总览、文件和终端等需要持续上下文、长时间阅读或操作的位置，保留完整页面。
- **任务附属动作**：搜索历史、任务队列、目标、审批、审查、运行模式等在当前工作上下文中打开弹层，关闭后回到原任务。
- **全局管理功能**：账号、模型服务商、技能、插件、连接应用、MCP、执行环境、权限、安全、诊断等保留在设置子页面，搜索仅查配置与管理内容。
- **局部选择与补充信息**：模型选择、“关于”、外观、使用限额、应用更新沿用选择弹层或所属设置/账号页面，无需创建平行主页面。
- **所有组件**：优先展示操作结果、需要做的决定和下一步；内核协议名、后台字段和重复标题不占据主界面。断连、空白、加载、失败状态要明确且不重复。导航离开后应保留可恢复的查询、草稿和滚动状态。

## 全部 29 个功能的核对结论

| 功能路由 | 所属位置 | 容器 / 交互判定 | 截图证据 |
| --- | --- | --- | --- |
| home | 工作区 | 首页 Page，常驻入口合理 | home-android35.png |
| chat | 工作区 | 对话 Page；输入、流式执行、工具保持上下文 | chat-android35.png、chat-toolbar-android35.png |
| threads | 会话侧栏 | 管理 Sheet；分组、检索、批量管理集中呈现 | thread-library-android35.png |
| approvals | 对话与顶栏 | 审批 Sheet；审批记录作为展开区 | audit-approvals-android35.png |
| search | 侧栏搜索 | **已调整**：打开会话库的全文检索 Sheet，复用同一检索与选中会话流程 | thread-full-search-android35.png |
| goals | 当前任务工具 | 目标 Sheet，保留编辑与状态选择 | audit-goals-sheet-android35.png |
| queue | 当前任务工具 | **已调整**：队列 Sheet，保留新增、编辑、排序、删除及分页 | audit-queue-android35.png（调整后重拍） |
| files | 工作区 | 文件 Page，文件浏览、导入、编辑保留完整空间 | workspace-files-android35.png |
| diff | 任务代码变更 | Reader 完整页面；代码差异适合长阅读 | diff-reader-android35.png |
| terminal | 工作区 | Page；输出优先，运行控制固定于下方 | terminal-android35.png |
| review | 当前任务工具 | 审查范围选择 Sheet | audit-review-sheet-android35.png |
| models | 设置 / 对话参数 | **已统一**：跨页面入口复用模型选择 Sheet | model-sheet-android35.png |
| modes | 当前任务工具 | 执行方式 Sheet，不增加主导航层级 | mode-sheet-android35.png |
| skills | 扩展管理 | 设置 Page；技能启停与列表 | audit-skills-android35.png |
| plugins | 扩展管理 | 设置 Page；已安装/发现、搜索、详情弹层 | plugins-android35.png、audit-plugin-detail-android35.png |
| apps | 扩展管理 | 设置 Page；连接、授权和空态 | audit-apps-android35.png |
| mcp | 扩展管理 | 设置 Page；服务管理与必要的授权请求 | audit-mcp-android35.png、audit-mcp-elicitation-android35.png |
| environments | 主机管理 | 设置 Page；本机状态与外部服务编辑 | audit-environments-android35.png、audit-environment-editor-android35.png |
| account | 账号设置 | 设置 Page；登录、账号和工作区切换 | account-android35.png、account-sessions-android35.png |
| providers | 模型设置 | 设置 Page；服务商表单按需展开 | audit-providers-android35.png、audit-provider-editor-android35.png |
| config | 高级设置 | 设置 Page；常用配置与高级编辑分层 | audit-config-android35.png、audit-config-editor-android35.png |
| security | 权限管理 | 设置 Page；审批、文件范围及权限方案 | audit-security-android35.png |
| usage | 账户用量 | 设置 Page；使用量与限额共享页签 | account-usage-android35.png |
| limits | 账户用量 | Page / 与用量共用页签，子页面保留返回按钮 | account-limits-android35.png |
| appearance | 显示设置 | Inline；在设置页内调整外观 | appearance-android35.png |
| diagnostics | 主机状态 | 设置 Page；连接诊断与故障记录 | audit-diagnostics-android35.png |
| about | 设置信息 | Sheet；跨页面入口复用关于弹层 | audit-about-android35.png |
| settings | 管理入口 | Page；**已调整**搜索仅显示长期配置及扩展 | settings-hub-android35.png |
| update | 通用设置 | Inline；应用内更新、安装回归单独验收 | settings-hub-android35.png |

## 子页面与共用组件核查

- 会话侧栏（搜索、切换、关闭搜索、最近会话、查看全部）、批量操作、全文检索和历史回溯：沿用现有服务器数据及取消/确认交互；保留原 PR #55 的弹层分层。
- 任务菜单、任务目标、执行队列、审查范围、附件、模式和模型：上下文由主工作区持有；无当前会话时先引导选择会话。
- 应用连接、MCP 调用授权与信息收集、插件详情、服务商编辑、外部环境编辑、账号切换：保留对应子页面/弹层，避免混入侧栏常驻导航。
- 账号用量与限额、外观、版本/更新、诊断：各自保留在管理页面；实验能力应有明确状态和下一步。
- 审批历史清理、命令执行前确认、权限范围、高风险操作确认、RTL 与 200% 字体：保留安全语义及现有回归。
- 主 Shell 改为 `rememberSaveableStateHolder`，页面间切换可恢复原来的设置搜索词、编辑草稿和列表滚动位置。

## 已实施的系统性修改

1. Shell 对 `search`、`queue`、`models`、`about` 统一作容器分发；避免因不同入口得到相互重复的完整页面。
2. 会话库增加 `initialFullText`，侧栏放大镜直接打开全文检索模式；完整会话管理仍由“查看全部会话”进入。
3. 任务队列增加独立弹层包装，保留原有完整编辑和执行协议；关闭返回工作区。
4. 设置搜索去除“跨功能搜索结果”，只包含账号、扩展、权限和设置，避免通过设置搜索意外打开任务操作。
5. 统一导航容器、功能归属和设备测试断言；在 Shell 保存各页面的可恢复状态。
6. 复用同一组件和协议调用链，维持 PR #55 已通过的权限隔离、安装器回归与工作区执行逻辑。

## 验收标准

必须以 **最新 Git 提交** 的 CI 为准，至少完成 Kotlin 编译/单测/Lint、Android 35 360dp/393dp/411dp UI 回归，重新生成 `audit-queue-android35.png`、`thread-global-search-android35.png`，检查离开设置子页面返回后搜索内容仍保留。对真实设备的安装升级验收仍按原流程独立执行。

## Figma 对齐情况

目标 Figma 文件 `K4FEMoNdmQbcnJZCHWr8c7`。本次创建工作会话后调用元数据读取，服务器明确返回 **Starter 计划 MCP 调用额度已耗尽**；whoami 确认当前连接为 Starter View 席位。无法读取原画板节点或执行更新，不能声称 Figma 已同步。以上页面归属与组件选择是后续设计同步的唯一工作清单，代码和真实 Android 截图是当前可复核依据。

## PR #55 交互闭环补充核实（2026-11-23）

- **选择会话后继续原动作**：快捷工具保留 `queue`、`goals`、`review`、`diff` 目标，若当前没有会话则进入会话库；仅在 `chooseThread` 完成真实 `thread/read` 和 `thread/resume` 后触发原动作。取消选择则清空待续操作，返回原工作区。
- **草稿与页面状态**：会话库、任务队列、目标和审查复用 Shell 的 `SaveableStateHolder`，按会话保存草稿；确认和删除仍遵守实际请求结果。升级前恢复出的 Sheet 路由统一由 Shell 重新分发。
- **重复错误根因**：队列与任务目标改用 `PresentationPolicy.visibleErrors`，以用户实际可见的错误文案去重；相同的内核断连原因只提示一次。会话分组错误同样按可见文案判重。
- **导航归属**：使用限额作为账号数据页面的另一入口与“用量”共享页签，保留完整页面返回导航。
- **新增回归**：无会话点击队列必须出现“选择会话后继续：任务队列”；取消回到原页；输入队列草稿、关闭并重新打开后文字保留；三个屏幕尺寸继续跑现有 UI 截图与协议验收。
- **验收证据**：此前 45104299871 的 51 项设备测试已通过。新提交以最新工作流与新增的测试结果为准；Figma 目前仍受 Starter MCP 限额限制，画板同步状态保持“未完成”。
