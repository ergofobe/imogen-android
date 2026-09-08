package com.imogen.android.backup

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/**
 * What has already gone where.
 *
 * Two columns make the key, and that is the whole design: choosing three accounts means
 * three copies, so "already uploaded" is a question about a pair, never about a
 * photograph on its own.
 *
 * The server would deduplicate a re-upload by checksum anyway. This exists so the phone
 * does not read, hash and post four thousand files every time the worker wakes up in
 * order to be told it need not have bothered.
 */
@Entity(tableName = "uploads", primaryKeys = ["accountId", "deviceAssetId"])
data class UploadRecord(
    val accountId: String,
    val deviceAssetId: String,
    val assetId: String?,
    val uploadedAt: Long,
    /**
     * Set when the upload failed. A row with attempts and no assetId is a thing to try
     * again later; without it a permanently broken file is retried on every single pass.
     */
    val attempts: Int = 0,
    val lastError: String? = null,
    /**
     * What the file was called when it was tried. Kept here rather than looked up later
     * because the point of the row is to describe an attempt, and a file that has since
     * been deleted off the phone still deserves to be nameable in a list of failures.
     */
    val displayName: String? = null,
)

@Dao
interface UploadDao {

    @Query("select deviceAssetId from uploads where accountId = :accountId and assetId is not null")
    suspend fun doneFor(accountId: String): List<String>

    @Query("select * from uploads where accountId = :accountId and assetId is null")
    suspend fun failuresFor(accountId: String): List<UploadRecord>

    @Query("select count(*) from uploads where accountId = :accountId and assetId is not null")
    fun countFor(accountId: String): Flow<Int>

    /** Everything outstanding, across every account, newest first. */
    @Query("select * from uploads where assetId is null order by uploadedAt desc")
    fun failures(): Flow<List<UploadRecord>>

    /**
     * Puts one file back in the running. `BackupWorker` decides what to skip from
     * `attempts`, so zeroing it is what actually undoes the giving-up.
     */
    @Query(
        "update uploads set attempts = 0, lastError = null " +
            "where accountId = :accountId and deviceAssetId = :deviceAssetId and assetId is null",
    )
    suspend fun clearAttempts(accountId: String, deviceAssetId: String)

    @Query("update uploads set attempts = 0, lastError = null where assetId is null")
    suspend fun clearAllAttempts()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(record: UploadRecord)

    @Query("delete from uploads where accountId = :accountId")
    suspend fun clearFor(accountId: String)
}

/**
 * Additive, and deliberately not destructive. Losing this table costs nothing visible and
 * a great deal of everything else: the phone would re-read, re-hash and re-post the whole
 * camera roll to be told it need not have bothered.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE uploads ADD COLUMN displayName TEXT")
    }
}

@Database(entities = [UploadRecord::class], version = 2, exportSchema = true)
abstract class BackupLedger : RoomDatabase() {
    abstract fun uploads(): UploadDao

    companion object {
        @Volatile
        private var instance: BackupLedger? = null

        fun get(context: Context): BackupLedger = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                BackupLedger::class.java,
                "backup-ledger",
            ).addMigrations(MIGRATION_1_2).build().also { instance = it }
        }
    }
}

/** How many times a file is retried before it is left alone. */
const val MAX_UPLOAD_ATTEMPTS = 3
