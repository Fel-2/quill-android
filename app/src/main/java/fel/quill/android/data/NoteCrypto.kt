package fel.quill.android.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class NoteCrypto(context: Context) {
    private val preferences = context.getSharedPreferences("quill_crypto", Context.MODE_PRIVATE)
    private val alias = "quill_notes_key"
    private val header = "QUILLENC1"

    fun isEnabled(): Boolean = preferences.getBoolean("at_rest", false)

    fun setEnabled(enabled: Boolean, root: File) {
        val currently = isEnabled()
        if (currently == enabled) return
        var failed = false
        root.walkTopDown()
            .filter { it.isFile && it.extension.equals("md", ignoreCase = true) && !it.name.startsWith(".") }
            .forEach { file ->
                val raw = runCatching { file.readBytes() }.getOrNull() ?: return@forEach
                if (enabled) {
                    if (!isEncrypted(raw)) {
                        if (runCatching { file.writeBytes(encrypt(raw)) }.isFailure) failed = true
                    }
                } else if (isEncrypted(raw)) {
                    val decoded = decrypt(raw)
                    if (decoded == null) {
                        failed = true
                    } else if (runCatching { file.writeBytes(decoded) }.isFailure) {
                        failed = true
                    }
                }
            }
        if (failed) return
        preferences.edit().putBoolean("at_rest", enabled).apply()
    }

    fun read(file: File): String? {
        val raw = runCatching { file.readBytes() }.getOrNull() ?: return null
        if (!isEncrypted(raw)) return String(raw, Charsets.UTF_8)
        val decoded = decrypt(raw) ?: return null
        return String(decoded, Charsets.UTF_8)
    }

    fun write(file: File, content: String) {
        if (!isEnabled()) {
            file.writeText(content)
            return
        }
        file.writeBytes(encrypt(content.toByteArray(Charsets.UTF_8)))
    }

    fun isEncrypted(raw: ByteArray): Boolean {
        val prefix = header.toByteArray(Charsets.UTF_8)
        if (raw.size < prefix.size + 2) return false
        for (index in prefix.indices) {
            if (raw[index] != prefix[index]) return false
        }
        return true
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(plain)
        val payload = header + "\n" + Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + "\n" +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
        return payload.toByteArray(Charsets.UTF_8)
    }

    private fun decrypt(raw: ByteArray): ByteArray? {
        val text = String(raw, Charsets.UTF_8)
        val parts = text.split("\n", limit = 3)
        if (parts.size < 3 || parts[0] != header) return null
        return runCatching {
            val iv = Base64.decode(parts[1], Base64.NO_WRAP)
            val ciphertext = Base64.decode(parts[2], Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            cipher.doFinal(ciphertext)
        }.getOrNull()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = keyStore.getKey(alias, null)
        if (existing is SecretKey) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }
}
