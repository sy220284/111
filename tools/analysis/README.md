# 111 代码依赖图与函数语义证据工具

这套工具在已有的 TypeScript 5.9.3 AST、函数指纹索引、定向行为证据账本之上，新增全仓模块依赖图、函数调用候选图、正反向查询和变更影响分析；不修改 2.1.310 的原始源码。

## 命令

```bash
npm run check:code-graph
npm run audit:code-graph
npm run rebuild:code-graph

# 查询具体函数的候选上游/下游及语义证据
node tools/analysis/code-graph.cjs trace --file src/chunk-tqjdehqd.js --symbol pN

# 某个模块被修改，静态导入关系上哪些模块可能受影响
node tools/analysis/code-graph.cjs impact --file src/chunk-tqjdehqd.js

# 把全量 JSON 导出到指定文件
node tools/analysis/code-graph.cjs scan --full --output .generated/code-graph.json > /dev/null
```

扫描范围为 2,551 个真实主模块（含嵌入式 Hook Worker）与两个 runtime 适配模块。生成的图谱包含静态模块依赖、SCC 循环依赖分组、调用表达式总数、确定可被静态分析关联的**候选**调用边、无法解析的动态/属性调用数量、针对性函数测试证据。每个候选调用边保存最多三处文件/行/列证据，避免巨量重复日志。

对于 import 重命名、namespace import 与本模块顶层函数声明，工具会查找候选调用目标；调用类实例方法、闭包变量、回调、反射和动态派发时不会捏造目标。候选链接不意味着 JavaScript 在运行时必然调用该函数；静态依赖受影响模块也不等于实际运行时影响。语义证据来自 `resources/function-verification-ledger.json` 已登记的真实测试，**不**自动生成“已验证的函数职责”，更不表示端到端业务能力恢复。

全仓 CI 在“源码恢复验证”中独立运行图谱分析，并上传 `111-code-graph-evidence` Artifact（完整图谱和指标摘要），不在源码恢复的每个快速测试阶段重复生成巨量图谱。

## 与第三方分析工具的关系

- **TypeScript AST**：已经复用现有锁定版本 TypeScript，无需更改 Node/npm 离线依赖和锁文件。
- **ts-morph**：适合需要 TypeScript 类型检查器的更强符号查询，但当前尚未将其加入本仓锁文件，不能宣称已安装或已完成全程序解析。
- **dependency-cruiser**：适合独立模块规则和依赖可视化；本次内建生成静态模块图，未将第三方程序作为现有离线工具链的强制依赖。
- **CodeQL / Joern**：属于进一步的数据流、污点分析和代码属性图能力；尚未部署，不将它们的功能算入当前验收。特别是 CodeQL 对私有仓库的使用和分析结果上传受许可、授权限制，需要另行确认。
- **V8 运行时覆盖率**：后续可在独立安全的测试夹具中导入，静态图不能取代真实运行证据。

下一步应先根据已生成的代码图谱和未解析候选边数量，针对 Agent、Tool、Hook、MCP、Daemon 等关键路径新增高价值的动态验证与语义测试，而不是把 649,628 个调用表达式直接标成同等可信的真实调用。
