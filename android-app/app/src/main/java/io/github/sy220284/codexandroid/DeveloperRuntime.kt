package io.github.sy220284.codexandroid

import android.content.Context
import android.util.AtomicFile
import android.util.Base64
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.zip.ZipInputStream

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
        env["LD_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir + ":" + File(root, "git/lib").absolutePath
        if (File(root, "git/lib").isDirectory) {
            env["GIT_TEMPLATE_DIR"] = File(root, "git/templates").absolutePath
            env["GIT_CONFIG_SYSTEM"] = File(root, "git/gitconfig").absolutePath
        }
        env["PYTHONHOME"] = root.absolutePath
        env["PYTHONUSERBASE"] = python.absolutePath
        env["ANDROIDDSH_NPM_ENTRY"] = File(root, "npm/bin/npm-cli.js").absolutePath
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
        val digest = context.assets.open("$ASSET.sha256").bufferedReader().use { it.readText().trim() }
        require(digest.matches(Regex("[a-f0-9]{64}"))) { "开发环境清单校验失败" }
        val parent = File(context.filesDir, "developer-runtime")
        check(parent.isDirectory || parent.mkdirs())
        val target = File(parent, digest)
        fun valid(dir: File) = File(dir, "lib/python3.13/encodings/__init__.py").isFile &&
            File(dir, "lib/python3.13/site-packages/pip/__init__.py").isFile &&
            File(dir, "npm/bin/npm-cli.js").isFile
        if (target.isDirectory) {
            check(valid(target)) { "开发环境数据已损坏，请重新安装应用" }
            return target
        }
        val staging = File(parent, "$digest.partial")
        if (staging.exists()) check(staging.deleteRecursively())
        check(staging.mkdir())
        val archive = File.createTempFile("developer-runtime", ".zip", context.cacheDir)
        try {
            val md = MessageDigest.getInstance("SHA-256")
            context.assets.open("$ASSET.zip").use { input ->
                archive.outputStream().use { output ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        md.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
            }
            check(md.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == digest) { "开发环境数据校验失败" }
            val prefix = staging.canonicalPath + File.separator
            ZipInputStream(archive.inputStream().buffered()).use { zip ->
                val seen = mutableSetOf<String>()
                var total = 0L
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val file = File(staging, entry.name).canonicalFile
                    require(file.path.startsWith(prefix) && seen.add(file.path)) { "开发环境压缩包路径异常" }
                    if (entry.isDirectory) check(file.isDirectory || file.mkdirs())
                    else {
                        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
                        file.outputStream().use { output ->
                            val buffer = ByteArray(65536)
                            while (true) {
                                val count = zip.read(buffer)
                                if (count < 0) break
                                total += count
                                check(total <= 512L * 1024 * 1024) { "开发环境数据超过安全上限" }
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
            check(valid(staging)) { "开发环境数据不完整" }
            check(staging.renameTo(target)) { "无法安装开发环境数据" }
            return target
        } finally {
            archive.delete()
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    /** Use Android's trusted system roots; never disable TLS verification. */
    private fun systemTrust(context: Context): File {
        val store = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
        val roots = store.aliases().toList().filter { it.startsWith("system:") }.sorted()
        require(roots.isNotEmpty()) { "系统根证书不可用" }
        val file = File(context.filesDir, "developer-system-ca.pem")
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            roots.forEach { alias ->
                val cert = checkNotNull(store.getCertificate(alias))
                val encoded = Base64.encodeToString(cert.encoded, Base64.NO_WRAP)
                val pem = "-----BEGIN CERTIFICATE-----\n" + encoded.chunked(64).joinToString("\n") +
                    "\n-----END CERTIFICATE-----\n"
                output.write(pem.toByteArray(Charsets.US_ASCII))
            }
            atomic.finishWrite(output)
        } catch (failure: Exception) {
            atomic.failWrite(output)
            throw failure
        }
        return file
    }
}
