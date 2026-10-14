package dev.autoflow.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "flows")
data class FlowEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** The whole flow graph as JSON (see FlowJson). */
    val json: String,
    val updatedAt: Long,
    val startAtBoot: Boolean = false,
)

@Entity(tableName = "logs", indices = [Index("flowId")])
data class LogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val flowId: String,
    val runId: String,
    val time: Long,
    val level: Int,
    val blockId: String?,
    val blockTitle: String?,
    val message: String,
    val durationMs: Long?,
)

@Dao
interface FlowDao {
    @Query("SELECT * FROM flows ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<FlowEntity>>

    @Query("SELECT * FROM flows WHERE id = :id")
    suspend fun get(id: String): FlowEntity?

    @Query("SELECT * FROM flows WHERE startAtBoot = 1")
    suspend fun bootFlows(): List<FlowEntity>

    @Upsert
    suspend fun upsert(flow: FlowEntity)

    @Query("UPDATE flows SET startAtBoot = :value WHERE id = :id")
    suspend fun setStartAtBoot(id: String, value: Boolean)

    @Query("DELETE FROM flows WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface LogDao {
    @Query("SELECT * FROM logs WHERE flowId = :flowId ORDER BY id DESC LIMIT :limit")
    fun observe(flowId: String, limit: Int): Flow<List<LogEntity>>

    @Insert
    suspend fun insertAll(rows: List<LogEntity>)

    @Query("DELETE FROM logs WHERE flowId = :flowId")
    suspend fun clear(flowId: String)

    /** Keeps only the newest [keep] rows of one flow. */
    @Query(
        "DELETE FROM logs WHERE flowId = :flowId AND id <= " +
            "(SELECT id FROM logs WHERE flowId = :flowId ORDER BY id DESC LIMIT 1 OFFSET :keep)"
    )
    suspend fun prune(flowId: String, keep: Int)

    @Query("SELECT DISTINCT flowId FROM logs")
    suspend fun flowIds(): List<String>

    @Query("DELETE FROM logs WHERE time < :before")
    suspend fun deleteOlderThan(before: Long)
}

@Database(entities = [FlowEntity::class, LogEntity::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun flows(): FlowDao
    abstract fun logs(): LogDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "autoflow.db").build()
    }
}
