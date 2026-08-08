package coredevices.pebble.account

import io.rebble.libpebblecommon.database.entity.APP_VERSION_REGEX
import io.rebble.libpebblecommon.web.LockerEntry
import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration
import kotlin.uuid.Uuid

/**
 * Small interface used to keep the local locker wiring independent of Koin providers.
 */
interface LibPebbleLockerProxy {
    fun getAllLockerUuids(): Flow<List<Uuid>>
    suspend fun addAppsToLocker(apps: List<LockerEntry>)
    suspend fun waitUntilAppSyncedToWatch(id: Uuid, timeout: Duration): Boolean
    suspend fun startAppOnWatch(id: Uuid): Boolean
}

/** Compare app versions numerically by major.minor segments. */
internal fun compareVersionStrings(a: String?, b: String?): Int {
    if (a == null && b == null) return 0
    if (a == null) return -1
    if (b == null) return 1

    val aMatch = APP_VERSION_REGEX.find(a)
    val bMatch = APP_VERSION_REGEX.find(b)
    val aMajor = aMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
    val aMinor = aMatch?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 0
    val bMajor = bMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
    val bMinor = bMatch?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 0
    return compareValuesBy(aMajor to aMinor, bMajor to bMinor, { it.first }, { it.second })
}
