package com.manfaz.vpn.vpn

import android.content.Context
import android.os.SystemClock
import android.util.AtomicFile
import com.manfaz.vpn.core.ServerCodec
import com.manfaz.vpn.data.model.ServerConfig
import org.json.JSONObject
import java.io.File

/**
 * Small process-safe state snapshot shared by the UI and the isolated VPN core.
 *
 * SharedPreferences keeps a per-process cache and is therefore unsuitable here. AtomicFile
 * gives the UI a fresh, complete snapshot after process recreation even if a broadcast was
 * missed while the activity was in the background.
 */
object ConnectionSnapshotStore {
    private const val FILE_NAME = "vpn_connection_state.json"
    private const val MAX_HEARTBEAT_AGE_MS = 15_000L

    data class Snapshot(
        val connected: Boolean,
        val server: ServerConfig?,
        val ip: String,
        val ping: Int,
        val connectedSince: Long,
        val updatedAt: Long,
    ) {
        val isFresh: Boolean
            get() = updatedAt > 0L &&
                SystemClock.elapsedRealtime() - updatedAt in 0..MAX_HEARTBEAT_AGE_MS
    }

    fun writeConnected(
        context: Context,
        server: ServerConfig,
        ip: String,
        ping: Int,
        connectedSince: Long,
    ) = write(context, JSONObject().apply {
        put("connected", true)
        put("server", ServerCodec.toJson(server))
        put("ip", ip)
        put("ping", ping)
        put("since", connectedSince)
        put("updated", SystemClock.elapsedRealtime())
    })

    fun writeStopped(context: Context) = write(context, JSONObject().apply {
        put("connected", false)
        put("updated", SystemClock.elapsedRealtime())
    })

    fun read(context: Context): Snapshot? = runCatching {
        val bytes = atomicFile(context).openRead().use { it.readBytes() }
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        Snapshot(
            connected = json.optBoolean("connected", false),
            server = json.optString("server").takeIf { it.isNotBlank() }
                ?.let { ServerCodec.fromJson(it) },
            ip = json.optString("ip", "متصل"),
            ping = json.optInt("ping", 0),
            connectedSince = json.optLong("since", 0L),
            updatedAt = json.optLong("updated", 0L),
        )
    }.getOrNull()

    private fun write(context: Context, json: JSONObject) {
        val file = atomicFile(context)
        var output: java.io.FileOutputStream? = null
        try {
            output = file.startWrite()
            output.write(json.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (_: Throwable) {
            output?.let(file::failWrite)
        }
    }

    private fun atomicFile(context: Context) = AtomicFile(
        File(context.applicationContext.filesDir, FILE_NAME),
    )
}
