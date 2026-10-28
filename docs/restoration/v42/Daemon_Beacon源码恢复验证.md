# v42 Daemon Beacon 持久化与交互进度：源码级恢复

基准：Claude Code 2.1.310 原版拆出的 `chunk-t2adf9g4.js`，保持实际实现、异常分支、短符号与运行路径不变。2.1.88 及v40研究包用于对照语义，不采用旧版函数体覆盖。

## 已落实的真实函数

- `$e → isDaemonRecordExpired`：超出一天或时间戳位于当前时间之后60秒以上时判陈旧。
- `tn → updateOwnedDaemonBeaconAttempt`：仅允许整数尝试号严格递增；写入前检查当前所有者；支持本地文件与 storage V5。
- `oxt → mergeOwnedDaemonBeaconProgress`：记录预期标志、启动、交互达到、首次帧统计；保留只增不降及首次attach字段，并使用原版持久化调用。

本轮只做源码内原位改名与 `const old=newName` 别名，不重写业务算法。原有 Daemon 协议测试夹具同步改为 AST 提取具名函数，测试内恢复原短符号绑定。

## 已接入验证

`tests/daemon-beacon-source-restoration.test.cjs` 针对真实源码函数体执行6组定向测试：原短符号同一引用、过期门槛、尝试计数单调、旧所有权防写入、交互状态不可降级、storage V5 与目录不可用分支。所有分支仅使用隔离依赖与伪存储，不向真实目录写入。

每项在 `resources/function-verification-ledger.json` 中继续标记 `targeted_behavior_test_only`；归档到 `resources/restored-symbol-aliases.json`，并随 `npm test`、AST全仓指纹、CLI启动和代码图谱索引复核。

## 明确未完成

真实后台守护进程重启续跑、跨设备会话、真实 OAuth/MCP 端到端以及全部 Bun/JSC 语义仍未完成。本批仅恢复3个已核对的语义明确函数，不把源码函数数量当业务链完成率。
