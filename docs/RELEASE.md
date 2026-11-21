# 111 Android 正式发布流程

## 自动发布顺序

1. 向 `main` 合并客户端源码后，安卓版本构建与独立 UI 验收分别执行双架构 APK、Android 35 模拟器测试及界面回归。原生构建优先查找**源码树、依赖修补器、上游版本和 Rust/NDK 编译配方完全相同**的旧构建，两种 ABI 产物均有效时跳过重复编译；真实编译输入变化、产物缺失或过期时重新编译。
2. 仅当**同一源码提交**的两项 Android 验收都在 `main` **成功**时，「111 Android 正式发布」才会继续进入版本检查。两项检查的先后顺序不限；也可以从 Actions 手动启动发布，但同样需要这两项成功记录。
3. 比较上一个正式 Release 到本次源码的变更：只有 `android-app/`、`upstream/codex/`、`CODEX_UPSTREAM_COMMIT` 或原生构建流程变化时才生成新 APK。已发布的源码提交、纯文档调整会跳过。
4. 从有效版本标签及 `.github/release-version` 计算单调递增的版本号与 Android `versionCode`，禁止复用曾发布的编号。
5. 从成功的原生构建记录中选择**完整输入完全一致、运行提交属于当前主分支历史且产物尚未过期**的 ARM64 内核。普通 Android 修改复用同一原生内核；Android 设备测试需要同时找到 ARM64 与 x86_64 两个产物。若未找到合格产物，先执行原生构建，再完成设备验收。
6. 验证 ARM64 ELF 的架构、Android 动态链接器与 16 KB 加载段对齐，使用 NDK 去除符号并装配手机 APK。
7. 运行 Kotlin 单元测试和 Release Lint，仅生成一个 **ARM64 正式签名手机 APK**；再核验版本号、包名、签名证书、APK 文件完整性和 ABI 隔离。
8. 由合并 PR 生成中文更新说明；仅上传 ARM64 手机完整 APK、`SHA256SUMS.txt`、`update-manifest.json`。有可用上版手机 APK 时尝试生成 ARM64 二进制增量包，必须通过原样回放、SHA-256 对照且小于完整包的 65% 才会发布。
9. 新 Release 发布后自动保留最近 3 个正式 Release（历史 Git 标签与提交保留）。**没有任何定时清理任务**。

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

## 验收与故障定位

- 在 PR 中发布工作流会执行中文更新说明、手机 APK 产物清单测试、Release 签名构建及 Shell 语法检查，不生成正式 Release。
- 原生内核采用统一的复用校验：上游源码目录树、`CODEX_UPSTREAM_COMMIT` 记录的源码版本、Android 编译修补器以及原生构建的编译步骤一致；工作流触发条件、编译前缓存判断和产物上传保留期变化不会让内核失效。原生编译产物保留 90 天，过期后自动进入重新编译流程。
- 缺少签名 Secret、可复用原生核心、签名失败或 APK 校验不通过，均会阻止正式发布。
- 每个 Release 保留源提交哈希、ARM64 手机完整 APK、SHA-256 和中文变更记录。
- 发布记录：[GitHub Releases](https://github.com/sy220284/111/releases)；工作流：[111 Android 正式发布](https://github.com/sy220284/111/actions/workflows/release.yml)。

### 与 777 的适配差异

保留 777 的正式签名、版本递增、中文说明、全量包、增量包、完整性校验及最多三版保留机制。111 的发布启动建立在原生 Android 35 和界面两条验收均成功之后，并从已验证的 Rust/NDK 构建产物仅装配并发布 ARM64 手机安装包；x86_64 模拟器仅用于开发和自动化验收。
