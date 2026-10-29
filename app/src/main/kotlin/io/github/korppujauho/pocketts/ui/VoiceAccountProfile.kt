package io.github.korppujauho.pocketts.ui

import androidx.compose.runtime.staticCompositionLocalOf
import io.github.korppujauho.pocketts.TsFile
import io.github.korppujauho.pocketts.proto.ServerModel

/** Local presentation of our account in the active voice session; never sent to another client. */
data class VoiceAccountProfile(val clientId: Int = 0, val username: String = "", val email: String = "", val avatarUrl: String = "")
val LocalVoiceAccountProfile = staticCompositionLocalOf { VoiceAccountProfile() }

internal fun personAvatarModels(person: ServerModel.Client?, server: String, account: VoiceAccountProfile): List<Any> =
    buildList {
        if (person != null && person.id == account.clientId && account.avatarUrl.isNotBlank()) add(account.avatarUrl)
        person?.let {
            TsFile.avatar(server, it.uid, it.avatarName, it.avatar)?.let(::add)
            if (it.myTeamSpeakAvatar.isNotBlank()) add(it.myTeamSpeakAvatar)
        }
    }.distinct()
