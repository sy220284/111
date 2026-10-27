# Claude Code 2.1.310 独立源码工程 v3（恢复增强版）

本包以 **2.1.310 原版提取的 2551 个 JavaScript 模块**为代码主体，保留 24 份随包源码、2 个原生 N-API 插件。仅收录源码、必需资源、运行适配、构建及测试文件；**不包含 v35 研究成果目录、旧版源码副本、原版可执行文件**。这是非官方、尚未通过全功能等价验收的恢复工程，不可当作官方发布物。


## 命名与目录规范（权威方案）

全仓源码、资源、工具、技能、插件、模板与接口声明的统一规范，详见 [源码与资源统一命名规范](docs/源码与资源统一命名规范.md)；每次已执行变更与验证结果记录在 [命名规范化改动记录](docs/命名规范化改动记录.md)。迁移保留原始 2.1.310 BunFS 键、字节哈希和功能契约，不因改名宣称功能已恢复。

## v3 源码恢复增量：资源函数及消费者（2026-10-27）

- 将 `src/chunk-6gaf2snv.js` 中 2.1.310 实际使用的 Zstandard 识别、二进制/文本同步读取、异步读取恢复为具名函数；保留 `Nwo`、`dt`、`Xft` 的原始导出别名及原有异常契约。
- 为 `src/chunk-a2y39866.js` 的 `Fe` 添加可读别名 `loadEmbeddedModule`，已有消费者无需修改。
- 新增 `scripts/build-resource-consumers.cjs`，基于 2.1.310 实际 `__cc310AssetPath` 字面量调用位置，核对全部 250 份资源的 SHA256、来源、类型、压缩方式和引用模块；其中 247 份有直接字面量调用位置，其余 3 份是独立附带 JS 文件。
- `npm test` 增加资源调用索引的即时检查，以及新旧函数导出、Zstandard 解压、文件缺失异常契约测试。

更新消费者清单可运行 `node scripts/build-resource-consumers.cjs`，生成 `resources/consumers.json`。索引只代表可证明的字面量静态调用位置，不将它误当作所有动态资源或整条技能执行链的验收。

## v3 新增的实际修复

- **找回此前缺失的 11 份真实 2.1.310 资源**：利用 v35 保存的原始 2.1.310 ELF 中资源顺序、相邻内容边界重新提取。包括 7 份 OpenTelemetry 文档、2 份 README、AWS 平台指南、C# Tool Use 文档，绝非占位文本。
- **原本借自 2.1.300 的 55 个资源候选，已逐字节确认与 2.1.310 原件一致**，不再标记为未确认候选；原始字节未改动。
- **对 12 个在原版 ELF 中以 UTF-16LE 存储、而 v35 中为 UTF-8 的文档做了原始全内容交叉校验**，保留正常 UTF-8 文档，运行时无需处理原 ELF 的 UTF-16LE。
- 现在 `resources/bunfs` **250 份资源齐全**，其中 247 份由已转换的虚拟资源路径引用、另 3 份随 v35 提取但没有在这 247 次原始引用里直接引用。2 个真实 `.node` 插件包含在资源中。原始源码中的 247 次旧 `/$bunfs/root/` 路径映射保持不变。
- 新增运行测试：实际加载包含 105 份 API 参考文档的源码模块，核实此前会因缺资源而中断的技能文档内容；每份资源做 SHA256 校验；验证 12 个编码规范化资源。

## 安装及使用

Linux x64，Node.js **22.16.0 或更高**（需要支持 Zstandard 原生解压），且可以访问 npm 以安装 `ws@8.22.0`。

```bash
mkdir claude310 && tar --zstd -xf Claude_Code_2.1.310_源码修复版_v3_20261027.tar.zst -C claude310 --strip-components=1
cd claude310
bash scripts/install.sh
```

单独运行可离线执行的测试：

```bash
npm test
node bin/claude-310.mjs --version
```

完整启动检测（需要安装 `ws`）：

```bash
npm ci --ignore-scripts --no-audit --no-fund
npm run verify:runtime
```

安装失败时脚本返回非零状态，不将“版本显示成功”误判为完整安装成功。

## 插件与函数复查

原版 2.1.310 和旧版 2.1.300 都只发现 `clipboard-napi.node`、`audio-capture.node` 两份 `.node` 嵌入插件，新包两者均为 2.1.310 原件。2.1.88 的 `modifiers-napi-src`、`url-handler-src`、`image-processor-src` 属旧版源码参考，目前没有证据表明 2.1.310 主程序仍需要这些同名旧插件，**不强行混入旧实现**。

2551 个主模块已恢复，并能静态解析导入/导出关系；v35 的 624 组新旧函数匹配是函数语义对照候选，不能当作“624 个函数缺失”。当前没有经代码验证的缺失函数可直接从旧版安全补入，继续保留 2.1.310 的原始主体实现，避免把旧版函数覆盖新功能。

## 尚未闭环的底层问题

- 由于当前运行环境不能连接 npm，`ws` 依赖尚未完成本机安装，Node 冷启动 `--help` 仍会报告 `ERR_MODULE_NOT_FOUND: ws`。**离线测试通过不等于主程序安装成功**。
- 原程序依赖多种 Bun/JSC 专属能力（例如 `Bun.ant`、`Bun.spawn`、`Bun.Terminal`、`Bun.SQL`），Node 22 下并不具有原版的全部运行行为；部分能力及硬件接口尚未验收。
- OAuth、远端会话、MCP 上传、打印取消传播、Daemon/Hook Worker 异常恢复等业务路径，仍需要与官方原版的实际行为比较。

本版本确认的是 **资源恢复闭环、模块静态检查、技能文档动态加载、原生插件导出检查和版本浅入口**；不宣称完成整个 Bun 运行时兼容与全部业务函数恢复。

## v3 源码恢复增量：Hook 函数与函数级索引（2026-10-27）

- 2.1.310 `chunk-y5vkfxm0.js` 中的 `xxt/e8t/Z5e/zto` 已恢复为可读的 Hook 元数据、事件分组、匹配排序及匹配读取函数，保留原导出别名，未以旧版覆盖新事件或权限逻辑。
- `tests/hooks-source-restoration.test.cjs` 针对当前源码函数体执行受控行为验证，覆盖新 Hook 事件、工具匹配、插件来源、空匹配，以及异常分支；不需要启动整个 CLI。
- `scripts/source-function-index.cjs` 按 TypeScript AST 遍历 2551 模块，枚举实际函数实体、位置、形参数量、调用点、模块导入导出、源码哈希和静态引用，输出可再生的索引。使用已有 TypeScript 5.x 分析器（`node scripts/source-function-index.cjs --full`），不执行源码业务逻辑。
- `resources/function-summary.json` 保存当次可重复审计的聚合计数和源码指纹。较大的函数/调用点详情仅在 `.generated/` 按需再生，不提交研究中间产物。
- 这些工作不代表所有函数语义已经确权，亦不代表完整 Hook Worker、插件或 MCP 端到端行为验收完成。

## v3 源码恢复增量：插件测试源码（2026-10-27）

- 从真实 2.1.310 内嵌 `kit-modules-02f8fcd0.js` 恢复 **294 份独立的 JavaScript 源文件**，写入 `src/plugin-testing-kit/`。这是插件测试工具包，**不计入 2551 个主程序模块**。
- 原始文件存在 **628 处静态 import/re-export 引用**。已将原构建别名和无后缀引用恢复为实际相对路径，保持导出声明不变；运行测试另发现并修复插件测试收集器的可变参数转发错误。
- `scripts/restore-plugin-testing-kit.cjs` 可从保留的原始内嵌文档重复生成源码；`--check` 按文件逐字节比对，防止将人工修订误当成原版源码。
- `npm run check:plugin-kit` 检查 294 份源码语法、628 条依赖并实测结构化比较与插件测试收集器的关键行为。
- 修正 `npm run rebuild:functions` 指向不存在脚本的问题，统一使用现有 `source-function-index.cjs`。

注意：恢复的是插件测试辅助工具包，不能据此宣称 Hook Worker、插件 API 及主代理全功能已经端到端验收。

- 插件测试收集器修复：原件 `test: (name, rest)` 将回调函数直接交给需要可迭代参数数组的 `testCaseOf(title, rest)`，导致实际注册失败；改为 `test: (name, ...rest)`，恢复单回调及带选项两种调用。生成脚本对该改动有明确、可复验的修复规则。

- 完整调用链复查发现公开 `test()` 对 `collector.test()` 的旧式数组传递；已同步改为可变参数展开，并把公开入口的单回调/带选项测试加入 CI，避免仅修复内部方法却破坏实际外部调用。

## v3 新增：2.1.310 插件接口声明与 Hook/流式协议针对性验收

- `resources/tools/plugin-api/types/claude-code.d.ts` 原始声明不改动；从 `HookStream`、`StreamNext`、`PermissionRequestDecision`、`PreToolUseResult`、`ToolCallInput/ToolCallResult` 等定义提取验收契约，防止误用旧版结构。
- `tests/function-hook-worker-contract.test.cjs` 直接执行 `hooks-worker.js` 中现有 `B/G/z/J/Q/W` 函数，覆盖 next 消息编号、工具操作回执、未加载环境拒绝、卸载中断与流提前关闭。
- `tests/streaming-tool-contract.test.cjs` 直接执行 `chunk-x3dkgs4r.js` 现有流事件转换/比较函数，覆盖文本、thinking、`input_json_delta`、索引和错误增量；另校验 `chunk-1nnn2mdy.js` 的 Hook 来源和 PreToolUse 规则。
- `resources/docs/other/permissions_external-*.txt` 属权限策略指导资料，不能作为整个 Claude Code 权限引擎的同一版本可执行规范，未直接复制为权限实现。
- 仅新增 2.1.310 源码的针对性回归，不重复处理已完成的 294 份插件测试源码；这些测试不等于完整 Worker 宿主/线上 SSE/工具执行与权限决策端到端等价。

## v3 源码恢复：文档行为复核与函数证据（2026-10-27）

- 依据原始 `reference-b976cc65.md`、`preserved-thinking-migration`、`prompt-caching`、`plugin-eval`、`error-codes` 和 `component-schemas` 补充源码行为测试，排除了此前已完成的 294 份插件测试源码。
- 新测试覆盖：`session.append` 的行筛选/幂等/关闭/Hook 失败，原版 Cache/Thinking tokens 统计、停止理由/拒绝识别、拒绝后未完成工具补录、Plugin Eval 聚合检验、原版 Hook/Agent/MCP 事件注册域。
- `resources/function-verification-ledger.json` 显式记录 **29 个已接受定向行为测试的原版短符号函数**，包含原始模块、源码定位锚点、业务职责和对应实际测试。其状态全部是 `targeted_behavior_test_only`，不得充作 29 个“完成语义重建”的函数，更不能推导全业务链验收。
- `scripts/verify-function-ledger.cjs` 对登记项的源函数位置、测试文件和原 AST 全量函数摘要进行复查，`npm test` 每次执行，防止源代码或测试移除后还虚报覆盖。
- 2551 个主模块的既有 AST 索引涵盖 **168102 个函数语法实体**，仅说明索引规模，不代表其已全部恢复语义。原版源码中没有证据的功能不要从通用 API 文档直接移植为“2.1.310 官方行为”。

- 阶段 A/C 实际源码整理：将 `src/chunk-1nnn2mdy.js` 内的 4 个 Hook 来源、插件身份与工具匹配函数恢复为有业务命名、输入输出契约的可维护函数，旧 `QMn/ZMn/jhs/Whs` 导出保留为相同函数的别名；不改变 2.1.310 的识别规则。新增独立边界测试。
- 源码指纹校验已在 `npm test` 执行：源码改变后应同步更新 `resources/function-summary.json` 的完整代码哈希，防止把旧 AST 索引标为当前源码。

- 阶段 A/D 新增对 `src/chunk-q85nvv4x.js` 的 Hook 事件消费者过滤函数语义恢复：从原短符号 `uun/HE` 恢复可读的托管策略模块识别与敏感 Hook 事件过滤实现，保持旧导出和按事件区别处理的权限边界，新增原模块导入实际链接与运行测试。未增加对其他权限规则的无证据猜测。

- 阶段 A/D/E：原版 `chunk-y7venc25.js` 的 Plugin Eval、MCP serve、Agent JSON、Remote Control 与 Project Verb 五个命令识别函数恢复为具名、独立可读源码，保留全部已有公共导出及细节边界。运行回归测试验证命令识别；这只验收 CLI 分流函数，并不声称这些后台功能均可运行。


## 阶段 A/C 主查询循环与会话写入调用链

- 追踪并通过 CI 静态复查 `src/chunk-tqjdehqd.js` 的原版 `pN -> Ou -> Du` 结构，明确外层会话保持 `Da` 的接入位置、内部 `Du` 的 Thinking 续接/工具收尾状态；见 `scripts/trace-query-loop-callchain.cjs`。
- `tests/query-loop-recovery.test.cjs` 针对原始 `Au/Uf/Nf/qf/Gr/Pu/Qf/vu` 执行 8 组 Thinking、截断、消息队列及失败 tool_result 测试。
- `tests/session-append-storage.test.cjs` 直接执行 `src/chunk-w9w4r9w1.js` 的 `D/Te/u`，覆盖持久化路径 Hook 变更、异常兜底、fills 与不可追加事件；这是与上层 `Da` 对应的存储端阶段检查。
- `resources/function-verification-ledger.json` 仅登记有限行为测试，所有登记项不得计作全功能恢复或已构建通过。

## 阶段 D 权限模板源函数恢复

- `src/chunk-eq8vmbm3.js` 的五个原始导出恢复为具名函数，保留原版空缓存初始化、摘要匹配优先级及 `PreToolUse` 条件；原版恒真函数保留恒真语义，不能推定其已实现完整权限引擎。
- `tests/permission-template-source.test.cjs` 从实际代码加载注册表逻辑与导出，覆盖空表、同表复用、哈希和 ID 匹配、大小写边界、旧符号一致性。

- 阶段 C：对主代理原版流式模型改写函数 `chunk-x3dkgs4r.js::Sr` 新增 `tests/thinking-model-override.test.cjs`；严格执行原函数，覆盖 Thinking 未完成时保持原模型、安全切换、只提示一次和 Hook effort 更新。仅登记定向行为测试，不把这段测试算作完整恢复。

## 阶段 D MCP OAuth Issuer 回显校验

- `src/chunk-x436erds.js` 五个 URL/Issuer 校验函数恢复可读名称，保留 `pt/pe/_t/mt/kUn` 全部原短符号调用；不改变默认 observe 模式、严格模式或 RFC 相关规则。
- `tests/mcp-issuer-echo.test.cjs` 对原函数执行 9 组行为检查，覆盖 HTTP(S)、地址差异、跨源拦截、遥测失败隔离、功能开关失败回退和身份兼容；此为定向验证，完整 MCP OAuth 登录/刷新尚未端到端验收。

## MCP OAuth 生命周期恢复

- `src/chunk-x436erds.js` 恢复 6 个可读 OAuth 流程与凭据清理函数，并保留 `jAi/WAi/LTr/Le/Dhi/Hhi` 原短符号。执行离线测试 `tests/mcp-oauth-lifecycle.test.cjs`，验证授权 Promise 竞争、没有令牌的持久化占位项清理、`offline_access`、微软 OAuth 发行者域名边界。远端认证仍未验证。

- 阶段 D Token Refresh：新增 `tests/mcp-oauth-refresh-lock.test.cjs` 直接执行 `Ben.refreshAuthorization` 和 `Ben.readConcurrentRefreshWinner` 原类方法，核对锁名、加锁参数、ELOCKED 重试、竞争赢家、刷新口令与异常释放，不涉及真实密钥或网络授权。

- 阶段 C 主代理 `Du` 周边：恢复取消回合定位、最近用户消息、模型失败回退文案与错误原因截断四个函数的可读命名；保留 `sr/Ff/Gf/Iu` 原短符号。新增源码级状态测试，不把辅助函数验证算作完整 `Du` 执行状态机验收。

- 阶段 D OAuth `_doRefresh`：补充 `tests/mcp-oauth-refresh-errors.test.cjs`，使用真正类方法验证授权元信息、SDK 错误类型、`invalid_grant`/`invalid_client` 的并发赢家保护、指数退避和失败收尾；Provider 可读类名为 `McpOAuthProvider`，保留原版 `Ben` 短符号。

- 阶段 D MCP 连接：`chunk-fbc1ty1s.js` 的结算函数 `hfr` 恢复为 `applyMcpConnectionSettlement`，旧符号兼容。离线测试覆盖配置过期、账户切换、连接禁用、策略拒绝以及成功安装时工具/命令登记，防止迟到连接覆盖已有状态。

- 阶段 E Daemon：恢复 `chunk-2af27mew.js` 的故障分类、重试计数、退出决策和 `chunk-yhfqc2mr.js` 的状态/日志路径，共 5 个可读函数，保留所有旧符号。新增 5 个边界测试；真实 Daemon 进程、OS 恢复与续跑仍未验收。

## 阶段 F：可读函数旧符号防回退验收

- `resources/restored-symbol-aliases.json` 记录 10 个主模块中 38 个已改造成可读声明的原短符号接口，排除只增加测试、没有改源码的函数。
- `scripts/check-restored-aliases.cjs` 校验可读声明、旧符号链接、已有定向行为测试三者同时存在，并由 `npm test` 持续执行。防止并行提交无声覆盖恢复实现；不因此宣称整个 Claude Code 语义或线上环境等价。

- 阶段 D/E MCP 故障恢复：`src/chunk-fbc1ty1s.js` 的 `QRn/aZr` 已恢复可维护业务命名，原短名保留；测试验证失败/连接中状态筛选、当前配置一致性和可嵌套的重试引用计数。两项纳入函数证据与防回退清单，防止账户/配置漂移导致错误重试。

- MCP 重试执行器闭环：`R6t` 恢复为 `retryFailedMcpConnections` 并保留旧引用，离线执行原逻辑验证退避延迟、身份换号、Abort、配置漂移、策略拒绝、成功发布与异常资源释放；此仅为模拟宿主执行，并未实现真实联网 MCP 端到端测试。

- Agent/Worker 恢复分支：`src/chunk-g936x3jq.js` 的 `mfo/en/Un` 恢复可读命名并保留原短符号，直接执行原函数检查子代理 `tool_use_id` 关联、Agent ID 隔离、关机中断后的工具结果映射及文本回退；新增 7 项离线回归，尚不代表真实 Worker/OS 恢复流程已完整验收。

- Daemon 安全恢复：`src/chunk-wapyem7a.js` 中旧式 `cc-daemon-*` 过期目录清理函数 `Zt` 恢复为 `pruneExpiredLegacyDaemonLogDirs`。添加真实源函数测试验证仍在使用的 UID/PID/哈希命名跳过、单 stderr.log 限制、截止时间、目录 inode 防竞态重验和失败不删除。此处仅为清理安全链，不含运行中 Daemon 进程复活。

- 阶段 D MCP 工具执行层：恢复 `src/chunk-r4g83g7p.js` 的 `Vg/Wg/Gg/Gd` 四个可读函数，保留原符号和协议分支。新增真实执行测试，覆盖 tools/list、resources/read 禁用缓存、tools/call 现代协议任务上下文、声明的任务引用以及旧协议不注入任务元信息；真实 MCP 远端服务仍未验收。

- 阶段 E Agent 恢复链：`src/chunk-g936x3jq.js` 的 `dwr/cwr/sn` 已恢复具名代码，原短符号兼容。执行级检查覆盖冻结的恢复 agentId、Hook 来源继承、启动中断句柄释放、插件拒绝、历史消息 UUID 父链及压缩 UUID 一致性。未声称所有后台 Agent OS 进程恢复已完成。

- 阶段 E 子代理恢复链：恢复三条 notification/inline/reply 入口、恢复单飞锁 `it` 与 Agent 通知关联结果 `Yn` 的 5 个具名源码，保留原短符号。新增直接执行原函数的子代理并发隔离、正常/异常释放及元数据回填测试；此处并不宣称网络或进程恢复完整验收。

- 子代理续跑错误分类：恢复 `Jgi`/`CF`/`uwr` 为可读函数，保留旧短符号；新增真实源函数测试验证临时/永久错误、插件拒绝类型与 resumedInline 标记，避免恢复失败被误分类。
