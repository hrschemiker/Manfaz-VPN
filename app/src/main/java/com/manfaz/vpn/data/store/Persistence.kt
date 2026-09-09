package com.manfaz.vpn.data.store

import android.content.Context
import com.manfaz.vpn.data.model.Protocol
import com.manfaz.vpn.data.model.ServerConfig
import com.manfaz.vpn.data.model.Subscription
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Minimal JSON-file persistence for servers and subscriptions (internal storage). */
class Persistence(private val context: Context) {

    private val secureStore = SecureFileStore(context)
    private val serversFile = File(context.filesDir, "servers.json")
    private val subsFile = File(context.filesDir, "subscriptions.json")
    fun loadServers(): List<ServerConfig>? = read(serversFile)?.let { arr ->
        (0 until arr.length()).map { serverFromJson(arr.getJSONObject(it)) }
    }

    fun saveServers(list: List<ServerConfig>) {
        val arr = JSONArray()
        list.forEach { arr.put(serverToJson(it)) }
        write(serversFile, arr)
    }

    /** Removes files left behind by features that no longer exist. */
    fun deleteObsoleteFiles() {
        listOf("free_configs.json", "free_configs_seen.json").forEach { name ->
            runCatching { File(context.filesDir, name).delete() }
        }
    }

    fun loadSubs(): List<Subscription>? = read(subsFile)?.let { arr ->
        (0 until arr.length()).map { subFromJson(arr.getJSONObject(it)) }
    }

    fun saveSubs(list: List<Subscription>) {
        val arr = JSONArray()
        list.forEach { arr.put(subToJson(it)) }
        write(subsFile, arr)
    }

    private fun read(file: File): JSONArray? = try {
        secureStore.read(file)?.let(::JSONArray)
    } catch (e: Exception) { null }

    private fun write(file: File, arr: JSONArray) = try {
        secureStore.write(file, arr.toString())
    } catch (_: Exception) {}

    private fun serverToJson(s: ServerConfig) = JSONObject().apply {
        put("id", s.id); put("name", s.name); put("protocol", s.protocol.name)
        put("address", s.address); put("port", s.port); put("uuid", s.uuid)
        put("alterId", s.alterId); put("encryption", s.encryption)
        put("password", s.password); put("method", s.method); put("network", s.network)
        put("security", s.security); put("sni", s.sni); put("host", s.host)
        put("path", s.path); put("serviceName", s.serviceName); put("mode", s.mode)
        put("flow", s.flow); put("alpn", s.alpn)
        put("fingerprint", s.fingerprint); put("publicKey", s.publicKey)
        put("shortId", s.shortId); put("spiderX", s.spiderX)
        put("mldsa65Verify", s.mldsa65Verify); put("extra", s.extra)
        put("group", s.group); put("favorite", s.favorite)
        put("pingMs", s.pingMs ?: JSONObject.NULL); put("latencyTested", s.latencyTested)
        put("rawUri", s.rawUri)
    }

    private fun serverFromJson(o: JSONObject) = ServerConfig(
        id = o.optString("id"),
        name = o.optString("name"),
        protocol = runCatching { Protocol.valueOf(o.optString("protocol")) }.getOrDefault(Protocol.UNKNOWN),
        address = o.optString("address"),
        port = o.optInt("port"),
        uuid = o.optString("uuid"),
        alterId = o.optInt("alterId", 0),
        encryption = o.optString("encryption"),
        password = o.optString("password"),
        method = o.optString("method"),
        network = o.optString("network", "tcp"),
        security = o.optString("security", "none"),
        sni = o.optString("sni"),
        host = o.optString("host"),
        path = o.optString("path"),
        serviceName = o.optString("serviceName"),
        mode = o.optString("mode"),
        flow = o.optString("flow"),
        alpn = o.optString("alpn"),
        fingerprint = o.optString("fingerprint"),
        publicKey = o.optString("publicKey"),
        shortId = o.optString("shortId"),
        spiderX = o.optString("spiderX"),
        mldsa65Verify = o.optString("mldsa65Verify"),
        extra = o.optString("extra"),
        group = o.optString("group"),
        favorite = o.optBoolean("favorite"),
        pingMs = if (o.isNull("pingMs")) null else o.optInt("pingMs"),
        latencyTested = o.optBoolean("latencyTested", !o.isNull("pingMs")),
        rawUri = o.optString("rawUri"),
    )

    private fun subToJson(s: Subscription) = JSONObject().apply {
        put("id", s.id); put("name", s.name); put("url", s.url); put("userAgent", s.userAgent)
        put("enabled", s.enabled); put("serverCount", s.serverCount)
        put("lastUpdated", s.lastUpdated); put("uploadBytes", s.uploadBytes)
        put("downloadBytes", s.downloadBytes); put("totalBytes", s.totalBytes)
        put("expireEpoch", s.expireEpoch); put("lastError", s.lastError ?: JSONObject.NULL)
    }

    private fun subFromJson(o: JSONObject) = Subscription(
        id = o.optString("id"),
        name = o.optString("name"),
        url = o.optString("url"),
        userAgent = o.optString("userAgent", "ManfazVPN/1.0"),
        enabled = o.optBoolean("enabled", true),
        serverCount = o.optInt("serverCount"),
        lastUpdated = o.optLong("lastUpdated"),
        uploadBytes = o.optLong("uploadBytes"),
        downloadBytes = o.optLong("downloadBytes"),
        totalBytes = o.optLong("totalBytes"),
        expireEpoch = o.optLong("expireEpoch"),
        lastError = if (o.isNull("lastError")) null else o.optString("lastError"),
    )
}
