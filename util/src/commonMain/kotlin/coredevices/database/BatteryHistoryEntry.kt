package coredevices.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "battery_history",
    indices = [Index(value = ["serial", "recordedAt"])],
)
data class BatteryHistoryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serial: String,
    val batteryLevel: Int,
    val recordedAt: Long,
)

@Dao
interface BatteryHistoryDao {
    @Insert
    suspend fun insert(entry: BatteryHistoryEntry): Long

    @Query("SELECT * FROM battery_history WHERE serial = :serial ORDER BY recordedAt ASC")
    fun observeForSerial(serial: String): Flow<List<BatteryHistoryEntry>>

    @Query("SELECT * FROM battery_history WHERE serial = :serial ORDER BY recordedAt DESC LIMIT 1")
    suspend fun getLatestForSerial(serial: String): BatteryHistoryEntry?

    @Query("DELETE FROM battery_history WHERE recordedAt < :cutoff")
    suspend fun deleteBefore(cutoff: Long)
}
