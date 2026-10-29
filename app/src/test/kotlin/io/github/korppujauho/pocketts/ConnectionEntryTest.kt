package io.github.korppujauho.pocketts

import android.content.Context
import io.github.korppujauho.pocketts.proto.MyTsBookmarks
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConnectionEntryTest {
    private val context get() = RuntimeEnvironment.getApplication() as Context
    private val signedOut = CloudAccountController.State()
    private val alice = signedOut.copy(hasAccount = true, username = "Alice")
    @Before fun clear() { context.getSharedPreferences("hub", Context.MODE_PRIVATE).edit().clear().commit() }

    @Test fun oldAccountEntryFlagDoesNotAuthorizeGuestAndAccountRestorationMustFinish() {
        context.getSharedPreferences("hub", Context.MODE_PRIVATE).edit().putBoolean("entered", true).commit()
        val entry = ConnectionEntry(context)
        assertFalse(entry.canConnect(signedOut))
        assertFalse(entry.enterGuest(signedOut.copy(restored = false)))
        assertNull(entry.nickname(alice.copy(restored = false), "Visitor"))
        assertFalse(entry.enterGuest(alice))
        assertFalse(entry.guestEntered)
    }

    @Test fun onlyExplicitGuestEntryAuthorizesSignedOutConnectionsAndLoginClearsIt() {
        val entry = ConnectionEntry(context)
        assertNull(entry.nickname(signedOut, "Visitor"))
        assertTrue(entry.enterGuest(signedOut))
        assertEquals("Visitor", entry.nickname(signedOut, "Visitor"))
        entry.clearGuest()
        assertEquals("Alice", entry.nickname(alice, "Visitor"))
        assertNull(entry.nickname(signedOut, "Visitor"))
        assertFalse(ConnectionEntry(context).guestEntered)
    }

    @Test fun accountAlwaysOverridesHistoricManualBookmarkAndInvitationNicknames() {
        val entry = ConnectionEntry(context)
        for (requested in listOf("回响访客", "Old account", "Invitation alias", ""))
            assertEquals("Alice", entry.nickname(alice, requested))
        assertEquals("Bob", entry.nickname(alice.copy(username = "Bob"), "Alice"))
        assertNull(entry.nickname(alice.copy(username = ""), "回响访客"))
    }

    @Test fun recentConnectionUsesCurrentAccountBookmarkCredentialsWithoutChangingHistory() {
        val recent = Favorite("fixture.invalid", "Last server", "回响访客")
        val bookmark = MyTsBookmarks.Bookmark("id", "Current bookmark", "fixture.invalid", "Old alias", "server password")
        val target = ConnectionEntry(context).recentTarget(alice.copy(bookmarks = listOf(bookmark)), recent,
            listOf(Favorite("fixture.invalid", nickname = "Local visitor", password = "old password")))
        assertEquals("Alice", target.nickname)
        assertEquals("server password", target.password)
        assertEquals("回响访客", recent.nickname)
        assertEquals("Last server", target.name)
    }
}
