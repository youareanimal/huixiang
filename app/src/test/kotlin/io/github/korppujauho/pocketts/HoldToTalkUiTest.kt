package io.github.korppujauho.pocketts

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.korppujauho.pocketts.ui.holdToTalk
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class HoldToTalkUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun cancelledGestureStopsTransmission() {
        var held = false
        compose.setContent { Box(Modifier.size(100.dp).testTag("ptt").holdToTalk { held = it }) }
        compose.onNodeWithTag("ptt").performTouchInput { down(center) }
        compose.runOnIdle { assertTrue(held) }
        compose.onNodeWithTag("ptt").performTouchInput { cancel() }
        compose.runOnIdle { assertFalse(held) }
    }

    @Test fun leavingThePageWhilePressedStopsTransmission() {
        var held = false
        val shown = mutableStateOf(true)
        compose.setContent { if (shown.value) Box(Modifier.size(100.dp).testTag("ptt").holdToTalk { held = it }) }
        compose.onNodeWithTag("ptt").performTouchInput { down(center) }
        compose.runOnIdle { assertTrue(held); shown.value = false }
        compose.waitForIdle()
        compose.runOnIdle { assertFalse(held) }
    }
}
