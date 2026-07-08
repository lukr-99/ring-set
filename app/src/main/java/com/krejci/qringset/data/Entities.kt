package com.krejci.qringset.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "samples", primaryKeys = ["metric", "epoch"])
data class SampleEntity(val metric: String, val epoch: Long, val value: Int)

@Entity(tableName = "sleep", primaryKeys = ["epoch"])
data class SleepEntity(val epoch: Long, val stage: Int, val durationMin: Int)

@Entity(tableName = "known_rings", primaryKeys = ["mac"])
data class KnownRingEntity(val mac: String, val name: String, val lastSeen: Long)

@Entity(tableName = "workouts")
data class WorkoutEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val startEpoch: Long,
    val endEpoch: Long,
    val avgHr: Int,
    val maxHr: Int,
    val minHr: Int,
    val samples: Int,
)
