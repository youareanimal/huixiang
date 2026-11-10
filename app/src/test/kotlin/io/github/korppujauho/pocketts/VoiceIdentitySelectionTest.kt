package io.github.korppujauho.pocketts

import io.github.korppujauho.pocketts.proto.CloudFailure
import io.github.korppujauho.pocketts.proto.Identity
import io.github.korppujauho.pocketts.proto.MyTsAccount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VoiceIdentitySelectionTest {
    @Test fun signedInUsesOnlyAccountIdentityAndNeverFallsBack() {
        val session = MyTsAccount.Session("fixture", "sample")
        val cloud = Identity.generate(securityLevel = 0)
        var guestCalls = 0
        val guest = { guestCalls++; Identity.generate(securityLevel = 0) }
        assertEquals(cloud.uid, resolveVoiceIdentity(session, true, { cloud }, guest).uid)
        assertThrows(CloudFailure::class.java) {
            resolveVoiceIdentity(session, true, { throw CloudFailure("NETWORK") }, guest)
        }
        assertThrows(CloudFailure::class.java) {
            resolveVoiceIdentity(null, true, { cloud }, guest)
        }
        assertEquals(0, guestCalls)
        resolveVoiceIdentity(null, false, { error("guest must not use account") }, guest)
        assertEquals(1, guestCalls)
    }
}
