package io.rebble.libpebblecommon.datalogging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BuiltinWorkoutHeartRateProtocolTest {
    @Test
    fun decodesWorkoutPointAndTerminalTimestamp() {
        val point = byteArrayOf(
            0x00, 0xf1.toByte(), 0x53, 0x65, // workout ID 1_700_000_000
            0x2a, 0x00, 0x00, 0x00, // sequence 42
            0x01, 0xf1.toByte(), 0x53, 0x65, // timestamp 1_700_000_001
            148.toByte(), 3, BuiltinWorkoutHeartRateProtocol.FLAG_WORKOUT_ACTIVE.toByte(), 1,
        )
        val terminal = point.copyOf().also {
            it[4] = 43
            it[8] = 0x02
            it[12] = 0
            it[14] = BuiltinWorkoutHeartRateProtocol.FLAG_WORKOUT_COMPLETE.toByte()
        }

        val decodedPoint = requireNotNull(BuiltinWorkoutHeartRateProtocol.decode(point, 1_700_000_100))
        val decodedTerminal = requireNotNull(BuiltinWorkoutHeartRateProtocol.decode(terminal, 1_700_000_100))

        assertEquals("builtin-workout-v1:1700000000:42", decodedPoint.recordId)
        assertEquals(148, decodedPoint.filteredBpm)
        assertEquals(0, decodedTerminal.filteredBpm)
        assertEquals(1_700_000_002, decodedTerminal.timestampEpochSeconds)
        assertTrue(BuiltinWorkoutHeartRateProtocol.isBuiltinWorkoutRecord(decodedPoint.recordId))
    }
}
