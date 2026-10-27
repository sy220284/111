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

- `resources/tools/plugin-api/types/claude-code-bf9d954c.d.ts` 原始声明不改动；从 `HookStream`、`StreamNext`、`PermissionRequestDecision`、`PreToolUseResult`、`ToolCallInput/ToolCallResult` 等定义提取验收契约，防止误用旧版结构。
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
