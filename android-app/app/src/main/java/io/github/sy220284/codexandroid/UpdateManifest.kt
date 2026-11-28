package io.github.sy220284.codexandroid

import org.json.JSONObject

/** 111 发布工作流生成的 schema=2 更新清单。所有字段均在下载前验证。 */
internal data class UpdateAsset(val name: String, val size: Long, val sha256: String) {
    init {
        require(name.matches(Regex("[A-Za-z0-9._-]+"))) { "更新文件名无效" }
        require(size in 1..(512L * 1024 * 1024)) { "更新文件体积无效" }
        require(sha256.matches(Regex("[a-fA-F0-9]{64}"))) { "更新校验值无效" }
    }
}

internal data class UpdatePatch(
    val asset: UpdateAsset,
    val fromVersion: String,
    val toVersion: String,
    val sourceSha256: String,
    val targetSha256: String,
    val algorithm: String
)

internal data class UpdateManifest(
    val targetVersion: String,
    val apk: UpdateAsset,
    val patches: List<UpdatePatch>
) {
    fun isNewer(installedVersionCode: Long): Boolean =
        (versionCode(targetVersion) ?: 0L) > installedVersionCode

    fun patchFor(installedVersion: String, installedApkSha256: String): UpdatePatch? =
        patches.firstOrNull {
            it.fromVersion == installedVersion &&
                it.toVersion == targetVersion &&
                it.algorithm == "hdiffpatch-window-zstd-v1" &&
                it.sourceSha256.equals(installedApkSha256, ignoreCase = true) &&
                it.targetSha256.equals(apk.sha256, ignoreCase = true) &&
                it.asset.size < apk.size
        }

    companion object {
        private val versionPattern = Regex("""\d+\.\d+\.\d+-111\.([1-9]\d*)""")

        fun versionCode(version: String): Long? =
            versionPattern.matchEntire(version)?.groupValues?.get(1)?.toLongOrNull()

        fun matchesUpgrade(
            expectedName: String, expectedCode: Long, archiveName: String?,
            archiveCode: Long, installedCode: Long
        ): Boolean = archiveName == expectedName &&
            archiveCode == expectedCode && archiveCode > installedCode

        fun parse(json: String): UpdateManifest {
            val obj = JSONObject(json)
            require(obj.getInt("schema") == 2) { "发布清单版本不受支持" }
            val target = obj.getString("targetVersion")
            require(versionCode(target) != null) { "发布版本号无效" }

            val apks = obj.getJSONArray("apks")
            require(apks.length() == 1) { "正式版须提供一个 ARM64 APK" }
            val apkObj = apks.getJSONObject(0)
            require(apkObj.getString("abi") == "arm64-v8a") { "更新架构不匹配" }
            val apk = UpdateAsset(
                apkObj.getString("name"), apkObj.getLong("size"),
                apkObj.getString("sha256")
            )
            require(apk.name == "codex-android-arm64-v8a-$target.apk") { "APK 文件名与版本不匹配" }

            val entries = obj.getJSONArray("patches")
            val patches = buildList {
                for (i in 0 until entries.length()) {
                    val p = entries.getJSONObject(i)
                    val from = p.getString("fromVersion")
                    val to = p.getString("toVersion")
                    val algorithm = p.getString("algorithm")
                    val asset = UpdateAsset(
                        p.getString("asset"), p.getLong("size"), p.getString("sha256")
                    )
                    require(p.getString("abi") == "arm64-v8a" && versionCode(from) != null &&
                        to == target && versionCode(from)!! < versionCode(target)!!) {
                        "增量包版本不匹配"
                    }
                    require(asset.name == "delta-$from-to-$to-arm64-v8a.hpatch") {
                        "增量包文件名无效"
                    }
                    val sourceSha = p.getString("sourceSha256")
                    val targetSha = p.getString("targetSha256")
                    require(sourceSha.matches(Regex("[a-fA-F0-9]{64}")) &&
                        targetSha.equals(apk.sha256, ignoreCase = true)) { "增量包校验值无效" }
                    add(UpdatePatch(asset, from, to, sourceSha, targetSha, algorithm))
                }
            }
            return UpdateManifest(target, apk, patches)
        }
    }
}
