package coredevices.pebble.firmware

import com.russhwolf.settings.MapSettings
import io.rebble.libpebblecommon.connection.PebbleSocketIdentifier
import io.rebble.libpebblecommon.services.FirmwareVersion
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class FirmwareUpdateNotificationGateTest {
    private val settings = MapSettings()
    private val gate = FirmwareUpdateNotificationGate(settings)
    private val watch = PebbleSocketIdentifier("watch-1")

    @Test
    fun doesNotNotifyForPatchOrOlderReleases() {
        assertFalse(
            gate.shouldNotify(
                watch,
                runningMajor = 4,
                runningMinor = 34,
                updateMajor = 4,
                updateMinor = 34,
            ),
        )
        assertFalse(
            gate.shouldNotify(
                watch,
                runningMajor = 4,
                runningMinor = 34,
                updateMajor = 4,
                updateMinor = 33,
            ),
        )
    }

    @Test
    fun treatsPatchOnlyVersionChangesAsTheSameNotificationRelease() {
        assertFalse(isNewNotificationRelease(version("v4.34.0"), version("v4.34.1")))
        assertTrue(isNewNotificationRelease(version("v4.34.1"), version("v4.35.0")))
    }

    @Test
    fun notifiesOnceForANewerMinorRelease() {
        assertTrue(
            gate.shouldNotify(
                watch,
                runningMajor = 4,
                runningMinor = 34,
                updateMajor = 4,
                updateMinor = 35,
            ),
        )
        assertFalse(
            gate.shouldNotify(
                watch,
                runningMajor = 4,
                runningMinor = 34,
                updateMajor = 4,
                updateMinor = 35,
            ),
        )
    }

    @Test
    fun remembersANotificationAfterTheTrackerIsRecreated() {
        assertTrue(
            gate.shouldNotify(
                watch,
                runningMajor = 4,
                runningMinor = 34,
                updateMajor = 4,
                updateMinor = 35,
            ),
        )

        val recreatedGate = FirmwareUpdateNotificationGate(settings)
        assertFalse(
            recreatedGate.shouldNotify(
                watch,
                runningMajor = 4,
                runningMinor = 34,
                updateMajor = 4,
                updateMinor = 35,
            ),
        )
    }

    @Test
    fun allowsTheNextMinorReleaseAndTracksWatchesSeparately() {
        assertTrue(
            gate.shouldNotify(
                watch,
                runningMajor = 4,
                runningMinor = 34,
                updateMajor = 4,
                updateMinor = 35,
            ),
        )
        assertTrue(
            gate.shouldNotify(
                watch,
                runningMajor = 4,
                runningMinor = 34,
                updateMajor = 4,
                updateMinor = 36,
            ),
        )

        val otherWatch = PebbleSocketIdentifier("watch-2")
        assertTrue(
            gate.shouldNotify(
                otherWatch,
                runningMajor = 4,
                runningMinor = 34,
                updateMajor = 4,
                updateMinor = 35,
            ),
        )
    }

    private fun version(tag: String): FirmwareVersion = FirmwareVersion.from(
        tag = tag,
        isRecovery = false,
        gitHash = "",
        timestamp = Instant.DISTANT_PAST,
        isDualSlot = false,
        isSlot0 = false,
    )!!
}
