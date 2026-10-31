package io.github.korppujauho.pocketts.proto

/** Local proof construction and server confirmation are deliberately separate states. Contains no account data. */
enum class MyTsVoiceStatus {
    NOT_REQUESTED, MATERIAL_UNAVAILABLE, PROOF_UNAVAILABLE, PROOF_SENT, SERVER_CONFIRMED, SERVER_NOT_CONFIRMED
}

/** Bounded server receipt information only; no account identifiers or remote text. */
enum class MyTsVoiceCheck {
    ID_MATCHED, ID_ABSENT, ID_MISMATCH, QUERY_REJECTED, QUERY_NO_ID,
    METADATA_ACCEPTED, METADATA_REJECTED
}
