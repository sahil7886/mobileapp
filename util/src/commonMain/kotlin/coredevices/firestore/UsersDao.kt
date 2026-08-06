package coredevices.firestore

import com.russhwolf.settings.Settings
import coredevices.util.auth.LocalIdentityStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.GlobalScope
import kotlinx.serialization.json.Json

interface UsersDao {
    val user: Flow<PebbleUser?>
    val loginEvents: Flow<PebbleUser>
    suspend fun updateTodoBlockId(todoBlockId: String)
    suspend fun updateNotionPageId(pageId: String) {}
    suspend fun initUserDevToken(rebbleUserToken: String?)
    suspend fun updateLastConnectedWatch(serial: String)
    suspend fun updateRingLifetimeCollectionCount(serial: String, count: Int)
    suspend fun updateEncryptionInfo(info: EncryptionInfo) {}
    fun init()
}

data class PebbleUser(
    val isAnonymousUser: Boolean,
    val user: User,
)

/**
 * Persistent, local replacement for the former Firestore user document. This
 * remains intentionally small: it keeps settings that Pebble itself needs but
 * never creates an anonymous network identity or uploads them to an account.
 */
class UsersDaoImpl(
    private val settings: Settings,
    private val identities: LocalIdentityStore,
) : UsersDao {
    private val json = Json { ignoreUnknownKeys = true }
    private val _user = MutableStateFlow(currentUser())
    override val user: Flow<PebbleUser?> = _user.asStateFlow()
    private val _loginEvents = MutableSharedFlow<PebbleUser>(replay = 1)
    override val loginEvents: Flow<PebbleUser> = _loginEvents.asSharedFlow()
    private var initialized = false

    override fun init() {
        if (initialized) return
        initialized = true
        GlobalScope.launch {
            identities.identity.collect { identity ->
                val updated = PebbleUser(isAnonymousUser = identity == null, user = readUser())
                _user.value = updated
                if (identity != null) _loginEvents.emit(updated)
            }
        }
    }

    override suspend fun updateTodoBlockId(todoBlockId: String) = update { it.copy(todoBlockId = todoBlockId) }

    override suspend fun updateNotionPageId(pageId: String) = update {
        it.copy(notionPageId = pageId, todoBlockId = null)
    }

    override suspend fun initUserDevToken(rebbleUserToken: String?) {
        if (rebbleUserToken != null) update { it.copy(rebbleUserToken = rebbleUserToken) }
    }

    override suspend fun updateLastConnectedWatch(serial: String) = update {
        it.copy(lastConnectedWatch = serial)
    }

    override suspend fun updateRingLifetimeCollectionCount(serial: String, count: Int) = update { existing ->
        if ((existing.ringLifetimeCollectionCounts?.get(serial) ?: -1) >= count) existing
        else existing.copy(ringLifetimeCollectionCounts = existing.ringLifetimeCollectionCounts.orEmpty() + (serial to count))
    }

    override suspend fun updateEncryptionInfo(info: EncryptionInfo) = update { it.copy(encryption = info) }

    private fun currentUser() = PebbleUser(
        isAnonymousUser = identities.identity.value == null,
        user = readUser(),
    )

    private fun readUser(): User {
        settings.getStringOrNull(USER_KEY)
            ?.let { encoded -> runCatching { json.decodeFromString<User>(encoded) }.getOrNull() }
            ?.let { return it }

        return User(pebbleUserToken = generateRandomUserToken()).also { created ->
            settings.putString(USER_KEY, json.encodeToString(created))
        }
    }

    private fun update(transform: (User) -> User) {
        val updated = transform(readUser())
        settings.putString(USER_KEY, json.encodeToString(updated))
        _user.value = PebbleUser(identities.identity.value == null, updated)
    }

    private companion object {
        const val USER_KEY = "local_pebble_user"
    }
}

fun generateRandomUserToken(): String {
    val charPool = "0123456789abcdef"
    return (1..24).joinToString("") { charPool.random().toString() }
}
