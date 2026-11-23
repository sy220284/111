# 111 × DSH Go：29 项 UI 交付与回归核对表

基线：2026-11-23 的 DSH Go × 111 对照清单（01–29），覆盖 P0/P1/P2 全部路由。目标为每项的入口、可用状态、真实业务处理、成功/失败/取消反馈、返回上下文和屏幕显示形成闭环。

此文档是持续验收索引：**源码列说明落地位置，测试列说明应提供的回归证据；实际完成以当前提交对应 CI 作业和截图为准**。不得因为功能路由存在就直接判定体验完成。

| 编号 | 功能 | 优先级 | 必达体验与实现焦点 | 代码位置 | 回归用例或截图 |
|---|---|---|---|---|---|
| 01 | `home` | P1 | 单一任务主动作；离线重连；紧凑项目和最近会话 | `Screens.kt / HomePage` | taskFirstHomeHasOnePrimaryActionAndUsefulShortcuts；home-android35.png |
| 02 | `chat` | P0 | 两列完整快捷建议；自增高输入；Queue/Steer；断连单处反馈 | `ConversationScreen.kt / ConversationComposer.kt` | chatSuggestionsStayVisibleWithinThePhoneWidth；chatKeyboardKeepsComposerActionsAccessible |
| 03 | `threads` | P1 | 会话侧栏保留筛选与切换上下文；统一触控反馈 | `SidebarSessions.kt / ThreadLibraryPage.kt` | sidebarUsesRealThreadListAndFiltersWithoutLosingCurrentSelection；sidebar-android35.png |
| 04 | `approvals` | P1 | 任务内权限范围、一次/会话授权、拒绝及历史 | `ControlScreens.kt / ApprovalQuickSheetContent` | permissionApprovalExplainsActualScope；audit-approvals-android35.png |
| 05 | `search` | P1 | 历史全文检索无结果可切标题并沿用关键词 | `ThreadLibraryPage.kt` | threadLibraryExposesServerBackedManagementLayout；thread-full-search-android35.png |
| 06 | `goals` | P1 | 目标摘要就近展示；会话草稿；随内容量变化高度的编辑弹层 | `TaskManagementScreens.kt / ThreadGoalSheet.kt` | audit-goals-sheet-android35.png；PhoneSheetLayoutTest |
| 07 | `queue` | P0 | 短 Sheet；无实验徽标；指令增删排队与草稿保留 | `TaskManagementScreens.kt` | queueDraftSurvivesClosingAndReopeningSheet；audit-queue-android35.png |
| 08 | `files` | P1 | 当前目录及导入固定近文件列表；项目管理按需展开 | `DeveloperScreens.kt / ProjectRegistryPanel.kt` | workspaceFoldersCanBeCreatedAndRemovedWithExplicitConfirmation；workspace-files-android35.png |
| 09 | `diff` | P1 | 文件摘要工具条；新旧行号；折行或横滚；复制及审查 | `DiffReaderPage.kt / UnifiedDiff.kt` | UnifiedDiffTest；diffReaderShowsFileFirstSummaryAndReviewAction |
| 10 | `terminal` | P1 | 空态缩小；输出优先；固定命令输入、停止与日志导出 | `TerminalPage.kt` | terminalExecutionShowsReadableConfirmation；terminal-confirm-android35.png |
| 11 | `review` | P1 | 审查范围选择与不可选原因、提交后返回任务 | `ReviewSurfaces.kt` | taskSpecificSheetsAreReachableAndReadable；audit-review-sheet-android35.png |
| 12 | `models` | P1 | 当前模型和可用目录；连接故障解释；过长名称可读 | `UiSharedSurfaces.kt / ModelChoiceSurface` | modelChoiceUsesOneSharedBottomSheet；model-sheet-android35.png |
| 13 | `modes` | P2 | 当前执行模式、下一轮生效范围与禁用原因 | `UiSharedSurfaces.kt / ModeChoiceSurface` | modeSheetRespectsRealEngineCapability；mode-sheet-android35.png |
| 14 | `skills` | P1 | 已启用/可用未启用/异常筛选；异常和写入状态收敛 | `EcosystemScreens.kt / SkillsPage` | secondarySettingsPagesHaveActualDeviceScreenshots；audit-skills-android35.png；本 PR 设备回归 |
| 15 | `plugins` | P1 | 发现、搜索、详情、安装/更新、失败重试与状态 | `EcosystemScreens.kt / PluginsPage` | pluginDiscoveryCanOpenSearchWithoutInventedResults；audit-plugin-detail-android35.png |
| 16 | `apps` | P1 | 可调用/待授权/待就绪/停用可读状态及就地授权 | `IntegrationScreens.kt / ConnectedAppsPage` | secondarySettingsPagesHaveActualDeviceScreenshots；audit-apps-android35.png |
| 17 | `mcp` | P1 | 智能体负责接入与授权；页面仅展示实际可用 MCP 服务与刷新入口 | `IntegrationScreens.kt / McpAvailableServices.kt / McpApprovalScreens.kt` | mcpFullPageShowsOnlyUsableServersAndNoRegistrationInterface；elicitationRendersOnlyRequiredUserForm；audit-mcp-usable-directory-android35.png |
| 18 | `environments` | P1 | 本机与远程二选；仅远程才显示连接表单 | `ExecutionEnvironmentPage.kt` | executionEnvironmentShowsChoiceBeforeConnectionFields；audit-environments-android35.png |
| 19 | `account` | P1 | 登录/退出与当前账号状态；用量额度统一入口 | `AccountLoginUi.kt` | accountPageExposesNarrowScreenDeviceLoginActions；account-android35.png |
| 20 | `providers` | P1 | 服务商列表、可读配置与确认表单；API Key 搜索直达 | `EcosystemScreens.kt / ProvidersPage` | approvalsAndChildEditorsHaveReadableStates；settingsSearchOpensPreciseProviderAndRemoteEditors |
| 21 | `config` | P1 | 高级参数按需编辑，移除重复设置总菜单并显式保存 | `ControlScreens.kt / ConfigPage` | settingsSearchDirectlyExpandsAdvancedConfig；audit-config-editor-android35.png |
| 22 | `security` | P1 | 审批与文件访问以自然语言呈现；确认风险及影响范围 | `ControlScreens.kt / SecurityPage` | expandedSecurityAndAboutViewsAreCaptured；audit-security-android35.png |
| 23 | `usage` | P2 | 用量/额度同屏切换，服务端数据和空态可理解 | `AccountMetricsPage.kt` | accountMetricsUsesSharedUsageAndLimitsTabs；account-usage-android35.png |
| 24 | `limits` | P2 | 旧路由保留；额度搜索直接选中额度窗口标签 | `AccountMetricsPage.kt / NavigationPolicy.kt` | settingsSearchOpensUnifiedAccountMetrics；account-limits-android35.png |
| 25 | `appearance` | P2 | 主题实时预览、字号/密度/阴影/减动效与可见焦点 | `SettingsHub.kt / AppearanceInlineControls` | settingsSearchFindsInlineAppearanceControls；darkAppearanceWithSystemDoubleFontRemainsReachable |
| 26 | `diagnostics` | P0 | 状态+重连优先，日志和排查按需展开并合并错误提示 | `EcosystemScreens.kt / EngineDiagnosticsPage` | secondarySettingsPagesHaveActualDeviceScreenshots；audit-diagnostics-android35.png |
| 27 | `about` | P2 | 版本、来源与隐私清楚；支持复制版本信息 | `UiSharedSurfaces.kt / AboutInfoSheet` | expandedSecurityAndAboutViewsAreCaptured；audit-about-android35.png |
| 28 | `settings` | P1 | 设置归属唯一；搜索直达目标控件并保留返回上下文 | `SettingsHub.kt / NavigationPolicy.kt` | NavigationPolicyTest；settingsSearchDirectlyOpensUpdateActions |
| 29 | `update` | P2 | 检查→下载→校验→安装状态连续显示；搜索展开操作 | `AppUpdateCard.kt / AppUpdateManager` | settingsGroupsAndInlineAppearanceAndUpdate；settingsSearchDirectlyOpensUpdateActions |

## 不遗漏的统一验收门槛

1. **代码与接口**：29 项功能入口与页面归属保持一一对应；用户任务操作不混入长期设置；无空点击处理器；官方 Codex RPC 的方向、入参和结果校验通过。检查器：`android-app/tools/audit_ui_feature_coverage.py` 与 `check_ui_protocol_matrix.py`。
2. **手机竖屏**：Android 35 360dp、393dp、411dp 三档均通过真实设备交互及前台截图才算验收。检查输入栏/键盘、抽屉导航、弹层高度和返回路径；横屏、平板已从交付范围移除。
3. **状态与视觉**：断连、重试、加载、空态、授权、运行中、确认、失败均检查；另含深色模式、系统 200% 字号、RTL、批量操作与草稿保留。逐项打开截图，而非只看 CI 绿灯。
4. **功能链路**：文件操作、账号登录、插件与 MCP 授权、服务商写入、技能启停、终端命令、自更新等继续使用原有真实业务调用。设备里使用的固定协议样例用于 UI 检查，线上可用性由真实连接及状态响应判定。
5. **PR 一致性**：[#63](https://github.com/sy220284/111/pull/63)、[#64](https://github.com/sy220284/111/pull/64)、[#65](https://github.com/sy220284/111/pull/65) 均已合并，页面和 MCP 表单成果保留；[#66](https://github.com/sy220284/111/pull/66) 已合并并替代横屏平板方案。三档手机验收见 [CI #45138957127](https://github.com/sy220284/111/actions/runs/45138957127)。
6. **Figma 独立验收**：目标文件 `K4FEMoNdmQbcnJZCHWr8c7`，MCP Starter 调用额度已用尽，现连接账号只有 View 席位。**Figma 原画板尚未修改**；当可编辑且额度可用时，逐项核对 29 项页面/组件并同步画板，再截屏审查。

## 固定执行顺序

先取最新主线和 PR 变更 → 对照本表核验全部 29 项 → 运行完整 Kotlin/协议/Lint → 进行三档手机竖屏设备截图与交互 → 逐图检查字体、键盘、深浅主题和弹层 → 复查 PR 冲突与 Figma 同步 → 明确区分“代码已提交”“设备已通过”“设计稿已同步”“已合并上线”四种状态。发现失败，修复同一分支后重新执行整条验收链。


## 2026-11-23 键盘展开证据补核

- 02 对话页同时核对系统软键盘是否真实可见，以及输入框、发送、附件和任务工具是否仍可操作；设置搜索也必须经真实键盘覆盖测试。旧 CI 可注入文本却不显示键盘，截图文件名不足以证明展开状态。
- 首轮通过后的旧/新截图哈希完全相同，已确定原因是 Compose 根节点截图排除了系统输入法图层。聊天键盘专项统一改为 Android 系统全屏截图，继续检查系统输入法可见状态与页面操作。
- 已在三档 Android 35 统一增加软件输入法设置与设备 Insets 状态断言；新 CI 通过和屏幕截图核实前，按“待真实 IME 复核”记账。
