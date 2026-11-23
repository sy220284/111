# 111 Android · 原生工作台图标与状态插画

本目录是可直接导入 Figma、SVG 编辑器和安卓工程的**原始矢量素材**，与 Compose V5 配色对齐，随 UI 代码一起维护。

## 清单

- `icons/`：16 个 24×24 线性 SVG：Codex 标记、执行、审批、完成、失败、会话、终端、差异、附件、项目、设置、搜索、侧栏、停止、重试、历史。所有路径可单独编辑；颜色只使用 `tokens.json` 的语义色。
- `illustrations/`：4 个 320×226 空态插画：新会话、待审批、无差异、断连。小屏仅显示有用的标题和行动说明，装饰不得挤压输入区。
- `tokens.json`：包含与 `VisualSystem.kt` 对应的亮色与深色语义颜色；CI 对两个模式逐项核验，供 Figma 按同名变量导入。
- 安卓系统通知使用 `app/src/main/res/drawable/ic_codex_status.xml` 与 `ic_codex_stop.xml`，保持单色、24dp、系统原生尺寸。

## 用法与约束

图标优先表达可执行动作，辅助说明紧挨实际功能。状态插画只出现在确实为空/失败的页面，不能代替真实错误、重试按钮或审批正文。任务执行态靠图标、文字和语义色三重表达，不能只靠颜色。

将这些 SVG 导入 Figma 时采用独立矢量节点，再与可编辑文字、组件变体组合。完整页面截图仅作为比对参考，不能贴图充当可编辑组件。Compose 内继续优先复用 Material 3 标准控件；需要定制品牌状态或通知时使用本目录中的独立素材。

素材不包含任何生成式外部网络依赖，源文件完全保存在仓库；更新后应同步核验浅色对比、360/393/411dp 截图、减弱动效和 200% 字体缩放。

## 已接入 App 的空态素材

`app/src/main/res/drawable/` 新增 `ic_empty_diff.xml`、`ic_empty_approval.xml`、`ic_empty_connection.xml`，它们是源 SVG 的 Android 原生矢量形式。已在 `CodexEmptyState(artworkRes = ...)`、差异页、审批页和断连页面实际接入；通用组件根据是否指定素材切换原生图像和默认标准图标，保留单一组件与统一无障碍行为。

### 双主题变量

`tokens.json` 的 `modes.light` 与 `modes.dark` 使用同名 14 项语义颜色。Figma 中为同一变量集合建立两种模式；Android 设置页的双主题预览卡直接切换该集合，状态语义保持不变。

## 交互图谱补充（2026-11-23）

- `interaction-atlas-mobile.svg`：八类原始 SVG 组件示意，包括对话输入、进度、步骤、筛选、骨架屏、系统反馈、底部面板和上下文操作。可直接导入 Figma，文字和形状均为 SVG 元素。
- 组件交互和实际 Android 落地对应表见 [交互组件图谱](../INTERACTION_COMPONENT_ATLAS_20261123.md)。图谱供复用，正式 App 交互仍以 Compose 组件及实测为准。

## 第二轮交互矢量组件图谱

- `interaction-atlas-expanded.svg`：十个可编辑示意区域，覆盖搜索、状态单选、顺序、进度、校验、骨架、列表计数、整行功能入口、详情面板与上下文菜单。
- 该 SVG 与 `interaction-atlas-mobile.svg` 构成完整的交互示意素材；真实 Android 组件在 `AtlasInteractionComponents.kt`、`CodexDetailSheet.kt` 中统一维护，以运行反馈和业务语义驱动视觉状态。
