# 111 工程文档导航

- `architecture/`：全仓架构及执行路线
- `status/`：实际功能完成与未完成矩阵
- **[111 用户可感知功能语义实现度核查（2026-10-29，113项）](status/2026-10-29-用户可感知功能语义实现度核查.md)**：按源码语义、主入口接线和真实运行证据标明功能实现度与缺口
- `governance/`：PR 分类、交付真实性与未闭环清单
- `conventions/`：目录命名规范及迁移记录
- `research/v37/`、`research/v44/`：历史源码研究、原始固定版本对照
- `restoration/v40/` 至 `restoration/v45/`：业务恢复记录及对应验证范围
- `verification/v37/`：浏览器、宿主和工具的独立验收
- `history/`：历史版本原始研究材料

源码根目录：`core/`（主程序，旧版目录名 `src/` 仅见于历史文档）、`embedded/`（内嵌源码）、`plugin-testing-kit/`（插件测试源码）、`tools/bundled/`（附带独立工具源码）。原始压缩资源位于 `resources/bunfs/`，原始搬迁路径对照见 `resources/layout/relocations.json`。

两份原始工具来源清单已按内容归入 `resources/layout/claude-test-source-layout.json` 与 `resources/layout/design-sync-source-layout.json`，字段内的旧路径仅作原始来源记录，新执行路径由 `resources/layout/relocations.json` 定位。
