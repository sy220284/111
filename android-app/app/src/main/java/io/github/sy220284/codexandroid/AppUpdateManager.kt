package io.github.sy220284.codexandroid

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import android.content.ClipData
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.github.sisong.HPatch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

internal enum class UpdatePhase {
    IDLE, CHECKING, DOWNLOADING, PATCHING, READY, UP_TO_DATE, NEEDS_PERMISSION, INSTALLING, ERROR
}

internal data class AppUpdateState(
    val phase: UpdatePhase = UpdatePhase.IDLE,
    val message: String = "点击检查更新，有新版本时自动下载并准备安装。",
    val progress: Int = 0,
    val targetVersion: String = "",
    val delta: Boolean = false
)

/**
 * 本机更新器：应用级任务不随设置页面切换而取消；Android 安装步骤由系统确认。
 * 只访问固定仓库的正式 Release，并以发布清单中的 SHA-256 校验每个文件。
 */
internal object AppUpdateManager {
    private const val REPO_OWNER = "sy220284"
    private const val REPO_NAME = "111"
    private val repo = "$REPO_OWNER/$REPO_NAME"
    private val latest = "https://api.github.com/repos/$repo/releases/latest"
    private const val MAX_JSON_BYTES = 1024 * 1024
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(AppUpdateState())
    val state = mutableState.asStateFlow()

    @Volatile private var readyApk: File? = null
    @Volatile private var readySha: String? = null
    @Volatile private var waitingForPermission = false
    private var task: Job? = null

    @Synchronized
    fun checkAndDownload(activity: Activity) {
        if (task?.isActive == true) return
        val app = activity.applicationContext
        val owner = WeakReference(activity)
        readyApk = null
        readySha = null
        waitingForPermission = false
        task = scope.launch {
            var temporary: File? = null
            var recovered: File? = null
            try {
                val info = app.packageManager.getPackageInfo(app.packageName, 0)
                val installedVersion = info.versionName.orEmpty()
                val installedCode = info.longVersionCode
                if ((app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
                    throw IOException("当前为调试签名版本。请在设置页的「备份与恢复」导出数据，安装正式签名版后再从同一入口导入；恢复后重新登录账号。")
                }
                set(UpdatePhase.CHECKING, "正在检查 GitHub 正式发布版本…")
                val release = JSONObject(readSmall(URL(latest)))
                require(!release.optBoolean("draft") && !release.optBoolean("prerelease")) {
                    "最新发布尚未开放"
                }
                val tag = release.getString("tag_name")
                val assets = release.getJSONArray("assets")
                val urls = buildMap<String, URL> {
                    for (i in 0 until assets.length()) {
                        val entry = assets.getJSONObject(i)
                        val name = entry.getString("name")
                        if (!name.matches(Regex("[A-Za-z0-9._-]+"))) continue
                        val url = entry.getString("browser_download_url")
                        val expected = "https://github.com/$repo/releases/download/$tag/$name"
                        if (url == expected) put(name, URL(url))
                    }
                }
                val manifestUrl = urls["update-manifest.json"]
                    ?: throw IOException("正式版缺少更新清单")
                val manifest = UpdateManifest.parse(readSmall(manifestUrl))
                require(tag == "v${manifest.targetVersion}") { "发布标签与更新清单不一致" }
                if (!manifest.isNewer(installedCode)) {
                    set(UpdatePhase.UP_TO_DATE, "已经是最新版本（$installedVersion）", version = installedVersion)
                    return@launch
                }
                val apkUrl = urls[manifest.apk.name]
                    ?: throw IOException("正式版 APK 下载地址缺失")
                val outputDir = File(app.cacheDir, "updates")
                if (!outputDir.isDirectory && !outputDir.mkdirs()) {
                    throw IOException("无法创建更新缓存目录")
                }
                val output = File(outputDir, "codex-update-${manifest.targetVersion}.apk")
                outputDir.listFiles()?.filter { file ->
                    file.isFile && file.name.startsWith("codex-update-") &&
                        file.name.endsWith(".apk") && file != output
                }?.forEach { stale ->
                    if (!stale.delete()) throw IOException("无法清理过期的安装包缓存")
                }
                temporary = File(outputDir, "update-download.part")
                recovered = File(outputDir, "update-reconstructed.part")
                temporary.delete()
                recovered.delete()
                var deltaUsed = false
                var fallbackNotice = ""
                val source = File(app.applicationInfo.sourceDir)
                val eligible = manifest.patches.any { it.fromVersion == installedVersion }
                if (eligible && source.isFile) {
                    set(UpdatePhase.CHECKING, "正在核验已安装版本，优先选择增量更新…",
                        version = manifest.targetVersion)
                    val sourceHash = sha256(source)
                    val patch = manifest.patchFor(installedVersion, sourceHash)
                    if (patch != null) {
                        val patchUrl = urls[patch.asset.name]
                        if (patchUrl != null) {
                            try {
                                download(patchUrl, temporary, patch.asset, "增量包",
                                    manifest.targetVersion, true)
                                set(UpdatePhase.PATCHING, "正在从现有安装包合成新版本…",
                                    version = manifest.targetVersion, delta = true)
                                val code = HPatch.patch(
                                    source.absolutePath, temporary.absolutePath,
                                    recovered.absolutePath, 8L * 1024 * 1024, 2, true
                                )
                                if (code != 0) throw IOException("差分恢复错误：$code")
                                verify(recovered, manifest.apk)
                                commit(recovered, output)
                                deltaUsed = true
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                fallbackNotice = "增量更新未成功（${e.message}），已切换完整包。"
                                recovered.delete()
                            } catch (e: LinkageError) {
                                fallbackNotice = "差分组件不可用，已切换完整包。"
                                recovered.delete()
                            }
                        } else {
                            fallbackNotice = "该版本缺少可用的增量文件，改用完整包。"
                        }
                    } else {
                        fallbackNotice = "本机安装包与增量源文件不匹配，改用完整包。"
                    }
                }
                if (!deltaUsed) {
                    download(apkUrl, temporary, manifest.apk, "完整安装包",
                        manifest.targetVersion, false)
                    commit(temporary, output)
                }
                verifyInstalledSigner(app, output)
                readyApk = output
                readySha = manifest.apk.sha256
                mutableState.value = AppUpdateState(
                    UpdatePhase.READY,
                    (if (deltaUsed) "增量更新已完成。" else fallbackNotice) +
                        "版本 ${manifest.targetVersion} 已校验，准备安装。",
                    100, manifest.targetVersion, deltaUsed
                )
                val activeActivity = owner.get()
                if (activeActivity != null && !activeActivity.isFinishing &&
                    !activeActivity.isDestroyed) {
                    withContext(Dispatchers.Main) { requestSystemInstall(activeActivity) }
                }
            } catch (e: CancellationException) {
                set(UpdatePhase.IDLE, "更新已取消。")
                throw e
            } catch (e: Exception) {
                set(UpdatePhase.ERROR, "更新失败：${e.message ?: e.javaClass.simpleName}")
            } finally {
                temporary?.delete()
                recovered?.delete()
            }
        }
    }

    fun cancel() {
        synchronized(this) { task?.cancel() }
    }

    fun install(activity: Activity) {
        val file = readyApk ?: return
        val hash = readySha ?: return
        scope.launch {
            try {
                if (!file.isFile || !sha256(file).equals(hash, ignoreCase = true)) {
                    throw IOException("安装包缓存校验失败，请重新检查更新")
                }
                verifyInstalledSigner(activity.applicationContext, file)
                withContext(Dispatchers.Main) { requestSystemInstall(activity) }
            } catch (e: Exception) {
                set(UpdatePhase.ERROR, "安装准备失败：${e.message}")
            }
        }
    }

    /** 用户从系统“允许安装未知应用”页返回后继续安装。 */
    fun resumeInstall(activity: Activity) {
        if (waitingForPermission && activity.packageManager.canRequestPackageInstalls()) {
            waitingForPermission = false
            install(activity)
        }
    }

    private fun requestSystemInstall(activity: Activity) {
        val file = readyApk ?: return
        if (!activity.packageManager.canRequestPackageInstalls()) {
            waitingForPermission = true
            set(UpdatePhase.NEEDS_PERMISSION, "下载完成。需要允许此 App 安装更新，授权后继续。")
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${activity.packageName}"))
            try {
                activity.startActivity(intent)
            } catch (e: Exception) {
                set(UpdatePhase.NEEDS_PERMISSION,
                    "请到系统设置允许此 App 安装应用，再返回点击安装更新。")
            }
            return
        }
        waitingForPermission = false
        try {
            val uri = FileProvider.getUriForFile(
                activity, "${activity.packageName}.updateprovider", file)
            activity.startActivity(systemInstallIntent(uri))
            set(UpdatePhase.INSTALLING, "安装包已交给 Android 系统，请确认安装。")
        } catch (e: Exception) {
            set(UpdatePhase.READY, "系统安装界面打开失败：${e.message}，可重试。")
        }
    }

    /**
     * A self-update replaces and stops the process that owns the current Activity.
     * Keep the system installer in its own task so its "Open" action does not rely
     * on an activity stack belonging to the application being replaced.
     */
    internal fun systemInstallIntent(uri: Uri): Intent =
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .apply { clipData = ClipData.newRawUri("verified-update", uri) }
            .addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_MULTIPLE_TASK
            )

    private fun set(
        phase: UpdatePhase, message: String, progress: Int = 0,
        version: String = mutableState.value.targetVersion, delta: Boolean = false
    ) {
        mutableState.value = AppUpdateState(phase, message, progress, version, delta)
    }

    private fun connection(url: URL): HttpURLConnection {
        require(url.protocol == "https") { "下载地址必须使用 HTTPS" }
        val c = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "CodexAndroid-111-Updater")
            setRequestProperty("Accept-Encoding", "identity")
        }
        try {
            if (c.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("服务器返回 HTTP ${c.responseCode}")
            }
            if (c.url.protocol != "https" || c.url.host !in setOf(
                    "api.github.com", "github.com", "release-assets.githubusercontent.com",
                    "objects.githubusercontent.com")) {
                throw IOException("下载链接重定向至未经许可的地址")
            }
            return c
        } catch (e: Exception) {
            c.disconnect()
            throw e
        }
    }

    private fun readSmall(url: URL): String {
        val conn = connection(url)
        try {
            conn.inputStream.use { stream ->
                val output = java.io.ByteArrayOutputStream()
                val bytes = ByteArray(8192)
                while (true) {
                    currentCoroutineCheck()
                    val count = stream.read(bytes)
                    if (count < 0) break
                    if (output.size() + count > MAX_JSON_BYTES) {
                        throw IOException("发布元数据超过大小上限")
                    }
                    output.write(bytes, 0, count)
                }
                return output.toString(Charsets.UTF_8.name())
            }
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun download(
        url: URL, target: File, asset: UpdateAsset,
        label: String, version: String, delta: Boolean
    ) {
        set(UpdatePhase.DOWNLOADING, "正在下载$label…", 0, version, delta)
        val conn = connection(url)
        try {
            var total = 0L
            val digest = MessageDigest.getInstance("SHA-256")
            conn.inputStream.use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > asset.size) throw IOException("$label 大小超出发布清单")
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                        val progress = (total * 100 / asset.size).toInt()
                        if (progress != mutableState.value.progress) {
                            set(UpdatePhase.DOWNLOADING, "正在下载$label：$progress%",
                                progress, version, delta)
                        }
                    }
                    output.fd.sync()
                }
            }
            require(total == asset.size &&
                hex(digest.digest()).equals(asset.sha256, ignoreCase = true)) {
                "$label 下载内容的 SHA-256 与正式发布不一致"
            }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * The downloaded APK must be signed by the same publisher as the installed
     * application. Android verifies this again at installation time; checking
     * now gives the user an actionable error before showing the system installer.
     */
    private fun verifyInstalledSigner(app: Context, apk: File) {
        val pm = app.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val installed = pm.getPackageInfo(app.packageName, flags)
        val incoming = pm.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: throw IOException("更新包无法识别或签名不完整")
        val active = installed.signingInfo?.apkContentsSigners.orEmpty()
            .map { hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }.toSet()
        val proposed = incoming.signingInfo?.apkContentsSigners.orEmpty()
            .map { hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }.toSet()
        if (!InstallSignaturePolicy.accepts(app.packageName, incoming.packageName, active, proposed)) {
            throw IOException("更新包应用身份或发布签名与当前安装版本不一致，请勿安装")
        }
    }

    private fun verify(file: File, asset: UpdateAsset) {
        require(file.length() == asset.size &&
            sha256(file).equals(asset.sha256, ignoreCase = true)) {
            "恢复后的 APK 与正式签名发布文件不一致"
        }
    }

    private fun commit(source: File, output: File) {
        if (output.exists() && !output.delete()) throw IOException("无法清理旧安装缓存")
        if (!source.renameTo(output)) throw IOException("无法保存校验后的 APK")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val bytes = ByteArray(128 * 1024)
            while (true) {
                currentCoroutineCheck()
                val count = stream.read(bytes)
                if (count < 0) break
                digest.update(bytes, 0, count)
            }
        }
        return hex(digest.digest())
    }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it.toInt() and 255) }

    // 文件摘要也会在非 suspend 的安装检查中使用。
    private fun currentCoroutineCheck() {
        if (Thread.currentThread().isInterrupted) throw IOException("任务被中断")
    }
}
