package io.github.korppujauho.pocketts

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import io.github.korppujauho.pocketts.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rCN-w360dp-h800dp-mdpi", sdk = [36])
class HeadlineSpacingUiTest {
    @get:Rule val compose = createComposeRule()
    private fun verify(fontScale: Float) {
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
            PocketTsTheme { ManualConnectScreen("访客", "", "", "", null, {}, onConnect = { _,_,_,_,_ -> }) }
        } }
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("输入地址，\n就能一起开聊。").assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        val layout = results.single()
        assertTrue(layout.lineCount >= 2)
        val style = layout.layoutInput.style
        assertTrue("Headline needs its own readable line height", style.lineHeight.value >= style.fontSize.value * 1.25f)
        val glyphHeight = with(layout.layoutInput.density) { style.fontSize.toPx() }
        for (line in 1 until layout.lineCount) {
            val gap = layout.getLineBaseline(line) - layout.getLineBaseline(line - 1)
            assertTrue("Baseline gap $gap must accommodate font height $glyphHeight", gap >= glyphHeight)
        }
    }
    @Test fun normalChineseHeadlineHasEnoughLineSpacing() = verify(1f)
    @Test fun enlargedChineseHeadlineWrapsWithoutOverlapping() = verify(1.5f)
}
