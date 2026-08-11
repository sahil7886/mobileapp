package io.rebble.libpebblecommon.datalogging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SleepCaptureProtocolTest {
    @Test
    fun `decodes raw PPI and preserves sequence idempotency`() {
        val record = record(
            sessionId = 1_700_000_000L,
            sequence = 42,
            timestamp = 1_700_000_030L,
            value = 812,
            quality = 5,
            type = SleepCaptureProtocol.TYPE_PPI,
        )

        val decoded = requireNotNull(SleepCaptureProtocol.decode(record, 1_700_000_100L))
        assertEquals(1_700_000_000L, decoded.sessionId)
        assertEquals(42L, decoded.sequence)
        assertEquals(812, decoded.value)
        assertEquals(5, decoded.quality)
        assertEquals(SleepCaptureProtocol.TYPE_PPI, decoded.sampleType)
        assertTrue(decoded.recordId.endsWith(":1700000000:42"))
    }

    @Test
    fun `accepts terminal drop diagnostic but rejects malformed records`() {
        val complete = record(
            sessionId = 1_700_000_000L,
            sequence = 99,
            timestamp = 1_700_028_800L,
            value = 3,
            quality = -128,
            type = SleepCaptureProtocol.TYPE_SESSION,
            flags = SleepCaptureProtocol.FLAG_COMPLETE or SleepCaptureProtocol.FLAG_DROPPED,
        )
        val decoded = requireNotNull(SleepCaptureProtocol.decode(complete, 1))
        assertEquals(3, decoded.value)
        assertTrue(decoded.flags and SleepCaptureProtocol.FLAG_COMPLETE != 0)
        assertTrue(decoded.flags and SleepCaptureProtocol.FLAG_DROPPED != 0)

        val invalidPpi = record(
            sessionId = 1,
            sequence = 1,
            timestamp = 1,
            value = 0,
            quality = 4,
            type = SleepCaptureProtocol.TYPE_PPI,
        )
        assertNull(SleepCaptureProtocol.decode(invalidPpi, 1))
        assertNull(SleepCaptureProtocol.decode(complete.copyOf(13), 1))
    }

    private fun record(
        sessionId: Long,
        sequence: Long,
        timestamp: Long,
        value: Int,
        quality: Int,
        type: Int,
        flags: Int = 0,
    ): ByteArray = ByteArray(SleepCaptureProtocol.RECORD_SIZE_BYTES).also { bytes ->
        putUInt32(bytes, 0, sessionId)
        bytes[4] = sequence.toByte()
        bytes[5] = (sequence ushr 8).toByte()
        putUInt32(bytes, 6, timestamp)
        bytes[10] = value.toByte()
        bytes[11] = (value ushr 8).toByte()
        bytes[12] = quality.toByte()
        bytes[13] = ((1 shl 6) or flags or type).toByte()
    }

    private fun putUInt32(bytes: ByteArray, offset: Int, value: Long) {
        repeat(4) { index -> bytes[offset + index] = (value ushr (index * 8)).toByte() }
    }
}
