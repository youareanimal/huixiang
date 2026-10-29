package io.github.korppujauho.pocketts

import io.github.korppujauho.pocketts.proto.ServerModel
import io.github.korppujauho.pocketts.ui.VoiceAccountProfile
import io.github.korppujauho.pocketts.ui.personAvatarModels
import org.junit.Assert.*
import org.junit.Test

class PersonAvatarModelsTest {
    private val own = ServerModel.Client(1, 1, "Alice", avatarName = "a".repeat(40), avatar = "server-hash",
        myTeamSpeakAvatar = "https://storage.googleapis.com/ts-sys-myts-avatars/id/1")
    private val account = VoiceAccountProfile(1, "Alice", "private@example.com", "https://storage.googleapis.com/account/own-avatar")

    @Test fun ownAccountAvatarComesFirstAndServerAndMyTsPicturesRemainFallbacks() {
        val models = personAvatarModels(own, "fixture.invalid", account)
        assertEquals(account.avatarUrl, models[0])
        assertTrue(models[1] is TsFile)
        assertEquals(own.myTeamSpeakAvatar, models[2])
    }

    @Test fun anotherUserNeverReceivesOurAvatarAndMissingPicturesHaveNoInventedIdentity() {
        val other = own.copy(id = 2, nickname = "Other")
        val models = personAvatarModels(other, "fixture.invalid", account)
        assertFalse(models.contains(account.avatarUrl))
        assertTrue(models.first() is TsFile)
        assertEquals(emptyList<Any>(), personAvatarModels(null, "fixture.invalid", account))
        assertEquals(emptyList<Any>(), personAvatarModels(ServerModel.Client(2, 1, "No avatar"), "fixture.invalid", account))
        assertFalse(personAvatarModels(own, "fixture.invalid", VoiceAccountProfile()).contains(account.avatarUrl))
    }
}
