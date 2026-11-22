# 111 Android APK 瘦身评估与保护性验收

## 决策

**功能完整优先。#61 的 R8 / 资源压缩虽通过 Release 编译与签名 smoke，但在完整 ARM64 原生核心安装包、29 个页面和动态资源路径未经运行级验收前，本轮保留既有未混淆的 Release 行为；#62 跳过进一步删减。** 后续体积收益以完整 ARM64 Codex 运行 APK 的实际数据为准，所有变更必须通过 Codex 启动、终端、会话、Git、ripgrep、更新恢复和关键 UI 的运行级验收。

## 已核实的事实

- `android-app/app/build.gradle.kts` 的 Release 维持 `isMinifyEnabled=false`、`isShrinkResources=false`；PR 曾验证混淆版能编译和签名，但未证明正式 ARM64 核心启动、动态资源和全页面行为正常，因此本轮回退该优化。正式发布通过 `codexReleaseTargetAbi=arm64-v8a` 仅构建 ARM64。调试包与 UI 专项验收不等于最终手机安装包。
- `CodexConnection.kt` 通过 `applicationInfo.nativeLibraryDir/libcodex_server.so` + `ProcessBuilder` 启动 Codex 原生核心。该 ELF 文件必须存在、与设备 ABI 一致，并被 Android 解包（`useLegacyPackaging=true`，清单设置 `extractNativeLibs=true`）。
- 完整运行包必须同时包含 `libcodex_server.so`、`libcodex_code_mode_host.so`、`libcodex_rg.so`、`libcodex_git.so`；仅保留 Server 会损失 Code Mode 和本机开发工具。
- `libhpatchz.so` 是本机增量升级恢复组件。完整签名发布流程已检查四个 Codex 原生组件、hpatchz 和 APK 签名。PR 内的 Release smoke 仅验证签名/构建与 `libhpatchz.so`，没有导入完整 Codex 内核，因此无法单凭该 smoke 判断核心运行能力。
- 已通过的独立 UI 验收覆盖 360/393/411dp 模拟器、41 项测试/屏宽；它验证界面与交互，未覆盖 ARM64 正式包中 Codex 真实子进程的工作回合。
- 原清单提及的“143MB APK”来自旧基线，不能与当前 UI APK 压缩 artifact 体积直接比较。要获得真实瘦身收益，需要两次均包含相同 Codex 原生二进制、签名方式与 ABI 的完整 Release APK 做同口径比较。

## 项目判断

| 选项 | 处理方式 | 理由 |
|---|---|---|
| R8 与资源压缩 | **本轮跳过** | 编译/签名成功不足以排除实际核心运行、动态资源和全页面的功能退化；缺完整设备证据时保留原 Release 行为 |
| 发布 ARM64 独立 APK | **保留** | 符合 Android 手机的发布 ABI，已有签名与 ABI 检查；调试/兼容流程仍可覆盖 x86_64 模拟器 |
| 原生核心、Git/ripgrep 可执行代码继续 strip/删减 | **跳过进一步瘦身** | 这些文件承担核心能力，减少任何必需内容可能造成运行、诊断或动态加载异常 |
| 删除上游静态资源、模型目录、技能文件、语言数据 | **跳过** | 文件可能在运行期间按路径被访问，R8 静态检查无法证明全部运行时依赖 |
| per-language split / 动态语言包 | **暂不实施** | 可能影响离线使用、系统语言切换、辅助功能及 Unicode/多语言诊断 |
| 修改原生库 ABI / 16KB 对齐方案 | **保留现有验证** | 跨设备可运行性和安装兼容性优先 |
| APK 体积自动记录和核心文件保护 | **实施** | 只读检查 ZIP、ELF/ABI 和各部分体积，不修改 APK |

## 完整 ARM64 Debug 安装包体积实测（2026-11-22）

[main 构建 #45014548448](https://github.com/sy220284/111/actions/runs/45014548448) 的 `build` 作业成功生成完整 Android ARM64 Debug APK，`audit_apk_contents.py --mode full` 已输出如下实际统计。该运行的独立二次验收因缺少脚本检出失败；其工作流缺口已在 PR #35 修复。本节数据来自**成功构建作业内的实际 APK 文件审计**，不将未通过的二次作业算作完成验收。

| 实测内容 | 字节数 | 对实际 APK 的比例 |
|---|---:|---:|
| APK 文件 | 208,601,989 | 100% |
| APK 内原生库压缩条目合计 | 147,529,955 | 70.7% |
| APK 内 DEX 压缩条目合计 | 60,559,084 | 29.0% |
| APK 内 resources 条目合计 | 443,588 | 0.21% |
| APK 内 assets 条目合计 | 9,810 | 0.005% |
| 四个 Codex 原生组件未压缩合计 | 352,416,376 | 不适用 |
| Codex Server 单文件未压缩 | 264,825,176 | 不适用 |

结论：原生功能组件是 APK 的最大体积来源，维持完整保留；删减零散资源或语言包的总收益很小，仍跳过 #62。DEX 在此 Debug 包内占约 29%，说明 R8 有潜在优化空间；但只有全功能 ARM64 Release 运行验收完成后才考虑 #61，避免动态加载、界面和编译器裁剪导致回归。后续测量需固定同一上游 Codex 二进制、签名方式和 ABI，以签名 Release APK 对照签名 Release APK。APK 压缩条目和安装后所占设备存储空间是不同口径。

## 未发送草稿的内存优化边界

原清单 #64 提出的 128 条 LRU 曾会直接丢弃最旧的未发送草稿，进程重启时的反序列化也存在相同截断。此类淘汰会造成真实用户输入丢失。已改为：只清除无文字且无图片的空条目，所有未发送草稿保持完整，并通过 200 条草稿跨序列化/反序列化回归测试验证。后续若确需进一步控制内存，应先建设持久化冷热分层，确保任何被移出内存的草稿都可完整恢复。

## 自动验收

`.github/scripts/audit_apk_contents.py` 根据 **full**（正式、含原生核心）和 **ui-smoke**（仅 PR 构建、无 Codex 核心）区分验收，检查 Android manifest、资源表、classes.dex、hpatchz、完整包里的四个 Codex 必需原生组件和每个本机 `.so` 的 ELF64 ABI，并输出 APK/DEX/原生库/资源构成。

入口：`python3 .github/scripts/audit_apk_contents.py --apk <实际文件> --abi arm64-v8a --mode full --report <报告路径>`。

**发布门禁原则**：Server、Code Mode Host、Git、ripgrep 任一缺失，架构混入或 ELF 无效必须阻断。体积数字用于实际趋势分析，绝不通过盲目删除功能换得更小安装包。新增静态门禁后仍要在真实 ARM64 设备验证最终签名包。
