package fel.quill.android.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import fel.quill.android.model.AiConfig
import fel.quill.android.model.BridgeConfig
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureStore(context: Context) {
    private val preferences = context.getSharedPreferences("quill_secure", Context.MODE_PRIVATE)
    private val alias = "quill_android_key"

    fun saveBridge(config: BridgeConfig) {
        val json = JSONObject()
            .put("host", config.host)
            .put("port", config.port)
            .put("useTls", config.useTls)
            .put("fingerprint", config.fingerprint)
            .put("token", config.token)
            .put("deviceName", config.deviceName)
        put("bridge", json.toString())
    }

    fun loadBridge(): BridgeConfig? {
        val value = get("bridge") ?: return null
        return runCatching {
            val json = JSONObject(value)
            BridgeConfig(
                host = json.getString("host"),
                port = json.getInt("port"),
                useTls = json.getBoolean("useTls"),
                fingerprint = json.optString("fingerprint"),
                token = json.getString("token"),
                deviceName = json.optString("deviceName", "Android device"),
            )
        }.getOrNull()
    }

    fun clearBridge() {
        preferences.edit().remove("bridge").apply()
    }

    fun saveAi(config: AiConfig) {
        val json = JSONObject()
            .put("provider", config.provider)
            .put("model", config.model)
            .put("baseUrl", config.baseUrl)
            .put("apiKey", config.apiKey)
            .put("maxTokens", config.maxTokens)
            .put("aiCapture", config.aiCapture)
        put("ai", json.toString())
    }

    fun loadAi(): AiConfig {
        val value = get("ai") ?: return AiConfig()
        return runCatching {
            val json = JSONObject(value)
            AiConfig(
                provider = json.optString("provider", "opencode-go"),
                model = json.optString("model", "deepseek-v4-flash"),
                baseUrl = json.optString("baseUrl", ""),
                apiKey = json.optString("apiKey", ""),
                maxTokens = json.optInt("maxTokens", 0),
                aiCapture = json.optBoolean("aiCapture", true),
            )
        }.getOrDefault(AiConfig())
    }

    private fun put(key: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val payload = Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
        preferences.edit().putString(key, payload).apply()
    }

    private fun get(key: String): String? {
        val encoded = preferences.getString(key, null) ?: return null
        return runCatching {
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            val iv = payload.copyOfRange(0, 12)
            val encrypted = payload.copyOfRange(12, payload.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = keyStore.getKey(alias, null)
        if (existing is SecretKey) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }
}
