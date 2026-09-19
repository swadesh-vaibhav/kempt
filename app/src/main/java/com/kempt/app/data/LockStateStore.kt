package com.kempt.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.MessageDigest
import java.security.SecureRandom

/** Single process-wide DataStore for the lock flags. */
private val Context.lockDataStore by preferencesDataStore(name = "lock_state")

/**
 * The lock flags — deliberately separate from the Room rules so the monitor and the
 * boot receiver can read "is a block active right now?" cheaply. The partner passcode
 * is never stored in the clear: only a salted SHA-256 hash is kept, so it can be
 * verified offline but not read back off the device.
 */
class LockStateStore(context: Context) {

    private val store = context.applicationContext.lockDataStore

    val isArmed: Flow<Boolean> = store.data.map { it[ARMED] ?: false }

    val armedSince: Flow<Long?> = store.data.map { it[ARMED_SINCE] }

    /** Whether a partner passcode has been set (unlocking is impossible without one). */
    val hasPasscode: Flow<Boolean> = store.data.map { it[PASS_HASH] != null }

    /** Blocking read for the boot receiver, which has no coroutine scope of its own. */
    suspend fun isArmedNow(): Boolean = store.data.first()[ARMED] ?: false

    suspend fun arm() {
        store.edit {
            it[ARMED] = true
            it[ARMED_SINCE] = System.currentTimeMillis()
        }
    }

    suspend fun disarm() {
        store.edit {
            it[ARMED] = false
            it.remove(ARMED_SINCE)
        }
    }

    suspend fun setPasscode(raw: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        store.edit {
            it[PASS_SALT] = salt.toHex()
            it[PASS_HASH] = hash(raw, salt)
        }
    }

    /** True only when a passcode is set and [raw] matches it. */
    suspend fun verifyPasscode(raw: String): Boolean {
        val prefs = store.data.first()
        val saltHex = prefs[PASS_SALT] ?: return false
        val expected = prefs[PASS_HASH] ?: return false
        return constantTimeEquals(hash(raw, saltHex.fromHex()), expected)
    }

    private fun hash(raw: String, salt: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        return digest.digest(raw.toByteArray(Charsets.UTF_8)).toHex()
    }

    private companion object {
        val ARMED = booleanPreferencesKey("armed")
        val ARMED_SINCE = longPreferencesKey("armed_since")
        val PASS_SALT = stringPreferencesKey("pass_salt")
        val PASS_HASH = stringPreferencesKey("pass_hash")
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

private fun String.fromHex(): ByteArray =
    chunked(2).map { it.toInt(16).toByte() }.toByteArray()

/** Length-constant comparison so a wrong passcode can't be timed out character by character. */
private fun constantTimeEquals(a: String, b: String): Boolean {
    if (a.length != b.length) return false
    var result = 0
    for (i in a.indices) result = result or (a[i].code xor b[i].code)
    return result == 0
}
