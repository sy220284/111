package io.github.sy220284.codexandroid

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** No plaintext secrets in config, child process environment, RPC or backup. */
internal object DomesticProviderCredentials {
    private const val ALIAS = "111-domestic-provider-v1"
    private const val MAX_KEY = 4096

    private fun file(context: Context, preset: DomesticModelPreset): AtomicFile {
        require(preset.id.matches(Regex("[a-z]+-cn")))
        return AtomicFile(File(context.noBackupFilesDir, "provider-auth-${preset.id}"))
    }

    @Synchronized
    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        check(create) { "国内模型密钥无法解密，请重新授权" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }

    fun valid(value: String): Boolean =
        value.isNotBlank() && value.length <= MAX_KEY && value.all { it.code in 33..126 }

    fun has(context: Context, preset: DomesticModelPreset): Boolean = file(context, preset).baseFile.exists()

    @Synchronized
    fun save(context: Context, preset: DomesticModelPreset, value: String) {
        require(valid(value)) { "请输入有效的 API Key" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(true))
        cipher.updateAAD(preset.id.toByteArray(Charsets.UTF_8))
        val plain = value.toByteArray(Charsets.UTF_8)
        val encrypted = try { cipher.doFinal(plain) } finally { plain.fill(0) }
        val target = file(context, preset)
        val out = target.startWrite()
        try {
            out.write(cipher.iv.size)
            out.write(cipher.iv)
            out.write(encrypted)
            target.finishWrite(out)
        } catch (failure: Exception) {
            target.failWrite(out)
            throw failure
        }
    }

    @Synchronized
    fun remove(context: Context, preset: DomesticModelPreset) = file(context, preset).delete()

    @Synchronized
    fun bootstrap(context: Context): ByteArray? {
        val result = JSONObject()
        for (preset in DomesticModelPresets.load(context)) {
            if (!has(context, preset)) continue
            val bytes = file(context, preset).readFully()
            require(bytes.size in 30..(MAX_KEY + 64) && bytes[0].toInt() == 12) { "密钥记录损坏，请重新授权" }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
            cipher.updateAAD(preset.id.toByteArray(Charsets.UTF_8))
            val plain = cipher.doFinal(bytes, 13, bytes.size - 13)
            try {
                val value = String(plain, Charsets.UTF_8)
                require(valid(value)) { "密钥记录无效，请重新授权" }
                result.put(preset.envKey, value)
            } finally { plain.fill(0) }
        }
        return if (result.length() == 0) null else result.toString().toByteArray(Charsets.UTF_8)
    }
}
