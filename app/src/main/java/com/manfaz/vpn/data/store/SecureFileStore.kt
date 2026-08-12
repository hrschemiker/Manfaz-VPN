package com.manfaz.vpn.data.store

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Small authenticated file store. Existing plaintext files are accepted once and
 * transparently rewritten encrypted, so upgrades never lose users' configurations.
 */
internal class SecureFileStore(context: Context) {
    private val dir = context.applicationContext.filesDir

    fun read(file: File): String? {
        if (!file.exists()) return null
        val stored = runCatching { file.readText() }.getOrNull() ?: return null
        if (!stored.startsWith(PREFIX)) {
            write(file, stored) // one-way migration from legacy JSON
            return stored
        }
        return runCatching {
            val packed = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            require(packed.size > IV_SIZE)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key(),
                GCMParameterSpec(TAG_BITS, packed.copyOfRange(0, IV_SIZE)),
            )
            cipher.doFinal(packed.copyOfRange(IV_SIZE, packed.size)).toString(Charsets.UTF_8)
        }.getOrNull()
    }

    fun write(file: File, plaintext: String): Boolean = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val packed = cipher.iv + encrypted
        val temp = File(dir, "${file.name}.tmp")
        temp.writeText(PREFIX + Base64.encodeToString(packed, Base64.NO_WRAP))
        if (!temp.renameTo(file)) {
            file.writeText(temp.readText())
            temp.delete()
        }
        true
    }.getOrDefault(false)

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            generateKey()
        }
    }

    companion object {
        private const val KEY_ALIAS = "manfaz.configs.v1"
        private const val PREFIX = "MANFAZ_ENC_V1:"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
        private const val TAG_BITS = 128
    }
}
