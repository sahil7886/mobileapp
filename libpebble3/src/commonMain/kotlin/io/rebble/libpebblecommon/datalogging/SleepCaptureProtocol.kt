package io.rebble.libpebblecommon.datalogging

import io.rebble.libpebblecommon.database.entity.SleepCaptureSampleEntity

/** UUID_SYSTEM / DlsSystemTagSleepCapture wire contract shared with PebbleOS. */
object SleepCaptureProtocol {
    const val DATA_LOGGING_TAG: UInt = 90u
    const val RECORD_SIZE_BYTES = 14

    const val TYPE_PPI = 1
    const val TYPE_BPM = 2
    const val TYPE_MOTION = 3
    const val TYPE_SESSION = 4

    const val FLAG_COMPLETE = 1 shl 3
    const val FLAG_DROPPED = 1 shl 4

    private const val TYPE_MASK = 0x07
    private const val FLAGS_MASK = FLAG_COMPLETE or FLAG_DROPPED
    private const val VERSION_SHIFT = 6
    private const val RECORD_VERSION = 1
    private const val RECORD_ID_PREFIX = "sleep-capture-v1"

    /**
     * Bytes: session ID (0..3), sequence (4..5), UTC timestamp (6..9), value (10..11),
     * signed quality (12), version/type/flags (13). Timestamp precision is deliberately seconds:
     * the Pebble HRM API does not expose a millisecond timestamp for each accepted PPI event.
     */
    fun decode(record: ByteArray, receivedAtEpochSeconds: Long): SleepCaptureSampleEntity? {
        if (record.size != RECORD_SIZE_BYTES) return null
        val typeFlags = record[13].unsigned()
        if ((typeFlags ushr VERSION_SHIFT) != RECORD_VERSION) return null

        val sessionId = record.readUInt32LE(0)
        val sequence = record.readUInt16LE(4).toLong()
        val timestamp = record.readUInt32LE(6)
        val value = record.readUInt16LE(10)
        val quality = record[12].toInt()
        val sampleType = typeFlags and TYPE_MASK
        val flags = typeFlags and FLAGS_MASK
        if (sessionId == 0L || timestamp == 0L || sampleType !in TYPE_PPI..TYPE_SESSION) return null
        if (typeFlags and 0x20 != 0) return null

        val complete = flags and FLAG_COMPLETE != 0
        if (sampleType == TYPE_SESSION && !complete && value != 0) return null
        if (sampleType != TYPE_SESSION && complete) return null
        if (sampleType != TYPE_SESSION && flags and FLAG_DROPPED != 0) return null
        if (sampleType == TYPE_SESSION && flags and FLAG_DROPPED != 0 && !complete) return null
        if (sampleType == TYPE_PPI && value == 0) return null

        return SleepCaptureSampleEntity(
            recordId = "$RECORD_ID_PREFIX:$sessionId:$sequence",
            sessionId = sessionId,
            sequence = sequence,
            timestampEpochSeconds = timestamp,
            value = value,
            quality = quality,
            sampleType = sampleType,
            flags = flags,
            receivedAtEpochSeconds = receivedAtEpochSeconds,
        )
    }

    private fun ByteArray.readUInt16LE(offset: Int): Int =
        this[offset].unsigned() or (this[offset + 1].unsigned() shl 8)

    private fun ByteArray.readUInt32LE(offset: Int): Long =
        (this[offset].unsigned().toLong()) or
            (this[offset + 1].unsigned().toLong() shl 8) or
            (this[offset + 2].unsigned().toLong() shl 16) or
            (this[offset + 3].unsigned().toLong() shl 24)

    private fun Byte.unsigned(): Int = toInt() and 0xff
}
