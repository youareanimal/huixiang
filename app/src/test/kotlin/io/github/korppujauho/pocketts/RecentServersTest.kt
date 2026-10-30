package io.github.korppujauho.pocketts

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RecentServersTest {
    @Test fun recentConnectionsNeverPersistPasswordsOrInvitationsAndKeepNicknameVariants() {
        val context = RuntimeEnvironment.getApplication()
        RecentServers.record(context, Favorite("voice.example.com", "Server", "Alice", "private-password"))
        RecentServers.record(context, Favorite("voice.example.com", "Server", "Bob", "other-password"))
        val rows = RecentServers.record(context, Favorite("ts3server://voice.example.com?password=secret"))
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.password.isEmpty() })
        val data = context.getSharedPreferences("recent-servers", 0).all.toString()
        assertFalse(data.contains("password")); assertFalse(data.contains("secret"))
        val updated = RecentServers.record(context, Favorite("VOICE.example.com:9987", "Renamed", "Alice"))
        assertEquals(2, updated.size)
        assertEquals("Renamed", updated.first().name)
    }
}
