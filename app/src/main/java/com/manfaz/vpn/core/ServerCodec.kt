package com.manfaz.vpn.core

import com.manfaz.vpn.data.model.Protocol
import com.manfaz.vpn.data.model.ServerConfig
import org.json.JSONObject

/** Serializes a [ServerConfig] to/from JSON for passing across the process boundary. */
object ServerCodec {

    fun toJson(s: ServerConfig): String = JSONObject().apply {
        put("id", s.id); put("name", s.name); put("protocol", s.protocol.name)
        put("address", s.address); put("port", s.port); put("uuid", s.uuid)
        put("password", s.password); put("method", s.method); put("network", s.network)
        put("security", s.security); put("sni", s.sni); put("host", s.host)
        put("path", s.path); put("serviceName", s.serviceName); put("mode", s.mode)
        put("flow", s.flow); put("alpn", s.alpn)
        put("fingerprint", s.fingerprint); put("publicKey", s.publicKey)
        put("shortId", s.shortId); put("group", s.group)
        put("favorite", s.favorite); put("pingMs", s.pingMs ?: JSONObject.NULL)
        put("noPingSinceMs", s.noPingSinceMs); put("rawUri", s.rawUri)
    }.toString()

    fun fromJson(json: String): ServerConfig {
        val o = JSONObject(json)
        return ServerConfig(
            id = o.optString("id"),
            name = o.optString("name"),
            protocol = runCatching { Protocol.valueOf(o.optString("protocol")) }.getOrDefault(Protocol.UNKNOWN),
            address = o.optString("address"),
            port = o.optInt("port"),
            uuid = o.optString("uuid"),
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
            group = o.optString("group"),
            favorite = o.optBoolean("favorite"),
            pingMs = if (o.isNull("pingMs")) null else o.optInt("pingMs"),
            noPingSinceMs = o.optLong("noPingSinceMs"),
            rawUri = o.optString("rawUri"),
        )
    }
}
