# Project-specific ProGuard/R8 rules.
# Hilt, Room, and Firebase ship their own consumer rules, so this is usually empty to start.
# If you add DTOs deserialized from FCM payloads (or any reflection-based serialization),
# keep them here, e.g.:
# -keep class com.kempt.app.sync.model.** { *; }
