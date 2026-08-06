package coredevices.pebble.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import co.touchlab.kermit.Logger
import com.russhwolf.settings.Settings
import coredevices.pebble.ui.SettingsKeys.KEY_ENABLE_MEMFAULT_UPLOADS
import coredevices.ui.PebbleElevatedButton
import coredevices.ui.PebbleWebview
import coredevices.ui.PebbleWebviewNavigator
import coredevices.ui.PebbleWebviewUrlInterceptor
import coredevices.ui.SignInDialog
import coredevices.util.auth.LocalIdentityStore
import org.koin.compose.koinInject

@Composable
fun BatterySettingsScreen(navBarNav: NavBarNav, topBarParams: TopBarParams) {
    val settings = koinInject<Settings>()
    val analyticsEnabled = settings.getBoolean(KEY_ENABLE_MEMFAULT_UPLOADS, true)
    val identities: LocalIdentityStore = koinInject()
    val identity by identities.identity.collectAsState()
    val accountEmail = identity?.email

    var showSignInDialog by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf<String?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        topBarParams.searchAvailable(null)
        topBarParams.title("Battery")
    }

    LaunchedEffect(accountEmail) {
        if (accountEmail == null) {
            url = null
            return@LaunchedEffect
        }
        url = null
        loadError = "Battery analytics requires the legacy cloud account and is not included in this local-only build."
    }

    if (showSignInDialog) {
        SignInDialog(onDismiss = { showSignInDialog = false })
    }

    if (accountEmail == null) {
        SignedOutBatteryContent(onSignIn = { showSignInDialog = true })
        LaunchedEffect(Unit) {
            topBarParams.actions { }
        }
        return
    }

    if (!analyticsEnabled) {
        AnalyticsDisabledBatteryContent(
            onOpenSettings = {
                navBarNav.navigateTo(
                    PebbleNavBarRoutes.WatchSettingsCategoryRoute(
                        section = Section.Diagnostics.name,
                        topLevelType = TopLevelType.Phone.name,
                    ),
                )
            },
        )
        LaunchedEffect(Unit) {
            topBarParams.actions { }
        }
        return
    }

    val currentUrl = url
    val currentError = loadError
    if (currentUrl == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            if (currentError != null) {
                Text(
                    currentError,
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        LaunchedEffect(Unit) {
            topBarParams.actions { }
        }
        return
    }

    var pageError by remember { mutableStateOf<String?>(null) }
    // Clear any prior failure when the target URL changes (e.g. sign-in or token refresh).
    LaunchedEffect(currentUrl) { pageError = null }

    if (pageError != null) {
        Logger.withTag("BatterySettingsScreen").w { "Battery page failed to load: $pageError" }
        BatteryLoadErrorContent(onRetry = { pageError = null })
        LaunchedEffect(Unit) {
            topBarParams.actions { }
        }
        return
    }

    val interceptor = remember {
        object : PebbleWebviewUrlInterceptor {
            override var navigator: PebbleWebviewNavigator? = null
            override fun onIntercept(url: String, navigator: PebbleWebviewNavigator) = true
        }
    }
    LaunchedEffect(interceptor) {
        topBarParams.actions {
            IconButton(onClick = { interceptor.navigator?.reload() }) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh")
            }
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        PebbleWebview(
            url = currentUrl,
            interceptor = interceptor,
            modifier = Modifier.fillMaxSize(),
            onPageError = { pageError = it },
        )
    }
}

@Composable
private fun SignedOutBatteryContent(onSignIn: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "You must be signed into your Pebble account to view your Battery usage.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Box(Modifier.height(12.dp))
        PebbleElevatedButton(
            onClick = onSignIn,
            text = "Sign in",
            primaryColor = true,
        )
    }
}

@Composable
private fun BatteryLoadErrorContent(onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "Couldn't load your Battery usage. Check your connection and try again.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Box(Modifier.height(12.dp))
        PebbleElevatedButton(
            onClick = onRetry,
            text = "Retry",
            primaryColor = true,
        )
    }
}

@Composable
private fun AnalyticsDisabledBatteryContent(onOpenSettings: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "You need 'Send watch analytics' enabled to view your Battery usage.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Box(Modifier.height(12.dp))
        PebbleElevatedButton(
            onClick = onOpenSettings,
            text = "Open settings",
            primaryColor = true,
        )
    }
}
