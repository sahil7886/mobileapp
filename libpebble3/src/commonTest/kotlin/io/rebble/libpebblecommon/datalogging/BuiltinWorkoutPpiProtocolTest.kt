package io.rebble.libpebblecommon.datalogging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BuiltinWorkoutPpiProtocolTest {
    @Test
    fun decodesMultipleIntervalsInTheSameSecondAndTerminalMarker() {
        val first = byteArrayOf(
            0x00, 0xf1.toByte(), 0x53, 0x65, // workout ID 1_700_000_000
            0x2a, 0x00, 0x00, 0x00, // sequence 42
            0x01, 0xf1.toByte(), 0x53, 0x65, // timestamp 1_700_000_001
            0x78, 0x03, // 888 ms
            3, // Good quality
            0x11, // version 1 / active
        )
        val terminal = first.copyOf().also {
            it[4] = 43
            it[8] = 0x02
            it[12] = 0
            it[13] = 0
            it[14] = -128
            it[15] = 0x12
        }

        val decodedFirst = requireNotNull(BuiltinWorkoutPpiProtocol.decode(first, 1_700_000_100))
        val decodedTerminal = requireNotNull(BuiltinWorkoutPpiProtocol.decode(terminal, 1_700_000_100))

        assertEquals("builtin-workout-ppi-v1:1700000000:42", decodedFirst.recordId)
        assertEquals(888, decodedFirst.intervalMs)
        assertEquals(3, decodedFirst.quality)
        assertEquals(1_700_000_002, decodedTerminal.timestampEpochSeconds)
        assertEquals(0, decodedTerminal.intervalMs)
        assertTrue(BuiltinWorkoutPpiProtocol.isBuiltinWorkoutPpiRecord(decodedFirst.recordId))
    }

    @Test
    fun rejectsMalformedTerminalAndUnknownVersion() {
        val malformedTerminal = byteArrayOf(
            0x00, 0xf1.toByte(), 0x53, 0x65,
            0x2a, 0x00, 0x00, 0x00,
            0x01, 0xf1.toByte(), 0x53, 0x65,
            0x78, 0x03, 3, 0x12,
        )
        val unknownVersion = malformedTerminal.copyOf().also { it[15] = 0x21 }

        assertNull(BuiltinWorkoutPpiProtocol.decode(malformedTerminal, 1_700_000_100))
        assertNull(BuiltinWorkoutPpiProtocol.decode(unknownVersion, 1_700_000_100))
    }
}
