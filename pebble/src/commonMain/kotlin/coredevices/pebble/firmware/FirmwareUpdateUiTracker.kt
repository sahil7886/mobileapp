package coredevices.pebble.firmware

import co.touchlab.kermit.Logger
import com.russhwolf.settings.Settings
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.FirmwareUpdateCheckResult
import io.rebble.libpebblecommon.connection.PebbleIdentifier
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

import coredevices.util.CoreConfigFlow
import io.rebble.libpebblecommon.connection.ConnectedPebble
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.services.FirmwareVersion
import kotlin.time.Duration.Companion.seconds

interface FirmwareUpdateUiTracker {
    fun didFirmwareUpdateCheckFromUi()
    fun shouldUiUpdateCheck(): Boolean
    fun maybeNotifyFirmwareUpdate(
        update: FirmwareUpdateCheckResult,
        identifier: PebbleIdentifier,
        watchName: String,
        runningFirmwareVersion: FirmwareVersion,
    )
    fun firmwareUpdateIsInProgress(identifier: PebbleIdentifier)
    fun updateWatchNow(libPebble: LibPebble, identifier: String)
}

class RealFirmwareUpdateUiTracker(
    private val settings: Settings,
    private val clock: Clock,
    private val appContext: AppContext,
    private val coreConfigFlow: CoreConfigFlow,
) : FirmwareUpdateUiTracker {
    private val logger = Logger.withTag("FirmwareUpdateUiTracker")
    private var lastUiUpdateMs: Long = settings.getLong(KEY_LAST_UI_UPDATE_CHECK_MS, 0)
    private val notificationGate = FirmwareUpdateNotificationGate(settings)

    override fun didFirmwareUpdateCheckFromUi() {
        val nowMs = clock.now().toEpochMilliseconds()
        lastUiUpdateMs = nowMs
        settings.putLong(KEY_LAST_UI_UPDATE_CHECK_MS, nowMs)
    }

    override fun shouldUiUpdateCheck(): Boolean {
        val nowMs = clock.now().toEpochMilliseconds()
        val shouldCheck = (nowMs - lastUiUpdateMs) > UI_UPDATE_CHECK_AGAIN_TIME.inWholeMilliseconds
        logger.d { "shouldUiUpdateCheck: $shouldCheck" }
        return shouldCheck
    }

    override fun maybeNotifyFirmwareUpdate(
        update: FirmwareUpdateCheckResult,
        identifier: PebbleIdentifier,
        watchName: String,
        runningFirmwareVersion: FirmwareVersion,
    ) {
        if (coreConfigFlow.value.disableFirmwareUpdateNotifications) {
            return
        }
        if (update !is FirmwareUpdateCheckResult.FoundUpdate) {
            return
        }
        if (!isNewNotificationRelease(runningFirmwareVersion, update.version)) {
            // Clear notifications produced by older app versions for a patch-only release.
            removeNotification(identifier.asString)
            logger.d {
                "Suppressing unchanged firmware release notification for ${identifier.asString}: " +
                    "running=${runningFirmwareVersion.stringVersion}, offered=${update.version.stringVersion}"
            }
            return
        }
        if (!notificationGate.shouldNotify(
                identifier = identifier,
                runningMajor = runningFirmwareVersion.major,
                runningMinor = runningFirmwareVersion.minor,
                updateMajor = update.version.major,
                updateMinor = update.version.minor,
            )
        ) {
            logger.d {
                "Suppressing already-notified firmware release for ${identifier.asString}: " +
                    "running=${runningFirmwareVersion.stringVersion}, offered=${update.version.stringVersion}"
            }
            return
        }
        val notificationKey = identifier.asString.hashCode()
        // Older app versions could have already delivered duplicate notifications for this watch.
        // Replace those with the single notification for this release.
        removeFirmwareUpdateNotification(appContext, notificationKey)
        notifyFirmwareUpdate(
            appContext = appContext,
            title = "PebbleOS update available",
            body = "PebbleOS ${update.version.stringVersion} is available for $watchName:\n${update.notes}",
            key = notificationKey,
            identifier = identifier,
        )
    }

    override fun firmwareUpdateIsInProgress(identifier: PebbleIdentifier) {
        logger.d { "Firmware update in progress; removing notification" }
        removeNotification(identifier.asString)
    }

    private fun removeNotification(identifier: String) {
        val notificationKey = identifier.hashCode()
        removeFirmwareUpdateNotification(appContext, notificationKey)
    }

    override fun updateWatchNow(libPebble: LibPebble, identifier: String) {
        removeNotification(identifier)
        val watch =
            libPebble.watches.value.firstOrNull { it.identifier.asString == identifier }
        if (watch == null) {
            logger.w { "No matching connected watch found for $identifier" }
            return
        }
        val update = (watch as? ConnectedPebble.Firmware)?.firmwareUpdateAvailable?.result
        if (update !is FirmwareUpdateCheckResult.FoundUpdate) {
            logger.w { "No update available for $watch" }
            return
        }
        val updater = watch as? ConnectedPebble.Firmware
        if (updater == null) {
            logger.w { "Can't update firmware for $watch" }
            return
        }
        logger.d { "Starting update for $identifier to $update" }
        updater.updateFirmware(update)
    }

    companion object {
        private const val KEY_LAST_UI_UPDATE_CHECK_MS = "LAST_UI_UPDATE_CHECK_MS"
        private val UI_UPDATE_CHECK_AGAIN_TIME = 1.hours
    }
}

/**
 * Decides whether to show a firmware-update notification for one watch.
 *
 * The update screen continues to show all available releases. System notifications are reserved
 * for a change in the first two version components (for example, v4.34 to v4.35); patch-only
 * releases do not alert. Each advertised release is recorded in persistent settings so app
 * restarts and changed release notes cannot re-alert.
 */
internal class FirmwareUpdateNotificationGate(
    private val settings: Settings,
) {
    fun shouldNotify(
        identifier: PebbleIdentifier,
        runningMajor: Int,
        runningMinor: Int,
        updateMajor: Int,
        updateMinor: Int,
    ): Boolean {
        if (!isNewNotificationRelease(
                runningMajor = runningMajor,
                runningMinor = runningMinor,
                updateMajor = updateMajor,
                updateMinor = updateMinor,
            )
        ) {
            return false
        }

        val key = "$KEY_LAST_NOTIFIED_RELEASE_PREFIX${identifier.asString}"
        val updateRelease = FirmwareNotificationRelease(updateMajor, updateMinor)
        val lastNotifiedRelease = settings.getString(key, "").toFirmwareNotificationRelease()
        if (lastNotifiedRelease != null && updateRelease <= lastNotifiedRelease) {
            return false
        }

        settings.putString(key, updateRelease.toString())
        return true
    }

    private companion object {
        const val KEY_LAST_NOTIFIED_RELEASE_PREFIX = "FIRMWARE_LAST_NOTIFIED_RELEASE_"
    }
}

private data class FirmwareNotificationRelease(
    val major: Int,
    val minor: Int,
) : Comparable<FirmwareNotificationRelease> {
    override fun compareTo(other: FirmwareNotificationRelease): Int =
        if (major != other.major) major.compareTo(other.major) else minor.compareTo(other.minor)

    override fun toString(): String = "$major.$minor"
}

private fun String.toFirmwareNotificationRelease(): FirmwareNotificationRelease? {
    val parts = split('.', limit = 2)
    if (parts.size != 2) {
        return null
    }
    val major = parts[0].toIntOrNull() ?: return null
    val minor = parts[1].toIntOrNull() ?: return null
    return FirmwareNotificationRelease(major, minor)
}

internal fun isNewNotificationRelease(
    runningFirmwareVersion: FirmwareVersion,
    updateFirmwareVersion: FirmwareVersion,
): Boolean = isNewNotificationRelease(
    runningMajor = runningFirmwareVersion.major,
    runningMinor = runningFirmwareVersion.minor,
    updateMajor = updateFirmwareVersion.major,
    updateMinor = updateFirmwareVersion.minor,
)

private fun isNewNotificationRelease(
    runningMajor: Int,
    runningMinor: Int,
    updateMajor: Int,
    updateMinor: Int,
): Boolean = FirmwareNotificationRelease(updateMajor, updateMinor) >
    FirmwareNotificationRelease(runningMajor, runningMinor)

expect fun notifyFirmwareUpdate(
    appContext: AppContext,
    title: String,
    body: String,
    key: Int,
    identifier: PebbleIdentifier,
)

expect fun removeFirmwareUpdateNotification(appContext: AppContext, key: Int)
