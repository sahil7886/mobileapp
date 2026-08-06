@file:OptIn(ExperimentalTime::class)

package coredevices.indexai.data.entity

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Local representation for an instant. All local records use the kotlinx map
 * shape `{epochSeconds, nanosecondsOfSecond}`.
 *
 * A malformed value decodes to `Instant.fromEpochSeconds(0,0)`, so it sorts
 * to the bottom of a local feed instead of stranding an entire recording.
 *
 * Writes emit the same kotlinx map shape.
 */
object TolerantInstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("kotlin.time.Instant") {
        element<Long>("epochSeconds")
        element<Int>("nanosecondsOfSecond", isOptional = true)
    }

    override fun deserialize(decoder: Decoder): Instant {
        // Kx serialization map shape `{epochSeconds, nanosecondsOfSecond}`.
        var epochSeconds = 0L
        var nanos = 0
        try {
            decoder.decodeStructure(descriptor) {
                while (true) {
                    val idx = try {
                        decodeElementIndex(descriptor)
                    } catch (_: Throwable) {
                        CompositeDecoder.DECODE_DONE
                    }
                    if (idx == CompositeDecoder.DECODE_DONE) break
                    try {
                        when (idx) {
                            0 -> epochSeconds = decodeLongElement(descriptor, 0)
                            1 -> nanos = decodeIntElement(descriptor, 1)
                            else -> { /* unknown element — skip */ }
                        }
                    } catch (_: Throwable) {
                        return@decodeStructure
                    }
                }
            }
        } catch (_: Throwable) {
            // Top-level decode failed — last-resort sentinel.
        }
        return Instant.fromEpochSeconds(epochSeconds, nanos)
    }

    override fun serialize(encoder: Encoder, value: Instant) {
        encoder.encodeStructure(descriptor) {
            encodeLongElement(descriptor, 0, value.epochSeconds)
            encodeIntElement(descriptor, 1, value.nanosecondsOfSecond)
        }
    }
}
