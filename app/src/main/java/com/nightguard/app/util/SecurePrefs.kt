package com.nightguard.app.util

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Stores the recovery PIN -- set once, while lucid, during setup -- that gates pausing
 * monitoring, plus the current pause-until timestamp. Backed by EncryptedSharedPreferences,
 * same at-rest encryption as everything else NightGuard stores. The PIN itself is salted
 * and hashed rather than stored directly, on top of that.
 */
class SecurePrefs(context: Context) {
    private val masterKey = MasterKey.Builder(context.applicationContext)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context.applicationContext,
        "nightguard_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun hasPin(): Boolean = prefs.contains(KEY_PIN_HASH)

    fun setPin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs.edit()
            .putString(KEY_PIN_SALT, salt.toHex())
            .putString(KEY_PIN_HASH, hash(pin, salt))
            .apply()
    }

    fun verifyPin(pin: String): Boolean {
        val saltHex = prefs.getString(KEY_PIN_SALT, null) ?: return false
        val expected = prefs.getString(KEY_PIN_HASH, null) ?: return false
        return hash(pin, saltHex.fromHex()) == expected
    }

    fun pausedUntil(): Long = prefs.getLong(KEY_PAUSED_UNTIL, 0L)

    fun setPausedUntil(millis: Long) {
        prefs.edit().putLong(KEY_PAUSED_UNTIL, millis).apply()
    }

    fun clearPause() = setPausedUntil(0L)

    private fun hash(pin: String, salt: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        return digest.digest(pin.toByteArray(Charsets.UTF_8)).toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    private fun String.fromHex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    companion object {
        private const val KEY_PIN_HASH = "pin_hash"
        private const val KEY_PIN_SALT = "pin_salt"
        private const val KEY_PAUSED_UNTIL = "paused_until"
    }
}
