package com.wifigatekeeper.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** Lifecycle states of a known device. */
enum class DeviceStatus { PENDING, WHITELISTED, BLACKLISTED }

@Entity(tableName = "known_devices")
data class KnownDeviceEntity(
    @PrimaryKey val mac: String,
    val name: String?,
    val lastIp: String?,
    val firstSeen: Long,
    val lastSeen: Long,
    val status: String,
)

@Entity(tableName = "activity_log")
data class ActivityLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val type: String,
    val message: String,
)

@Dao
interface DeviceDao {
    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun upsert(device: KnownDeviceEntity)

    @Query("SELECT * FROM known_devices WHERE mac = :mac LIMIT 1")
    suspend fun byMac(mac: String): KnownDeviceEntity?

    @Query("SELECT * FROM known_devices WHERE status = 'PENDING' ORDER BY lastSeen DESC")
    fun pendingDevices(): Flow<List<KnownDeviceEntity>>

    @Query("SELECT * FROM known_devices WHERE status = 'WHITELISTED' ORDER BY lastSeen DESC")
    fun whitelistedDevices(): Flow<List<KnownDeviceEntity>>

    @Query("SELECT COUNT(*) FROM known_devices WHERE status = 'WHITELISTED'")
    fun whitelistedCount(): Flow<Int>

    @Query("UPDATE known_devices SET status = :status WHERE mac = :mac")
    suspend fun updateStatus(mac: String, status: String)

    @Query("UPDATE known_devices SET lastSeen = :seen, lastIp = :ip WHERE mac = :mac")
    suspend fun touch(mac: String, seen: Long, ip: String?)
}

@Dao
interface ActivityLogDao {
    @Insert
    suspend fun insert(entry: ActivityLogEntity)

    @Query("SELECT * FROM activity_log ORDER BY timestamp DESC LIMIT :limit")
    fun recent(limit: Int = 50): Flow<List<ActivityLogEntity>>

    @Query("DELETE FROM activity_log")
    suspend fun clear()
}

@Database(
    entities = [KnownDeviceEntity::class, ActivityLogEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun deviceDao(): DeviceDao
    abstract fun activityLogDao(): ActivityLogDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "wifigatekeeper.db",
            ).fallbackToDestructiveMigration().build().also { instance = it }
        }
    }
}
