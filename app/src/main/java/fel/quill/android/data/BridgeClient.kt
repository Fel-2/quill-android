package fel.quill.android.data

import android.annotation.SuppressLint
import fel.quill.android.model.BridgeConfig
import fel.quill.android.model.DiscoveredBridge
import fel.quill.android.model.Manifest
import fel.quill.android.model.ManifestFile
import fel.quill.android.model.RemoteFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class BridgeException(val statusCode: Int, message: String) : IOException(message)

class BridgeClient {
    suspend fun discover(timeoutMs: Long = 1_500): List<DiscoveredBridge> = withContext(Dispatchers.IO) {
        val found = linkedMapOf<String, DiscoveredBridge>()
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = timeoutMs.toInt()
            val request = "QUILL_DISCOVER".toByteArray()
            socket.send(DatagramPacket(request, request.size, InetAddress.getByName("255.255.255.255"), 8766))
            val buffer = ByteArray(4096)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: java.net.SocketTimeoutException) {
                    break
                }
                runCatching {
                    val json = JSONObject(String(packet.data, 0, packet.length, Charsets.UTF_8))
                    if (json.optString("service") != "quill-bridge") return@runCatching
                    val bridge = DiscoveredBridge(
                        host = json.getString("host"),
                        port = json.getInt("port"),
                        useTls = json.optBoolean("tls", true),
                        fingerprint = json.optString("fingerprint"),
                    )
                    found["${bridge.host}:${bridge.port}"] = bridge
                }
            }
        }
        found.values.toList()
    }

    suspend fun pair(config: BridgeConfig, code: String, deviceName: String): BridgeConfig = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("code", code)
            .put("device", deviceName)
        val request = Request.Builder()
            .url(url(config, "api/pair"))
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        execute(config, request).use { response ->
            val json = JSONObject(response.body?.string().orEmpty())
            val fingerprint = json.optString("fingerprint")
            if (config.fingerprint.isNotBlank() && normalizeFingerprint(config.fingerprint) != normalizeFingerprint(fingerprint)) {
                throw BridgeException(400, "The PC certificate fingerprint changed")
            }
            config.copy(
                token = json.getString("token"),
                fingerprint = fingerprint.ifBlank { config.fingerprint },
            )
        }
    }

    suspend fun rotatePairing(config: BridgeConfig): String = withContext(Dispatchers.IO) {
        val request = authenticated(config, "api/pair/rotate")
            .post(ByteArray(0).toRequestBody(JSON_MEDIA_TYPE))
            .build()
        execute(config, request).use { response ->
            JSONObject(response.body?.string().orEmpty()).getString("code")
        }
    }

    suspend fun health(config: BridgeConfig): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url(config, "api/health")).get().build()
        execute(config, request).use { response ->
            response.body?.string().orEmpty()
        }
    }

    suspend fun manifest(config: BridgeConfig): Manifest = withContext(Dispatchers.IO) {
        val request = authenticated(config, "api/manifest").get().build()
        execute(config, request).use { response ->
            val json = JSONObject(response.body?.string().orEmpty())
            val files = buildList {
                val array = json.optJSONArray("files") ?: org.json.JSONArray()
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        ManifestFile(
                            path = item.getString("path"),
                            etag = item.getString("etag"),
                            size = item.optLong("size"),
                            modifiedAt = item.optLong("mtime"),
                        ),
                    )
                }
            }
            Manifest(json.getString("revision"), files)
        }
    }

    suspend fun readFile(config: BridgeConfig, path: String): RemoteFile = withContext(Dispatchers.IO) {
        val request = authenticated(config, "api/file", mapOf("path" to path)).get().build()
        execute(config, request).use { response ->
            val content = response.body?.string().orEmpty()
            val etag = response.header("ETag")?.trim('"')?.removePrefix("W/") ?: sha256(content)
            RemoteFile(
                entry = ManifestFile(path, etag, content.toByteArray().size.toLong(), 0L),
                content = content,
            )
        }
    }

    suspend fun writeFile(
        config: BridgeConfig,
        path: String,
        content: String,
        etag: String?,
        create: Boolean,
    ): RemoteFile = withContext(Dispatchers.IO) {
        val builder = authenticated(config, "api/file", mapOf("path" to path))
            .put(content.toRequestBody(MARKDOWN_MEDIA_TYPE))
        if (create) builder.header("If-None-Match", "*")
        else if (!etag.isNullOrBlank()) builder.header("If-Match", "\"$etag\"")
        execute(config, builder.build()).use { response ->
            val json = JSONObject(response.body?.string().orEmpty())
            val item = json.getJSONObject("file")
            RemoteFile(
                entry = ManifestFile(
                    path = item.getString("path"),
                    etag = item.getString("etag"),
                    size = item.optLong("size"),
                    modifiedAt = item.optLong("mtime"),
                ),
                content = content,
            )
        }
    }

    suspend fun deleteFile(config: BridgeConfig, path: String, etag: String) = withContext(Dispatchers.IO) {
        val request = authenticated(config, "api/file", mapOf("path" to path))
            .delete()
            .header("If-Match", "\"$etag\"")
            .build()
        execute(config, request).use { }
    }

    suspend fun remoteEtag(config: BridgeConfig, path: String): String = withContext(Dispatchers.IO) {
        try {
            readFile(config, path).entry.etag
        } catch (error: BridgeException) {
            if (error.statusCode == 404) "" else throw error
        }
    }

    private fun authenticated(config: BridgeConfig, path: String, query: Map<String, String> = emptyMap()): Request.Builder {
        return Request.Builder()
            .url(url(config, path, query))
            .header("Authorization", "Bearer ${config.token}")
    }

    private fun url(config: BridgeConfig, path: String, query: Map<String, String> = emptyMap()): HttpUrl {
        val builder = HttpUrl.Builder()
            .scheme(if (config.useTls) "https" else "http")
            .host(config.host)
            .port(config.port)
            .addPathSegments(path.trim('/'))
        query.forEach { (key, value) -> builder.addQueryParameter(key, value) }
        return builder.build()
    }

    private fun execute(config: BridgeConfig, request: Request): okhttp3.Response {
        val response = client(config).newCall(request).execute()
        if (response.isSuccessful) return response
        val body = response.body?.string().orEmpty()
        val statusCode = response.code
        val message = runCatching { JSONObject(body).optString("error") }.getOrNull().orEmpty()
        response.close()
        throw BridgeException(statusCode, message.ifBlank { "Bridge request failed ($statusCode)" })
    }

    suspend fun pairInfo(host: String, port: Int, useTls: Boolean): DiscoveredBridge = withContext(Dispatchers.IO) {
        val probe = BridgeConfig(host = host, port = port, useTls = useTls, fingerprint = "", token = "", deviceName = "")
        val request = Request.Builder().url(url(probe, "api/pair/info")).get().build()
        val builder = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
        if (useTls) {
            val trust = trustAllManager()
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf<TrustManager>(trust), SecureRandom())
            builder.sslSocketFactory(sslContext.socketFactory, trust)
            builder.hostnameVerifier { _, _ -> true }
        }
        builder.build().newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw BridgeException(response.code, "Could not read the bridge certificate")
            val json = JSONObject(response.body?.string().orEmpty())
            if (json.optString("service") != "quill-bridge") throw BridgeException(400, "That host is not a Quill bridge")
            DiscoveredBridge(
                host = host,
                port = port,
                useTls = json.optBoolean("tls", useTls),
                fingerprint = json.optString("fingerprint"),
            )
        }
    }

    private fun client(config: BridgeConfig): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
        if (config.useTls) {
            val trust = trustManager(config.fingerprint)
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf<TrustManager>(trust), SecureRandom())
            builder.sslSocketFactory(sslContext.socketFactory, trust)
            builder.hostnameVerifier { _, _ -> true }
        }
        return builder.build()
    }

    @SuppressLint("CustomX509TrustManager")
    private fun trustAllManager(): X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    @SuppressLint("CustomX509TrustManager")
    private fun trustManager(fingerprint: String): X509TrustManager {
        val expected = normalizeFingerprint(fingerprint)
        if (expected.isBlank()) throw CertificateException("A bridge certificate fingerprint is required for TLS pairing")
        return object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = Unit

            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
                if (chain.isEmpty()) throw CertificateException("The PC did not present a certificate")
                val actual = normalizeFingerprint(sha256(chain[0].encoded))
                if (actual.isBlank() || actual != expected) throw CertificateException("The PC certificate fingerprint does not match")
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
    }

    private fun normalizeFingerprint(value: String): String = value.replace(":", "").replace(" ", "").lowercase()

    private fun sha256(value: String): String = sha256(value.toByteArray(Charsets.UTF_8))

    private fun sha256(value: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value)
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val MARKDOWN_MEDIA_TYPE = "text/markdown; charset=utf-8".toMediaType()
    }
}
