package app.ikev2manager.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "servers", indices = [Index(value = ["hostname"], unique = true)])
data class ServerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val hostname: String,
    val name: String,
    val label: String? = null,
    val tags: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "scan_results",
    foreignKeys = [ForeignKey(entity = ServerEntity::class, parentColumns = ["id"], childColumns = ["serverId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("serverId")],
)
data class Stage1Entity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serverId: Long,
    val timestamp: Long,
    val dnsOk: Boolean,
    val dnsMs: Long,
    val ipv4: String,
    val ipv6: String,
    val pingSupported: Boolean,
    val pingSent: Int,
    val pingAvg: Double?,
    val pingMin: Double?,
    val pingMax: Double?,
    val lossPct: Double?,
    val udp500: String?,
    val udp4500: String?,
    val tcp443: Boolean?,
    val error: String?,
)

@Entity(
    tableName = "vpn_tests",
    foreignKeys = [ForeignKey(entity = ServerEntity::class, parentColumns = ["id"], childColumns = ["serverId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("serverId")],
)
data class VpnTestEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serverId: Long,
    val timestamp: Long,
    val success: Boolean,
    val failure: String?,
    val detail: String?,
    val connectMs: Long?,
    val tunnelAvg: Double?,
    val tunnelMin: Double?,
    val tunnelMax: Double?,
    val lossPct: Double?,
    val stability: Double?,
)

@Entity(tableName = "settings")
data class SettingEntity(@PrimaryKey val skey: String, val svalue: String)

@Dao
interface ServerDao {
    @Query("SELECT * FROM servers ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<ServerEntity>>

    @Query("SELECT * FROM servers WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<ServerEntity>

    @Query("SELECT hostname FROM servers")
    suspend fun hostnames(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<ServerEntity>): List<Long>

    @Update
    suspend fun update(item: ServerEntity)

    @Query("DELETE FROM servers WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)
}

@Dao
interface ScanDao {
    @Insert
    suspend fun insert(r: Stage1Entity)

    @Query("SELECT * FROM scan_results WHERE id IN (SELECT MAX(id) FROM scan_results GROUP BY serverId)")
    fun observeLatest(): Flow<List<Stage1Entity>>

    @Query("SELECT * FROM scan_results WHERE serverId = :id ORDER BY timestamp DESC LIMIT 30")
    fun history(id: Long): Flow<List<Stage1Entity>>

    @Query("DELETE FROM scan_results WHERE timestamp < :before")
    suspend fun prune(before: Long)
}

@Dao
interface VpnTestDao {
    @Insert
    suspend fun insert(r: VpnTestEntity)

    @Query("SELECT * FROM vpn_tests WHERE id IN (SELECT MAX(id) FROM vpn_tests GROUP BY serverId)")
    fun observeLatest(): Flow<List<VpnTestEntity>>

    @Query("SELECT * FROM vpn_tests WHERE serverId = :id ORDER BY timestamp DESC LIMIT 30")
    fun history(id: Long): Flow<List<VpnTestEntity>>

    @Query("DELETE FROM vpn_tests WHERE timestamp < :before")
    suspend fun prune(before: Long)
}

@Dao
interface SettingsDao {
    @Query("SELECT * FROM settings")
    fun observeAll(): Flow<List<SettingEntity>>

    @Query("SELECT * FROM settings")
    suspend fun getAll(): List<SettingEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAll(items: List<SettingEntity>)
}

@Database(
    entities = [ServerEntity::class, Stage1Entity::class, VpnTestEntity::class, SettingEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun servers(): ServerDao
    abstract fun scans(): ScanDao
    abstract fun vpnTests(): VpnTestDao
    abstract fun settings(): SettingsDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "ikev2manager.db").build()
    }
}
