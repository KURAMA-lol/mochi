package com.sahil.chibi.plugins

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps plugin secrets (bot tokens, OAuth tokens) encrypted with an AES key that lives in the Android Keystore.
 * The key never leaves the phone's secure hardware or keystore, so the stored text is useless on its own.
 */
object SecureStore {
    private const val ALIAS = "nana_plugin_key"
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("plugin_secure", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build()
        )
        return g.generateKey()
    }

    fun put(c: Context, name: String, value: String) {
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val ct = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            val s = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
            prefs(c).edit().putString(name, s).apply()
        } catch (e: Exception) {
            prefs(c).edit().remove(name).apply()
        }
    }

    /** The stored value, or "" when missing or unreadable. */
    fun get(c: Context, name: String): String {
        val s = prefs(c).getString(name, null) ?: return ""
        return try {
            val i = s.indexOf(':')
            val iv = Base64.decode(s.substring(0, i), Base64.NO_WRAP)
            val ct = Base64.decode(s.substring(i + 1), Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) { "" }
    }

    fun remove(c: Context, name: String) { prefs(c).edit().remove(name).apply() }

    /** Plain, non-secret flags (like the connected account name). */
    fun meta(c: Context, name: String): String = prefs(c).getString("m_" + name, "") ?: ""
    fun setMeta(c: Context, name: String, v: String) { prefs(c).edit().putString("m_" + name, v).apply() }
}
