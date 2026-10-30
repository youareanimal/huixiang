package io.github.korppujauho.pocketts.proto

/** Local proof construction and server confirmation are deliberately separate states. Contains no account data. */
enum class MyTsVoiceStatus {
    NOT_REQUESTED, MATERIAL_UNAVAILABLE, PROOF_UNAVAILABLE, PROOF_SENT, SERVER_CONFIRMED, SERVER_NOT_CONFIRMED
}
