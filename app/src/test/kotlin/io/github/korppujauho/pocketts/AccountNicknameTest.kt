package io.github.korppujauho.pocketts

import android.content.Context
import io.github.korppujauho.pocketts.proto.MyTsAccount
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AccountNicknameTest {
    private val context get() = RuntimeEnvironment.getApplication() as Context
    private fun settings() = Settings(context).also {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun loginSuppliesTheNicknameAndTracksAnAccountSwitch() {
        val settings = settings()
        settings.applyAccountUsername("Alice", overwrite = true)
        assertEquals("Alice", settings.nickname)
        settings.applyAccountUsername("Bob")
        assertEquals("Bob", settings.nickname)
        settings.nickname = ""
        assertEquals("Bob", settings.nickname)
        settings.clearAccountUsername()
        assertEquals("", settings.nickname)
    }

    @Test fun reopeningPreservesAManuallyChosenNicknameAndLogoutKeepsIt() {
        val settings = settings()
        settings.applyAccountUsername("Alice", overwrite = true)
        settings.nickname = "Server alias"
        settings.applyAccountUsername("Alice")
        assertEquals("Server alias", settings.nickname)
        settings.clearAccountUsername()
        assertEquals("Server alias", settings.nickname)
        assertEquals("", settings.accountUsername)
    }

    @Test fun anAlreadyLoggedInInstallationGetsANicknameWithoutSigningInAgain() {
        val settings = settings()
        val prefs = context.getSharedPreferences("nickname-cloud-fixture", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val box = SecretBox(SoftwareKey())
        val store = CloudAccountStore(prefs) { box }
        store.writeIdentity(MyTsAccount.Session("private-fixture-token", "Alice"))
        settings.restoreAccountNickname(store)
        assertEquals("Alice", settings.nickname)
        assertEquals("Alice", settings.accountUsername)
    }
}
