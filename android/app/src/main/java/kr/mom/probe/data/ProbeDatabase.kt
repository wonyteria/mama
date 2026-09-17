package kr.mom.probe.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

// Only retention metadata and opaque deduplication IDs remain outside the encrypted payload.
@Entity(tableName = "probe_records")
internal data class StoredRecord(@PrimaryKey val id: String, val receivedAt: Long, val encryptedPayload: ByteArray)

@Entity(tableName = "probe_settings")
internal data class StoredSettings(@PrimaryKey val id: Int = 1, val encryptedPayload: ByteArray)

// A deleted callback revision stays suppressed until the original 14-day retention boundary.
@Entity(tableName = "probe_tombstones")
internal data class Tombstone(@PrimaryKey val id: String, val receivedAt: Long)

@Dao
internal interface ProbeDao {
    @Query("SELECT * FROM probe_records ORDER BY receivedAt DESC")
    fun observeRecords(): Flow<List<StoredRecord>>
    @Query("SELECT * FROM probe_records ORDER BY receivedAt DESC")
    suspend fun currentRecords(): List<StoredRecord>
    @Query("SELECT * FROM probe_settings WHERE id = 1")
    suspend fun settings(): StoredSettings?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSettings(settings: StoredSettings)
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(record: StoredRecord): Long
    @Query("SELECT COUNT(*) FROM probe_records WHERE id = :id")
    suspend fun recordExists(id: String): Int
    @Query("SELECT COUNT(*) FROM probe_tombstones WHERE id = :id")
    suspend fun isDeleted(id: String): Int
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTombstone(tombstone: Tombstone)
    @Query("INSERT OR IGNORE INTO probe_tombstones (id, receivedAt) SELECT id, receivedAt FROM probe_records WHERE id = :id")
    suspend fun suppress(id: String)
    @Query("DELETE FROM probe_records WHERE id = :id")
    suspend fun delete(id: String)
    @Query("DELETE FROM probe_records WHERE id IN (:ids)")
    suspend fun deleteIds(ids: List<String>)
    @Query("DELETE FROM probe_records WHERE receivedAt <= :cutoff")
    suspend fun deleteExpired(cutoff: Long)
    @Query("DELETE FROM probe_tombstones WHERE receivedAt <= :cutoff")
    suspend fun deleteExpiredTombstones(cutoff: Long)
    @Query("DELETE FROM probe_records")
    suspend fun deleteRecords()
    @Query("DELETE FROM probe_tombstones")
    suspend fun deleteTombstones()
    @Query("DELETE FROM probe_settings")
    suspend fun deleteSettings()
}

@Database(entities = [StoredRecord::class, StoredSettings::class, Tombstone::class], version = 1, exportSchema = false)
internal abstract class ProbeDatabase : RoomDatabase() {
    abstract fun dao(): ProbeDao
}
