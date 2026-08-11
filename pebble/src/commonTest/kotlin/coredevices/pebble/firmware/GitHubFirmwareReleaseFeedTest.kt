package coredevices.pebble.firmware

import io.rebble.libpebblecommon.connection.FirmwareUpdateCheckResult
import io.rebble.libpebblecommon.metadata.WatchColor
import io.rebble.libpebblecommon.metadata.WatchHardwarePlatform
import io.rebble.libpebblecommon.services.FirmwareVersion
import io.rebble.libpebblecommon.services.WatchInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

class GitHubFirmwareReleaseFeedTest {
    @Test
    fun selectsOnlyTheMatchingBoardAsset() {
        val result = release(
            GitHubFirmwareAsset(
                name = "normal_obelix_dvt_v4.33.1.pbz",
                downloadUrl = "https://github.com/coredevices/PebbleOS/releases/download/v4.33.1/normal_obelix_dvt_v4.33.1.pbz",
            ),
            GitHubFirmwareAsset(
                name = "normal_obelix_pvt_v4.33.1.pbz",
                downloadUrl = "https://github.com/coredevices/PebbleOS/releases/download/v4.33.1/normal_obelix_pvt_v4.33.1.pbz",
            ),
        ).toFirmwareUpdate(watch("v4.31.1"))

        val update = assertIs<FirmwareUpdateCheckResult.FoundUpdate>(result)
        assertEquals("v4.33.1", update.version.stringVersion)
        assertEquals(
            "https://github.com/coredevices/PebbleOS/releases/download/v4.33.1/normal_obelix_pvt_v4.33.1.pbz",
            update.url,
        )
    }

    @Test
    fun rejectsAReleaseWithoutAnAssetForTheWatchBoard() {
        val result = release(
            GitHubFirmwareAsset(
                name = "normal_obelix_dvt_v4.33.1.pbz",
                downloadUrl = "https://github.com/coredevices/PebbleOS/releases/download/v4.33.1/normal_obelix_dvt_v4.33.1.pbz",
            ),
        ).toFirmwareUpdate(watch("v4.31.1"))

        val failure = assertIs<FirmwareUpdateCheckResult.UpdateCheckFailed>(result)
        assertTrue(failure.error.contains("obelix_pvt"))
    }

    @Test
    fun doesNotOfferTheSameVersionAgain() {
        val result = release(
            GitHubFirmwareAsset(
                name = "normal_obelix_pvt_v4.33.1.pbz",
                downloadUrl = "https://github.com/coredevices/PebbleOS/releases/download/v4.33.1/normal_obelix_pvt_v4.33.1.pbz",
            ),
        ).toFirmwareUpdate(watch("v4.33.1"))

        assertIs<FirmwareUpdateCheckResult.FoundNoUpdate>(result)
    }

    private fun release(vararg assets: GitHubFirmwareAsset) = GitHubFirmwareRelease(
        tagName = "v4.33.1",
        body = "Release notes",
        assets = assets.toList(),
    )

    private fun watch(version: String) = WatchInfo(
        runningFwVersion = FirmwareVersion.from(
            tag = version,
            isRecovery = false,
            gitHash = "",
            timestamp = Instant.DISTANT_FUTURE,
            isDualSlot = true,
            isSlot0 = true,
        )!!,
        recoveryFwVersion = FirmwareVersion.from(
            tag = "v4.9.142",
            isRecovery = true,
            gitHash = "",
            timestamp = Instant.DISTANT_PAST,
            isDualSlot = true,
            isSlot0 = false,
        )!!,
        platform = WatchHardwarePlatform.CORE_OBELIX_PVT,
        bootloaderTimestamp = Instant.DISTANT_PAST,
        board = "obelix",
        serial = "test",
        btAddress = "00:00:00:00:00:00",
        resourceCrc = 0,
        resourceTimestamp = Instant.DISTANT_PAST,
        language = "en_US",
        languageVersion = 1,
        capabilities = emptySet(),
        isUnfaithful = false,
        healthInsightsVersion = 0,
        javascriptVersion = 0,
        color = WatchColor.PebbleTime2SilverGray,
    )
}
