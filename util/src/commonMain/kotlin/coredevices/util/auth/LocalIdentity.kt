package coredevices.util.auth

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A device-local account marker. It intentionally stores no Apple identity token:
 * the Pebble-only app has no account server to exchange it with.
 */
data class LocalIdentity(
    val provider: String,
    val subject: String,
    val email: String?,
    val displayName: String?,
)

class LocalIdentityStore(private val settings: Settings) {
    private val _identity = MutableStateFlow(read())
    val identity: StateFlow<LocalIdentity?> = _identity.asStateFlow()

    fun save(identity: LocalIdentity) {
        settings.putString(PROVIDER, identity.provider)
        settings.putString(SUBJECT, identity.subject)
        identity.email?.let { settings.putString(EMAIL, it) } ?: settings.remove(EMAIL)
        identity.displayName?.let { settings.putString(DISPLAY_NAME, it) } ?: settings.remove(DISPLAY_NAME)
        _identity.value = identity
    }

    fun clear() {
        listOf(PROVIDER, SUBJECT, EMAIL, DISPLAY_NAME).forEach { settings.remove(it) }
        _identity.value = null
    }

    private fun read(): LocalIdentity? {
        val provider = settings.getStringOrNull(PROVIDER) ?: return null
        val subject = settings.getStringOrNull(SUBJECT) ?: return null
        return LocalIdentity(
            provider = provider,
            subject = subject,
            email = settings.getStringOrNull(EMAIL),
            displayName = settings.getStringOrNull(DISPLAY_NAME),
        )
    }

    private companion object {
        const val PROVIDER = "local_identity_provider"
        const val SUBJECT = "local_identity_subject"
        const val EMAIL = "local_identity_email"
        const val DISPLAY_NAME = "local_identity_display_name"
    }
}
