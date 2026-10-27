# Claude Code 2.1.310 独立源码工程 v3（恢复增强版）

本包以 **2.1.310 原版提取的 2551 个 JavaScript 模块**为代码主体，保留 24 份随包源码、2 个原生 N-API 插件。仅收录源码、必需资源、运行适配、构建及测试文件；**不包含 v35 研究成果目录、旧版源码副本、原版可执行文件**。这是非官方、尚未通过全功能等价验收的恢复工程，不可当作官方发布物。


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

## 源码级恢复：Hook 与原生音频接口

- 恢复 `chunk-y5vkfxm0.js` 中 Hook 元数据、事件分组、匹配读取与排序函数的可读名称，旧导出保持兼容。
- 恢复 `chunk-2ctyt5p9.js` 中原生音频适配器的可读源码，保持回退加载顺序、参数转发与缺失行为。
- 新增 6 项直接执行当前源代码函数体的受控测试，覆盖新版 Hook 事件、用户与插件配置合并、权限说明、音频初始化、故障回退和录放状态。
- 不宣称已恢复 .node 二进制内部实现、整个 Hook Worker、MCP 或远程服务的完整行为。

## 全模块函数索引（可复现）

运行 `node scripts/source-function-index.cjs --full`，使用 TypeScript 5.x AST 分析器扫描全部 2551 个模块，生成函数位置、调用表达式、模块导入导出及哈希索引。大索引保存在本地 `.generated/`，不混入代码提交；`resources/function-summary.json` 保存已经核验的全量聚合和源码指纹。运行 `npm run audit:functions` 可检查当前源码是否与聚合记录一致。该脚本只解析源码、不执行业务函数，不将函数候选误判为语义等价。

