# 111 Android · 统一原生矢量与组件设计素材

本目录为 App 实际 UI 的可编辑设计源。图标、插画与原生资源采用一致几何，Android 不将整张图谱作为界面贴图。

- `icons/`：85 个 24×24 图标，统一 1.8 线宽与圆形端点/转角；对应 `ic_ui_*.xml`，通过 `CodexGlyph` 和 `CodexIcon` 调用；颜色由 Ink 及真实状态控制。
- `illustrations/`：七类 152×116 状态插画；`illustrations/dark/` 为深色几何对应版本。源 SVG 与 `ic_empty_*.xml` 路径和颜色一致，页面标题及行动文字由 Compose 渲染。
- `tokens.json`：浅、深主题各 14 个 Ink 语义色，与原有设计 token 检查保持一致。
- 四份组件图谱：`four-skills-interaction-v6.svg`、`interaction-atlas-mobile.svg`、`interaction-atlas-expanded.svg`、`状态与交互组件.svg`。只用于可编辑设计与代码对照。
- `素材接入清单.json`：全部真实路由、图谱、插画和测试声明；CI 按注册表、实际生产调用生成逐图标及逐插画的页面/业务/测试报告。

## 使用与验收

新增图标同时提交 SVG、VectorDrawable、`CodexGlyph` 注册和生产调用。插画使用 `CodexStateArtwork`，区分真实无数据、失败和等待；不得添加无业务来源的媒体播放器、底部导航、营销轮播或假进度。新增图谱必须登记对应组件与测试。CI 执行 `python3 android-app/tools/check_ui_assets.py`，检查源文件、原生路径、使用位置和测试声明，输出 JSON 与 Markdown。设备测试另验证实际加载、操作和截图。Figma 原画板受 Starter MCP 配额限制，未完成同步；这些 SVG 可作为后续导入的独立编辑素材。
