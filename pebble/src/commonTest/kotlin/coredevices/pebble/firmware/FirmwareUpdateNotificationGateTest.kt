package coredevices.pebble.firmware

import com.russhwolf.settings.MapSettings
import io.rebble.libpebblecommon.connection.PebbleSocketIdentifier
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FirmwareUpdateNotificationGateTest {
    private val settings = MapSettings()
    private val gate = FirmwareUpdateNotificationGate(settings)
    private val watch = PebbleSocketIdentifier("watch-1")

    @Test
    fun doesNotNotifyForMinorOrPatchReleases() {
        assertFalse(gate.shouldNotify(watch, runningMajor = 4, updateMajor = 4))
        assertFalse(gate.shouldNotify(watch, runningMajor = 4, updateMajor = 3))
    }

    @Test
    fun notifiesOnceForANewerMajorRelease() {
        assertTrue(gate.shouldNotify(watch, runningMajor = 4, updateMajor = 5))
        assertFalse(gate.shouldNotify(watch, runningMajor = 4, updateMajor = 5))
    }

    @Test
    fun remembersANotificationAfterTheTrackerIsRecreated() {
        assertTrue(gate.shouldNotify(watch, runningMajor = 4, updateMajor = 5))

        val recreatedGate = FirmwareUpdateNotificationGate(settings)
        assertFalse(recreatedGate.shouldNotify(watch, runningMajor = 4, updateMajor = 5))
    }

    @Test
    fun allowsTheNextMajorReleaseAndTracksWatchesSeparately() {
        assertTrue(gate.shouldNotify(watch, runningMajor = 4, updateMajor = 5))
        assertTrue(gate.shouldNotify(watch, runningMajor = 4, updateMajor = 6))

        val otherWatch = PebbleSocketIdentifier("watch-2")
        assertTrue(gate.shouldNotify(otherWatch, runningMajor = 4, updateMajor = 5))
    }
}
