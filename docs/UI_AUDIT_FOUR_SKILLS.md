# Codex Android：四项技能交叉审计与界面优化记录

## 适用范围

目标代码：`android-app/app/src/main/java/io/github/sy220284/codexandroid/`，涵盖 28 个原生 Compose 页面、四组侧栏入口、任务执行、生态管理与设置页面。使用资料库 2026-11-20 上传的四项完整技能包：UI设计技能（finesse-ui）、工作台架构师技能（finesse-brief）、视频审美动效技能（frame-smith）、术语技能（finesse-term）。

复用技能的方法：将其中与**原生手机工作台**直接相关的原则转换为 Compose 可验证行为。视频技能中的影视输出、音效、Remotion 构建等不适用于 Android UI，保留其动效节奏、一致性和减少多余运动原则；Web/H5 中浏览器特有的滚动锁和 CSS 写法由原生 ModalBottomSheet、窗口 Insets 与 Compose 状态驱动方案承担。

## 一、审计发现与代码闭环

| 审计维度 | 原有问题 | 本次优化 | 验证入口 |
| --- | --- | --- | --- |
| 工作台主任务 | 首页统计卡片占据大半屏，任务路径分散 | 首页直接给新任务、文件、Diff 与真实内核/审批提示 | `Screens.kt` HomePage |
| 信息架构 | 28 个侧栏入口同时展开，操作密度高 | 工作台/开发工具/Codex生态/设置分组折叠，搜索即显示匹配项、提供常用入口 | `AppNavigation.kt` Sidebar |
| 真实数据状态 | 加载、成功、错误、无数据表达分散 | `CodexNotice`、`CodexEmptyState`、`StatusPill` 形成一致反馈；保留 VM 数据源 | `InteractionSystem.kt` |
| AI 执行可信度 | 工具步骤仅见类型和短文本，无法追溯执行来源 | `TimelineItem.evidence` 保存原始服务端步骤；手机端点击步骤打开独立底部弹层，可查看和复制未经改写的原始 JSON，状态与类型图标区分 | `CodexViewModel.kt`、`Screens.kt` |
| 三阶段任务信息 | 队列、运行、历史分散，任务中无法快速确认 | 会话顶部提供待办队列/历史任务/改动记录入口，运行中与待审批提供驻留提示；停止按钮始终占据底部拇指触控区域，任务运行时启用 | `Screens.kt` ConversationPage |
| 阅读长任务 | 每次出现新步骤都强制自动滚到底 | 用户仍在底部时才跟随新内容，阅读旧步骤时保留位置并显示可点击的“新步骤”计数 | `Screens.kt` ConversationPage |
| 模型与模式选择 | 窄下拉菜单不便手机操作 | 使用可滚动、明确选中态的底部选择弹层 | `InteractionSystem.kt` CodexChoiceSheet |
| 触摸反馈与动效 | 页面转场有单一渐隐，其他组件缺少一致规则 | 110/210/240ms 分层；按压缩放、状态展开、分组切换；简化动效统一归零 | `CodexMotion`、`CodexActionTile`、`CodexReveal` |
| 危险操作 | 不同页面各自编写确认弹窗，正文和主次按钮不一致 | 统一标题/风险说明/原始详情/确认与取消，重点动作采用危险语义色 | `CodexConfirmDialog`，全部业务入口 |
| 材质和密度 | 外观面板的视觉说明与真实选择范围不一致 | 全局柔雾渐层开关、紧凑排版与减少动画，均保存到设备 SharedPreferences | `UiPreferences`、`VisualSystem.kt` |
| 语言 | 功能标签中混用内部术语、英文标题 | 用户操作按钮优先中文动词和动作结果，协议和代码字段保留精确原文 | 各页面文案 |

## 二、结构设计

- 一级：侧栏的四个功能分组；搜索功能与直达按钮附属于侧栏入口，不产生额外层级。
- 二级：28 个页面各自负责一个明确任务；主页承载开始、恢复与全局风险提醒。
- 三级：详情展开、模型/模式选择、风险确认弹窗、文件编辑等操作层。
- 移动窄屏：`ModalNavigationDrawer`；宽度达到 850dp：常驻侧栏；任务输入保持底部且避让系统键盘。
- 短路径：主页新建任务 → 会话；当前会话 → 队列/历史/Diff；待审批提醒 → 审批详情 → 决策。所有决策继续调用原来的真实 Codex RPC 方法。
- 以服务端状态为准：无连接、无模型、无技能或无可用应用时明确展示真实情况，不生成模拟数据或虚假成功提示。

## 三、弹层与组件契约

| 组件 | 触发 | 用户看到什么 | 关闭/结果 |
| --- | --- | --- | --- |
| `CodexChoiceSheet` | 点击模型或协作方式 | 标题、说明、至少 58dp 高的选项和选中状态 | 点击选项写入真实 VM 状态；取消由原生遮罩/返回键与拖拽处理 |
| `CodexConfirmDialog` | 命令、权限、删除、退出、安装、配置写入等 | 作用范围、确认对象、必要时展示 JSON/命令全文 | 取消不发送 RPC；确认才发送，数据刷新由原流程负责 |
| `CodexNotice` | 引擎断开、待审批、运行状态 | 语义图标、具体事件与下一步操作 | 点击查看审批或连接；停止操作固定在会话底部 |
| `CodexReveal` | 展开设置详情/配置表单/提示 | 随内容高度平滑展开或关闭 | 跟随当前用户动作；减弱动效时即时完成 |
| `CodexActionTile` | 首页高频操作、外观预览 | 标题、说明、方向提示与按压反馈 | 点击后导航或更新配置，按压松手复位 |
| `TimelineStepCard` | 协议事件进入任务流 | 事件类型、真实文本、完成或异常状态、执行依据入口 | 只有带 `evidence` 的事件才展示详情按钮 |
| `CodexEvidenceSheet` | 点击步骤执行依据 | 原始 JSON/命令详情、滚动阅读与复制 | Material 3 底部弹层；关闭恢复当前步骤与阅读位置 |

各类弹层均使用 Material 3 原生语义、返回键和系统 Insets；嵌入式详情用 Compose 状态控制。信息底层的请求/权限方案未被视觉层替换。

## 四、动效与无障碍验收

1. 操作按压 110ms、内容展示 210ms、页面导航 240ms。每项动画都响应明确操作或状态变化，没有无限循环的装饰动画。
2. `UiPreferences.reducedMotion` 控制自定义动效归零；系统的 Material 3 弹层仍服从 Android 设备系统级动画设置。
3. 危险操作的确认与取消使用不同语义色，主要触控区域目标不低于 44–48dp；文本溢出策略按信息重要性设计，命令、路径、JSON 允许换行与滚动。
4. 优先保留原生无障碍标签与标准按钮语义。真机还需验证 TalkBack 顺序、系统字体放大和深色对比。
5. 对话框和底部选择弹层的遮罩/关闭由 Material 3 管理；不额外模拟拖动手势，避免与系统滑动冲突。

## 五、实际验收证据与边界

- Android 代码内有 UI instrumentation 检查：侧栏导航、协作模式选择弹层、侧栏搜索与外观页、终端执行确认与取消、系统返回键，以及执行依据弹层复制与关闭。
- `.github/workflows/check-android-ui.yml` 会在提交后执行 Kotlin 编译、官方协议检查、单元测试、Lint、APK 编译和 Android 35 模拟器设备 UI 测试，并保存截图。
- 以对应分支最新 GitHub Actions 记录为准，失败应在该分支修复后重跑。
- 独立 UI 编译成功不等于 Android 原生 Codex 核心端到端执行成功；核心 ABI、真实账户登录、实际文件权限和低端机性能仍以端到端验收矩阵判断。

## 六、复用规则

后续新增页面只需复用 `CodexTheme / MaterialPanel / SectionIntro / CodexNotice / CodexEmptyState / CodexChoiceSheet / CodexEvidenceSheet / CodexConfirmDialog / CodexReveal / CodexActionTile` 及 `CodexMotion`。业务页面只提供准确的操作对象、风险和回调，不重复声明阴影、颜色、弹窗结构或动效时长。所有操作保留真实数据源与错误路径。
