# 111 Android 正式发布流程

## 自动发布顺序

1. 向 `main` 合并客户端源码后，安卓版本构建与独立 UI 验收并行推进；如果同一提交同时触发原生内核和 APK 工作流，APK 会等待原生构建结束后只执行一次。原生构建优先查找**Android Rust 源码、父级 Cargo 配置、平台适配器和 Rust/NDK 编译配方完全相同**的旧 ARM64 构建，已验证产物仍有效时复用；真实编译输入变化、产物缺失或过期时重新编译。
2. 仅当**同一源码提交**的 ARM64 APK 构建及二次核验、Kotlin/协议/Lint、360/393/411dp 设备交互与截图验收在 `main` **全部成功**时，「111 Android 正式发布」才会进入版本检查。两项检查的先后顺序不限；也可以从 Actions 手动启动发布，但同样需要这两项成功记录。
3. 比较上一个正式 Release 到本次源码的变更：仅当正式 APK 的 Android 源码、打包配置、发行资产或 ARM64 原生编译输入真正变化时才发布新版本。CI 调度、测试、上游非 Android 文件和仅更新上游版本标记均不再产生重复 APK。
4. 从有效版本标签及 `.github/release-version` 计算单调递增的版本号与 Android `versionCode`，禁止复用曾发布的编号。
5. 从成功的原生构建记录中选择**完整输入完全一致、运行提交属于当前主分支历史且产物尚未过期**的 ARM64 内核。普通 Android 修改复用同一原生内核；Android APK 验收只依赖 ARM64 原生内核，独立 UI 模拟器不依赖手机原生内核。若未找到合格产物，先执行原生构建，再完成设备验收。
6. 原生构建同时生成 App Server 与独立 Code Mode Host，V8 与 Cargo.lock 的版本、Android 架构和沙箱配置必须匹配；逐一核验 ELF 架构、动态链接器、16 KB 对齐及所需共享库，再组装手机 APK。
7. 复用同一提交在独立 UI CI 中通过的 Kotlin 单元测试，在此执行 Release Lint 并只生成一个 **ARM64 正式签名手机 APK**；再核验版本号、包名、签名证书、APK 文件完整性和 ABI 隔离。
8. 由合并 PR 生成中文更新说明；仅上传 ARM64 手机完整 APK、`SHA256SUMS.txt`、`update-manifest.json`。有可用上版手机 APK 时尝试生成 ARM64 二进制增量包，必须通过原样回放、SHA-256 对照且小于完整包的 65% 才会发布。
9. 正式签名发布成功后，发布工作流在同一流水线直接调用可复用版本清理作业，自动保留最新 3 个正式 Release（历史 Git 标签与提交保留）。**没有定时清理任务**。

## CI 优化与上游升级

- `import-codex.yml` 仅接受手动操作，可以指定官方源码完整提交 SHA；更新后创建中文 PR，经协议、ARM64 编译与 Android UI 验收后合并。
- 发布调度只监听主分支验收事件。发布脚本预检查在相关 PR 或手动发布时执行，避免每次 UI 或 APK 检查结束都重复跑一轮。
- 独立 `verify-android-git.yml` 已移除，ARM64 Git 编译、加载器、架构及 16KB 内存页对齐仍由原生核心构建验证。
- 仅变更上游文档、SDK、历史工作流或上游提交号标记时不会重新构建原生核心；所有真实 Rust/Cargo/Android 编译输入变化仍触发构建。
- `.github/scripts/test_ci_scope.py`、`test_native_reuse.py`、`test_decide_android_build.py`、`test_verify_release_acceptance.py` 和 `test_release_change_gate.py` 持续核对触发去重、核心复用、完整设备验收与新版本发布条件。

## 首次配置：独立且长期一致的 Android 签名

在本机安全位置创建长期保存的 111 专用密钥库：

```bash
keytool -genkeypair -v -keystore 111-release.jks -storetype PKCS12 \
  -alias 111-release -keyalg RSA -keysize 3072 -validity 10000
```

提示时使用**相同的密钥库密码与私钥密码**（PKCS12 默认一致）。将密钥库备份到离线安全位置。不要提交到 Git 仓库。

在 GitHub 仓库的 **Settings → Secrets and variables → Actions → New repository secret** 中配置：

| Secret 名称 | 内容 |
|---|---|
| `RELEASE_KEYSTORE` | `111-release.jks` 整个文件的 Base64 文本（`base64 -w0 111-release.jks`；macOS 可使用 `base64 < 111-release.jks | tr -d '\\n'`） |
| `RELEASE_KEY_PASSWORD` | 创建密钥时的密码 |

这与 777 的两个 Secret 名称一致，但应使用**111 独立的签名密钥**，私钥别名固定为 `111-release`。更新同一个应用的正式版本需持续使用该密钥。遗失签名密钥会影响旧版应用的升级安装。

首次签名配置完成后，在 Actions 手动运行一次「111 Android 正式发布」。以后合并到 `main` 且核心、界面两项验收都通过的应用变更会按上述流程发布。若需要更新原生核心，请先运行「编译 Android 原生 Codex 核心」。

## 版本与下载产物

首次版本：`v0.1.0-111.1`。下次发布的尾号自动加一。更新 `.github/release-version` 的 `0.1.0` 前缀即可提升主次修订版本，尾号仍保持单调增加，以保证 Android `versionCode` 向前递增。

正式 APK 采用包名 `io.github.sy220284.codexandroid`、最小 Android 10（API 29）：

- `codex-android-arm64-v8a-<版本>.apk`：安卓 ARM64 手机。
- `SHA256SUMS.txt`：发布文件校验值。
- `update-manifest.json`：schema 2，仅记录 ARM64 手机 APK 和增量包。
- `delta-<旧版>-to-<新版>-arm64-v8a.hpatch`：仅在成功生成、校验并满足体积阈值时提供。

`update-manifest.json` 是 App 内检查更新的正式元数据。设置中心点击“检查更新”后，客户端读取最新正式版，优先下载与当前已安装 APK SHA-256 精确匹配的增量包，由 HDiffPatch 恢复并校验最终 APK；没有匹配增量或恢复失败时自动切换到完整 APK。下载完成后打开 Android 系统安装确认界面，首次需要允许此 App 安装更新。系统按原签名进行升级认证。

独立 UI 验收包和日常 Actions 调试包使用调试签名，无法覆盖正式签名版本；首次使用正式版前请先备份数据并安装 Releases 中的正式签名 APK。HDiffPatch 安卓恢复组件使用上游 v5.1.3 官方 SDK ZIP，CI 校验固定 SHA-256 后装配。

Actions 现有的调试包签名与正式包签名不同。第一次由调试包切换至正式包时可能需要卸载调试包，卸载前应导出应用数据。

## vivo/OriginOS 安装器「打开」真机验收

下一次正式签名的应用内升级必须按 [vivo 最小验收与证据格式](VIVO_SELF_UPDATE_ACCEPTANCE.md) 记录一次真实安装器「打开」进入首页的结果。Android 35 模拟器自更新回归属于持续 CI 检查，不能替代 OriginOS 真机交付证据。

## 验收与故障定位

- Android 设备完整构建会使用与正式发布一致的 `-WD / zstd / xxh128` 参数生成小型增量测试包，在 Android 35 模拟器上用应用实际打包的 `libhpatchz.so` 重建并逐字节比对；同时测试损坏补丁被拒绝。
- 更新器显示当前已安装版本，并优先进行增量恢复。按 777 的更新交接机制，每次新下载先清理上一轮暂存；经过哈希和签名验证的 APK 在交给系统安装器前写入持久化交接标记，进程退出后仍保留最多 30 分钟，允许安装器持续读取；检测到目标 `versionCode` 已安装时立即清理。损坏的交接标记按有界保护窗口处理，安装器未完成时不会因应用重启误删安装包。HDiffPatch v5.1.3 的 MIT 及第三方许可文本随 APK 放在 `assets/licenses/HDiffPatch-LICENSE.txt`。


- PR 内的 Release 签名构建属于打包和签名冒烟测试；只有原生构建、完整双组件 APK 和 Android 35 真正启动测试均通过，才算运行核心验收通过。
- Code Mode Host 在 Android 使用 APK 内 `libcodex_code_mode_host.so`。构建时为 ARM64 核对固定 SHA-256 的 V8 152.2.0 沙箱产物，原生交付只构建 ARM64，x86_64 模拟器使用独立的界面验收包。
- 原生内核采用统一的复用校验：Android Rust 源码目录树、父级 Cargo 配置、Android 适配器、V8 沙箱构建器与原生编译步骤一致；工作流触发条件、编译前缓存判断和产物上传保留期变化不会让内核失效。原生编译产物保留 90 天，过期后自动进入重新编译流程。
- 缺少签名 Secret、可复用原生核心、签名失败或 APK 校验不通过，均会阻止正式发布。
- 每个 Release 保留源提交哈希、ARM64 手机完整 APK、SHA-256 和中文变更记录。
- 发布记录：[GitHub Releases](https://github.com/sy220284/111/releases)；工作流：[111 Android 正式发布](https://github.com/sy220284/111/actions/workflows/release.yml)。

### 与 777 的适配差异

保留 777 的正式签名、版本递增、中文说明、全量包、增量包、完整性校验及最多三版保留机制。111 的发布启动建立在原生 Android 35 和界面两条验收均成功之后，并从已验证的 Rust/NDK 构建产物仅装配并发布 ARM64 手机安装包；x86_64 模拟器仅用于开发和自动化验收。
