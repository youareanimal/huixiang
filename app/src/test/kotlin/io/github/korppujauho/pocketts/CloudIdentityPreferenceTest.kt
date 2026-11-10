package io.github.korppujauho.pocketts

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class CloudIdentityPreferenceTest {
    @Test fun selectionIsScopedToTheSignedInAccount() {
        val context = RuntimeEnvironment.getApplication() as Context
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        val settings = Settings(context)
        settings.selectCloudIdentityId("Alice@example.com", "first")
        settings.selectCloudIdentityId("bob@example.com", "second")
        assertEquals("first", settings.selectedCloudIdentityId("alice@example.com"))
        assertEquals("second", settings.selectedCloudIdentityId("BOB@example.com"))
        assertNull(settings.selectedCloudIdentityId("charlie@example.com"))
        assertNull(settings.selectedCloudIdentityId(""))
    }
}
