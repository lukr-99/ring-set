package com.krejci.qringset.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [SampleEntity::class, SleepEntity::class, KnownRingEntity::class, WorkoutEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class RingDb : RoomDatabase() {
    abstract fun dao(): RingDao

    companion object {
        /** v2 adds the workouts table (keeps existing synced data). */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `workouts` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`type` TEXT NOT NULL, `startEpoch` INTEGER NOT NULL, `endEpoch` INTEGER NOT NULL, " +
                        "`avgHr` INTEGER NOT NULL, `maxHr` INTEGER NOT NULL, `minHr` INTEGER NOT NULL, " +
                        "`samples` INTEGER NOT NULL)"
                )
            }
        }

        /** v3 stores each workout's per-second HR samples for the detail chart. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `workouts` ADD COLUMN `samplesCsv` TEXT NOT NULL DEFAULT ''")
            }
        }

        @Volatile private var instance: RingDb? = null
        fun get(context: Context): RingDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, RingDb::class.java, "ring.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build().also { instance = it }
        }
    }
}
