package io.rebble.libpebblecommon.datalogging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HealthCaptureProtocolTest {
    @Test
    fun decodesVersionedLittleEndianWorkoutRecord() {
        val record = byteArrayOf(
            0x78, 0x56, 0x34, 0x12, // workout ID 0x12345678
            0x2a, 0x00, 0x00, 0x00, // sequence 42
            0x00, 0xf1.toByte(), 0x53, 0x65, // timestamp 1_700_000_000
            148.toByte(), // filtered BPM
            151.toByte(), // raw BPM
            (HealthCaptureProtocol.FLAG_WORKOUT_ACTIVE or
                HealthCaptureProtocol.FLAG_FILTERED_AVAILABLE or
                HealthCaptureProtocol.FLAG_RAW_AVAILABLE).toByte(),
            HealthCaptureProtocol.RECORD_VERSION.toByte(),
        )

        val decoded = requireNotNull(HealthCaptureProtocol.decode(record, receivedAtEpochSeconds = 1_700_000_100))

        assertEquals("health-capture-v1:305419896:42", decoded.recordId)
        assertEquals(1_700_000_000, decoded.timestampEpochSeconds)
        assertEquals(148, decoded.filteredBpm)
        assertEquals(151, decoded.rawBpm)
        assertEquals(1_700_000_100, decoded.receivedAtEpochSeconds)
    }

    @Test
    fun rejectsUnknownRecordVersionsWithoutSilentlyDroppingData() {
        val record = ByteArray(HealthCaptureProtocol.RECORD_SIZE_BYTES)
        record[0] = 1
        record[8] = 1
        record[15] = 2

        assertNull(HealthCaptureProtocol.decode(record, receivedAtEpochSeconds = 1))
    }
}
