package coredevices.pebble.firmware

import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import io.ktor.client.call.NoTransformationFoundException
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.serialization.ContentConvertException
import io.rebble.libpebblecommon.connection.FirmwareUpdateCheckResult
import io.rebble.libpebblecommon.services.FirmwareVersion
import io.rebble.libpebblecommon.services.WatchInfo
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Public firmware feed for Core Devices watches.
 *
 * The signed Core Devices companion can use its private OTA service. Local builds deliberately
 * have none of those credentials, so they use the public PebbleOS release assets instead. The
 * updater still validates the selected PBZ's board, inactive slot, and CRC before it is sent.
 */
class GitHubFirmwareReleaseFeed(
    private val httpClient: HttpClient,
) {
    private val logger = Logger.withTag("GitHubFirmwareReleaseFeed")

    suspend fun getLatestFirmware(watch: WatchInfo): FirmwareUpdateCheckResult {
        val release = try {
            val response = httpClient.get(LATEST_RELEASE_URL) {
                header(HttpHeaders.Accept, GITHUB_ACCEPT_HEADER)
                header(HttpHeaders.UserAgent, USER_AGENT)
            }
            if (!response.status.isSuccess()) {
                logger.w { "GitHub release check failed: ${response.status}" }
                return FirmwareUpdateCheckResult.UpdateCheckFailed("Failed to check for PebbleOS update")
            }
            response.body<GitHubFirmwareRelease>()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            logger.w(e) { "GitHub release check failed: ${e.message}" }
            return FirmwareUpdateCheckResult.UpdateCheckFailed("Failed to check for PebbleOS update")
        } catch (e: NoTransformationFoundException) {
            logger.w(e) { "Couldn't parse GitHub firmware release: ${e.message}" }
            return FirmwareUpdateCheckResult.UpdateCheckFailed("Failed to check for PebbleOS update")
        } catch (e: ContentConvertException) {
            logger.w(e) { "Couldn't parse GitHub firmware release: ${e.message}" }
            return FirmwareUpdateCheckResult.UpdateCheckFailed("Failed to check for PebbleOS update")
        }

        return release.toFirmwareUpdate(watch)
    }

    private companion object {
        const val LATEST_RELEASE_URL = "https://api.github.com/repos/coredevices/PebbleOS/releases/latest"
        const val GITHUB_ACCEPT_HEADER = "application/vnd.github+json"
        const val USER_AGENT = "Pebble-Local-Companion"
    }
}

@Serializable
internal data class GitHubFirmwareRelease(
    @SerialName("tag_name") val tagName: String,
    val body: String? = null,
    val assets: List<GitHubFirmwareAsset> = emptyList(),
)

@Serializable
internal data class GitHubFirmwareAsset(
    val name: String,
    @SerialName("browser_download_url") val downloadUrl: String,
)

internal fun GitHubFirmwareRelease.toFirmwareUpdate(watch: WatchInfo): FirmwareUpdateCheckResult {
    val version = FirmwareVersion.from(
        tag = tagName,
        isRecovery = false,
        gitHash = "",
        timestamp = Instant.DISTANT_PAST,
        isDualSlot = false,
        isSlot0 = false,
    ) ?: return FirmwareUpdateCheckResult.UpdateCheckFailed("Invalid PebbleOS release version")

    val expectedAssetName = "normal_${watch.platform.revision}_${tagName}.pbz"
    val asset = assets.firstOrNull { it.name == expectedAssetName }
        ?: return FirmwareUpdateCheckResult.UpdateCheckFailed(
            "No PebbleOS $tagName update is published for ${watch.platform.revision}",
        )
    if (!asset.downloadUrl.startsWith(GITHUB_RELEASE_DOWNLOAD_PREFIX)) {
        return FirmwareUpdateCheckResult.UpdateCheckFailed("Invalid PebbleOS release download")
    }

    return if (watch.runningFwVersion.isRecovery || version > watch.runningFwVersion) {
        FirmwareUpdateCheckResult.FoundUpdate(
            version = version,
            url = asset.downloadUrl,
            notes = body.orEmpty(),
        )
    } else {
        FirmwareUpdateCheckResult.FoundNoUpdate
    }
}

private const val GITHUB_RELEASE_DOWNLOAD_PREFIX =
    "https://github.com/coredevices/PebbleOS/releases/download/"
