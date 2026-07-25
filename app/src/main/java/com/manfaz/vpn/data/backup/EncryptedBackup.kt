package com.manfaz.vpn.data.backup

import android.util.Base64
import android.content.Context
import com.manfaz.vpn.core.ServerCodec
import com.manfaz.vpn.data.ServerRepository
import com.manfaz.vpn.data.SubscriptionRepository
import com.manfaz.vpn.data.Prefs
import com.manfaz.vpn.data.model.Subscription
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object EncryptedBackup {
    private const val PREFIX = "MANFAZ_BACKUP_V1:"
    private const val ITERATIONS = 210_000
    private const val SALT_SIZE = 16
    private const val IV_SIZE = 12

    fun export(context: Context, password: CharArray): ByteArray {
        require(password.size >= 8) { "گذرواژه باید حداقل ۸ نویسه باشد." }
        val root = JSONObject()
            .put("version", 1)
            .put("createdAt", System.currentTimeMillis())
            .put("preferences", Prefs(context).exportJson())
            .put("servers", JSONArray().apply {
                ServerRepository.servers.value.forEach { put(JSONObject(ServerCodec.toJson(it))) }
            })
            .put("subscriptions", JSONArray().apply {
                SubscriptionRepository.subs.value.forEach { s ->
                    put(JSONObject().apply {
                        put("id", s.id); put("name", s.name); put("url", s.url)
                        put("enabled", s.enabled); put("userAgent", s.userAgent)
                        put("lastUpdated", s.lastUpdated); put("serverCount", s.serverCount)
                        put("uploadBytes", s.uploadBytes); put("downloadBytes", s.downloadBytes)
                        put("totalBytes", s.totalBytes); put("expireEpoch", s.expireEpoch)
                    })
                }
            })
        val random = SecureRandom()
        val salt = ByteArray(SALT_SIZE).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, derive(password, salt))
        val encrypted = cipher.doFinal(root.toString().toByteArray(Charsets.UTF_8))
        password.fill('\u0000')
        return (PREFIX + Base64.encodeToString(salt + cipher.iv + encrypted, Base64.NO_WRAP))
            .toByteArray(Charsets.UTF_8)
    }

    fun restore(context: Context, bytes: ByteArray, password: CharArray): Int {
        require(password.size >= 8) { "گذرواژه معتبر نیست." }
        val text = bytes.toString(Charsets.UTF_8)
        require(text.startsWith(PREFIX)) { "این فایل، پشتیبان معتبر منفذ نیست." }
        val packed = Base64.decode(text.removePrefix(PREFIX), Base64.NO_WRAP)
        require(packed.size > SALT_SIZE + IV_SIZE)
        val salt = packed.copyOfRange(0, SALT_SIZE)
        val iv = packed.copyOfRange(SALT_SIZE, SALT_SIZE + IV_SIZE)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, derive(password, salt), GCMParameterSpec(128, iv))
        val root = JSONObject(cipher.doFinal(packed.copyOfRange(SALT_SIZE + IV_SIZE, packed.size))
            .toString(Charsets.UTF_8))
        password.fill('\u0000')
        require(root.optInt("version") == 1)
        val serversJson = root.getJSONArray("servers")
        val servers = (0 until serversJson.length()).map {
            ServerCodec.fromJson(serversJson.getJSONObject(it).toString())
        }
        val subsJson = root.getJSONArray("subscriptions")
        val subs = (0 until subsJson.length()).map { i ->
            val o = subsJson.getJSONObject(i)
            Subscription(
                id = o.getString("id"), name = o.getString("name"), url = o.getString("url"),
                enabled = o.optBoolean("enabled", true), userAgent = o.optString("userAgent"),
                lastUpdated = o.optLong("lastUpdated"), serverCount = o.optInt("serverCount"),
                uploadBytes = o.optLong("uploadBytes"), downloadBytes = o.optLong("downloadBytes"),
                totalBytes = o.optLong("totalBytes"), expireEpoch = o.optLong("expireEpoch"),
            )
        }
        // Parse and authenticate the full file before changing either repository.
        ServerRepository.replaceAll(servers)
        SubscriptionRepository.replaceAll(subs)
        root.optJSONObject("preferences")?.let { Prefs(context).restoreJson(it) }
        return servers.size
    }

    private fun derive(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(bytes, "AES")
    }
}
