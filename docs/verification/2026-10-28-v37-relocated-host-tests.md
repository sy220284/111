# 111 v37 工具路径迁移后的真实宿主复验

- 当前 Artifact SDK 的真实入口：`tools/bundled/artifact/scripts/appifact_sdk.js`；通过 Unix FD RPC + 文件资源清理测试检查。
- 当前 Thinking/Compaction 的 Python 差分分析器：`tools/bundled/evaluation/prefix_diff.py`；调用真正由 CLI 本地模型测试捕获的请求。
- Claude Test Playwright MCP 的依赖锁：`tools/bundled/claude-test/scripts/lib/tools-lock.json`；CI 的路径配置由独立治理修复 PR #69 更新。
- 三处迁移后路径均有显式文件存在检查，旧 `resources/tools/` 运行路径不可复活。
- 实际验收仍须以 `v37-host-real-acceptance.yml` 本提交三组作业全部成功为准。原版云端 / JSC / 原生硬件等价不在本次范围。
