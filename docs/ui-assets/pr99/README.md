# PR99 新增能力 · 手机端 UI / 组件 / 交互设计

设计基线：[main b6134d7e](https://github.com/sy220284/111/commit/b6134d7eef99fe49b85689d8d46fc8d112cc9edc)（PR #99）。
本目录属于设计与实现交接源，**不宣称 Android 已按新画板完成改版**。既有 Compose 功能仍保持原有实现。

## 设计查看顺序

- [会话工作台与新建任务](01-workbench.svg)
- [模型目录、选择与恢复 1](02-model-catalog-1.svg) / [2](02-model-catalog-2.svg)
- [国内模型配置与授权 1](03-domestic-provider-1.svg) / [2](03-domestic-provider-2.svg) / [3](03-domestic-provider-3.svg) / [4](03-domestic-provider-4.svg) / [5](03-domestic-provider-5.svg)
- [账号默认声音](04-account-voice.svg)
- [MCP 可用目录与启动诊断](05-mcp-diagnostics.svg)
- [Hook 检查 1](06-hook-states-1.svg) / [2](06-hook-states-2.svg)
- [内核自动恢复](07-runtime-recovery.svg)
- [多任务 1](08-multitask-1.svg) / [2](08-multitask-2.svg)
- [会话排序与筛选 1](09-session-sort-1.svg) / [2](09-session-sort-2.svg)
- [文档、图片全屏预览](10-file-preview.svg)
- [生成图片与元数据](11-image-generation.svg)
- [深色与手机宽度适配 1](12-adaptation-1.svg) / [2](12-adaptation-2.svg) / [3](12-adaptation-3.svg) / [4](12-adaptation-4.svg)
- [共享状态组件](13-component-states.svg)
- [完整交互图谱](14-interaction-atlas.svg)

[可点击交互原型](prototype.html) 为自包含 HTML；下载后使用浏览器打开。手机画面内的操作区域可点击、键盘聚焦和 Enter/空格触发；右侧事件面板模拟账号和内核回执，**不属于真实 Android 控件**。原型中的示例模型、声音、任务、耗时和生成信息均为设计样例。原型适合评审页面归属、状态转换及返回路径；真实搜索、模型调用、身份授权、文件操作仍由原生应用实现和验证。

[设计清单](manifest.json) 记录 97 个可见手机设计画面、额外 65 个原型组合状态、738 个点击区域、10 条主流程、51 个场景回执选项。25 个 SVG 采用独立文本节点与矢量形状，可导入 Figma，保留命名组和同源主题；导入后应复核文本、组件化及原型连线。现有在线画板 [111 Codex Android](https://www.figma.com/design/K4FEMoNdmQbcnJZCHWr8c7/111) 仍是目标文件，本次 Figma MCP Starter 调用额度耗尽，浏览器又提示 WebGL 不支持，在线画板未能写入或回读截图。

## 1. 产品结构与信息来源

常驻工作对象为当前会话、工作区及正在执行的任务。用户提供草稿、选择与审批；官方内核提供真实目录、状态、回执和执行结果；本机提供文件读取、存储权限及进程生命周期。选择、读取、验证、写入、完成是不同状态。未取得有效回执时保留旧选择和输入。设计不增平板或横屏，不增加底栏，不增加企业/实验能力入口；PR99 已有账号声音等能力继续按现有可用性门禁展示。

| 需求 | 入口和容器 | 主要组件 / 文件 | 实际状态来源、操作和完成条件 |
|---|---|---|---|
| 官方模型目录刷新 | 对话输入框锚定 280dp 菜单；新会话可选择服务商 | `UiSharedSurfaces.kt: ModelChoiceSurface`、`ComposerTaskOptions.kt`、`ComposerProviderScope.kt` | 当前 provider 的 `model/list(forceRefresh=true)`；隐藏项不进入选择，失败保留旧选择；模型和思考强度按真实目录校验；历史会话 provider 固定 |
| 国内模型 | 设置 → 模型服务商 → 配置/目录 Sheet → 确认 Dialog | `EcosystemScreens.kt: ProvidersPage`、`DomesticCatalogSheet.kt`、`DomesticProviderController.kt`、`DomesticProbeContract.kt` | `modelProvider/list`、配置写入/回读、重新连接、`model/list`、`modelProvider/model/verify`；候选不等于授权；仅真实双轮工具回传及模型身份确认后准入，费用先确认 |
| 账号默认声音 | 账号设置 → 声音选择；实时语音界面提供账号设置入口 | `AccountVoiceSettings.kt`、`RealtimeVoiceSheet.kt` | `account/voiceSettings/read` / `write` / 回读一致；会话声音无单独覆盖；账号切换或连接代次变化丢弃旧回执 |
| MCP 启动耗时 | 扩展能力 → 只读可用目录 → 服务详情；失败记录在诊断页 | `IntegrationScreens.kt: McpServicesPage`、`McpStartupReceipt.kt` | `mcpServerStatus/list` + 原生 status 通知；仅真实 ready/connected 可用服务进入目录；起始、就绪、失败、取消分开，耗时字段缺失不显示；配置与授权交智能体 |
| Hook 执行检查 | 高级设置已有用户规则；任务时间线执行详情 | `HookPolicySettings.kt`、`HookRunPresentation.kt` | 真实规则、版本、信任和可写性；异步检查不允许阻断；running/completed/failed/blocked/stopped 来自原生结果，失败与阻断分开；真实耗时/原因按返回展示 |
| 内核自动恢复 | 对话内运行状态入口 → 运行状态 Sheet → 诊断与日志 | `RuntimeStatusPresentation.kt`、`RuntimeSupervisor.kt`、`EngineStatusSheet` | status/retiring/restarting/retry；等待间隔、回收、初始化、连接按真实阶段显示，无虚构百分比；恢复连接后再开放任务；未完成指令不自动重发 |
| 会话排序 | 单一抽屉 → 排序筛选 Sheet | `SessionTaskControls.kt`、`SidebarSessions.kt` | SessionSort 最近活动、更新、创建时间；排他单选即时生效，完成关闭；保留搜索、滚动、工作区、归档状态 |
| 多任务 | 同一抽屉内的进行中入口和状态筛选 → 定位原任务 | `SidebarSessionRow`、`GlobalWorkSnapshot.kt`、`WorkspaceTaskOperations.kt` | ThreadActivity、真实审批/回答请求；执行中、待审批、待回答、异常单独显示；按会话与回合停止，二次确认只停止选定任务；其他会话不受影响 |
| 文件预览 | 对话附件或任务建议 → 全屏阅读 | `AttachmentPreviewDialog.kt`、`ModelDocumentPreview.kt`、`ModelPreviewController.kt` | 本机读取和真实失败；文档标题/行号/匹配定位、图片换图和缩放；返回保留任务与草稿；模型建议预览内容不回传给模型 |
| 图片生成元数据 | 时间线图片产物 → 原图/执行详情 | `NativeTimelineMedia.kt`、`ImageGenerationPresentation.kt` | transparentBackground 明确存在才显示背景类型；imageMetadata 非空才显示数量和原始记录；不推测模型、种子、尺寸、耗时；缺失图片显示原因及可恢复动作 |

国内模型登记的六类端点全部使用独立授权表单：DeepSeek、GLM、MiniMax、Kimi 开放平台、Kimi Code 国内订阅、通义千问；通义千问展示北京业务空间 ID 和只读自动生成的官方地址，切换空间需匹配密钥；Kimi Code 与开放平台凭据分开。自定义服务商填写环境变量名称而非密钥值；已配置服务商提供真实能力详情、默认切换确认及本机密钥移除回读。

MCP 服务固定启动目录属于底层能力；设计仅在既有专业诊断表达实际启动记录和范围，不增目录配置页面。后续 Compose 实现时复核共享工作区、cwd 及连接代次。

## 2. 共用面板与组件规范

- 手机 360/393/411dp；横向间距 16dp，控制半径 13dp、卡片 16dp、面板 24dp；完整交互区域至少 48dp，图标 18/24dp。长内容使用全屏 Page，轻量选择使用按钮锚定悬浮菜单，复杂编辑使用带关闭按钮的 Sheet，风险确认使用 Dialog。原型展示 393dp 主稿、全部十项业务的深色代表画面、360/411dp 窄宽布局和三个 200% 字号自然增高示例。
- 色彩直接使用上级 `tokens.json`（与 `VisualSystem.kt` 一致），常态画面白/墨，主按钮墨色实心、次按钮中性描边、危险操作只在明确后果的最终确认使用红色实心。珊瑚色用于活动变化，翡翠用于真实成功，铜/棕用于等待用户操作，红用于失败或风险。图标与文字共同表达状态。
- 复用 `CodexFloatingMenu`、`CodexDetailSheet`、`CodexConfirmDialog`、`CodexPrimaryButton`、`CodexSecondaryButton`、`AtlasChoiceRow`、`AtlasOperationProgress`、`CodexRecoveryState`、`StatusPill`、`CapabilityFeedback`；不为每个接口创造新的通用按钮。
- 模型状态／声音状态／MCP 回执／Hook 回执／运行恢复／文件预览共用读取中、正常、有效空结果、错误与重试语义；调用身份、连接代次、会话、工作区或源文件变化时重新获取真实信息，旧回执不覆盖新页面。
- 最前层聚焦当前操作，长说明与原始字段进入二级详情；模型候选名单、已选状态、配置状态、验证通过状态互不混淆。排序和任务状态筛选分别独立保存。
- 字号 200% 时允许行高、标题、按钮自然增加、列表滚动；不要缩小字号来避免换行。弹层不得滚动穿透，主操作随 IME 和系统导航安全区避让；TalkBack 将图标动作朗读成具体中文含义。图片有双指缩放，同时保留可点击放大、缩小、复位等辅助操作。

## 3. 动效交接

全部使用项目既有 `CodexMotion` 和系统动画偏好，不引入视频、WebView 或额外运行时依赖。

| 事件 | 动画与持续时间 | 状态触发 / 完成原则 |
|---|---|---|
| 按钮按压/释放 | 100 / 140ms，细微压缩、原位还原 | 手势，禁用/忙碌态不重复触发 |
| 模型/筛选/声音选项 | 180ms 勾选或状态文字淡变 | 本地选项更新；账号声音仅回读确认后勾选 |
| 模型锚定菜单 | 200ms 跟随锚点淡入与轻微缩放 | 保持输入和原选择，外部点击/返回关闭 |
| Sheet 打开/关闭 | 320ms 原生面板过渡 | 退出后只提交一次关闭回调，返回原对象 |
| 长阅读器/图片 | 页面 280ms；缩放 220ms | 缩放以手势/按钮实际比例；换图复位，退出恢复原位置 |
| 回执与诊断内容展开 | 240ms 显隐、尺寸自然过渡 | 有真实内容才展开；未返回字段不虚构 |
| 操作成功 | 450ms 图标/轻量反馈收敛 | 以实际写入回读、验证或内核回执确认 |
| 运行中与自动恢复 | 状态阶段 180ms 更新；不确定进度使用原生指示 | 不填充虚构进度条；阶段取真实事件 |
| 系统减弱动效 | 自定义过渡为 0ms | 所有状态、焦点、返回动作与普通模式一致 |

## 4. 验收与实现入口

1. 基线 [CI #45547425113](https://github.com/sy220284/111/actions/runs/45547425113) 四作业均 success，Android 35 三档手机截图产物已生成。393dp 原始设备截图经人工视读：模型、服务商配置、账号（未登录）、MCP 详情、时间线（断开）、内核状态、任务筛选、文档及图片预览；393dp 仪器日志显示 140 tests。未登录、未连接及测试数据画面只证明相应状态的设备呈现。
2. 新设计 SVG 已逐份解析、渲染并复核；原型状态跳转通过本地 JavaScript 事件测试。Figma 在线写入和回读未完成；Android 运行、新设计的真机视觉与真实服务端回执须待实现 PR 验收，不能沿用旧版 CI 作为新版通过证据。
3. 更新单一生成源 `android-app/tools/generate_pr99_ui_design.py` 后执行：

   ```sh
   python3 android-app/tools/generate_pr99_ui_design.py
   node android-app/tools/test_pr99_ui_prototype.js
   ```

   生成器验证每个点击区尺寸、边界、目标状态及事件目标；禁止手工分叉 SVG、原型、状态清单。按真实 Compose 改动重新生成设计源与验收截图。变更影响协议归属时仍由 `ui_surface_policy.json` 生成权威接口映射。

4. 后续实现逐项以主线 `AGENTS.md`、接口台账、以上对象及状态映射为准。先复用共享组件和实际状态来源，再实现专属交互，跑 Kotlin 编译、Lint、协议/单测、Android 35 三档设备截图、深色、200% 字号、键盘和减弱动效；涉及账号、MCP 或模型验证的真实专项另有独立证据。在线 Figma 恢复写入后导入本次图层、组件化为本地变量/实例并回读截图。
