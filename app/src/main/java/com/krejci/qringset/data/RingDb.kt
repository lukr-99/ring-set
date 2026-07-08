package com.krejci.qringset.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [SampleEntity::class, SleepEntity::class, KnownRingEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class RingDb : RoomDatabase() {
    abstract fun dao(): RingDao

    companion object {
        @Volatile private var instance: RingDb? = null
        fun get(context: Context): RingDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, RingDb::class.java, "ring.db")
                .build().also { instance = it }
        }
    }
}
