package io.github.korppujauho.pocketts.proto

/** Local proof construction and server confirmation are deliberately separate states. Contains no account data. */
enum class MyTsVoiceStatus {
    NOT_REQUESTED, MATERIAL_UNAVAILABLE, PROOF_UNAVAILABLE, PROOF_SENT, SERVER_CONFIRMED, SERVER_NOT_CONFIRMED
}

/** Bounded server receipt information only; no account identifiers or remote text. */
enum class MyTsVoiceCheck {
    ID_MATCHED, ID_ABSENT, ID_MISMATCH, QUERY_REJECTED, QUERY_NO_ID,
    METADATA_ACCEPTED, METADATA_REJECTED, METADATA_UNAVAILABLE, METADATA_FETCH_FAILED
}


/** MyTS IDs arrive in the initial handshake and in per-client view/update notifications. */
internal fun myTsIdentityIdGroups(cmd: Command, ownClientId: Int): List<Map<String, String>> =
    when (cmd.name) {
        "initserver" -> listOf(cmd.params)
        "clientvariables", "notifyclientupdated", "notifycliententerview" ->
            cmd.groups.filter { it["clid"]?.toIntOrNull() == ownClientId }
        else -> emptyList()
    }
