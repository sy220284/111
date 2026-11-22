# 111 第二轮 100 项问题 · 逐项执行账本

原始清单：[111-optimization-100-r2-20261122.md](111-optimization-100-r2-20261122.md)。此文件按原文编号逐项追踪，避免重复排查或按 PR 提交数误算完成数量。

> 统计口径：“已提交待专项验收”仅表示存在对应代码/既有实现，仍需逐项确认运行行为；“已有部分改动”表示关键路径尚未闭环；“尚待实施”表示目前未建立完整修复证据。通过本 PR 的独立 UI CI 后，再把有专项证据的项改为“已验收”。

**本次统计（已合并 PR #47、#49）：已专项验收 10 项；有对应实现待专项验收 58 项；部分处理 22 项；待完整实施 7 项；经安全评估跳过 2 项；按明确需求取消 1 项；合计 100。**

当前 PR：[#34](https://github.com/sy220284/111/pull/34)。独立 UI 构建与三屏宽设备验收：[Actions](https://github.com/sy220284/111/actions?query=branch%3Aui%2Fr2-visual-assets-20261122)。

| 编号 | 分类 | 原始问题 | 执行状态 | 直接核对入口 |
|---:|:---:|---|---|---|
| 1 | A | 切会话时 LaunchedEffect(thread) 读到的 items 可能还是旧会话时间线，lastKnownStepCount 基线错→未读数虚增 | 已提交待专项验收 | ConversationScreen.kt |
| 2 | A | 死条件：外层已保证 thread!=null，内层还判 thread==null"新的工作会话"——迁移拷贝残留 | 已提交待专项验收 | ConversationScreen.kt |
| 3 | A | 图片导入双重上限死分支：room 先验后，循环内 current.size<MAX 永真分支 | 已提交待专项验收 | AttachmentImportController.kt |
| 4 | A | 选择器 maxItems 固定 3 张，不随草稿已有张数收缩——已有 2 张时选 3 张被截 1 张+提示 | 已提交待专项验收 | ConversationScreen.kt |
| 5 | A | 图片导入中退出页面：rememberCoroutineScope 取消→导入静默丢失，无任何提示 | 已提交待专项验收 | AttachmentImportController.kt |
| 6 | A | 升级逻辑死锁：调试签名版提示"请先导出数据并安装正式签名版"，但 App 无任何数据导出功能 | 已提交待专项验收 | LocalDataArchive.kt · LocalDataTransferCard.kt |
| 7 | A | 审批等待不保活：前台服务只绑 running，任务停在"等审批"切后台→进程冻结、审批失联 | 已提交待专项验收 | CodexForegroundService.kt |
| 8 | A | 通知状态脱节：Service 不订阅 EngineStatus，内核崩溃后通知仍显示"正在执行任务" | 已提交待专项验收 | CodexForegroundService.kt |
| 9 | A | 内核子进程无优雅关闭：进程被杀时无 shutdownHook/close 路径，codex 内核可能残留孤儿进程 | 已提交待专项验收 | CodexConnection.kt |
| 10 | A | onCleared→bridge.close() 是死代码：Application 级 session 永不 onCleared | 已提交待专项验收 | CodexApplication.kt |
| 11 | A | 非活跃会话无执行/审批标识：侧栏 running=running&&activeId==id，其他会话后台跑任务看不见 | 专项验收通过 | ThreadActivityPolicyTest.kt、UiPopulatedSubpagesInstrumentedTest.kt；状态通知与非当前会话审批/等待回答的 360/393/411dp 测试通过，CI #45015762628 |
| 12 | A | MainActivity.onResume 里 resumeInstall 主线程做安装恢复 IO，轻重未验 | 已提交待专项验收 | AppUpdateManager.kt · CodexApplication.kt |
| 13 | A | stopRequested 以 remember(activeTurn) 键控——点停止瞬间恰逢 turn 换代则 stopping 态闪烁误判 | 已提交待专项验收 | ConversationScreen.kt |
| 14 | A | 批量操作失败时已完成数与保留选择可能不同步（bulkCompleted 清理只含成功项，UI 无二次确认展示） | 已提交待专项验收 | ThreadLibraryPage.kt · CodexViewModel.kt |
| 15 | A | TimelineReconciler 临时 ID 未随 RPC 传服务端——对账只做了客户端一半 | 已有部分改动 | TimelineReconciler.kt |
| 16 | A | 连续两次打开附件菜单选图：pendingImagePick 时序依赖 LaunchedEffect，快速操作第二次覆盖第一次的归属会话 | 已提交待专项验收 | AttachmentImportController.kt |
| 17 | A | currentTitle 从 sidebarThreads+threads 双源快照取——会话改名后标题滞后不刷新 | 已提交待专项验收 | ConversationScreen.kt · SessionRefreshPolicy.kt |
| 18 | A | followTail 判定 total<=1 视为底部——单条会话上滑也算底部，未读逻辑边缘失效 | 已提交待专项验收 | ConversationScreen.kt |
| 19 | B | 图片导入 IO 编排（协程+逐张导入+失败聚合+释放）写在 Composable 内——应下沉 VM | 已提交待专项验收 | AttachmentImportController.kt |
| 20 | B | attachmentBusy/attachmentError 是页面本地态——VM 级状态放 UI 层，页面销毁即丢 | 已提交待专项验收 | AttachmentImportController.kt |
| 21 | B | 会话库 refresh key 动态拼接（archived:searchTerm:section:cursor）在 UI 层——契约应入 VM/contract 层 | 已提交待专项验收 | ThreadLibraryQuery.kt |
| 22 | B | resumeInstall 由 Activity onResume 驱动——安装恢复职责应归 Service/WorkManager | 已提交待专项验收 | CodexApplication.kt |
| 23 | B | 审批 decision 字符串由 UI 直接拼——决策策略层漏到 UI | 专项验收通过 | ApprovalDecisionPolicyTest.kt、ApprovalProtocolTest.kt、UiPopulatedSubpagesInstrumentedTest.kt；单次/整会话/权限范围隔离与无效拒绝按钮验收通过，CI #45015762628 |
| 24 | B | 终端 stdin 行缓冲与 afterSent 编排留在 UI 层 | 已有部分改动 | DeveloperScreens.kt |
| 25 | B | 重连策略只挂 Activity ON_RESUME——Service/WorkManager 级自动重连缺失，后台断线无人管 | 已提交待专项验收 | CodexForegroundService.kt |
| 26 | B | 侧栏数据靠"打开抽屉"触发 thread/list——无 WorkManager 周期同步，后台数据永远滞后 | 已提交待专项验收 | SessionRefreshPolicy.kt · AppNavigation.kt |
| 27 | B | 增量更新 hpatch 合成在 Service 执行但由 UI Card 全程驱动——后台化不彻底，页面离开流程中断 | 已有部分改动 | AppUpdateManager.kt |
| 28 | B | 模型目录/技能目录等 catalog 预热无自动策略——用户进对应页面才拉，首屏等待长 | 已提交待专项验收 | CodexViewModel.kt · EcosystemScreens.kt · CatalogRequestContractTest.kt；连接后预热、避免重复请求、账号刷新失效 |
| 29 | B | 终端导出 64MiB 全量字符串拼接在 UI 层做——内存峰值应流式落盘 | 已提交待专项验收 | TerminalTranscriptStore.kt |
| 30 | B | 会话标题生成 PresentationPolicy.threadTitle 在 UI 多处重复调用——应单点 presentation 层 | 已有部分改动 | PresentationPolicy.kt |
| 31 | B | VM.receive() 仍 250+ 行 if-else 大杂烩——事件分发应表驱动（TimelineReconciler 只接了一半） | 已有部分改动 | TimelineEventReducer.kt |
| 32 | B | 连接策略多点暴露：诊断页/设置页各有手动连接入口——自动重连为主+手动兜底应单点化 | 已提交待专项验收 | ConnectionRestartController.kt |
| 33 | B | 任务完成后的图片清理在 VM receive 协程，但 UI attachmentError 不感知清理结果——状态两处管 | 已提交待专项验收 | CodexViewModel.kt |
| 34 | B | 草稿持久化时机由 UI 输入事件驱动写盘——防抖/合并策略应在持久层 | 专项验收通过 | DraftStorageController.kt · DraftPersistence.kt；350ms 合并写盘、旧文字草稿兼容、持久化恢复；[PR #41 单测/Lint/三屏宽设备验收通过](https://github.com/sy220284/111/actions/runs/45020471975) |
| 35 | B | 首回合拒绝后的空会话清理逻辑（thread/read 确认）埋在 VM 发送链——应独立恢复策略对象并测 | 专项验收通过 | FirstTurnRecoveryController.kt；3/3 单元测试通过 |
| 36 | C | 通知图标用 android.R.drawable.ic_media_play（系统播放三角）——占位符级，语义错位 | 已提交待专项验收 | ic_codex_status.xml |
| 37 | C | 通知无"停止任务"操作按钮——Android 媒体/下载类通知惯例带 action | 已提交待专项验收 | CodexForegroundService.kt |
| 38 | C | 通知无进度信息（当前步骤/步数）——静态"正在执行任务" | 已提交待专项验收 | CodexForegroundService.kt |
| 39 | C | disabled 按钮普遍无 semantics 解释——"新建"执行中灰着不说为什么 | 已有部分改动 | AccessibilitySemantics.kt |
| 40 | C | diff 入口重复两处：顶栏 Difference 图标（语义不直观）+时间线 chip"查看最新改动" | 已提交待专项验收 | ConversationScreen.kt |
| 41 | C | 对话顶栏动作过载：新建+MoreHoriz+diff 三件挤一行，移动端惯例是收敛进溢出菜单 | 已提交待专项验收 | ConversationScreen.kt |
| 42 | C | 配置分型输入仍保留"JSON"类型裸露给普通用户——应藏进"高级"折叠 | 已提交待专项验收 | ControlScreens.kt |
| 43 | C | 升级引导文案指向不存在的操作路径（同 A6）——升级流程不闭环 | 已提交待专项验收 | LocalDataTransferCard.kt |
| 44 | C | 侧栏"执行中"提示项在会话列表中间（item position 随列表走）——不置顶，扫视不到 | 已提交待专项验收 | SidebarSessions.kt |
| 45 | C | 空态快捷卡短视口隐藏（maxHeight<345dp）——小屏用户永远见不到快捷任务 | 已提交待专项验收 | ConversationScreen.kt |
| 46 | C | "新的工作会话"→发首条消息→"当前任务/会话名"标题跳变，命名体系不连贯 | 已提交待专项验收 | ConversationScreen.kt |
| 47 | C | 批量操作确认弹窗不显示影响数量（"归档"vs"归档 12 项会话"） | 已提交待专项验收 | ThreadLibraryPage.kt |
| 48 | C | 进入批量模式无模式切换视觉提示——toolbar 直接出现，无动画/背景变化 | 已提交待专项验收 | ThreadLibraryPage.kt |
| 49 | C | 搜索历史固定 5 条且无清除入口 | 已提交待专项验收 | RecentThreadSearches.kt |
| 50 | C | 永久删除（不可逆）与归档（可逆）用同一确认弹窗语气——危险等级无区分 | 已提交待专项验收 | ThreadLibraryPage.kt |
| 51 | C | 终端输入对危险命令（rm -rf 等）无升级警示——仅通用"可能修改工作区" | 已提交待专项验收 | TerminalCommandPolicy.kt |
| 52 | C | 审批弹层逐项处理无"第 x/N 项"进度指示 | 已提交待专项验收 | ControlScreens.kt |
| 53 | C | 图片附件预览缩略图不可点开大图查看/删除单张 | 已提交待专项验收 | LocalImageAttachments.kt |
| 54 | C | 会话历史无时间分组（今天/昨天/更早）——长列表平铺 | 已提交待专项验收 | ThreadDisplayPolicy.kt |
| 55 | C | 手机抽屉与宽屏常驻侧栏切换时滚动/选择状态不共享 | 已提交待专项验收 | AppNavigation.kt · SidebarSessions.kt |
| 56 | C | 对话页系统返回键直接退出而非回会话库（Android 返回栈惯例，需真机确认） | 已提交待专项验收 | AppNavigation.kt |
| 57 | C | 批量操作"已完成 N 项"Snackbar 4 秒短驻——大批量结果来不及看清 | 已提交待专项验收 | CodexViewModel.kt · ThreadLibraryPage.kt |
| 58 | C | 加载指示器形态不统一：LinearProgressIndicator 与转圈混用，位置各页不一 | 已有部分改动 | InteractionSystem.kt |
| 59 | C | 深浅色切换无预览卡——设置页改完全局生效，用户盲选 | 已提交待专项验收 | SettingsHub.kt |
| 60 | C | 无字体大小偏好设置——系统字体缩放是唯一途径 | 已提交待专项验收 | SettingsHub.kt |
| 61 | D | R8 混淆+资源压缩未开（isMinifyEnabled=false）——143MB APK 瘦身第一刀 | **安全评估后跳过本轮启用** | app/build.gradle.kts · APK_SIZE_SAFETY_ASSESSMENT_20261122.md；缺失完整 ARM64 正式包运行验证与全页面回归 |
| 62 | D | APK 继续瘦身：上游静态资源剔除、per-language split 评估 | **安全评估后跳过侵入式瘦身** | APK_SIZE_SAFETY_ASSESSMENT_20261122.md；保留运行依赖、离线及语言支持 |
| 63 | D | timeline 进程级无界增长（session 长驻后加剧）——分页/窗口化裁剪 | 专项验收通过 | TimelineWindowStore.kt；2/2 长历史与分页单元测试通过 |
| 64 | D | composerDrafts 无上限累积——LRU/超期清理 | 专项验收通过（保留全部未发送内容） | ThreadComposerDrafts.kt · DraftPersistence.kt · LocalImageAttachments.kt · AttachmentRetentionPolicy.kt；200 条草稿保留、图片恢复、正在执行任务附件保护与孤儿清理；[PR #41 完整验收](https://github.com/sy220284/111/actions/runs/45020471975) |
| 65 | D | errors map 无界（动态 key 残留）——TTL/上限 | 已有部分改动 | CodexViewModel.kt |
| 66 | D | LazyColumn key 稳定性审计（id 稳定防全列表重组） | 已有部分改动 | ThreadLibraryPage.kt |
| 67 | D | 图片导入串行 forEach——并行化+进度 | 已提交待专项验收 | AttachmentImportController.kt |
| 68 | D | 图片导入迁 WorkManager——脱离页面生命周期（配合 A5） | 已有部分改动 | AttachmentImportController.kt |
| 69 | D | 终端 stdout 16K 截断改环形缓冲+按需落盘加载 | 专项验收通过 | TerminalTranscriptStore.kt；5/5 流式存储、导出与 UTF-8 分页单元测试通过 |
| 70 | D | 事件流 backpressure 压测（512 buffer 满载行为） | 专项验收通过 | RpcEventBuffer.kt · RpcEventBufferTest.kt；[PR #36 全套 Kotlin/压力测试/三屏宽 CI](https://github.com/sy220284/111/actions/runs/45016904710)、[main ARM64 二次校验](https://github.com/sy220284/111/actions/runs/45017404821) 均通过 |
| 71 | D | 对话页 15 个 collectAsState 订阅重组剖析（derivedStateOf 合并机会） | 尚待实施 | 待定位 |
| 72 | D | 冷启动 Macrobenchmark 基线 | 尚待实施 | 待定位 |
| 73 | D | 启动期 syncRecentThreads 等 IO 后台化 | 已有部分改动 | CodexViewModel.kt |
| 74 | D | CodexConnection 流抽象+MockProcess 单测 | 专项验收通过 | RpcProcessChannel.kt · RpcProcessChannelTest.kt · CodexConnection.kt；[PR #38 Kotlin/MockProcess/三屏宽 CI](https://github.com/sy220284/111/actions/runs/45017467158) 通过，真实 ARM64 核心执行仍由独立门禁覆盖 |
| 75 | D | sendPrompt 全路径状态机测试（成功/半失败/双失败/断连） | 已有部分改动（状态机单测已落地） | [PR #49](https://github.com/sy220284/111/pull/49) 已合并；TurnSubmissionCoordinator.kt、TurnSubmissionCoordinatorTest.kt 覆盖成功、已有会话、明确拒绝、创建失败、超时、回合编号缺失、输入无效；Kotlin 单测与 Android 三档模拟器 CI 已通过，真实 ARM64 核心双阶段断线恢复仍待专项验收 |
| 76 | D | detekt/ktlint 接入 | 尚待实施 | 待定位 |
| 77 | D | Kotlin 警告预算门禁 | 尚待实施 | 待定位 |
| 78 | D | Roborazzi/Paparazzi 截图 diff 基线 | 已有部分改动 | .github/workflows/check-android-ui.yml |
| 79 | D | TalkBack 全 29 页走查 | 已有部分改动 | UiPopulatedSubpagesInstrumentedTest.kt |
| 80 | D | strings.xml 抽取（英文骨架+避免 Compose 内联中文） | 尚待实施（仅保留中文资源统一） | 用户确定只需要中文版；无需英文界面或英文资源骨架。中文资源集中管理仍可择机实施 |
| 81 | D | RTL 布局声明与截图 | 专项验收通过 | AndroidManifest.xml RTL 声明、ThreadBulkToolbar.kt、3 种屏宽均通过的 RTL＋200% 字体实际截图 |
| 82 | D | 深色主题全页回归（VisualSystem 仅 lightColorScheme） | 已有部分改动 | VisualSystem.kt |
| 83 | D | 200% 字体降级策略 | 已有部分改动 | ThreadBulkToolbar.kt |
| 84 | D | dp 文本全量换 sp | 尚待实施 | 待定位 |
| 85 | D | contentDescription 覆盖率 CI 检测 | 已有部分改动 | AccessibilitySemantics.kt |
| 86 | D | 触控热区 <48dp 扫描 CI | 已有部分改动 | ThreadBulkToolbar.kt |
| 87 | D | 更新清单签名链（现仅 SHA-256） | 已有部分改动 | AppUpdateManager.kt · InstallSignaturePolicy.kt |
| 88 | D | networkSecurityConfig 显式禁明文 | 已提交待专项验收 | res/xml/network_security_config.xml |
| 89 | D | backup_rules 数据类别逐项验收 | 已有部分改动 | LocalDataArchive.kt |
| 90 | D | stderr 缓冲威胁模型与清理策略 | 已有部分改动 | StderrSanitizer.kt |
| 91 | D | Renovate 替代 dependabot 误报 | 尚待实施 | 待定位 |
| 92 | D | 上游快照升级演练脚本（MXC 补丁适用性自动验证） | 已提交待专项验收 | rehearse_upstream_upgrade.py · test_rehearse_upstream_upgrade.py · rehearse-android-upgrade.yml；只拉取锁定版本 MXC 提交而非全量 Cargo 依赖，先校验补丁再编译 |
| 93 | D | 11 项设备测试升 release 强制 gate | 已提交待专项验收 | .github/workflows/release.yml |
| 94 | D | changelog 从提交自动生成 | 已提交待专项验收 | .github/scripts/generate-release-notes.py |
| 95 | D | crash 本地收集+诊断页入口 | 已提交待专项验收 | LocalCrashReporter.kt |
| 96 | D | specialUse 前台服务的 Play 上架合规预案 | 按明确需求取消 | 用户确定不在 Google Play 上架；保留本地安装、运行、通知和后台任务能力 |
| 97 | D | START_NOT_STICKY→STICKY+重建重连评估（保活韧性） | 已有部分改动 | CodexForegroundService.kt |
| 98 | D | 通知进度+动作按钮（配合 C37/38） | 已提交待专项验收 | CodexForegroundService.kt |
| 99 | D | 多会话并行执行+任务中心（单 active 限制的产品化放宽） | 已提交待专项验收 | [PR #47](https://github.com/sy220284/111/pull/47) 已合并；ThreadExecutionPolicy.kt、CodexNotificationDispatcher.kt、CodexViewModel.kt、SidebarSessions.kt、ThreadExecutionPolicyTest.kt、UiPopulatedSubpagesInstrumentedTest.kt；Kotlin/Android UI CI 与真实 ARM64 核心多任务待分别验收 |
| 100 | D | 深链 codex://thread/xxx 外部直达会话 | 已提交待专项验收 | CodexDeepLink.kt |

## 本次专项验收证据

- **#11**：官方 `thread/status/changed` 和 `ThreadStatus` 契约已核实；`ThreadActivityPolicyTest` 验证五种可见状态与过期列表覆盖；新增 `audit-sidebar-background-status-android35.png` 截图在 360dp、393dp、411dp 各自的仪器测试中采集，三档验收均通过。[CI 证据](https://github.com/sy220284/111/actions/runs/45015762628)。
- **#23**：`ApprovalDecisionPolicyTest` 验证单次授权、会话授权、人类命令逐次授权、额外权限仅本轮、MCP 类型限制；新增输入问题只允许完整回答的设备测试；三档安卓设备与 Kotlin/Lint 均通过。[CI 证据](https://github.com/sy220284/111/actions/runs/45015762628)。
- **交付门禁**：主分支此前构建已通过但独立 `verify-apk` 作业遗漏检出 `audit_apk_contents.py`，PR #35 加入显式检出；以合并后 main 的 ARM64 完整构建和二次校验结果为闭环依据。

- **[UI 完整验收 #45008981995](https://github.com/sy220284/111/actions/runs/45008981995)**：Kotlin 编译、协议检查、单测、Lint 均通过；Android 35 的 360/393/411dp 设备测试各 41/41 项通过。
- **#35**：`FirstTurnRecoveryControllerTest` 3 项全通过，分别覆盖已拒绝空会话安全清理、非空或已切换会话保护、已启动回合禁止误删。
- **#63**：`TimelineWindowStoreTest` 2 项全通过，覆盖 2060 条历史的磁盘分页与恢复、读回后写入新数据时防重复对账。
- **#69**：`TerminalTranscriptStoreTest` 5 项全通过，覆盖双流输出、容量限制、UTF-8 跨页连续性、跨命令边界访问限制。
- **#61（安全跳过）**：R8 与资源压缩仅有签名 smoke 编译通过，尚缺完整 ARM64 核心、29 页 UI 与资源动态调用的运行回归；当前完整 Codex 原生二进制占主要体积，优先保留已验证的 Release 配置。
- **#62（安全跳过）**：保留上游静态资源、技能、语言数据及原生工具，不扩大原生 strip；正式 APK 强制核查 Server、Code Mode Host、Git、ripgrep 与 hpatchz，参见 APK_SIZE_SAFETY_ASSESSMENT_20261122.md。
- **#81**：AndroidManifest 已声明 RTL；`audit-rtl-200font-bulk-android35.png` 在三种屏宽被强制采集，按真实可滚动容器确认按钮能够到达、可见且触控高度不小于 48dp。[360dp 原始截图包](https://github.com/sy220284/111/actions/runs/45008981995/artifacts/14748699322)。
- 完整 29 页面 TalkBack、其他页面的 200% 字体与 RTL 覆盖，仍按清单 #79、#83、#85、#86 独立推进。

## 验收与推进规则

每项从“已有部分改动”到“已提交待专项验收”，必须给出实际代码入口；从“已提交待专项验收”到“已验收”，必须核对源码行为、单测或设备交互与对应截图，必要时包含性能数据。主线合并与真机安装另外核验，独立 UI CI 成功不能替代原生安装包完整性测试。

**调整后的优先事项**：先完成多会话任务中心（#99）的真实 ARM64 核心并行/断线/审批专项验收，再完善后台恢复与失败状态（#75、#97）、全页面 TalkBack 和无障碍检测（#79、#85、#86）、性能基准（#71–#73）、中文文本资源统一及缩放适配（#80、#83、#84）、工程质量和自有分发安全（#76–#78、#87、#89–#95）。英文界面与 Google Play 上架专项移出需求范围；#61、#62 高风险瘦身维持安全跳过。

本账本只记录 111 UI 清单，未纳入其他 PR 的开发任务。
