package coredevices.ui

import PlatformUiContext
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import co.touchlab.kermit.Logger
import coredevices.analytics.AnalyticsBackend
import coredevices.analytics.setUser
import coredevices.util.auth.AppleAuthUtil
import coredevices.util.auth.LocalIdentityStore
import coredevices.util.rememberUiContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Signs in with Apple only. The result is kept on this iPhone and is not sent
 * to an account server or used as a cloud token.
 */
@Composable
fun SignInDialog(
    onDismiss: () -> Unit = {},
    skipAccountSwitchConfirmation: Boolean = false,
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            shape = MaterialTheme.shapes.extraLarge,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "Sign in with Apple",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(8.dp),
                )
                SignInButtons(onDismiss = onDismiss, primaryColor = true, skipAccountSwitchConfirmation = skipAccountSwitchConfirmation)
            }
        }
    }
}

@Composable
fun SignInButtons(
    onDismiss: () -> Unit,
    primaryColor: Boolean,
    @Suppress("UNUSED_PARAMETER") skipAccountSwitchConfirmation: Boolean = false,
) {
    val appleAuth: AppleAuthUtil = koinInject()
    val identities: LocalIdentityStore = koinInject()
    val analytics: AnalyticsBackend = koinInject()
    val context: PlatformUiContext? = rememberUiContext()
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PebbleElevatedButton(
            text = "Sign in with Apple",
            primaryColor = primaryColor,
            modifier = Modifier.fillMaxWidth().testTag("onboarding_sign_in_apple"),
            onClick = {
                // The native authorization sheet can temporarily dispose the
                // Compose layer, so the coroutine must outlive this composition.
                GlobalScope.launch(Dispatchers.Main) {
                    try {
                        val identity = appleAuth.signInApple(context ?: return@launch) ?: return@launch
                        identities.save(identity)
                        identity.email?.let(analytics::setUser)
                        analytics.logEvent("signed_in_apple_local", mapOf("provider" to identity.provider))
                        onDismiss()
                    } catch (e: Exception) {
                        Logger.e(e) { "Apple sign-in failed" }
                        error = e.message ?: "Apple sign-in failed"
                    }
                }
            },
        )
        Text(
            "This identifies this app on this iPhone only. It does not create a cloud Locker account.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
        error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}
