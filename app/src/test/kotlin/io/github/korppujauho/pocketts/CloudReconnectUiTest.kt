package io.github.korppujauho.pocketts

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import io.github.korppujauho.pocketts.ui.MessagesHub
import io.github.korppujauho.pocketts.ui.PocketTsTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rCN-w360dp-h800dp-mdpi", sdk = [36])
class CloudReconnectUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun connectedChatHasManualReconnectAction() {
        var attempts = 0
        compose.setContent {
            PocketTsTheme {
                MessagesHub(
                    CloudAccountController.State(hasAccount = true, cloudConnected = true, username = "Fixture"),
                    onLogin = {}, onConnect = { attempts++ }, onRoom = {},
                )
            }
        }
        compose.onNodeWithText("重新连接云消息").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, attempts) }
    }

    @Test fun reconnectCannotBeTappedDuringAnotherCloudOperation() {
        compose.setContent {
            PocketTsTheme {
                MessagesHub(
                    CloudAccountController.State(hasAccount = true, cloudConnected = true, busy = true),
                    onLogin = {}, onConnect = {}, onRoom = {},
                )
            }
        }
        compose.onNodeWithText("重新连接云消息").assertIsNotEnabled()
    }

    @Test fun login503OffersReadOnlyDiagnosisWithoutTriggeringAnotherLogin() {
        var loginAttempts = 0
        var diagnostics = 0
        compose.setContent {
            PocketTsTheme {
                MessagesHub(
                    CloudAccountController.State(hasAccount = true, cloudError = "CHAT_LOGIN_HTTP_503"),
                    onLogin = {}, onConnect = { loginAttempts++ }, onRoom = {},
                    onDiagnose = { diagnostics++ },
                )
            }
        }
        compose.onNodeWithText("检测官方聊天登录接口（不发送密码）").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1, diagnostics)
            assertEquals(0, loginAttempts)
        }
    }
}
