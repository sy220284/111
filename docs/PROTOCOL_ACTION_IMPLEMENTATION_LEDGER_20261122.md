# 111 接口协议逐项动作清单与 UI 落地验收台账（2026-11-22）

> 审计基线：`sy220284/111`，`main@99519677e157efc3bed6bd4055583b8d0e6adeab`（PR #34 合并后）。官方权威为锁定的 `upstream/codex/codex-rs/app-server-protocol/src/protocol/common.rs` 及 `v2` Rust 类型。下表 195 项由 `ui_surface_policy.json` 与官方协议方法集逐项交叉生成。**“已定位调用”仅指源码证据，尚不等于设备验收通过**。

## 核查结论和数字

| 维度 | 数量 | 判定 |
| --- | ---: | --- |
| 客户端→Codex 服务端请求 | 187 | Kotlin 发起请求；由 Rust 内核执行 |
| Codex 服务端→Android Host 请求 | 8 | 需要宿主处理结果；其中 5 种已被 Android 分流与 UI 处理，另 3 种当前返回明确不支持 |
| 协议方法合计 | 195 | Rust 与 Kotlin 目录及 JSON 分类名称对应 |
| 直接操作 | 40 | 页面/快捷入口，由用户执行 |
| 上下文操作 | 44 | 选中对象后用菜单、Sheet 或行内动作 |
| 高级按需 | 70 | 能力探测后显示，不替代稳定主流程 |
| 后台内部 | 39 | 后台同步或服务端主动请求，UI 只显示结果 |
| 平台不适用 | 2 | Windows Sandbox；Android 不生成入口 |

**重点说明**：`initialize`、`initialized` 属于连接握手，不在上述 195 项带斜杠方法中；通知事件也不在这 195 项请求中。校验脚本还须覆盖握手、通知、服务端主动请求和返回结构。

## 后续落地进展（相对本报告审计基线）

- **C01/P0 方向校验已接入（PR #39）**：从锁定的 Rust 协议中分别提取 187 项客户端方法和 8 项 Host 主动请求，验证 Android Host-only 清单与上游逐项一致；`CodexConnection.request/notify` 在发出任何消息前统一拦截反向 Host 方法。补充 Python 与 Kotlin 反向调用测试。其他 C02～C09 仍按下面缺口逐一实施。

## 已查出的差异与落实任务

| ID | 已查证事实 | 应落地处理 | 验收证据 |
| --- | --- | --- | --- |
| C01/P0 | `verify_protocol_methods.py` 用一组名称匹配 Rust 协议，未区分 187 个客户端请求与 8 个服务端主动请求；`audit_ui_feature_coverage.py` 也把请求方向合计 | 将方向写入验证器；用户发起动作只允许客户端请求，Host 请求必须有回传或明确功能门禁 | 构造反向调用与漏回传测试，应被 CI 拦截 |
| C02/P0 | `RpcEnvelopeRouter.kt` 只列出 5 个支持的 Host 请求；`item/tool/call`、`account/chatgptAuthTokens/refresh`、`attestation/generate` 当前走 UnsupportedHostRequest | 逐项按 Android 实际启用能力补 Host 处理，尤其工具执行的参数、结果、安全审批；如果能力未启用，集中显示清晰限制 | 三类真实 Host 请求测试与非法输入拒绝测试 |
| C03/P0 | 方法名检查和 UI 分层检查未验证请求必填项、枚举、返回 DTO、分页游标及通知事件 | 建立 `method→params/response→Kotlin builder/reader→测试` 字段级清单；优先覆盖执行/审批/项目/终端/账号/插件 | 契约样例与失败路径双向测试 |
| C04/P1 | `thread/start.projectId` 存在于 Rust `ThreadStartParams`，标注 `#[experimental]`，但当前生成的稳定 `ThreadStartParams.json` 没有此属性 | 保留正确的项目关联逻辑；验证 `experimentalApi` 授权与创建后回读，不以稳定 Schema 缺字段直接删掉代码 | 项目创建/选择→新会话创建→服务端回读项目 ID |
| C05/P1 | 策略仍把 `command/exec/resize` 作为“暂不具有 PTY”的条件路径，当前 `TerminalTaskOperations` 和 `TerminalPage` 实际已支持 PTY/尺寸输入 | 将策略状态改为真实接口，保留普通命令/PTY 的功能分支和行列验证 | 真实 PTY 启动、resize、停止、分片输出测试 |
| C06/P1 | `server/diagnostics` 被归为“后台内部”，但 `EcosystemScreens.kt` 存在直接诊断按钮 | 改为高级诊断的可见入口语义；从普通设置隐藏诊断原始字段 | 诊断页可查、普通页面不泄露原始协议 |
| C07/P1 | `audit_ui_feature_coverage.py` 以“任何字符串引用”和“等效实现包含指定文本”视作覆盖，无法证明按钮操作实际请求成功 | 改为页面点击→ViewModel→请求→响应/错误→真实界面的分层证据；本地文件等效独立验收 | 可复现设备用例，不能只检查字符串包含 |
| C08/P1 | `check_ui_protocol_matrix.py` 比较方法与展示层级，未比较 `owner`、`experimental`、请求方向 | 核对 Kotlin、JSON 策略、Rust 实验标注；字段级实验与方法级实验分别处理 | 元信息失配的负向测试 |
| C09/P1 | 路由目录 30 项含 28 项旧页面与设置/更新入口，目录完整不自动代表所有业务闭环 | 每个用户功能执行至少一次正常、失败、断线/恢复；全屏宽截图与操作回执 | Android 35 360/393/411dp 与真机实测记录 |

## 前后端分工与界面转换规则

- **服务端（Rust App Server）**：187 项客户端请求的业务操作、权限裁决、项目与会话持久化、终端执行、MCP、模型连接和事件生成；不会因为 UI 有按钮就自动可用。
- **Android 数据与协议层**：负责连接、请求 ID、参数构造、响应解析、分页、错误、通知归并、重连和 8 项反向 Host 请求；`fs/*` 的本地等效实现需单独注明本地路径权限与官方服务端文件协议的语义差别。
- **Android 前端（Jetpack Compose）**：负责对象选择、表单、列表、弹层、确认、加载、成功状态和出错恢复。原则为入口按业务、底层字段按需折叠，默认隐藏调试参数。
- **分层方案**：全局频繁使用用页面/固定操作区；对象操作用溢出菜单或上下文 Sheet；轻量选择用底部 Sheet；高风险删除/权限/覆盖用确认 Dialog；长文、差异、日志用独立阅读页；后台事件更新当前页面，无需弹出无意义提示；未启用的实验方法放高级区并进行服务探测。
- **素材与动效**：复用 Compose Material Symbols/仓库自有矢量资产与既有设计 token。状态图标按真实语义（播放、暂停、分支、锁、文件、差异、终端、搜索、同步）；只有空态与连接失败用插画；不要用大型插画替代数据内容。统一 48dp 触控目标，按压约 110ms，展开约 210ms，页面转场约 240ms；系统开启减少动画时使用低运动版本。
- **操作反馈**：进行中防重复提交、提交后依据真实结果更新、失败保留表单/草稿，审批回到原任务，列表分页保留滚动与筛选，长任务用持续状态条而非一直转圈。

## UI 实现优先清单（按完整业务闭环验收）

| 优先 | 功能组与操作对象 | 页面/组件落地 | 验收标准 |
| --- | --- | --- | --- |
| P0 | 新会话、继续会话、发送/追加/停止、异常重试 | 对话页面+固定输入区+时间线+操作状态 | 首次发送、运行中追加、停止、重连、重复文本对账和跨会话隔离 |
| P0 | 会话搜索、归档、恢复、删除、分组、批量管理 | 侧栏历史+资料库+分组 Sheet+删除确认 | 分页/筛选/批量部分失败真实回显；删除不误操作 |
| P0 | 项目选择、创建、导入、编辑、排序、关联线程 | 工作区项目列表+项目详情 Sheet+操作菜单 | project ID/根路径/新建线程的服务端关联闭环 |
| P0 | 终端执行、交互输入、PTY、尺寸调整、停止与导出 | 终端页面+输出区+输入栏+尺寸 Sheet | 实际进程 ID 贯穿、分片合并、实时输出、退出码、权限控制 |
| P0 | 命令、文件、额外权限、人工输入、MCP 授权 | 对话内审批卡+详情 Sheet+风险确认 | 服务端 ID 一一回传，拒绝和重复授权行为符合 DTO |
| P0 | Host 工具调用、令牌刷新、设备证明 | Android 后台 Host 路由；有交互需要才弹层 | 已启用能力可执行并回传；未启用有明确受控反馈 |
| P0 | 工作区文件读写/复制/删除/导入与差异 | 文件浏览器+编辑器+Diff Reader+危险确认 | 私有目录边界、原子保存、取消与失败回滚 |
| P1 | 模型/协作模式/服务商/账号/使用量和额度 | 模型 Sheet+账号页+服务商表单+用量图 | 模型选择生效、账号登录/取消、额度真实数值 |
| P1 | 技能、插件市场、已安装插件、应用连接 | 扩展页+搜索过滤+详情 Sheet+安装确认 | 安装/卸载后两源同步刷新，错误与空态正确 |
| P1 | MCP 状态、OAuth、资源阅读、工具调用 | MCP 列表+工具/资源 Sheet+动态参数表单 | 参数类型、授权、安全提示、isError 正确展示 |
| P1 | 任务目标、计划批准、队列排序和代码审查 | 会话上下文工具+队列页+审查结果 Sheet | 真实线程 ID、实验门禁、任务结果跳转 |
| P1 | 配置、安全、权限档位、诊断和日志 | 设置分组+开关/字段表单+诊断高级页 | 字段类型/校验/来源、失败不丢设置、脱敏显示 |
| P2 | 记忆、远程控制、实时语音、后台进程、共享插件 | 仅探测到服务端能力后开放相应高级区 | 能力检测、权限、完整操作与退出路径 |
| 排除 | Windows Sandbox 独占接口 | Android 不创建 UI | 目录保持对应，平台入口排除 |

## 逐接口协议动作清单（195/195）

下表“推荐精确组件”为逐接口建议落地点，表达 **UI 化结构选择**；“源码状态”严格区分显式调用、本地等效和待实测。后台请求也列明，避免遗漏 Host 能力。协议字段和错误响应必须继续以对应 Rust v2 类型核对。


### 会话、消息、目标、队列、附件、搜索与时间线（70 项）

| 方法 | 方向 | 用途/动作 | 官方请求/响应类型 | 展示责任 | 推荐精确组件 | 具体实现路径 | 源码/验证状态 | 优先级 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `thread/start` | Android→服务端 | 创建会话并设置工作目录与可选项目 | `ThreadStartParams → ThreadStartResponse` | 直接操作：对话页/侧栏/历史资料库 | 新建会话主操作区（模型、项目、权限按需展开） | 页面/快捷入口→填写必填参数→点击操作→真实 RPC→成功更新与失败恢复 | 已定位 Android RPC 引用；待端到端实测 | P0 |
| `thread/resume` | Android→服务端 | 恢复已有会话上下文 | `ThreadResumeParams → ThreadResumeResponse` | 直接操作：对话页/侧栏/历史资料库 | 历史会话行“继续”按钮；恢复中状态 | 页面/快捷入口→填写必填参数→点击操作→真实 RPC→成功更新与失败恢复 | 已定位 Android RPC 引用；待端到端实测 | P0 |
| `thread/fork` | Android→服务端 | 从当前会话复制分支 | `ThreadForkParams → ThreadForkResponse` | 上下文操作：对话页/侧栏/历史资料库 | 会话标题菜单“创建分支”+预览 Sheet | 用户选中当前对象→Sheet/菜单操作→必要确认→调用 RPC→刷新对象并返回原场景 | 待逐项验证调用入口/响应/实测 | P1 |
| `thread/sample` | Android→服务端 | 按条件生成会话样本 | `ThreadSampleParams → ThreadSampleResponse` | 高级按需：对话页/侧栏/历史资料库 | 按对象显示简洁状态；高级接口需能力门禁 | 服务探测成功并通过能力开关后，在高级操作区提供入口；错误原位呈现 | 待逐项验证调用入口/响应/实测 | P2 |
| `thread/sample/cancel` | Android→服务端 | 取消样本生成 | `ThreadSampleCancelParams → ThreadSampleCancelResponse` | 高级按需：对话页/侧栏/历史资料库 | 运行状态下的取消/停止按钮 | 服务探测成功并通过能力开关后，在高级操作区提供入口；错误原位呈现 | 待逐项验证调用入口/响应/实测 | P2 |
| `thread/archive` | Android→服务端 | 归档会话 | `ThreadArchiveParams → ThreadArchiveResponse` | 直接操作：对话页/侧栏/历史资料库 | 会话行侧滑/溢出菜单“归档” | 页面/快捷入口→填写必填参数→点击操作→真实 RPC→成功更新与失败恢复 | 待逐项验证调用入口/响应/实测 | P0 |
| `thread/delete` | Android→服务端 | 永久删除会话 | `ThreadDeleteParams → ThreadDeleteResponse` | 直接操作：对话页/侧栏/历史资料库 | 危险操作确认 Dialog（提示永久删除范围） | 页面/快捷入口→填写必填参数→点击操作→真实 RPC→成功更新与失败恢复 | 待逐项验证调用入口/响应/实测 | P0 |
| `thread/increment_elicitation` | Android→服务端 | 为外部人工交互暂停会话超时计时 | `ThreadIncrementElicitationParams → ThreadIncrementElicitationResponse` | 后台处理：对话内审批 | 按对象显示简洁状态；高级接口需能力门禁 | 后台事件/缓存/权限/同步管理；不新增独立按钮 | 待逐项验证调用入口/响应/实测 | P2 |
| `thread/decrement_elicitation` | Android→服务端 | 结束人工交互暂停并恢复超时计时 | `ThreadDecrementElicitationParams → ThreadDecrementElicitationResponse` | 后台处理：对话内审批 | 按对象显示简洁状态；高级接口需能力门禁 | 后台事件/缓存/权限/同步管理；不新增独立按钮 | 待逐项验证调用入口/响应/实测 | P2 |
| `thread/unsubscribe` | Android→服务端 | 取消订阅会话事件 | `ThreadUnsubscribeParams → ThreadUnsubscribeResponse` | 后台处理：对话页/侧栏/历史资料库 | 按对象显示简洁状态；高级接口需能力门禁 | 后台事件/缓存/权限/同步管理；不新增独立按钮 | 待逐项验证调用入口/响应/实测 | P2 |
| `thread/name/set` | Android→服务端 | 修改会话标题 | `ThreadSetNameParams → ThreadSetNameResponse` | 直接操作：对话页/侧栏/历史资料库 | 标题行内编辑及提交确认 | 页面/快捷入口→填写必填参数→点击操作→真实 RPC→成功更新与失败恢复 | 待逐项验证调用入口/响应/实测 | P0 |
| `thread/prediction/request` | Android→服务端 | 请求会话预测结果 | `ThreadPredictionRequestParams → ThreadPredictionRequestResponse` | 高级按需：对话页/侧栏/历史资料库 | 按对象显示简洁状态；高级接口需能力门禁 | 服务探测成功并通过能力开关后，在高级操作区提供入口；错误原位呈现 | 待逐项验证调用入口/响应/实测 | P2 |
| `thread/goal/set` | Android→服务端 | 设置会话目标 | `ThreadGoalSetParams → ThreadGoalSetResponse` | 上下文操作：对话页/侧栏/历史资料库 | 目标 Sheet 文本编辑和保存 | 用户选中当前对象→Sheet/菜单操作→必要确认→调用 RPC→刷新对象并返回原场景 | 已定位 Android RPC 引用；待端到端实测 | P1 |
| `thread/goal/get` | Android→服务端 | 读取会话目标 | `ThreadGoalGetParams → ThreadGoalGetResponse` | 上下文操作：对话页/侧栏/历史资料库 | 目标 Sheet 阅读卡 | 用户选中当前对象→Sheet/菜单操作→必要确认→调用 RPC→刷新对象并返回原场景 | 已定位 Android RPC 引用；待端到端实测 | P1 |
| `thread/goal/clear` | Android→服务端 | 清除会话目标 | `ThreadGoalClearParams → ThreadGoalClearResponse` | 上下文操作：对话页/侧栏/历史资料库 | 目标 Sheet 清除确认 | 用户选中当前对象→Sheet/菜单操作→必要确认→调用 RPC→刷新对象并返回原场景 | 已定位 Android RPC 引用；待端到端实测 | P1 |
| `thread/queue/add` | Android→服务端 | 向任务队列添加步骤 | `ThreadQueueAddParams → ThreadQueueAddResponse` | 上下文操作：任务队列页/上下文操作 | 队列页固定“添加步骤”入口 | 用户选中当前对象→Sheet/菜单操作→必要确认→调用 RPC→刷新对象并返回原场景 | 已定位 Android RPC 引用；待端到端实测 | P0 |
| `thread/queue/list` | Android→服务端 | 查询任务队列和顺序 | `ThreadQueueListParams → ThreadQueueListResponse` | 上下文操作：任务队列页/上下文操作 | 队列有序卡片列表和进度 | 用户选中当前对象→Sheet/菜单操作→必要确认→调用 RPC→刷新对象并返回原场景 | 已定位 Android RPC 引用；待端到端实测 | P0 |
| `thread/queue/update` | Android→服务端 | 修改已有队列步骤 | `ThreadQueueUpdateParams → ThreadQueueUpdateResponse` | 上下文操作：任务队列页/上下文操作 | 队列任务行编辑 Sheet | 用户选中当前对象→Sheet/菜单操作→必要确认→调用 RPC→刷新对象并返回原场景 | 已定位 Android RPC 引用；待端到端实测 | P0 |
| `thread/queue/delete` | Android→服务端 | 删除队列步骤 | `ThreadQueueDeleteParams → ThreadQueueDeleteResponse` | 上下文操作：任务队列页/上下文操作 | 队列行删除确认（保留列表位置） | 用户选中当前对象→Sheet/菜单操作→必要确认→调用 RPC→刷新对象并返回原场景 | 已定位 Android RPC 引用；待端到端实测 | P0 |
| `thread/queue/reorder` | Android→服务端 | 调整队列步骤顺序 | `ThreadQueueReorderParams → ThreadQueueReorderResponse` | 上下文操作：任务队列页/上下文操作 | 队列行拖动手柄及上移/下移 | 用户选中当前对象→Sheet/菜单操作→必要确认→调用 RPC→刷新对象并返回原场景 | 已定位 Android RPC 引用；待端到端实测 | P0 |
| `thread/queue/start` | Android→服务端 | 启动队列中的任务 | `ThreadQueueStartParams → ThreadQueueStartResponse` | 高级按需：任务队列页/上下文操作 | 队列“开始执行”按钮与运行状态 | 服务探测成功并通过能力开关后，在高级操作区提供入口；错误原位呈现 | 待逐项验证调用入口/响应/实测 | P0 |
| `thread/metadata/update` | Android→服务端 | 修改会话元数据与项目关联 | `ThreadMetadataUpdateParams → ThreadMetadataUpdateResponse` | 高级按需：对话页/侧栏/历史资料库 | 行内开关/字段表单＋操作回执 | 服务探测成功并通过能力开关后，在高级操作区提供入口；错误原位呈现 | 待逐项验证调用入口/响应/实测 | P2 |
| `thread/attachment/add` | Android→服务端 | 给会话添加附件 | `ThreadAttachmentAddParams → ThreadAttachmentAddResponse` | 上下文操作：对话页/侧栏/历史资料库 | 系统附件选择器、文件信息与移除按钮 | 用户选中当前对象→Sheet/菜单操作→必要确认→调用 RPC→刷新对象并返回原场景 | 待逐项验证调用入口/响应/实测 | P0 |
| `thread/attachment/list` | Android→服务端 | 列出会话附件 | `ThreadAttachmentListParams → ThreadAttachmentListResponse` | 上下文操作：对话页/侧栏/历史资料库 | 会话附件 Sheet/带缩略图的列表 | 用户选中当前对象→Sheet/菜单操作→必要确认→调用 RPC→刷新对象并返回原场景 | 待逐项验证调用入口/响应/实测 | P0 |
| `thread/attachmentOwner/list` | Android→服务端 | 查询附件拥有者关系 | `ThreadAttachmentOwnerListParams → ThreadAttachmentOwnerListResponse` | 后台处理：对话页/侧栏/历史资料库 | 分页列表、筛选、加载/空态 | 后台事件/缓存/权限/同步管理；不新增独立按钮 | 待逐项验证调用入口/响应/实测 | P0 |
| `thread/attachment/remove` | Android→服务端 | 从会话移除附件 | `ThreadAttachmentRemoveParams → ThreadAttachmentRemoveResponse` | 上下文操作：对话页/侧栏/历史资料库 | 附件卡片删除操作及撤销 | 用户选中当前对象→Sheet/菜单操作→必要确认→调用 RPC→刷新对象并返回原场景 | 待逐项验证调用入口/响应/实测 | P0 |
| `thread/section/move` | Android→服务端 | 将会话移动到指定分组 | `ThreadSectionMoveParams → ThreadSectionMoveResponse` | 上下文操作：侧栏/会话分组管理 | 会话行“移动到分组”选择 Sheet | 用户选中当前对象→Sheet/菜单操作→必要确认→调用 RPC→刷新对象并返回原场景 | 待逐项验证调用入口/响应/实测 | P0 |
| `thread/settings/update` | Android→服务端 | 修改会话高级设置 | `ThreadSettingsUpdateParams → ThreadSettingsUpdateResponse` | 高级按需：对话页/侧栏/历史资料库 | 行内开关/字段表单＋操作回执 | 服务探测成功并通过能力开关后，在高级操作区提供入口；错误原位呈现 | 待逐项验证调用入口/响应/实测 | P2 |
| `thread/memoryMode/set` | Android→服务端 | 切换会话记忆模式 | `ThreadMemoryModeSetParams → ThreadMemoryModeSetResponse` | 高级按需：对话页/侧栏/历史资料库 | 行内开关/字段表单＋操作回执 | 服务探测成功并通过能力开关后，在高级操作区提供入口；错误原位呈现 | 待逐项验证调用入口/响应/实测 | P2 |
| `thread/memory/read` | Android→服务端 | 读取会话关联记忆 | `ThreadMemoryReadParams → ThreadMemoryReadResponse` | 高级按需：对话页/侧栏/历史资料库 | 详情阅读 Sheet / 状态卡 | 服务探测成功并通过能力开关后，在高级操作区提供入口；错误原位呈现 | 待逐项验证调用入口/响应/实测 | P2 |
| `rollout/compress` | Android→服务端 | 压缩内部历史记录 | `无参 → RolloutCompressResponse` | 后台处理：会话高级设置 | 按对象显示简洁状态；高级接口需能力门禁 | 后台事件/缓存/权限/同步管理；不新增独立按钮 | 待逐项验证调用入口/响应/实测 | P2 |
| `thread/unarchive` | Android→服务端 | 恢复已归档会话 | `ThreadUnarchiveParams → ThreadUnarchiveResponse` | 直接操作：对话页/侧栏/历史资料库 | 归档列表行“恢复”按钮 | 页面/快捷入口→填写必填参数→点击操作→真实 RPC→成功更新与失败恢复 | 待逐项验证调用入口/响应/实测 | P1 |
| `thread/compact/start` | Android→服务端 | 压缩会话上下文 | `ThreadCompactStartParams → ThreadCompactStartResponse` | 上下文操作：对话页/侧栏/历史资料库 | 上下文占用提示与压缩确认 | 用户选中当前对象→Sheet/菜单操作→必要确认→调用 RPC→刷新对象并返回原场景 | 待逐项验证调用入口/响应/实测 | P0 |
| `thread/shellCommand` | Android→服务端 | 执行会话内部 shell 指令 | `ThreadShellCommandParams → ThreadShellCommandResponse` | 后台处理：对话页/侧栏/历史资料库 | 按对象显示简洁状态；高级接口需能力门禁 | 后台事件/缓存/权限/同步管理；不新增独立按钮 | 待逐项验证调用入口/响应/实测 | P2 |
| `thread/approveGuardianDeniedAction` | Android→服务端 | 批准被守护策略拒绝的动作 | `ThreadApproveGuardianDeniedActionParams → ThreadApproveGuardianDeniedActionResponse` | 高级按需：对话页/侧栏/历史资料库 | 按对象显示简洁状态；高级接口需能力门禁 | 服务探测成功并通过能力开关后，在高级操作区提供入口；错误原位呈现 | 待逐项验证调用入口/响应/实测 | P2 |
| `thread/backgroundTerminals/clean` | Android→服务端 | 清理已结束的后台终端 | `ThreadBackgroundTerminalsCleanParams → ThreadBackgroundTerminalsCleanResponse` | 高级按需：对话页/侧栏/历史资料库 | 按对象显示简洁状态；高级接口需能力门禁 | 服务探测成功并通过能力开关后，在高级操作区提供入口；错误原位呈现 | 待逐项验证调用入口/响应/实测 | P2 |
| `thread/backgroundTerminals/list` | Android→服务端 | 查询会话后台终端 | `ThreadBackgroundTerminalsListParams → ThreadBackgroundTerminalsListResponse` | 高级按需：对话页/侧栏/历史资料库 | 分页列表、筛选、加载/空态 | 服务探测成功并通过能力开关后，在高级操作区提供入口；错误原位呈现 | 待逐项验证调用入口/响应/实测 | P2 |
| `thread/backgroundTerminals/terminate` | Android→服务端 | 终止会话后台终端 | `ThreadBackgroundTerminalsTerminateParams → ThreadBackgroundTerminalsTerminateResponse` | 高级按需：对话页/侧栏/历史资料库 | 危险动作确认 Dialog＋回执 | 服务探测成功并通过能力开关后，在高级操作区提供入口；错误原位呈现 | 待逐项验证调用入口/响应/实测 | P2 |
| `thread/revert` | Android→服务端 | 回退会话指定变更 | `ThreadRevertParams → ThreadRevertResponse` | 上下文操作：对话页/侧栏/历史资料库 | 差异回退预览 Reader＋危险确认 | 用户选中当前对象→Sheet/菜单操作→必要确认→调用 RPC→刷新对象并返回原场景 | 待逐项验证调用入口/响应/实测 | P1 |
| `thread/list` | Android→服务端 | 分页列出会话历史 | `ThreadListParams → ThreadListResponse` | 直接操作：对话页/侧栏/历史资料库 | 分页列表、筛选、加载/空态 | 页面/快捷入口→填写必填参数→点击操作→真实 RPC→成功更新与失败恢复 | 已定位 Android RPC 引用；待端到端实测 | P0 |
| `threadSection/list` | Android→服务端 | 分页读取会话分组 | `ThreadSectionListParams → ThreadSectionListResponse` | 上下文操作：侧栏/会话分组管理 | 侧栏分组折叠区与全部分组选项 | 用户选中当前对象→Sheet/菜单操作→必要确认→调用 RPC→刷新对象并返回原场景 | 待逐项验证调用入口/响应/实测 | P1 |
| `threadSection/create` | Android→服务端 | 创建会话分组 | `ThreadSectionCreateParams → ThreadSectionCreateResponse` | 上下文操作：侧栏/会话分组管理 | 侧栏新建分组输入 Dialog | 用户选中当前对象→Sheet/菜单操作→必要确认→调用 RPC→刷新对象并返回原场景 | 待逐项验证调用入口/响应/实测 | P1 |
| `threadSection/update` | Android→服务端 | 修改会话分组名称 | `ThreadSectionUpdateParams → ThreadSectionUpdateResponse` | 上下文操作：侧栏/会话分组管理 | 分组标题编辑 Sheet | 用户选中当前对象→Sheet/菜单操作→必要确认→调用 RPC→刷新对象并返回原场景 | 待逐项验证调用入口/响应/实测 | P1 |
| `threadSection/delete` | Android→服务端 | 删除会话分组 | `ThreadSectionDeleteParams → ThreadSectionDeleteResponse` | 上下文操作：侧栏/会话分组管理 | 分组删除范围确认 Dialog | 用户选中当前对象→Sheet/菜单操作→必要确认→调用 RPC→刷新对象并返回原场景 | 待逐项验证调用入口/响应/实测 | P1 |
| `thread/search` | Android→服务端 | 搜索历史会话 | `ThreadSearchParams → ThreadSearchResponse` | 直接操作：对话页/侧栏/历史资料库 | 侧栏/资料库搜索框与高亮命中列表 | 页面/快捷入口→填写必填参数→点击操作→真实 RPC→成功更新与失败恢复 | 已定位 Android RPC 引用；待端到端实测 | P1 |
| `thread/search/stream` | Android→服务端 | 启动流式历史检索 | `ThreadSearchParams → ThreadSearchResponse` | 高级按需：对话页/侧栏/历史资料库 | 输入防抖的实时命中列表 | 服务探测成功并通过能力开关后，在高级操作区提供入口；错误原位呈现 | 待逐项验证调用入口/响应/实测 | P2 |
| `thread/search/cancel` | Android→服务端 | 取消历史检索 | `ThreadSearchCancelParams → ThreadSearchCancelResponse` | 高级按需：对话页/侧栏/历史资料库 | 搜索中的取消控件 | 服务探测成功并通过能力开关后，在高级操作区提供入口；错误原位呈现 | 待逐项验证调用入口/响应/实测 | P2 |
| `thread/search/stream/cancel` | Android→服务端 | 取消流式检索 | `ThreadSearchCancelParams → ThreadSearchCancelResponse` | 高级按需：对话页/侧栏/历史资料库 | 流式检索停止按钮 | 服务探测成功并通过能力开关后，在高级操作区提供入口；错误原位呈现 | 待逐项验证调用入口/响应/实测 | P2 |
| `thread/searchOccurrences` | Android→服务端 | 查找历史中的具体命中位置 | `ThreadSearchOccurrencesParams → ThreadSearchOccurrencesResponse` | 高级按需：对话页/侧栏/历史资料库 | 全文阅读器内命中位置侧轨 | 服务探测成功并通过能力开关后，在高级操作区提供入口；错误原位呈现 | 待逐项验证调用入口/响应/实测 | P2 |
| `thread/loaded/list` | Android→服务端 | 查看已载入内存的会话 | `ThreadLoadedListParams → ThreadLoadedListResponse` | 后台处理：对话页/侧栏/历史资料库 | 分页列表、筛选、加载/空态 | 后台事件/缓存/权限/同步管理；不新增独立按钮 | 待逐项验证调用入口/响应/实测 | P2 |
| `thread/read` | Android→服务端 | 读取会话内容和状态 | `ThreadReadParams → ThreadReadResponse` | 直接操作：对话页/侧栏/历史资料库 | 会话时间线/消息阅读器 | 页面/快捷入口→填写必填参数→点击操作→真实 RPC→成功更新与失败恢复 | 已定位 Android RPC 引用；待端到端实测 | P0 |
| `thread/readState/update` | Android→服务端 | 更新会话已读状态 | `ThreadReadStateUpdateParams → ThreadReadStateUpdateResponse` | 高级按需：对话页/侧栏/历史资料库 | 行内开关/字段表单＋操作回执 | 服务探测成功并通过能力开关后，在高级操作区提供入口；错误原位呈现 | 待逐项验证调用入口/响应/实测 | P0 |
| `thread/turns/list` | Android→服务端 | 分页读取会话执行轮次 | `ThreadTurnsListParams → ThreadTurnsListResponse` | 上下文操作：对话页/侧栏/历史资料库 | 历史轮次分页时间线 | 用户选中当前对象→Sheet/菜单操作→必要确认→调用 RPC→刷新对象并返回原场景 | 待逐项验证调用入口/响应/实测 | P1 |
| `thread/items/list` | Android→服务端 | 分页读取轮次内消息条目 | `ThreadItemsListParams → ThreadItemsListResponse` | 上下文操作：对话页/侧栏/历史资料库 | 消息条目懒加载列表 | 用户选中当前对象→Sheet/菜单操作→必要确认→调用 RPC→刷新对象并返回原场景 | 待逐项验证调用入口/响应/实测 | P1 |
| `thread/inject_items` | Android→服务端 | 向内部会话历史注入条目 | `ThreadInjectItemsParams → ThreadInjectItemsResponse` | 后台处理：对话页/侧栏/历史资料库 | 按对象显示简洁状态；高级接口需能力门禁 | 后台事件/缓存/权限/同步管理；不新增独立按钮 | 待逐项验证调用入口/响应/实测 | P2 |
| `thread/items/read` | Android→服务端 | 读取单条消息详情 | `ThreadItemsReadParams → ThreadItemsReadResponse` | 上下文操作：对话页/侧栏/历史资料库 | 完整消息阅读 Sheet | 用户选中当前对象→Sheet/菜单操作→必要确认→调用 RPC→刷新对象并返回原场景 | 待逐项验证调用入口/响应/实测 | P1 |
| `thread/guardianReview` | Android→服务端 | 发起守护规则审查 | `ThreadGuardianReviewParams → ThreadGuardianReviewResponse` | 高级按需：对话页/侧栏/历史资料库 | 按对象显示简洁状态；高级接口需能力门禁 | 服务探测成功并通过能力开关后，在高级操作区提供入口；错误原位呈现 | 待逐项验证调用入口/响应/实测 | P2 |
| `thread/prepareApprovedPlan` | Android→服务端 | 生成已批准计划的执行指令 | `ThreadPrepareApprovedPlanParams → ThreadPrepareApprovedPlanResponse` | 高级按需：对话页/侧栏/历史资料库 | 计划卡“批准并执行”与准备状态 | 服务探测成功并通过能力开关后，在高级操作区提供入口；错误原位呈现 | 已定位 Android RPC 引用；待端到端实测 | P2 |
| `turn/start` | Android→服务端 | 启动一轮模型任务 | `TurnStartParams → TurnStartResponse` | 直接操作：对话页/任务工具 | 对话底部发送按钮＋流式任务状态 | 页面/快捷入口→填写必填参数→点击操作→真实 RPC→成功更新与失败恢复 | 已定位 Android RPC 引用；待端到端实测 | P0 |
| `turn/settings/update` | Android→服务端 | 更新当前执行轮次的设置 | `TurnSettingsUpdateParams → TurnSettingsUpdateResponse` | 后台处理：对话页/任务工具 | 行内开关/字段表单＋操作回执 | 后台事件/缓存/权限/同步管理；不新增独立按钮 | 待逐项验证调用入口/响应/实测 | P0 |
| `turn/steer` | Android→服务端 | 给正在执行的轮次追加指令 | `TurnSteerParams → TurnSteerResponse` | 上下文操作：对话页/任务工具 | 执行中“补充指令”输入与发送 | 用户选中当前对象→Sheet/菜单操作→必要确认→调用 RPC→刷新对象并返回原场景 | 已定位 Android RPC 引用；待端到端实测 | P0 |
| `turn/interrupt` | Android→服务端 | 中断正在运行的任务 | `TurnInterruptParams → TurnInterruptResponse` | 直接操作：对话页/任务工具 | 运行态可见的停止按钮及进行中锁 | 页面/快捷入口→填写必填参数→点击操作→真实 RPC→成功更新与失败恢复 | 已定位 Android RPC 引用；待端到端实测 | P0 |
| `thread/realtime/start` | Android→服务端 | 启动实验性实时会话 | `ThreadRealtimeStartParams → ThreadRealtimeStartResponse` | 高级按需：实验性实时语音工作区 | 实时语音入口（权限与实验开关） | 服务探测成功并通过能力开关后，在高级操作区提供入口；错误原位呈现 | 待逐项验证调用入口/响应/实测 | P2 |
| `thread/realtime/appendAudio` | Android→服务端 | 向实时会话追加音频片段 | `ThreadRealtimeAppendAudioParams → ThreadRealtimeAppendAudioResponse` | 高级按需：实验性实时语音工作区 | 录音波形、录音时长与暂停 | 服务探测成功并通过能力开关后，在高级操作区提供入口；错误原位呈现 | 待逐项验证调用入口/响应/实测 | P2 |
| `thread/realtime/appendText` | Android→服务端 | 向实时会话追加文字 | `ThreadRealtimeAppendTextParams → ThreadRealtimeAppendTextResponse` | 高级按需：实验性实时语音工作区 | 实时文字补充输入区 | 服务探测成功并通过能力开关后，在高级操作区提供入口；错误原位呈现 | 待逐项验证调用入口/响应/实测 | P2 |
| `thread/realtime/appendSpeech` | Android→服务端 | 向实时会话追加语音片段 | `ThreadRealtimeAppendSpeechParams → ThreadRealtimeAppendSpeechResponse` | 高级按需：实验性实时语音工作区 | 录音/语音输入状态反馈 | 服务探测成功并通过能力开关后，在高级操作区提供入口；错误原位呈现 | 待逐项验证调用入口/响应/实测 | P2 |
| `thread/realtime/stop` | Android→服务端 | 结束实时会话 | `ThreadRealtimeStopParams → ThreadRealtimeStopResponse` | 高级按需：实验性实时语音工作区 | 录音停止按钮及确认状态 | 服务探测成功并通过能力开关后，在高级操作区提供入口；错误原位呈现 | 待逐项验证调用入口/响应/实测 | P2 |
| `thread/timeline/list` | Android→服务端 | 分页读取会话时间线 | `ThreadTimelineListParams → ThreadTimelineListResponse` | 上下文操作：对话页/侧栏/历史资料库 | 可折叠事件时间线与跳转锚点 | 用户选中当前对象→Sheet/菜单操作→必要确认→调用 RPC→刷新对象并返回原场景 | 待逐项验证调用入口/响应/实测 | P1 |
| `thread/realtime/list` | Android→服务端 | 查看实时会话列表 | `ThreadRealtimeListParams → ThreadRealtimeListResponse` | 高级按需：实验性实时语音工作区 | 实时会话记录列表 | 服务探测成功并通过能力开关后，在高级操作区提供入口；错误原位呈现 | 待逐项验证调用入口/响应/实测 | P2 |
| `thread/realtime/listVoices` | Android→服务端 | 读取实时语音候选 | `ThreadRealtimeListVoicesParams → ThreadRealtimeListVoicesResponse` | 高级按需：实验性实时语音工作区 | 语音选择 Sheet/试听入口 | 服务探测成功并通过能力开关后，在高级操作区提供入口；错误原位呈现 | 待逐项验证调用入口/响应/实测 | P2 |

### 项目和本地工作区（16 项）

| 方法 | 方向 | 用途/动作 | 官方请求/响应类型 | 展示责任 | 推荐精确组件 | 具体实现路径 | 源码/验证状态 | 优先级 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `project/list` | Android→服务端 | 读取项目目录 | `ProjectListParams → ProjectListResponse` | 高级按需：工作区/项目管理 | 项目分组列表、当前项目标记与搜索 | 服务探测成功并通过能力开关后，在高级操作区提供入口；错误原位呈现 | 已定位 Android RPC 引用；待端到端实测 | P0 |
| `project/read` | Android→服务端 | 读取项目详情与目录根 | `ProjectReadParams → ProjectReadResponse` | 高级按需：工作区/项目管理 | 项目详情 Sheet（根目录/关联任务） | 服务探测成功并通过能力开关后，在高级操作区提供入口；错误原位呈现 | 已定位 Android RPC 引用；待端到端实测 | P0 |
| `project/create` | Android→服务端 | 创建项目并登记根目录 | `ProjectCreateParams → ProjectCreateResponse` | 高级按需：工作区/项目管理 | “新建项目”名称/根目录表单 | 服务探测成功并通过能力开关后，在高级操作区提供入口；错误原位呈现 | 已定位 Android RPC 引用；待端到端实测 | P0 |
| `project/import` | Android→服务端 | 导入已有会话成为项目 | `ProjectImportParams → ProjectImportResponse` | 高级按需：工作区/项目管理 | “导入当前任务”确认 Sheet | 服务探测成功并通过能力开关后，在高级操作区提供入口；错误原位呈现 | 已定位 Android RPC 引用；待端到端实测 | P0 |
| `project/update` | Android→服务端 | 修改项目名称或根目录等信息 | `ProjectUpdateParams → ProjectUpdateResponse` | 高级按需：工作区/项目管理 | 项目名称/根目录编辑 Sheet | 服务探测成功并通过能力开关后，在高级操作区提供入口；错误原位呈现 | 已定位 Android RPC 引用；待端到端实测 | P0 |
| `project/move` | Android→服务端 | 调整项目在目录中的排列 | `ProjectMoveParams → ProjectMoveResponse` | 高级按需：工作区/项目管理 | 项目列表顺序调整菜单或拖动手柄 | 服务探测成功并通过能力开关后，在高级操作区提供入口；错误原位呈现 | 已定位 Android RPC 引用；待端到端实测 | P0 |
| `project/delete` | Android→服务端 | 删除项目登记 | `ProjectDeleteParams → ProjectDeleteResponse` | 高级按需：工作区/项目管理 | 项目删除确认 Dialog（交代文件是否保留） | 服务探测成功并通过能力开关后，在高级操作区提供入口；错误原位呈现 | 待逐项验证调用入口/响应/实测 | P0 |
| `fs/readFile` | Android→服务端 | 读取文件内容 | `FsReadFileParams → FsReadFileResponse` | 上下文操作：文件浏览器/编辑器 | 代码阅读器与编辑器 | 用户选中当前对象→Sheet/菜单操作→必要确认→调用 RPC→刷新对象并返回原场景 | 本地等效路径已登记；需核对官方文件语义 | P0 |
| `fs/writeFile` | Android→服务端 | 写入文件内容 | `FsWriteFileParams → FsWriteFileResponse` | 上下文操作：文件浏览器/编辑器 | 编辑器保存按钮、差异提示与失败恢复 | 用户选中当前对象→Sheet/菜单操作→必要确认→调用 RPC→刷新对象并返回原场景 | 本地等效路径已登记；需核对官方文件语义 | P0 |
| `fs/createDirectory` | Android→服务端 | 新建目录 | `FsCreateDirectoryParams → FsCreateDirectoryResponse` | 上下文操作：文件浏览器/编辑器 | 文件浏览器新建文件夹 Sheet | 用户选中当前对象→Sheet/菜单操作→必要确认→调用 RPC→刷新对象并返回原场景 | 本地等效路径已登记；需核对官方文件语义 | P0 |
| `fs/getMetadata` | Android→服务端 | 读取文件或目录元数据 | `FsGetMetadataParams → FsGetMetadataResponse` | 后台处理：文件浏览器/编辑器 | 按对象显示简洁状态；高级接口需能力门禁 | 后台事件/缓存/权限/同步管理；不新增独立按钮 | 待逐项验证调用入口/响应/实测 | P0 |
| `fs/readDirectory` | Android→服务端 | 列出文件夹内容 | `FsReadDirectoryParams → FsReadDirectoryResponse` | 上下文操作：文件浏览器/编辑器 | 虚拟化文件夹树/面包屑 | 用户选中当前对象→Sheet/菜单操作→必要确认→调用 RPC→刷新对象并返回原场景 | 本地等效路径已登记；需核对官方文件语义 | P0 |
| `fs/remove` | Android→服务端 | 删除文件或目录 | `FsRemoveParams → FsRemoveResponse` | 上下文操作：文件浏览器/编辑器 | 文件行危险删除确认 | 用户选中当前对象→Sheet/菜单操作→必要确认→调用 RPC→刷新对象并返回原场景 | 本地等效路径已登记；需核对官方文件语义 | P0 |
| `fs/copy` | Android→服务端 | 复制文件或目录 | `FsCopyParams → FsCopyResponse` | 上下文操作：文件浏览器/编辑器 | 文件行复制菜单与目标路径选择器 | 用户选中当前对象→Sheet/菜单操作→必要确认→调用 RPC→刷新对象并返回原场景 | 本地等效路径已登记；需核对官方文件语义 | P0 |
| `fs/watch` | Android→服务端 | 开始监听文件变化 | `FsWatchParams → FsWatchResponse` | 后台处理：文件浏览器/编辑器 | 按对象显示简洁状态；高级接口需能力门禁 | 后台事件/缓存/权限/同步管理；不新增独立按钮 | 待逐项验证调用入口/响应/实测 | P0 |
| `fs/unwatch` | Android→服务端 | 停止监听文件变化 | `FsUnwatchParams → FsUnwatchResponse` | 后台处理：文件浏览器/编辑器 | 按对象显示简洁状态；高级接口需能力门禁 | 后台事件/缓存/权限/同步管理；不新增独立按钮 | 待逐项验证调用入口/响应/实测 | P0 |

### 终端与代码审查（9 项）

| 方法 | 方向 | 用途/动作 | 官方请求/响应类型 | 展示责任 | 推荐精确组件 | 具体实现路径 | 源码/验证状态 | 优先级 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `review/start` | Android→服务端 | 发起代码审查 | `ReviewStartParams → ReviewStartResponse` | 直接操作：对话页/任务工具 | 上下文“代码审查”Sheet＋审查进度 | 页面/快捷入口→填写必填参数→点击操作→真实 RPC→成功更新与失败恢复 | 待逐项验证调用入口/响应/实测 | P1 |
| `command/exec` | Android→服务端 | 启动终端命令或 PTY | `CommandExecParams → CommandExecResponse` | 上下文操作：终端页 | 终端命令输入、流式输出与运行状态 | 用户选中当前对象→Sheet/菜单操作→必要确认→调用 RPC→刷新对象并返回原场景 | 已定位 Android RPC 引用；待端到端实测 | P0 |
| `command/exec/write` | Android→服务端 | 向终端标准输入发送内容 | `CommandExecWriteParams → CommandExecWriteResponse` | 上下文操作：终端页 | 终端标准输入行和发送按钮 | 用户选中当前对象→Sheet/菜单操作→必要确认→调用 RPC→刷新对象并返回原场景 | 已定位 Android RPC 引用；待端到端实测 | P0 |
| `command/exec/terminate` | Android→服务端 | 结束终端进程 | `CommandExecTerminateParams → CommandExecTerminateResponse` | 上下文操作：终端页 | 终端停止按钮及进程状态 | 用户选中当前对象→Sheet/菜单操作→必要确认→调用 RPC→刷新对象并返回原场景 | 已定位 Android RPC 引用；待端到端实测 | P0 |
| `command/exec/resize` | Android→服务端 | 调整正在运行的 PTY 尺寸 | `CommandExecResizeParams → CommandExecResizeResponse` | 上下文操作：终端页/PTY 控件 | PTY 行列数设置 Sheet、键盘状态 | 用户选中当前对象→Sheet/菜单操作→必要确认→调用 RPC→刷新对象并返回原场景 | 已定位 Android RPC 引用；待端到端实测 | P0 |
| `process/spawn` | Android→服务端 | 创建高级进程 | `ProcessSpawnParams → ProcessSpawnResponse` | 高级按需：终端页 | 高级进程控制台（需实验门禁） | 服务探测成功并通过能力开关后，在高级操作区提供入口；错误原位呈现 | 待逐项验证调用入口/响应/实测 | P2 |
| `process/writeStdin` | Android→服务端 | 写入高级进程标准输入 | `ProcessWriteStdinParams → ProcessWriteStdinResponse` | 高级按需：终端页 | 高级进程输入栏 | 服务探测成功并通过能力开关后，在高级操作区提供入口；错误原位呈现 | 待逐项验证调用入口/响应/实测 | P2 |
| `process/kill` | Android→服务端 | 终止高级进程 | `ProcessKillParams → ProcessKillResponse` | 高级按需：终端页 | 后台进程终止确认 | 服务探测成功并通过能力开关后，在高级操作区提供入口；错误原位呈现 | 待逐项验证调用入口/响应/实测 | P2 |
| `process/resizePty` | Android→服务端 | 调整高级进程 PTY 尺寸 | `ProcessResizePtyParams → ProcessResizePtyResponse` | 高级按需：终端页 | 高级终端尺寸控制 | 服务探测成功并通过能力开关后，在高级操作区提供入口；错误原位呈现 | 待逐项验证调用入口/响应/实测 | P2 |

### 模型、模式、账号和额度（26 项）

| 方法 | 方向 | 用途/动作 | 官方请求/响应类型 | 展示责任 | 推荐精确组件 | 具体实现路径 | 源码/验证状态 | 优先级 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `model/list` | Android→服务端 | 读取可用模型与思考配置 | `ModelListParams → ModelListResponse` | 直接操作：对话页/任务工具 | 模型选择 Sheet，按可用性/推理档位分组 | 页面/快捷入口→填写必填参数→点击操作→真实 RPC→成功更新与失败恢复 | 已定位 Android RPC 引用；待端到端实测 | P1 |
| `account/gatewayOAuth/read` | Android→服务端 | 读取高级网关授权状态 | `NullableGatewayOAuthReadParams → GatewayOAuthReadResponse` | 高级按需：设置→账户与模型 | 详情阅读 Sheet / 状态卡 | 服务探测成功并通过能力开关后，在高级操作区提供入口；错误原位呈现 | 待逐项验证调用入口/响应/实测 | P2 |
| `account/gatewayOAuth/login` | Android→服务端 | 发起网关 OAuth 授权 | `NullableGatewayOAuthLoginParams → GatewayOAuthLoginResponse` | 高级按需：设置→账户与模型 | 按对象显示简洁状态；高级接口需能力门禁 | 服务探测成功并通过能力开关后，在高级操作区提供入口；错误原位呈现 | 待逐项验证调用入口/响应/实测 | P2 |
| `account/gatewayOAuth/cancel` | Android→服务端 | 取消网关授权 | `无参 → GatewayOAuthCancelResponse` | 高级按需：设置→账户与模型 | 运行状态下的取消/停止按钮 | 服务探测成功并通过能力开关后，在高级操作区提供入口；错误原位呈现 | 待逐项验证调用入口/响应/实测 | P2 |
| `modelProvider/list` | Android→服务端 | 读取模型服务商列表 | `ModelProviderListParams → ModelProviderListResponse` | 直接操作：设置→账户与模型 | 服务商选择列表和默认标记 | 页面/快捷入口→填写必填参数→点击操作→真实 RPC→成功更新与失败恢复 | 已定位 Android RPC 引用；待端到端实测 | P1 |
| `modelProvider/config/write` | Android→服务端 | 保存服务商连接设置 | `ModelProviderConfigWriteParams → ModelProviderConfigWriteResponse` | 上下文操作：设置→账户与模型 | 分型配置表单+密钥脱敏输入 | 用户选中当前对象→Sheet/菜单操作→必要确认→调用 RPC→刷新对象并返回原场景 | 已定位 Android RPC 引用；待端到端实测 | P1 |
| `modelProvider/default/set` | Android→服务端 | 设置默认模型服务商 | `ModelProviderDefaultSetParams → ConfigWriteResponse` | 直接操作：设置→账户与模型 | 服务商行默认项切换 | 页面/快捷入口→填写必填参数→点击操作→真实 RPC→成功更新与失败恢复 | 已定位 Android RPC 引用；待端到端实测 | P1 |
| `modelProvider/capabilities/read` | Android→服务端 | 查询服务商支持的能力 | `ModelProviderCapabilitiesReadParams → ModelProviderCapabilitiesReadResponse` | 上下文操作：设置→账户与模型 | 服务商能力详情表 | 用户选中当前对象→Sheet/菜单操作→必要确认→调用 RPC→刷新对象并返回原场景 | 已定位 Android RPC 引用；待端到端实测 | P1 |
| `collaborationMode/list` | Android→服务端 | 列出协作工作方式 | `CollaborationModeListParams → CollaborationModeListResponse` | 直接操作：对话页/任务工具 | 对话工作模式选择 Sheet | 页面/快捷入口→填写必填参数→点击操作→真实 RPC→成功更新与失败恢复 | 已定位 Android RPC 引用；待端到端实测 | P1 |
| `account/login/start` | Android→服务端 | 开始账号登录 | `LoginAccountParams → LoginAccountResponse` | 直接操作：设置→账户与模型 | 浏览器/设备码登录 Sheet | 页面/快捷入口→填写必填参数→点击操作→真实 RPC→成功更新与失败恢复 | 已定位 Android RPC 引用；待端到端实测 | P0 |
| `account/bedrock/discover` | Android→服务端 | 发现 Bedrock 账号配置 | `BedrockDiscoverParams → BedrockDiscoverResponse` | 后台处理：设置→账户与模型 | 按对象显示简洁状态；高级接口需能力门禁 | 后台事件/缓存/权限/同步管理；不新增独立按钮 | 待逐项验证调用入口/响应/实测 | P2 |
| `account/bedrock/setup` | Android→服务端 | 配置 Bedrock 凭证 | `BedrockSetupParams → BedrockSetupResponse` | 后台处理：设置→账户与模型 | 按需输入 Sheet＋校验 | 后台事件/缓存/权限/同步管理；不新增独立按钮 | 待逐项验证调用入口/响应/实测 | P2 |
| `account/gcp/setup` | Android→服务端 | 配置 GCP 凭证 | `GcpSetupParams → GcpSetupResponse` | 后台处理：设置→账户与模型 | 按需输入 Sheet＋校验 | 后台事件/缓存/权限/同步管理；不新增独立按钮 | 待逐项验证调用入口/响应/实测 | P2 |
| `account/bedrock/checkGovCloudRequirements` | Android→服务端 | 检查 GovCloud 前置条件 | `BedrockCheckGovCloudRequirementsParams → BedrockCheckGovCloudRequirementsResponse` | 后台处理：设置→账户与模型 | 按对象显示简洁状态；高级接口需能力门禁 | 后台事件/缓存/权限/同步管理；不新增独立按钮 | 待逐项验证调用入口/响应/实测 | P2 |
| `account/login/cancel` | Android→服务端 | 取消正在进行的账号登录 | `CancelLoginAccountParams → CancelLoginAccountResponse` | 直接操作：设置→账户与模型 | 授权等待 Sheet 的取消按钮 | 页面/快捷入口→填写必填参数→点击操作→真实 RPC→成功更新与失败恢复 | 已定位 Android RPC 引用；待端到端实测 | P0 |
| `accountSession/list` | Android→服务端 | 列出可切换账号会话 | `AccountSessionsListParams → AccountSessionsResponse` | 上下文操作：设置→账户与模型 | 可切换账号列表 | 用户选中当前对象→Sheet/菜单操作→必要确认→调用 RPC→刷新对象并返回原场景 | 待逐项验证调用入口/响应/实测 | P1 |
| `accountSession/switch` | Android→服务端 | 切换当前账号会话 | `AccountSessionsSwitchParams → AccountSessionsSwitchResponse` | 上下文操作：设置→账户与模型 | 账号行切换确认并刷新当前配置 | 用户选中当前对象→Sheet/菜单操作→必要确认→调用 RPC→刷新对象并返回原场景 | 待逐项验证调用入口/响应/实测 | P1 |
| `account/logout` | Android→服务端 | 退出账号 | `无参 → LogoutAccountResponse` | 直接操作：设置→账户与模型 | 退出登录确认 Dialog | 页面/快捷入口→填写必填参数→点击操作→真实 RPC→成功更新与失败恢复 | 待逐项验证调用入口/响应/实测 | P1 |
| `account/securitySetup/read` | Android→服务端 | 读取账号安全设置 | `无参 → GetAccountSecuritySetupResponse` | 高级按需：设置→账户与模型 | 详情阅读 Sheet / 状态卡 | 服务探测成功并通过能力开关后，在高级操作区提供入口；错误原位呈现 | 待逐项验证调用入口/响应/实测 | P2 |
| `account/rateLimits/read` | Android→服务端 | 读取账号额度与重置窗口 | `NullableGetAccountRateLimitsParams → GetAccountRateLimitsResponse` | 直接操作：设置→账户与模型 | 额度进度条、剩余窗口与重置时间 | 页面/快捷入口→填写必填参数→点击操作→真实 RPC→成功更新与失败恢复 | 待逐项验证调用入口/响应/实测 | P1 |
| `account/rateLimitResetCredit/consume` | Android→服务端 | 消耗额度重置权益 | `ConsumeAccountRateLimitResetCreditParams → ConsumeAccountRateLimitResetCreditResponse` | 后台处理：设置→账户与模型 | 按对象显示简洁状态；高级接口需能力门禁 | 后台事件/缓存/权限/同步管理；不新增独立按钮 | 待逐项验证调用入口/响应/实测 | P2 |
| `account/usage/read` | Android→服务端 | 读取账号使用量 | `NullableGetAccountTokenUsageParams → GetAccountTokenUsageResponse` | 直接操作：设置→账户与模型 | 使用量关键指标卡与日趋势图 | 页面/快捷入口→填写必填参数→点击操作→真实 RPC→成功更新与失败恢复 | 待逐项验证调用入口/响应/实测 | P1 |
| `account/workspaceMessages/read` | Android→服务端 | 读取工作区账号提示 | `无参 → GetWorkspaceMessagesResponse` | 高级按需：设置→账户与模型 | 详情阅读 Sheet / 状态卡 | 服务探测成功并通过能力开关后，在高级操作区提供入口；错误原位呈现 | 待逐项验证调用入口/响应/实测 | P2 |
| `account/sendAddCreditsNudgeEmail` | Android→服务端 | 发送额度提醒邮件 | `SendAddCreditsNudgeEmailParams → SendAddCreditsNudgeEmailResponse` | 后台处理：设置→账户与模型 | 按对象显示简洁状态；高级接口需能力门禁 | 后台事件/缓存/权限/同步管理；不新增独立按钮 | 待逐项验证调用入口/响应/实测 | P2 |
| `account/read` | Android→服务端 | 读取当前账号登录状态 | `GetAccountParams → GetAccountResponse` | 直接操作：设置→账户与模型 | 账号登录状态卡 | 页面/快捷入口→填写必填参数→点击操作→真实 RPC→成功更新与失败恢复 | 已定位 Android RPC 引用；待端到端实测 | P1 |
| `account/chatgptAuthTokens/refresh` | 服务端→Android | 服务端要求 Host 刷新登录令牌 | `ChatgptAuthTokensRefreshParams → ChatgptAuthTokensRefreshResponse` | 后台处理：设置→账户与模型 | Host 令牌刷新回传后台（无直接 UI） | 后台接收请求→按真实已启用能力实现 Host 回传；未启用时明确提示能力限制 | Host 当前按不支持处理；待能力判定 | P0 |

### 技能、插件、应用、MCP 与钩子（30 项）

| 方法 | 方向 | 用途/动作 | 官方请求/响应类型 | 展示责任 | 推荐精确组件 | 具体实现路径 | 源码/验证状态 | 优先级 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `skills/list` | Android→服务端 | 列出当前工作区可用技能 | `SkillsListParams → SkillsListResponse` | 直接操作：设置→扩展能力 | 技能列表、启停状态、来源标识 | 页面/快捷入口→填写必填参数→点击操作→真实 RPC→成功更新与失败恢复 | 已定位 Android RPC 引用；待端到端实测 | P1 |
| `skills/extraRoots/set` | Android→服务端 | 设置技能扫描路径 | `SkillsExtraRootsSetParams → SkillsExtraRootsSetResponse` | 后台处理：设置→扩展能力 | 行内开关/字段表单＋操作回执 | 后台事件/缓存/权限/同步管理；不新增独立按钮 | 待逐项验证调用入口/响应/实测 | P2 |
| `hooks/list` | Android→服务端 | 列出运行钩子配置 | `HooksListParams → HooksListResponse` | 高级按需：设置→扩展能力 | 触发钩子只读状态列表 | 服务探测成功并通过能力开关后，在高级操作区提供入口；错误原位呈现 | 已定位 Android RPC 引用；待端到端实测 | P2 |
| `marketplace/add` | Android→服务端 | 新增插件市场来源 | `MarketplaceAddParams → MarketplaceAddResponse` | 高级按需：设置→扩展能力 | 高级“新增市场”来源表单 | 服务探测成功并通过能力开关后，在高级操作区提供入口；错误原位呈现 | 待逐项验证调用入口/响应/实测 | P2 |
| `marketplace/remove` | Android→服务端 | 移除插件市场来源 | `MarketplaceRemoveParams → MarketplaceRemoveResponse` | 高级按需：设置→扩展能力 | 来源删除确认 | 服务探测成功并通过能力开关后，在高级操作区提供入口；错误原位呈现 | 待逐项验证调用入口/响应/实测 | P2 |
| `marketplace/upgrade` | Android→服务端 | 升级插件市场来源 | `MarketplaceUpgradeParams → MarketplaceUpgradeResponse` | 高级按需：设置→扩展能力 | 市场版本更新提示与确认 | 服务探测成功并通过能力开关后，在高级操作区提供入口；错误原位呈现 | 待逐项验证调用入口/响应/实测 | P2 |
| `plugin/list` | Android→服务端 | 读取插件市场目录 | `PluginListParams → PluginListResponse` | 直接操作：设置→扩展能力 | 插件市场目录卡 | 页面/快捷入口→填写必填参数→点击操作→真实 RPC→成功更新与失败恢复 | 已定位 Android RPC 引用；待端到端实测 | P1 |
| `plugin/search` | Android→服务端 | 按关键字检索插件 | `PluginSearchParams → PluginSearchResponse` | 上下文操作：设置→扩展能力 | 插件搜索框与结果列表 | 用户选中当前对象→Sheet/菜单操作→必要确认→调用 RPC→刷新对象并返回原场景 | 已定位 Android RPC 引用；待端到端实测 | P1 |
| `plugin/installed` | Android→服务端 | 读取已安装插件 | `PluginInstalledParams → PluginInstalledResponse` | 直接操作：设置→扩展能力 | “已安装”分页列表及版本标签 | 页面/快捷入口→填写必填参数→点击操作→真实 RPC→成功更新与失败恢复 | 已定位 Android RPC 引用；待端到端实测 | P1 |
| `plugin/reconcile` | Android→服务端 | 同步插件实际安装状态 | `PluginReconcileParams → PluginReconcileResponse` | 高级按需：设置→扩展能力 | 按对象显示简洁状态；高级接口需能力门禁 | 服务探测成功并通过能力开关后，在高级操作区提供入口；错误原位呈现 | 待逐项验证调用入口/响应/实测 | P2 |
| `plugin/read` | Android→服务端 | 读取单个插件详情 | `PluginReadParams → PluginReadResponse` | 直接操作：设置→扩展能力 | 插件详情 Sheet（权限、工具、技能） | 页面/快捷入口→填写必填参数→点击操作→真实 RPC→成功更新与失败恢复 | 待逐项验证调用入口/响应/实测 | P1 |
| `plugin/skill/read` | Android→服务端 | 读取插件内技能内容 | `PluginSkillReadParams → PluginSkillReadResponse` | 上下文操作：设置→扩展能力 | 技能详情阅读 Sheet | 用户选中当前对象→Sheet/菜单操作→必要确认→调用 RPC→刷新对象并返回原场景 | 待逐项验证调用入口/响应/实测 | P1 |
| `plugin/share/save` | Android→服务端 | 保存插件共享内容 | `PluginShareSaveParams → PluginShareSaveResponse` | 高级按需：设置→扩展能力 | 共享目标选择 Sheet | 服务探测成功并通过能力开关后，在高级操作区提供入口；错误原位呈现 | 待逐项验证调用入口/响应/实测 | P2 |
| `plugin/share/updateTargets` | Android→服务端 | 更新插件共享目标 | `PluginShareUpdateTargetsParams → PluginShareUpdateTargetsResponse` | 高级按需：设置→扩展能力 | 共享范围表单 | 服务探测成功并通过能力开关后，在高级操作区提供入口；错误原位呈现 | 待逐项验证调用入口/响应/实测 | P2 |
| `plugin/share/list` | Android→服务端 | 查看插件共享记录 | `PluginShareListParams → PluginShareListResponse` | 高级按需：设置→扩展能力 | 共享记录列表 | 服务探测成功并通过能力开关后，在高级操作区提供入口；错误原位呈现 | 待逐项验证调用入口/响应/实测 | P2 |
| `plugin/share/checkout` | Android→服务端 | 检出共享插件内容 | `PluginShareCheckoutParams → PluginShareCheckoutResponse` | 高级按需：设置→扩展能力 | 共享插件检出确认 | 服务探测成功并通过能力开关后，在高级操作区提供入口；错误原位呈现 | 待逐项验证调用入口/响应/实测 | P2 |
| `plugin/share/delete` | Android→服务端 | 删除插件共享记录 | `PluginShareDeleteParams → PluginShareDeleteResponse` | 高级按需：设置→扩展能力 | 共享关系删除确认 | 服务探测成功并通过能力开关后，在高级操作区提供入口；错误原位呈现 | 待逐项验证调用入口/响应/实测 | P2 |
| `app/read` | Android→服务端 | 读取连接应用详情 | `AppsReadParams → AppsReadResponse` | 直接操作：设置→扩展能力 | 连接应用详情 Sheet | 页面/快捷入口→填写必填参数→点击操作→真实 RPC→成功更新与失败恢复 | 已定位 Android RPC 引用；待端到端实测 | P1 |
| `app/list` | Android→服务端 | 查询可连接应用 | `AppsListParams → AppsListResponse` | 直接操作：设置→扩展能力 | 可连接应用列表/可用性标识 | 页面/快捷入口→填写必填参数→点击操作→真实 RPC→成功更新与失败恢复 | 已定位 Android RPC 引用；待端到端实测 | P1 |
| `app/installed` | Android→服务端 | 查询已连接或安装应用 | `AppsInstalledParams → AppsInstalledResponse` | 直接操作：设置→扩展能力 | 已连接应用管理区 | 页面/快捷入口→填写必填参数→点击操作→真实 RPC→成功更新与失败恢复 | 已定位 Android RPC 引用；待端到端实测 | P1 |
| `skills/config/write` | Android→服务端 | 更新技能启停配置 | `SkillsConfigWriteParams → SkillsConfigWriteResponse` | 直接操作：设置→扩展能力 | 技能行开关（结果回滚） | 页面/快捷入口→填写必填参数→点击操作→真实 RPC→成功更新与失败恢复 | 已定位 Android RPC 引用；待端到端实测 | P1 |
| `plugin/install` | Android→服务端 | 安装插件 | `PluginInstallParams → PluginInstallResponse` | 直接操作：设置→扩展能力 | 插件安装确认（能力/来源/权限） | 页面/快捷入口→填写必填参数→点击操作→真实 RPC→成功更新与失败恢复 | 已定位 Android RPC 引用；待端到端实测 | P1 |
| `plugin/uninstall` | Android→服务端 | 卸载插件 | `PluginUninstallParams → PluginUninstallResponse` | 直接操作：设置→扩展能力 | 插件卸载确认（影响范围） | 页面/快捷入口→填写必填参数→点击操作→真实 RPC→成功更新与失败恢复 | 已定位 Android RPC 引用；待端到端实测 | P1 |
| `mcpServer/oauth/login` | Android→服务端 | 发起 MCP 服务授权 | `McpServerOauthLoginParams → McpServerOauthLoginResponse` | 直接操作：设置→扩展能力 | MCP OAuth 授权 Sheet | 页面/快捷入口→填写必填参数→点击操作→真实 RPC→成功更新与失败恢复 | 已定位 Android RPC 引用；待端到端实测 | P1 |
| `mcpServerStatus/list` | Android→服务端 | 查询 MCP 服务连接状态 | `ListMcpServerStatusParams → ListMcpServerStatusResponse` | 直接操作：设置→扩展能力 | MCP 服务状态列表＋连接状态标签 | 页面/快捷入口→填写必填参数→点击操作→真实 RPC→成功更新与失败恢复 | 已定位 Android RPC 引用；待端到端实测 | P1 |
| `mcpServer/resource/read` | Android→服务端 | 读取 MCP 资源 | `McpResourceReadParams → McpResourceReadResponse` | 直接操作：设置→扩展能力 | MCP 文本/资源阅读器 | 页面/快捷入口→填写必填参数→点击操作→真实 RPC→成功更新与失败恢复 | 待逐项验证调用入口/响应/实测 | P1 |
| `mcpServer/event/stream/start` | Android→服务端 | 订阅 MCP 事件流 | `McpServerEventStreamStartParams → McpServerEventStreamStartResponse` | 高级按需：设置→扩展能力 | 高级事件订阅开关 | 服务探测成功并通过能力开关后，在高级操作区提供入口；错误原位呈现 | 待逐项验证调用入口/响应/实测 | P2 |
| `mcpServer/event/stream/stop` | Android→服务端 | 取消 MCP 事件流 | `McpServerEventStreamStopParams → McpServerEventStreamStopResponse` | 高级按需：设置→扩展能力 | 高级事件订阅关闭 | 服务探测成功并通过能力开关后，在高级操作区提供入口；错误原位呈现 | 待逐项验证调用入口/响应/实测 | P2 |
| `mcpServer/tool/call` | Android→服务端 | 调用 MCP 服务工具 | `McpServerToolCallParams → McpServerToolCallResponse` | 上下文操作：设置→扩展能力 | 工具 Schema 动态表单、结果/错误详情 | 用户选中当前对象→Sheet/菜单操作→必要确认→调用 RPC→刷新对象并返回原场景 | 待逐项验证调用入口/响应/实测 | P1 |
| `mcpServer/elicitation/request` | 服务端→Android | 请求用户提供 MCP 交互信息 | `McpServerElicitationRequestParams → McpServerElicitationRequestResponse` | 后台处理：当前会话审批卡/Host | 任务内 MCP 用户输入/授权弹层 | 后台收到服务端请求→在当前任务弹出阻断审批卡→校验输入→按请求 id 回传 result | Host 已有分类与回传实现；待真机校验 | P0 |

### 环境、配置、安全与远程控制（24 项）

| 方法 | 方向 | 用途/动作 | 官方请求/响应类型 | 展示责任 | 推荐精确组件 | 具体实现路径 | 源码/验证状态 | 优先级 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `experimentalFeature/list` | Android→服务端 | 列出实验开关 | `ExperimentalFeatureListParams → ExperimentalFeatureListResponse` | 高级按需：设置→主机与安全 | 实验功能开关列表 | 服务探测成功并通过能力开关后，在高级操作区提供入口；错误原位呈现 | 待逐项验证调用入口/响应/实测 | P2 |
| `permissionProfile/list` | Android→服务端 | 读取可用权限档位 | `PermissionProfileListParams → PermissionProfileListResponse` | 直接操作：设置→主机与安全 | 权限档位选择器及范围说明 | 页面/快捷入口→填写必填参数→点击操作→真实 RPC→成功更新与失败恢复 | 已定位 Android RPC 引用；待端到端实测 | P1 |
| `experimentalFeature/enablement/set` | Android→服务端 | 调整实验功能开关 | `ExperimentalFeatureEnablementSetParams → ExperimentalFeatureEnablementSetResponse` | 高级按需：设置→主机与安全 | 实验功能危险等级提示与开关 | 服务探测成功并通过能力开关后，在高级操作区提供入口；错误原位呈现 | 待逐项验证调用入口/响应/实测 | P2 |
| `remoteControl/enable` | Android→服务端 | 启用远程控制 | `NullableRemoteControlEnableParams → RemoteControlEnableResponse` | 高级按需：高级→远程控制 | 启用远程访问确认 | 服务探测成功并通过能力开关后，在高级操作区提供入口；错误原位呈现 | 待逐项验证调用入口/响应/实测 | P2 |
| `remoteControl/disable` | Android→服务端 | 禁用远程控制 | `NullableRemoteControlDisableParams → RemoteControlDisableResponse` | 高级按需：高级→远程控制 | 关闭远程访问确认 | 服务探测成功并通过能力开关后，在高级操作区提供入口；错误原位呈现 | 待逐项验证调用入口/响应/实测 | P2 |
| `remoteControl/status/read` | Android→服务端 | 查看远程控制状态 | `无参 → RemoteControlStatusReadResponse` | 高级按需：高级→远程控制 | 远程连接状态卡 | 服务探测成功并通过能力开关后，在高级操作区提供入口；错误原位呈现 | 待逐项验证调用入口/响应/实测 | P2 |
| `remoteControl/pairing/start` | Android→服务端 | 开始远程设备配对 | `RemoteControlPairingStartParams → RemoteControlPairingStartResponse` | 高级按需：高级→远程控制 | 配对向导（扫码/验证码） | 服务探测成功并通过能力开关后，在高级操作区提供入口；错误原位呈现 | 待逐项验证调用入口/响应/实测 | P2 |
| `remoteControl/pairing/status` | Android→服务端 | 查询设备配对进度 | `RemoteControlPairingStatusParams → RemoteControlPairingStatusResponse` | 高级按需：高级→远程控制 | 配对进度与过期提示 | 服务探测成功并通过能力开关后，在高级操作区提供入口；错误原位呈现 | 待逐项验证调用入口/响应/实测 | P2 |
| `remoteControl/client/list` | Android→服务端 | 列出已配对的远程设备 | `RemoteControlClientsListParams → RemoteControlClientsListResponse` | 高级按需：高级→远程控制 | 已配对设备列表 | 服务探测成功并通过能力开关后，在高级操作区提供入口；错误原位呈现 | 待逐项验证调用入口/响应/实测 | P2 |
| `remoteControl/client/revoke` | Android→服务端 | 撤销远程设备访问 | `RemoteControlClientsRevokeParams → RemoteControlClientsRevokeResponse` | 高级按需：高级→远程控制 | 设备访问撤销确认 | 服务探测成功并通过能力开关后，在高级操作区提供入口；错误原位呈现 | 待逐项验证调用入口/响应/实测 | P2 |
| `environment/add` | Android→服务端 | 登记执行环境 | `EnvironmentAddParams → EnvironmentAddResponse` | 上下文操作：设置→主机与安全 | 新增执行环境表单 | 用户选中当前对象→Sheet/菜单操作→必要确认→调用 RPC→刷新对象并返回原场景 | 已定位 Android RPC 引用；待端到端实测 | P1 |
| `environment/info` | Android→服务端 | 读取执行环境信息 | `EnvironmentInfoParams → EnvironmentInfoResponse` | 上下文操作：设置→主机与安全 | 执行环境详情 Sheet | 用户选中当前对象→Sheet/菜单操作→必要确认→调用 RPC→刷新对象并返回原场景 | 已定位 Android RPC 引用；待端到端实测 | P1 |
| `environment/status` | Android→服务端 | 读取执行环境状态 | `EnvironmentStatusParams → EnvironmentStatusResponse` | 上下文操作：设置→主机与安全 | 环境状态汇总卡 | 用户选中当前对象→Sheet/菜单操作→必要确认→调用 RPC→刷新对象并返回原场景 | 已定位 Android RPC 引用；待端到端实测 | P1 |
| `config/mcpServer/reload` | Android→服务端 | 重新加载 MCP 配置 | `无参 → McpServerRefreshResponse` | 高级按需：设置→主机与安全 | 按对象显示简洁状态；高级接口需能力门禁 | 服务探测成功并通过能力开关后，在高级操作区提供入口；错误原位呈现 | 待逐项验证调用入口/响应/实测 | P0 |
| `windowsSandbox/setupStart` | Android→服务端 | 配置 Windows 沙箱 | `WindowsSandboxSetupStartParams → WindowsSandboxSetupStartResponse` | 无 Android UI | 按对象显示简洁状态；高级接口需能力门禁 | Android 无前端，核心侧保持协议兼容 | 待逐项验证调用入口/响应/实测 | 排除 |
| `windowsSandbox/readiness` | Android→服务端 | 检查 Windows 沙箱就绪状态 | `无参 → WindowsSandboxReadinessResponse` | 无 Android UI | 按对象显示简洁状态；高级接口需能力门禁 | Android 无前端，核心侧保持协议兼容 | 待逐项验证调用入口/响应/实测 | 排除 |
| `config/read` | Android→服务端 | 读取引擎配置 | `ConfigReadParams → ConfigReadResponse` | 直接操作：设置→主机与安全 | 设置分组列表与配置来源 | 页面/快捷入口→填写必填参数→点击操作→真实 RPC→成功更新与失败恢复 | 已定位 Android RPC 引用；待端到端实测 | P0 |
| `externalAgentConfig/detect` | Android→服务端 | 发现其他助手配置 | `ExternalAgentConfigDetectParams → ExternalAgentConfigDetectResponse` | 高级按需：设置→主机与安全 | 导入向导的来源发现列表 | 服务探测成功并通过能力开关后，在高级操作区提供入口；错误原位呈现 | 待逐项验证调用入口/响应/实测 | P2 |
| `externalAgentConfig/import` | Android→服务端 | 导入其他助手配置 | `ExternalAgentConfigImportParams → ExternalAgentConfigImportResponse` | 高级按需：设置→主机与安全 | 选择要导入的配置/预览/确认 | 服务探测成功并通过能力开关后，在高级操作区提供入口；错误原位呈现 | 待逐项验证调用入口/响应/实测 | P2 |
| `externalAgentConfig/import/recordHistory` | Android→服务端 | 记录配置导入历史 | `ExternalAgentConfigImportHistoryRecordParams → ExternalAgentConfigImportHistoryRecordResponse` | 高级按需：设置→主机与安全 | 导入动作后台审计记录 | 服务探测成功并通过能力开关后，在高级操作区提供入口；错误原位呈现 | 待逐项验证调用入口/响应/实测 | P2 |
| `externalAgentConfig/import/readHistories` | Android→服务端 | 查看配置导入记录 | `无参 → ExternalAgentConfigImportHistoriesReadResponse` | 高级按需：设置→主机与安全 | 历史导入记录阅读列表 | 服务探测成功并通过能力开关后，在高级操作区提供入口；错误原位呈现 | 待逐项验证调用入口/响应/实测 | P2 |
| `config/value/write` | Android→服务端 | 保存单项配置 | `ConfigValueWriteParams → ConfigWriteResponse` | 直接操作：设置→主机与安全 | 类型感知的字段设置/保存 | 页面/快捷入口→填写必填参数→点击操作→真实 RPC→成功更新与失败恢复 | 已定位 Android RPC 引用；待端到端实测 | P0 |
| `config/batchWrite` | Android→服务端 | 批量保存配置 | `ConfigBatchWriteParams → ConfigWriteResponse` | 直接操作：设置→主机与安全 | 设置批量确认与结果反馈 | 页面/快捷入口→填写必填参数→点击操作→真实 RPC→成功更新与失败恢复 | 已定位 Android RPC 引用；待端到端实测 | P0 |
| `configRequirements/read` | Android→服务端 | 读取配置和安全约束 | `无参 → ConfigRequirementsReadResponse` | 直接操作：设置→主机与安全 | 权限/配置限制说明卡 | 页面/快捷入口→填写必填参数→点击操作→真实 RPC→成功更新与失败恢复 | 已定位 Android RPC 引用；待端到端实测 | P1 |

### 内核内部、验证、反馈与检索（20 项）

| 方法 | 方向 | 用途/动作 | 官方请求/响应类型 | 展示责任 | 推荐精确组件 | 具体实现路径 | 源码/验证状态 | 优先级 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `server/diagnostics` | Android→服务端 | 读取服务端运行诊断 | `ServerDiagnosticsParams → ServerDiagnosticsResponse` | 后台处理：高级诊断 | 高级诊断按钮/数据分组卡 | 后台事件/缓存/权限/同步管理；不新增独立按钮 | 已定位 Android RPC 引用；待端到端实测 | P2 |
| `userVerification/status` | Android→服务端 | 查看用户身份核验状态 | `UserVerificationStatusParams → UserVerificationStatusResponse` | 后台处理：后台安全流程 | 详情阅读 Sheet / 状态卡 | 后台事件/缓存/权限/同步管理；不新增独立按钮 | 待逐项验证调用入口/响应/实测 | P2 |
| `userVerification/enroll` | Android→服务端 | 登记用户身份核验方式 | `UserVerificationEnrollParams → UserVerificationEnrollResponse` | 后台处理：后台安全流程 | 按需输入 Sheet＋校验 | 后台事件/缓存/权限/同步管理；不新增独立按钮 | 待逐项验证调用入口/响应/实测 | P2 |
| `userVerification/delete` | Android→服务端 | 删除身份核验登记 | `UserVerificationDeleteParams → UserVerificationDeleteResponse` | 后台处理：后台安全流程 | 危险动作确认 Dialog＋回执 | 后台事件/缓存/权限/同步管理；不新增独立按钮 | 待逐项验证调用入口/响应/实测 | P2 |
| `userVerification/verify` | Android→服务端 | 验证用户身份凭证 | `UserVerificationVerifyParams → UserVerificationVerifyResponse` | 后台处理：后台安全流程 | 按对象显示简洁状态；高级接口需能力门禁 | 后台事件/缓存/权限/同步管理；不新增独立按钮 | 待逐项验证调用入口/响应/实测 | P2 |
| `userVerification/cancel` | Android→服务端 | 取消身份核验流程 | `UserVerificationCancelParams → UserVerificationCancelResponse` | 后台处理：后台安全流程 | 运行状态下的取消/停止按钮 | 后台事件/缓存/权限/同步管理；不新增独立按钮 | 待逐项验证调用入口/响应/实测 | P2 |
| `memory/status` | Android→服务端 | 读取记忆服务状态 | `MemoryStatusParams → MemoryStatusResponse` | 高级按需：会话高级设置 | 详情阅读 Sheet / 状态卡 | 服务探测成功并通过能力开关后，在高级操作区提供入口；错误原位呈现 | 待逐项验证调用入口/响应/实测 | P2 |
| `memory/reset` | Android→服务端 | 重置记忆数据 | `无参 → MemoryResetResponse` | 高级按需：会话高级设置 | 危险动作确认 Dialog＋回执 | 服务探测成功并通过能力开关后，在高级操作区提供入口；错误原位呈现 | 待逐项验证调用入口/响应/实测 | P2 |
| `mock/experimentalMethod` | Android→服务端 | 供测试使用的实验接口 | `MockExperimentalMethodParams → MockExperimentalMethodResponse` | 后台处理：开发诊断 | 按对象显示简洁状态；高级接口需能力门禁 | 后台事件/缓存/权限/同步管理；不新增独立按钮 | 待逐项验证调用入口/响应/实测 | P2 |
| `feedback/upload` | Android→服务端 | 上传用户反馈材料 | `FeedbackUploadParams → FeedbackUploadResponse` | 后台处理：诊断与日志 | 按对象显示简洁状态；高级接口需能力门禁 | 后台事件/缓存/权限/同步管理；不新增独立按钮 | 待逐项验证调用入口/响应/实测 | P2 |
| `feedback/save` | Android→服务端 | 本地保存反馈材料 | `FeedbackSaveParams → FeedbackSaveResponse` | 后台处理：诊断与日志 | 按对象显示简洁状态；高级接口需能力门禁 | 后台事件/缓存/权限/同步管理；不新增独立按钮 | 待逐项验证调用入口/响应/实测 | P2 |
| `fuzzyFileSearch/sessionStart` | Android→服务端 | 开始后台模糊文件检索 | `FuzzyFileSearchSessionStartParams → FuzzyFileSearchSessionStartResponse` | 后台处理：全局搜索 | 按对象显示简洁状态；高级接口需能力门禁 | 后台事件/缓存/权限/同步管理；不新增独立按钮 | 待逐项验证调用入口/响应/实测 | P2 |
| `fuzzyFileSearch/sessionUpdate` | Android→服务端 | 更新后台文件检索条件 | `FuzzyFileSearchSessionUpdateParams → FuzzyFileSearchSessionUpdateResponse` | 后台处理：全局搜索 | 按对象显示简洁状态；高级接口需能力门禁 | 后台事件/缓存/权限/同步管理；不新增独立按钮 | 待逐项验证调用入口/响应/实测 | P2 |
| `fuzzyFileSearch/sessionStop` | Android→服务端 | 停止后台文件检索 | `FuzzyFileSearchSessionStopParams → FuzzyFileSearchSessionStopResponse` | 后台处理：全局搜索 | 按对象显示简洁状态；高级接口需能力门禁 | 后台事件/缓存/权限/同步管理；不新增独立按钮 | 待逐项验证调用入口/响应/实测 | P2 |
| `item/commandExecution/requestApproval` | 服务端→Android | 请求用户批准命令执行 | `CommandExecutionRequestApprovalParams → CommandExecutionRequestApprovalResponse` | 后台处理：当前会话审批卡/Host | 命令预览与本轮/会话审批卡 | 后台收到服务端请求→在当前任务弹出阻断审批卡→校验输入→按请求 id 回传 result | Host 已有分类与回传实现；待真机校验 | P0 |
| `item/fileChange/requestApproval` | 服务端→Android | 请求用户批准文件修改 | `FileChangeRequestApprovalParams → FileChangeRequestApprovalResponse` | 后台处理：当前会话审批卡/Host | 文件差异预览与修改确认 | 后台收到服务端请求→在当前任务弹出阻断审批卡→校验输入→按请求 id 回传 result | Host 已有分类与回传实现；待真机校验 | P0 |
| `item/tool/requestUserInput` | 服务端→Android | 请求用户填写工具问题 | `ToolRequestUserInputParams → ToolRequestUserInputResponse` | 后台处理：当前会话审批卡/Host | 问题/选项动态表单 | 后台收到服务端请求→在当前任务弹出阻断审批卡→校验输入→按请求 id 回传 result | Host 已有分类与回传实现；待真机校验 | P0 |
| `item/permissions/requestApproval` | 服务端→Android | 请求用户批准额外权限 | `PermissionsRequestApprovalParams → PermissionsRequestApprovalResponse` | 后台处理：当前会话审批卡/Host | 权限变更范围卡与授权确认 | 后台收到服务端请求→在当前任务弹出阻断审批卡→校验输入→按请求 id 回传 result | Host 已有分类与回传实现；待真机校验 | P0 |
| `item/tool/call` | 服务端→Android | 服务端要求 Android Host 执行工具 | `DynamicToolCallParams → DynamicToolCallResponse` | 后台处理：当前会话审批卡/Host | Host 工具执行后台；需用户确认时才弹层 | 后台接收请求→按真实已启用能力实现 Host 回传；未启用时明确提示能力限制 | Host 当前按不支持处理；待能力判定 | P0 |
| `attestation/generate` | 服务端→Android | 服务端要求 Host 生成设备证明 | `AttestationGenerateParams → AttestationGenerateResponse` | 后台处理：后台安全流程 | Host 设备证明回传后台（无直接 UI） | 后台接收请求→按真实已启用能力实现 Host 回传；未启用时明确提示能力限制 | Host 当前按不支持处理；待能力判定 | P0 |

## 统一验收模板：每个可见动作都需要的 9 项证据

- [ ] U01：真实业务入口可找到，选中正确会话/文件/项目/账号，普通 UI 不露底层字段
- [ ] U02：显式列出该方法所属方向、实验标注及所需参数来源
- [ ] U03：请求参数与 Rust v2 类型核对；稳定 Schema 不足时补 Rust 实验字段核验
- [ ] U04：加载期间防重入；取消/关闭时保留当前场景和未提交数据
- [ ] U05：实际内核响应成功后更新界面，分页游标与事件增量不覆盖新状态
- [ ] U06：失败、断线、超时、权限拒绝均有可恢复反馈和可追踪错误
- [ ] U07：8 项 Host 请求按请求 ID 回传对应结构；对未启用能力明确阻断
- [ ] U08：单测 + Android 35 360/393/411dp 设备点击与截图；关键原生能力另外做 ARM64 实机验证
- [ ] U09：协议清单、实现状态、CI 运行 SHA 保持同一次提交对应

## 推荐自动化校验的技术顺序（无需定时任务）

1. 将官方 187 个客户端请求和 8 个 Host 请求分离为可核对目录，明确握手与通知。
2. 扩充 `check_ui_protocol_matrix.py`：核验方法、方向、owner、experimental 与策略；对字段级实验标注另列清单。
3. 为 Kotlin 请求构造器添加每方法正例/反例测试，从 Rust v2 类型及 Schema 导出可复用案例。
4. 为 `RpcEnvelopeRouter` 和审批回传增加 8 类 Host 请求的能力门禁、响应结构和超时测试。
5. 将 UI 核查结果按“按钮点击→真实 RPC→真实回执→页面刷新/恢复”留证，而不是只记录方法字符串。
6. 在 PR 检查中产出协议目录覆盖率、正确参数覆盖率、真实操作覆盖率三个独立数值；后续新增协议自动生成待办条目。

> 变更边界：本文是审计与实施台账，不声称以上待办已经修改、测试或合入；实施顺序以 P0→P1→P2 按完整业务链验收推进。当前仓库运行时行为以实际 CI、Android 模拟器和 ARM64 实机回执为准。
