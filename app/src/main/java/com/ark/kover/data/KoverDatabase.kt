package com.ark.kover.data

import androidx.room.ColumnInfo
import androidx.room.Database
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import com.ark.kover.core.model.Metadata

@Entity(
    tableName = "rom_hashes",
    indices = [Index("relative_path", unique = true)],
)
data class RomHashEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "relative_path") val relativePath: String,
    @ColumnInfo(name = "system") val system: String,
    @ColumnInfo(name = "md5") val md5: String,
    @ColumnInfo(name = "size") val size: Long,
)

@Dao
interface RomHashDao {
    @Query("SELECT * FROM rom_hashes WHERE relative_path = :path")
    suspend fun getByPath(path: String): RomHashEntity?

    @Query("SELECT * FROM rom_hashes WHERE system = :system")
    suspend fun getBySystem(system: String): List<RomHashEntity>

    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: RomHashEntity)

    @Query("DELETE FROM rom_hashes")
    suspend fun clear()
}

@Entity(
    tableName = "matches",
    indices = [Index("rom_id", unique = true)],
)
data class MatchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "rom_id") val romId: String,
    @ColumnInfo(name = "game_name") val gameName: String,
    @ColumnInfo(name = "source") val source: String,
    @ColumnInfo(name = "matched_by") val matchedBy: String,
    @ColumnInfo(name = "confidence") val confidence: Float,
    @ColumnInfo(name = "metadata_json") val metadataJson: String,
    @ColumnInfo(name = "assets_json") val assetsJson: String,
)

@Dao
interface MatchDao {
    @Query("SELECT * FROM matches WHERE rom_id = :romId")
    suspend fun getByRomId(romId: String): MatchEntity?

    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MatchEntity)

    @Query("DELETE FROM matches WHERE rom_id = :romId")
    suspend fun deleteByRomId(romId: String)

    @Query("DELETE FROM matches")
    suspend fun clear()
}

@Database(
    entities = [RomHashEntity::class, MatchEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class KoverDatabase : androidx.room.RoomDatabase() {
    abstract fun romHashDao(): RomHashDao
    abstract fun matchDao(): MatchDao
}
