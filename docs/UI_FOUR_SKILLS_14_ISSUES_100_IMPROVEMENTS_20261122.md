# 111 Android · 四设计技能与「14问题/100建议」核查记录
> 源清单：用户上传《111 问题清单 + 100 条优化点》，审计旧基线 `240acf08`，2026-11-22。对照对象：PR #30 `ui/chatgpt-inspired-mobile-shell-20261122`。该清单的「100 条」是改进建议，并不等同已存在的 100 个故障。

## 四技能的作用边界

1. **UI 设计**：控制层次、间距、可读性、状态表达、视觉一致性；统一 Compose 组件及视觉 token。
2. **工作台架构师**：在主工作流优先的布局中落实「聊天/工作、侧栏、历史、工具、审批」关系；页面链接必须通到真实功能。
3. **视频审美动效**：提炼节奏/减弱动效/变化反馈的标准；原生界面沿用简短动画与 reducedMotion，避免无业务意义的视频化交互。
4. **术语**：用户语言替换协议术语；失败提示提供下一步行动，危险操作说清作用范围。

## 14 项原始问题逐条复核

| 编号 | 原始问题 | 本次处理与代码证据 | 验收边界 |
| --- | --- | --- | --- |
| 1 | P0 后台长任务因 ViewModel 生命周期中断 | `CodexApplication.kt` 持有会话、`CodexForegroundService.kt` 保留前台运行通知、Activity 复用同一实例；Manifest 注册必要服务权限 | 锁屏/厂商省电策略仍须真实手机核验 |
| 2 | thread/read/resume 45 秒超时 | `CodexConnection.kt` 为读取与恢复设置 120 秒，其他调用保持有界 | 模拟器和大历史数据验证 |
| 3 | 回前台无自动重连 | `MainActivity.onResume` 调用 `reconnectIfNeeded()`；连接重建后重新读取当前线程 | 网络/子进程异常组合需验 |
| 4 | 发送失败遗留乐观气泡、空壳会话 | `CodexViewModel` 发送失败按临时 ID 回撤气泡；首次回合遭明确拒绝时调用 `thread/read` 确认空会话后清理，草稿归还原会话；超时状态保留待核实 | 真实网络超时与断连组合仍须真机验收 |
| 5 | 同文消息按文字对账 | `TimelineReconciler.kt` 按服务端 ID 或独立 pending 标识匹配，新增同文单测 | 消息事件乱序压力需验 |
| 6 | 10 张图全部拒绝 | `ConversationScreen.kt` 用系统多图选择器，最多三张；容量不足或个别导入错误时保留已成功图片 | 不同相册提供方行为需验 |
| 7 | Screens.kt 持续膨胀 | 将 `ConversationPage/TimelineStepCard` 抽为 `ConversationScreen.kt`，`Screens.kt` 884 行缩至约 404 行 | 编译与页面路由审计 |
| 8 | 上游 MXC 升级税 | `prepare_android_mxc.py` 当前按固定版本和三个调用点严格校验，遇变化会显式失败；自动升级演练与适配仍缺 | 保持已知固定版本直到有可复核升级演练 |
| 9 | 终端 PTY 语义与移动交互 | 终端新增 ↑/↓ 历史命令导航，按原样恢复未发送内容；输出按事件顺序写入本机私有存储，提供标准/错误输出导出（64MiB 上限） | PTY 真机交互、长输出设备测试待验 |
| 10 | 动态 errors/data 键增长 | `CodexViewModel` 对 library/fullSearch 动态查询缓存限制 32 个，移除历史门票及临时状态 | 大量翻页与错误并发测试 |
| 11 | 运行中允许删除分组 | `ThreadLibraryPage.kt` 对编辑、删除、确认添加运行/切换中安全守卫 | 动态状态切换设备测试 |
| 12 | 高级配置要手写 JSON | `ControlScreens.kt` + `ConfigInputParser.kt` 提供文本、开关、数字和高级 JSON；审批/沙箱另有专用表单 | 具体 configRequirements 的全字段引导仍可扩充 |
| 13 | knownethod 拼写问题 | 当前 `ActionFeedbackCopy.kt` 已用 `known` 映射，无所述残留 | 源码静态检查 |
| 14 | RPC 运输分流缺测试 | `RpcEnvelopeRouter.kt` 抽离消息分类并接入运行层，单测覆盖普通响应、审批/输入、未知主机请求、结束通知 | 原生 Android 设备测试仍要复核 |

## 100 条建议如何转为可复用的体验验收

- **B 可靠性**：优先保证任务连接、草稿保存、首次/多次发送、会话恢复及动态检索状态。长任务在通知栏提供可返回入口。
- **C 交互体验**：从截图入手解决输入、附件、错误重试、会话骨架屏、终端输出/历史，以及分组操作冲突。
- **D/E 视觉与无障碍**：统一非侵入式空态、轻边界、48dp 触控面积和高对比可读主色；独立会话页降低整体组件耦合；重度无障碍、200% 字体、折叠屏和深色模式属于单独验收维度。
- **F/G 性能与安全**：历史会话 `LazyColumn`、32 键查询缓存上限、已存在的 Android 私有空间与加密传输约束。完整 APK 精简和发布签名链需通过构建/安全专项验证。
- **H/I 测试与发布**：借助两个 GitHub Actions 工作流，分别进行 Kotlin/协议/Lint/三屏宽设备截图及 ARM64/x86_64 原生二进制、APK 验证；新增草稿、消息、配置和 RPC 纯逻辑单测，新增前台服务声明设备测试。
- **J/K 长期演进**：多会话并行、插件和技能安装、通知与桌面快捷方式等属于产品功能设计，涉及新后端能力时需要单独接口契约和真实运行场景检验。

## 重要边界

本次重构的主线是「能顺手使用的原生 Codex 安卓界面」，不从界面层凭空添加尚无服务支撑的语音、云端同步和多会话并行功能；其他 100 条建议按是否已有实际能力、是否能验证来判断，不以列清单代替代码与设备验收。

Figma 现有目标：`K4FEMoNdmQbcnJZCHWr8c7`。官方连接返回 Starter MCP 工具调用额度上限，故目前只更新 GitHub 的布局契约和 Compose 实现，原始 Figma 文件尚未写入。组件、颜色、尺寸和状态命名与 `UI_CHATGPT_REFERENCE_LAYOUT_20261122.md` 保持一致。

## 第二轮逐项复核 · 原始 100 条全部列账

核查时间：2026-11-22。对照用户原始文档逐条编号，证据版本：PR #30 `eccccd0826a87cd84241e6d5c99e9e84b63066ab`。状态采用四类：**完成**＝明确找到现有源码实现（仍以专项验证范围为界）；**部分**＝已有基础功能但距离原建议尚有差距；**未完成**＝在此次已检查的相关页面、流程、测试及配置中未找到完整落地；**待验**＝已找到实现路径但缺针对性运行证据。

本次复查统计（代码与待验状态同步至 PR #30 最新改动）：完成 21，部分 27，未完成 43，待验 9。 源码覆盖门禁通过不等于所有功能端到端通过，以下每项保持独立结论。

| # | 状态 | 原始建议（摘要） | 代码核查 / 尚缺事项 |
|---:|---|---|---|
| 1 | 完成 | 进程级共享核心会话 | CodexApplication.kt 持有同一 CodexViewModel；锁屏持续性仍需真机验 |
| 2 | 完成 | 回到前台自动重连 | MainActivity.onResume → reconnectIfNeeded |
| 3 | 部分 | 长会话超时支持可配置且提升至 90–120 秒 | CodexConnection.kt 已调整到 120 秒；尚未提供用户可配置项 |
| 4 | 待验 | 超时 deferred 对账与过期响应清理 | CodexConnection.kt 有 waiting/关闭清理；缺超时叠加专项回归 |
| 5 | 部分 | 断线保留时间线并恢复会话 | connect() 在非运行状态 chooseThread；运行中断线的恢复链仍待验 |
| 6 | 待验 | chooseThread 失败回滚测试 | 实现先读取后恢复、成功才切换；尚缺覆盖失败组合的专门设备回归 |
| 7 | 完成 | 发送失败撤回本次临时气泡 | CodexViewModel.sendPrompt 按 pendingId 回撤 |
| 8 | 部分 | 客户端消息 ID 与服务端 ID 对账 | TimelineReconciler 按独立 ID；临时 ID 尚未随 RPC 传给服务端 |
| 9 | 完成 | 回合完成清理 activeTurn | CodexViewModel 的 turn/completed 清理并结算 UI 状态 |
| 10 | 完成 | 动态请求错误键上限 | CodexViewModel 的 dynamicRequestHistory 最多保留 32 键 |
| 11 | 待验 | LatestRequestGate key 命名空间 | 已有门票和单测；跨页面键冲突专项验证未闭环 |
| 12 | 待验 | close→connect 重入专项测试 | 当前连接重建路径存在；重入压测未见证据 |
| 13 | 完成 | 发送失败一键重试 | ConversationScreen 提供重试发送入口 |
| 14 | 完成 | 系统选择器限制三张图片 | PickMultipleVisualMedia(MAX_FILES=3) |
| 15 | 完成 | 图片超额保留已成功导入并提示 | ConversationScreen 按剩余名额导入，跳过/失败给出数量反馈 |
| 16 | 完成 | 切换会话骨架屏 | ConversationLoadingSkeleton.kt |
| 17 | 完成 | Diff 展开状态记忆 | DiffExpansionPreferences 在本机保存会话/差异指纹与展开集合；DiffReaderPage 离开页面后仍能恢复状态 |
| 18 | 待验 | 终端 ↑ 键回溯历史命令 | TerminalHistoryNavigation.kt + DeveloperScreens.kt 已处理硬件 ↑↓ 及草稿恢复，TerminalHistoryNavigationTest 覆盖往返和边界；最新 CI 设备验收待确认 |
| 19 | 部分 | 终端输出复制和长按自由选择 | 已有复制与展开；长按全文自由选取专项体验未核实 |
| 20 | 完成 | 审批弹层会话级记住命令 | ControlScreens 的 ApprovalRequestCard 已有本会话持续允许的二次确认，调用 answerApproval(..., acceptForSession)；ApprovalProtocol 仅允许普通命令和文件审批使用，humanCommand 禁用；是否由内核按匹配规则复用需真实模型端到端测试 |
| 21 | 完成 | 会话库长按多选批量操作 | ThreadLibraryPage 长按、多选、全选、取消、批量归档/恢复/删除；CodexViewModel 串行 RPC 并保留部分失败进度；新增 Compose 点击测试 |
| 22 | 完成 | 会话搜索保留最近五个查询 | RecentThreadSearches 分开记录标题/全文最近五条查询，SharedPreferences 持久保存；界面快捷再次搜索，附单元测试 |
| 23 | 部分 | 侧栏未读和执行中角标 | SidebarSessions 显示运行状态；缺完整未读计数 |
| 24 | 部分 | configRequirements 枚举化表单 | ConfigInputParser 支持文字/开关/数字/JSON；未按配置要求动态生成全量字段 |
| 25 | 部分 | 更新进度显示下载百分比 | AppUpdateManager 与 AppUpdateCard 有下载进度、修补、校验状态；包大小展示仍可优化 |
| 26 | 未完成 | 首次启动三步引导 | 未见登录→模型→首任务的完整引导状态机 |
| 27 | 部分 | 失败类型到建议动作术语表 | ActionFeedbackCopy/PresentationPolicy 有用户文案；缺覆盖全量错误的映射表及测试 |
| 28 | 完成 | Screens 拆分并设置超长文件 CI 门禁 | ConversationScreen 已拆分；check_kotlin_file_budget.py 在 CI 强制限制单文件最多 760 行，避免再次膨胀 |
| 29 | 部分 | 时间线执行状态颜色 | TimelineStepCard 按状态显示颜色；三色规范的视觉截图还缺 |
| 30 | 未完成 | 深色主题全页回归 | VisualSystem 当前只有 lightColorScheme；无全页深色截图门禁 |
| 31 | 部分 | 600dp 以上常驻双栏深化 | AppNavigation 有大屏常驻侧栏，但设计稿写的是 720dp 门槛；600–719dp 未覆盖 |
| 32 | 未完成 | 折叠屏铰链/屏折适配测试 | 三档屏宽测试均为直板 Android 35 模拟器 |
| 33 | 未完成 | 自动扫描小于 48dp 触控区域 | 已有局部组件尺寸约束；CI 无全页面触控区域扫描 |
| 34 | 未完成 | 图标 contentDescription 覆盖脚本 | 未见面向 Compose 图标的描述字段检测门禁 |
| 35 | 未完成 | 空态统一插画资产 | 当前仍采用文字/Material 图标为空态主内容 |
| 36 | 未完成 | 200% 字体放大降级策略验收 | 缺大字号设备配置与截图回归 |
| 37 | 部分 | 页面转场第三档 220ms | CodexMotion.navigation 当前 240ms；尚未按建议统一验证 220ms |
| 38 | 部分 | edge-to-edge 全页一致性 | MainActivity 启用 EdgeToEdge；缺全部弹层、键盘和设备组合验收 |
| 39 | 部分 | 主题色 token 化和可切换皮肤 | Ink 颜色集中管理；尚未实现运行时主题切换 |
| 40 | 未完成 | TalkBack 全页面走查 | 没有 29 页 TalkBack 焦点与朗读验收证据 |
| 41 | 待验 | 对话焦点顺序明确规范 | 缺输入→时间线→审批横幅完整焦点设备测试 |
| 42 | 待验 | 文本尺寸 dp→sp 全量检查 | Compose 使用 typography；缺自动识别硬编码字号的扫描门禁 |
| 43 | 未完成 | contentDescription 缺失 CI 检测 | 暂无覆盖全量图标/可点击元素的脚本 |
| 44 | 未完成 | 英文 strings.xml locale 骨架 | 仍存在大量 Compose 内联中文 |
| 45 | 部分 | RTL 布局声明及测试 | Manifest supportsRtl=true；还没有 RTL UI 截图与动作测试 |
| 46 | 未完成 | 减少动效自动化断言 | UiPreferences.reducedMotion 接入；测试未覆盖开关前后差异 |
| 47 | 未完成 | 高对比模式设备验收 | 缺相关屏幕配置与对比度专项测试 |
| 48 | 未完成 | 外接键盘 Tab 焦点流 | 缺硬键 Tab、回车和方向键设备测试 |
| 49 | 未完成 | 第三方 IME 兼容性测试清单 | 缺各类输入法组合回归记录 |
| 50 | 部分 | APK 体积精简 | 已做 ARM64/x86_64 拆包；未形成持续体积预算与资源剥离门禁 |
| 51 | 未完成 | 冷启动 Macrobenchmark 门禁 | 当前工作流无冷启动基准测试 |
| 52 | 完成 | 时间线 LazyColumn 稳定 key | ConversationScreen items(items,key={it.id}) |
| 53 | 部分 | 终端环形缓冲及完整展开 | UI 仍为 128KiB 滑动尾部缓存；新增 TerminalTranscriptStore 流式写入本机私有文件及文档导出，可留存至 64MiB，超过上限明确警告；极端日志压测尚需验证 |
| 54 | 部分 | 附件图片下采样 | LocalImageAttachments 预览缩略图下采样；导入原图仍保留完整大小 |
| 55 | 未完成 | 512 事件缓冲 backpressure 压测 | 无满载高并发专项报告 |
| 56 | 未完成 | 对话 15 项状态订阅性能剖析 | 没有 Compose 重组次数基线 |
| 57 | 未完成 | 按语言拆 APK 评估 | 构建仅按 ABI 分包 |
| 58 | 未完成 | R8 fullMode 与资源压缩 | build.gradle.kts 当前 isMinifyEnabled=false |
| 59 | 待验 | 启动读取历史 IO 完全后台化 | refresh 使用异步请求；缺冷启动主线程 trace 验证 |
| 60 | 待验 | 审批决策持久化审计日志 | ApprovalAuditController 原子写入本机最近 100 条审批记录；ApprovalAuditSection 可浏览和确认清理，已有 Codec 单测与 Compose 点击测试，设备 CI 验收待确认 |
| 61 | 未完成 | 设备码复制后提醒剪贴板清除 | 未见设备码剪贴板过期清除机制 |
| 62 | 部分 | 终端命令注入风险提示与检查 | 执行前需确认且显示风险提示；尚缺明确的转义/特殊符号检查 |
| 63 | 未完成 | 更新清单数字签名链 | UpdateManifest/AppUpdateManager 使用 SHA-256；还没有独立签名真实性验证 |
| 64 | 完成 | 禁止明文网络的独立安全配置 | AndroidManifest 声明 usesCleartextTraffic=false 且引用 network_security_config.xml，base-config 明确关闭明文流量，仅信任系统证书 |
| 65 | 未完成 | 用户数据清除一键入口 | 此项未在已核查设置页面发现；状态按未完成处理 |
| 66 | 部分 | 备份敏感数据排除 | Manifest allowBackup=false 且配备 backup_rules/data_extraction_rules；数据类别逐项验收仍缺 |
| 67 | 未完成 | stderr 环形缓冲明文隐私风险评估 | 未见独立威胁模型与清理策略验证 |
| 68 | 部分 | CodexConnection 分流直接单测 | RpcEnvelopeRouter 已拆出并有测试；尚未注入真实流/MockProcess 压测 |
| 69 | 部分 | sendPrompt 全路径状态机测试 | FirstTurnRecoveryPolicy 与草稿回收单测已补；完整端到端发送状态机的成功/双失败/断连组合仍待覆盖 |
| 70 | 完成 | UI 协议矩阵对上游名称 CI 校验 | check_ui_protocol_matrix.py 已在 CI 三方核对官方 195 项方法、UiProtocolMatrix 和 ui_surface_policy.json 的层级；对缺失和层级不一致有回归测试，UI 静态构建 #44988500821 验证成功 |
| 71 | 未完成 | 上游快照升级兼容演练脚本 | prepare_android_mxc.py 固定断言；未见可复核的升级演练 |
| 72 | 部分 | goal/queue/catalog 契约测试全覆盖 | 已有部分请求契约测试；未确认三个 contract 的全分支覆盖 |
| 73 | 未完成 | 截图像素差异回归 | 工作流保存截图；未集成 Roborazzi/Paparazzi 对比基线 |
| 74 | 未完成 | Kotlin 编译警告逐步归零门禁 | 现有编译/Lint，但尚未设置警告预算 |
| 75 | 未完成 | detekt/ktlint 检查 | 未见相应 CI 执行步骤 |
| 76 | 未完成 | Renovate 取代上游误报依赖更新 | 未见仓库根级 Renovate 配置 |
| 77 | 未完成 | 英文 README 版本 | 未见独立英文 README 的现行证据 |
| 78 | 部分 | 发布签名哈希公告 | Release 具签名构建和 SHA 校验链；公开的独立签名/最小哈希公告尚未核验 |
| 79 | 完成 | 版本日志自动生成 | .github/scripts/generate-release-notes.py 在 release.yml 中执行 |
| 80 | 未完成 | 本地崩溃采集与诊断入口 | 现有诊断页面；缺崩溃持久化收集和恢复说明 |
| 81 | 完成 | 版本递增规则 | release.yml 与 build.gradle.kts 校验 0.1.0-111.N |
| 82 | 未完成 | Artifacts 与旧 Release 同步清理 | cleanup-old-releases.yml 当前只删除旧 Release |
| 83 | 未完成 | CI 失败状态进入应用诊断页 | 未见应用端读取仓库 CI 状态的功能 |
| 84 | 未完成 | PR 29 页影响面模板门禁 | 缺基于变更的页面影响矩阵 PR gate |
| 85 | 未完成 | 设备 11 项测试变为 Release 强制门禁 | 发布前依赖双工作流；全量 11 项覆盖仍缺明确强制验证 |
| 86 | 未完成 | 多会话并行和任务中心 | 当前 running/activeTurn 为单活动任务 |
| 87 | 未完成 | MCP 添加服务一键向导 | 现有 MCP 浏览与登录；缺服务器创建全流程 |
| 88 | 未完成 | 技能安装交互 | skills/list 已有；未发现 skills install 专项 UI 链路 |
| 89 | 完成 | 对话 Markdown 导出 | ThreadHistorySheet 从 thread/read 的 turns 中导出用户/Codex 文本消息，Android 系统文件选择器创建 .md 文件，附测试 |
| 90 | 部分 | 系统通知任务完成与审批等待 | CodexForegroundService 发运行中通知；缺结束通知与审批操作通知 |
| 91 | 未完成 | 桌面快捷方式或 Widget | 未见桌面入口组件 |
| 92 | 未完成 | 输入栏语音输入 | 当前输入以文字/图片为主 |
| 93 | 完成 | 设计系统文档与组件自动同步 | DESIGN_SYSTEM.md 的 Ink 颜色表与 VisualSystem.kt 一一校验，Material 主题主色统一；check_design_system_tokens.py 和回归单测已在 UI 静态构建 #44988500821 通过；Figma 原画板仍待同步 |
| 94 | 未完成 | 验收矩阵自动从 CI 写入 | docs/ACCEPTANCE.md 依靠人工更新，未见自动生成流程 |
| 95 | 未完成 | 会话数据模型版本化 | 未见面向上游结构变化的迁移机制 |
| 96 | 部分 | 前台服务低功耗策略 | 通知渠道 IMPORTANCE_LOW；缺空闲降频的设备耗电基线 |
| 97 | 未完成 | codex://thread 深链直达 | Manifest 没有对应 intent-filter |
| 98 | 未完成 | 协作/共享/评审接口预留 | 现有 Code Review 属单用户工作流；未见协作共享接口 |
| 99 | 部分 | 协议契约文档双向检查 | PROTOCOL_CONTRACTS 与 verify_protocol_methods 各自存在；缺双向内容一致性验证 |
| 100 | 部分 | UI 功能覆盖趋势图 | audit_ui_feature_coverage 输出 Markdown/JSON；未实现随提交/周期的历史趋势图 |

### 14 项原始缺陷复核后的真实收口边界

- **已补代码，尚待特定真实设备组合验证**：#1 锁屏/厂商省电保护；#2 超时值；#3 重连；#5 同文消息对账；#6 多图导入；#10 动态键上限；#11 分组运行中守卫；#13 拼写已修；#14 RPC 分流覆盖有新增单测。
- **进一步完善**：#4 首条指令收到服务端明确拒绝时先读取确认空会话、删除空线程并恢复草稿；超时/断连保留会话避免误删真实执行；#7 已增加 Kotlin 行数预算门禁；#8 上游 MXC 更新仍缺兼容演练；#9 终端 PTY 和完整流式输出待设备验证；#12 输入已类型化并严格检查 JSON 完整性，但尚未按 configRequirements 自动生成枚举表单。

### 当轮 CI 证据与设计系统偏差

- 对应同一 `eccccd08` 源码：[Android UI / 协议 / Lint / 360、393、411dp 验收](https://github.com/sy220284/111/actions/runs/44982471832) 全部成功；[APK / 原生核心 Android 35 设备测试](https://github.com/sy220284/111/actions/runs/44982471857) 全部成功。
- 此次门禁核验了页面能打开、基础控件与设备交互，以及原生内核运行；**真实账号、模型生成、长任务锁屏、异常网络、TalkBack、RTL、折叠屏与发布签名仍属于独立验收**。
- `docs/DESIGN_SYSTEM.md` 原强调色仍写暖铜 `#805A3F`，PR #30 主操作已经采用珊瑚 `#C8483E`。需按源码统一文档；Figma 文件 `K4FEMoNdmQbcnJZCHWr8c7` 尚未同步，MCP 返回 Starter 额度耗尽。

### 后续工作排序（按使用风险与完成依赖）

1. **可靠性和安全先行**：#4 的首次发送失败空会话、#8/#71 上游兼容升级演练、#63 更新签名、#69 全路径状态测试、#60 审批日志、#53 终端输出完整性。
2. **主要使用体验**：#20 审批会话级记住、#23 未读计数、#24 配置引导、#26 首次启动引导、#87 MCP 向导、#88 技能安装。
3. **统一设计和设备适配**：#30 深色主题、#32 折叠屏、#33 触控热区、#36 字体放大、#40–49 无障碍和键盘、#93 设计系统同步、Figma 可编辑设计稿。
4. **发布质量与长远能力**：#50–59 性能指标，#78–85 发布门禁，#86 并行任务中心，#90–92 通知/桌面/语音，#94–100 持续化审计工具。


### 新增功能实现证据（2026-11-22，继续完善）

- 新增 `ThreadBulkToolbar.kt`、`ThreadBulkActionPolicy.kt`，批量操作支持长按/多选/当前页全选/归档/恢复/删除；服务端逐条处理，失败时报告进度。`UiPopulatedSubpagesInstrumentedTest` 新增真实点击测试。
- 新增 `RecentThreadSearches.kt`：标题和全文检索分别记住最近五条查询，本机存储；新建 `DiffExpansionPreferences.kt` 持久化同一会话和差异的展开选择。
- 新增 `ThreadMarkdownExporter.kt`，在 `ThreadHistorySheet.kt` 中通过系统文件选择器保存 Markdown，排除工具内部记录；新增导出单测。
- 首轮 `thread/start` 成功、随后 `turn/start` **明确拒绝**且查询证实无回合时，执行安全删除空会话和草稿迁回；传输超时/断连视为可能已经成功发送，保留会话。新增策略与草稿测试。
- 新增 `check_kotlin_file_budget.py` 和 CI 行数门禁；`ConfigInputParser` 拒绝多余 JSON/数字尾随内容。
- 这些改动的最新 GitHub Actions 结果请检查 [PR #30](https://github.com/sy220284/111/pull/30) 对应提交；此前 `eccccd08` 两套 CI 通过，**不借用旧提交结论替代本轮测试**。
- Figma 源文件 `K4FEMoNdmQbcnJZCHWr8c7` 的 Starter MCP 额度已用尽，读取元数据和设计库均收到调用上限；原画板尚未改动。

## 终端日志与设计系统增量 · 2026-11-22

- `TerminalHistoryNavigation.kt` 为硬键 ↑↓ 历史导航提供纯逻辑和单元测试，输入框仅在单行内容下接管方向键，保留多行编辑的正常光标操作。
- `TerminalTranscriptStore.kt` 保存原始 stdout/stderr 字节、保留 UTF-8 跨事件边界并串行写入私有目录；UI 内存仍有 128KiB 上限，本机文件有 64MiB 上限、失败提示和文档选择器导出。长输出性能与系统存储紧张设备测试仍需补。
- `docs/DESIGN_SYSTEM.md` 维护 Ink 颜色表；`android-app/tools/check_design_system_tokens.py` 在 CI 比对 Compose 实际值并阻断颜色偏移，`MaterialTheme.primary` 已统一为 `Ink.coral`。
- 此处为源码核查状态，**以上新功能待 CI 同一提交成功后再标为完全验收**。原始 Figma 画板仍受 Starter MCP 调用额度限制。
