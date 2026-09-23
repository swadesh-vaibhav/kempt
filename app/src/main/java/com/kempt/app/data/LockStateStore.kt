/**
 * @file
 * @brief Persistent, lightweight store for the lock flags and the partner passcode hash.
 */
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

/**
 * @brief Process-wide @c DataStore holding the lock flags, keyed by the name "lock_state".
 *
 * @note This is a Kotlin *extension property* on @c Context: @c preferencesDataStore is a
 * delegate that lazily creates a single DataStore the first time it's read, so every
 * @c Context in the process shares the same underlying file.
 */
private val Context.lockDataStore by preferencesDataStore(name = "lock_state")

/**
 * @brief Stores the "is a block active?" flags and the partner passcode, separate from the Room rules.
 *
 * @details Kept in DataStore rather than Room so the monitor and boot receiver can answer
 * "is a block active right now?" cheaply and without touching the database. The partner
 * passcode is never stored in the clear — only a random-salted SHA-256 hash is kept, so it
 * can be verified offline but not recovered from the device.
 *
 * @param context Any context; the application context is used so the store outlives any single component.
 */
class LockStateStore(context: Context) {

    /** @brief The backing DataStore, resolved from the application context. */
    private val store = context.applicationContext.lockDataStore

    /**
     * @brief Emits @c true while a lock is active.
     * @note A Kotlin @c Flow is a cold, asynchronous stream: the UI collects it and is
     * re-notified whenever the stored value changes.
     */
    val isArmed: Flow<Boolean> = store.data.map { it[ARMED] ?: false }

    /** @brief Emits the epoch-millis timestamp the current lock was armed, or @c null when disarmed. */
    val armedSince: Flow<Long?> = store.data.map { it[ARMED_SINCE] }

    /**
     * @brief Emits whether a partner passcode has been set.
     * @details Unlocking is impossible without one, so the UI uses this to gate the "Lock down" action.
     */
    val hasPasscode: Flow<Boolean> = store.data.map { it[PASS_HASH] != null }

    /**
     * @brief Reads the armed flag once (a single snapshot rather than an ongoing stream).
     * @details Provided for callers with no long-lived coroutine scope, such as the boot
     * receiver. @c suspend means it must be called from a coroutine and awaits the first
     * emitted value instead of blocking a thread.
     * @return @c true if a lock is currently active.
     */
    suspend fun isArmedNow(): Boolean = store.data.first()[ARMED] ?: false

    /** @brief Marks a lock as active and records the current time as the arm timestamp. */
    suspend fun arm() {
        store.edit {
            it[ARMED] = true
            it[ARMED_SINCE] = System.currentTimeMillis()
        }
    }

    /** @brief Clears the active-lock flag and the arm timestamp. */
    suspend fun disarm() {
        store.edit {
            it[ARMED] = false
            it.remove(ARMED_SINCE)
        }
    }

    /**
     * @brief Sets (or replaces) the partner passcode, storing only a salted hash.
     * @details Generates a fresh 16-byte random salt with @c SecureRandom, hashes @p raw with
     * it, and persists both the salt and the hash. The plaintext is never written to disk.
     * @param raw The plaintext passcode chosen by the partner.
     */
    suspend fun setPasscode(raw: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        store.edit {
            it[PASS_SALT] = salt.toHex()
            it[PASS_HASH] = hash(raw, salt)
        }
    }

    /**
     * @brief Checks a candidate passcode against the stored salted hash.
     * @param raw The plaintext passcode entered at unlock time.
     * @return @c true only when a passcode has been set and @p raw hashes to the stored value.
     */
    suspend fun verifyPasscode(raw: String): Boolean {
        val prefs = store.data.first()
        val saltHex = prefs[PASS_SALT] ?: return false
        val expected = prefs[PASS_HASH] ?: return false
        return constantTimeEquals(hash(raw, saltHex.fromHex()), expected)
    }

    /**
     * @brief Computes @c SHA-256(salt || raw) and returns it as a lowercase hex string.
     * @param raw The plaintext to hash.
     * @param salt The per-passcode random salt, mixed in before the plaintext.
     * @return The hex-encoded digest.
     */
    private fun hash(raw: String, salt: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        return digest.digest(raw.toByteArray(Charsets.UTF_8)).toHex()
    }

    /** @brief DataStore preference keys for the persisted lock fields. */
    private companion object {
        val ARMED = booleanPreferencesKey("armed")
        val ARMED_SINCE = longPreferencesKey("armed_since")
        val PASS_SALT = stringPreferencesKey("pass_salt")
        val PASS_HASH = stringPreferencesKey("pass_hash")
    }
}

/** @brief Encodes a byte array as a lowercase hex string. */
private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

/** @brief Decodes a lowercase hex string back into the original byte array. */
private fun String.fromHex(): ByteArray =
    chunked(2).map { it.toInt(16).toByte() }.toByteArray()

/**
 * @brief Compares two strings in time independent of where they first differ.
 * @details A naive @c == can leak, through its timing, how many leading characters matched,
 * letting an attacker guess a hash character by character. This XORs every pair of characters
 * and ORs the results, so the loop always runs to the end regardless of the input.
 * @param a First string (here, a freshly computed hash).
 * @param b Second string (here, the stored hash).
 * @return @c true only when the strings are identical.
 */
private fun constantTimeEquals(a: String, b: String): Boolean {
    if (a.length != b.length) return false
    var result = 0
    for (i in a.indices) result = result or (a[i].code xor b[i].code)
    return result == 0
}
