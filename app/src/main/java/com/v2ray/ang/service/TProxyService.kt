package com.v2ray.ang.service

/**
 * JNI binding for the prebuilt libhev-socks5-tunnel.so (MIT, by heiher).
 *
 * The native library uses dynamic JNI registration (RegisterNatives) hardcoded to the
 * class name "com/v2ray/ang/service/TProxyService" with these exact method signatures,
 * so this binding MUST live at this package/class name. It is a thin native-method
 * interface only — the tunnel logic lives entirely in the .so.
 */
class TProxyService {
    companion object {
        @JvmStatic external fun TProxyStartService(configPath: String, fd: Int)
        @JvmStatic external fun TProxyStopService()
        @JvmStatic external fun TProxyGetStats(): LongArray?

        @Volatile private var loaded = false
        fun ensureLoaded() {
            if (!loaded) { System.loadLibrary("hev-socks5-tunnel"); loaded = true }
        }
    }
}
