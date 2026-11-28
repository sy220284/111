package io.github.sy220284.codexandroid

import android.content.Context
import android.util.AtomicFile
import android.util.Base64
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest

/** Signed APK native code; versioned, verified data in the app-private directory. */
internal object DeveloperRuntime {
    private const val ASSET = "development-runtime/development-runtime"
    private val lock = Any()

    fun configure(context: Context, tools: File, env: MutableMap<String, String>) {
        val root = synchronized(lock) { prepareData(context) }
        val user = File(context.filesDir, "developer-packages")
        check(user.isDirectory || user.mkdirs()) { "无法创建开发依赖目录" }
        val python = File(user, "python")
        val node = File(user, "node")
        val pythonBin = File(python, "bin")
        val nodeBin = File(node, "bin")
        for (dir in listOf(pythonBin, nodeBin))
            check(dir.isDirectory || dir.mkdirs()) { "无法创建开发工具入口目录：${dir.name}" }
        // APK-native and system commands retain priority; installed package entries are discoverable.
        // Android may prohibit direct exec of writable scripts; use the installed interpreter for them.
        env["PATH"] = listOf(tools.absolutePath, "/system/bin", "/system/xbin",
            pythonBin.absolutePath, nodeBin.absolutePath).joinToString(":")
        env["SHELL"] = "/system/bin/sh"
        env["GIT_EXEC_PATH"] = tools.absolutePath
        GitWorkspaceTrust.configure(WorkspaceStorage.root(context), env)
        env["LD_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir
        env["CODEX_DEVELOPER_RUNTIME_ROOT"] = root.absolutePath
        env["GIT_TEMPLATE_DIR"] = File(root, "git/home/share/git-core/templates").absolutePath
        env["GIT_CONFIG_NOSYSTEM"] = "1"
        env["PYTHONHOME"] = File(root, "python/home").absolutePath
        env["PYTHONDONTWRITEBYTECODE"] = "1"
        env["PYTHONUSERBASE"] = python.absolutePath
        env["PIP_ONLY_BINARY"] = ":all:"
        env["npm_config_prefix"] = node.absolutePath
        env["npm_config_script_shell"] = "/system/bin/sh"
        env["NODE_PATH"] = File(node, "lib/node_modules").absolutePath
        val trust = synchronized(lock) { systemTrust(context) }
        for (key in listOf("SSL_CERT_FILE", "GIT_SSL_CAINFO", "CURL_CA_BUNDLE", "PIP_CERT", "NODE_EXTRA_CA_CERTS")) {
            env[key] = trust.absolutePath
        }
    }

    private fun prepareData(context: Context): File {
        val archiveDigest = context.assets.open("$ASSET.sha256").bufferedReader().use { it.readText().trim() }
        require(archiveDigest.matches(Regex("[a-f0-9]{64}"))) { "开发环境归档摘要异常" }
        val manifestBytes = context.assets.open("$ASSET.files.tsv").use { it.readBytes() }
        val entries = RuntimePayloadIntegrity.manifest(manifestBytes)
        val digest = RuntimePayloadIntegrity.manifestDigest(manifestBytes)
        val parent = File(context.filesDir, "developer-runtime")
        check(!java.nio.file.Files.isSymbolicLink(parent.toPath())) { "开发环境目录不能是符号链接" }
        check(parent.isDirectory || parent.mkdirs())
        val target = File(parent, digest)
        fun valid(dir: File) = File(dir, "python/home/lib/python3.14/encodings/__init__.py").isFile &&
            File(dir, "python/home/lib/python3.14/site-packages/pip/__init__.py").isFile &&
            File(dir, "node/npm/package/bin/npm-cli.js").isFile
        if (RuntimePayloadIntegrity.matches(target, entries) && valid(target)) {
            RuntimePayloadIntegrity.sealReadOnly(target)
            RuntimePayloadIntegrity.removeRetired(parent, target)
            return target
        }
        val staging = File(parent, "$digest.partial")
        RuntimePayloadIntegrity.removeTree(staging)
        check(staging.mkdir())
        try {
            context.assets.open("$ASSET.zip").use {
                RuntimePayloadIntegrity.install(staging, it, entries, archiveDigest)
            }
            check(valid(staging)) { "开发环境必要工具数据缺失" }
            RuntimePayloadIntegrity.sealReadOnly(staging)
            RuntimePayloadIntegrity.removeTree(target)
            check(staging.renameTo(target)) { "无法安装开发环境数据" }
            RuntimePayloadIntegrity.removeRetired(parent, target)
            return target
        } finally {
            RuntimePayloadIntegrity.removeTree(staging)
        }
    }

    /** Use Android's trusted system roots; never disable TLS verification. */
    private fun systemTrust(context: Context): File {
        val store = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
        val roots = store.aliases().toList().filter { it.startsWith("system:") }.sorted()
        require(roots.isNotEmpty()) { "系统根证书不可用" }
        val file = File(context.filesDir, "developer-system-ca.pem")
        val expected = roots.joinToString("") { alias ->
            val cert = checkNotNull(store.getCertificate(alias))
            val encoded = Base64.encodeToString(cert.encoded, Base64.NO_WRAP)
            "-----BEGIN CERTIFICATE-----\n" + encoded.chunked(64).joinToString("\n") +
                "\n-----END CERTIFICATE-----\n"
        }.toByteArray(Charsets.US_ASCII)
        check(!java.nio.file.Files.isSymbolicLink(file.toPath())) { "系统证书文件不能是符号链接" }
        // Reuse only the exact current system trust set.
        if (file.isFile && file.length() == expected.size.toLong() &&
            MessageDigest.isEqual(file.readBytes(), expected)) return file
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(expected)
            atomic.finishWrite(output)
        } catch (failure: Exception) {
            atomic.failWrite(output)
            throw failure
        }
        return file
    }
}
