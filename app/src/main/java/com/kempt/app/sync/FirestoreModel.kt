/**
 * @file
 * @brief Firestore schema for the accountability backend: field-name constants and read models.
 */
package com.kempt.app.sync

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId

/**
 * @brief Central catalogue of Firestore collection and field names.
 *
 * @details The security rules (@c firestore.rules at the repo root) pin an exact field set per
 * document with @c hasOnly, so writes must use precisely these names — a typo becomes a
 * "permission denied". Centralising the strings here keeps the writer
 * (@ref FirestoreAccountabilityService) and the read models below from drifting apart.
 *
 * @note @c const @c val values are compile-time constants: the compiler inlines each one at its
 * use site, so there is no lookup cost. An @c object is Kotlin's singleton — one shared instance,
 * accessed as @c Fs.USERS.
 */
object Fs {
    // ---- Collections ----
    /** @brief Top-level collection of user documents, keyed by Auth uid. */
    const val USERS = "users"
    /** @brief Subcollection of a user's registered devices. */
    const val DEVICES = "devices"
    /** @brief Subcollection holding the append-only accountability log. */
    const val EVENTS = "events"
    /** @brief Subcollection of remote unlock/lock commands. */
    const val GRANTS = "grants"
    /** @brief Top-level collection linking an accountable user to a partner. */
    const val PAIRINGS = "pairings"

    // ---- users / devices fields ----
    const val DISPLAY_NAME = "displayName"
    const val PLATFORM = "platform"
    const val ARMED = "armed"
    const val CREATED_AT = "createdAt"
    const val FCM_TOKEN = "fcmToken"
    const val LAST_HEARTBEAT_AT = "lastHeartbeatAt"

    // ---- events fields ----
    const val TYPE = "type"
    const val AT = "at"
    const val PACKAGE_NAME = "packageName"
    const val DEVICE_ID = "deviceId"

    // ---- grants fields ----
    const val ACTION = "action"
    const val ISSUED_BY_UID = "issuedByUid"
    const val STATUS = "status"
    const val EXPIRES_AT = "expiresAt"

    // ---- pairings fields ----
    const val ACCOUNTABLE_UID = "accountableUid"
    const val PARTNER_UID = "partnerUid"

    /** @brief The only @ref PLATFORM value this app writes. */
    const val PLATFORM_ANDROID = "android"

    /** @brief Grant @ref ACTION value: temporarily unlock (requires an @ref EXPIRES_AT). */
    const val ACTION_UNLOCK = "unlock"
    /** @brief Grant @ref ACTION value: re-lock now. */
    const val ACTION_LOCK = "lock"

    /** @brief Grant @ref STATUS value on creation. */
    const val STATUS_ACTIVE = "active"
    /** @brief Pairing @ref STATUS value once both sides are linked. */
    const val STATUS_ACCEPTED = "accepted"
}

// =============================================================================
// Read models
//
// These describe the *shape* of each document for when the app reads data back
// (e.g. the upcoming remote-unlock listener deserialises grants into GrantDoc).
// Writes deliberately do NOT use these classes — see the note on DeviceDoc.
//
// Firestore's automatic deserialisation needs a public no-arg constructor: giving
// every property a default value makes the Kotlin data class provide one. The
// @DocumentId annotation tells Firestore to fill that property with the document's
// id (which is part of its path, not a stored field).
// =============================================================================

/**
 * @brief Read model for a @c users/{uid} document.
 * @property uid The document id (the Auth uid).
 * @property displayName A safe, non-PII display name.
 * @property createdAt When the profile was created (server time), or @c null if unread.
 */
data class UserDoc(
    @DocumentId val uid: String = "",
    val displayName: String = "",
    val createdAt: Timestamp? = null,
)

/**
 * @brief Read model for a @c users/{uid}/devices/{deviceId} document.
 *
 * @warning Writes never serialise this class. The rules reject documents whose optional fields
 * are present-but-null, and serialising a data class always writes every property (including
 * @c null ones). @ref FirestoreAccountabilityService therefore builds explicit maps that omit
 * absent optional fields.
 *
 * @property deviceId The document id.
 * @property platform The device platform, e.g. "android".
 * @property armed Whether a lock is currently active on the device.
 * @property createdAt First-write time (server), or @c null if unread.
 * @property fcmToken The push token, or @c null if not yet registered.
 * @property lastHeartbeatAt The last heartbeat time, or @c null.
 */
data class DeviceDoc(
    @DocumentId val deviceId: String = "",
    val platform: String = "",
    val armed: Boolean = false,
    val createdAt: Timestamp? = null,
    val fcmToken: String? = null,
    val lastHeartbeatAt: Timestamp? = null,
)

/**
 * @brief Read model for a @c users/{uid}/events/{eventId} document (one accountability event).
 * @property id The document id.
 * @property type One of @ref com.kempt.app.data.BlockEvent's type constants.
 * @property at When the event occurred (server-comparable timestamp), or @c null if unread.
 * @property packageName The blocked app involved, or @c null.
 * @property deviceId The device that reported it, or @c null.
 */
data class EventDoc(
    @DocumentId val id: String = "",
    val type: String = "",
    val at: Timestamp? = null,
    val packageName: String? = null,
    val deviceId: String? = null,
)

/**
 * @brief Read model for a @c users/{uid}/grants/{grantId} document (a remote command).
 * @property id The document id.
 * @property action @ref Fs.ACTION_UNLOCK or @ref Fs.ACTION_LOCK.
 * @property issuedByUid The partner uid that created the grant.
 * @property createdAt When it was issued, or @c null if unread.
 * @property status @ref Fs.STATUS_ACTIVE on creation.
 * @property expiresAt For an unlock, when it lapses; @c null for a lock.
 */
data class GrantDoc(
    @DocumentId val id: String = "",
    val action: String = "",
    val issuedByUid: String = "",
    val createdAt: Timestamp? = null,
    val status: String = "",
    val expiresAt: Timestamp? = null,
)

/**
 * @brief Read model for a @c pairings/{accountableUid}_{partnerUid} document.
 * @property id The document id (the two uids joined by an underscore).
 * @property accountableUid The user whose device is monitored.
 * @property partnerUid The accountability partner.
 * @property status e.g. @ref Fs.STATUS_ACCEPTED.
 * @property createdAt When the pairing was created, or @c null if unread.
 */
data class PairingDoc(
    @DocumentId val id: String = "",
    val accountableUid: String = "",
    val partnerUid: String = "",
    val status: String = "",
    val createdAt: Timestamp? = null,
)
