package com.krejci.qringset.data

import android.content.Context
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Single API over the ring's stored data: Room for the app, CSV files for export. */
class RingRepository(private val context: Context) {
    private val dao = RingDb.get(context).dao()

    fun samples(m: MetricType): Flow<List<SampleEntity>> = dao.samples(m.key)
    fun count(m: MetricType): Flow<Int> = dao.count(m.key)
    fun sleep(): Flow<List<SleepEntity>> = dao.sleep()
    fun sleepCount(): Flow<Int> = dao.sleepCount()
    fun rings(): Flow<List<KnownRingEntity>> = dao.rings()
    fun workouts(): Flow<List<WorkoutEntity>> = dao.workouts()
    suspend fun workoutsNow(): List<WorkoutEntity> = dao.workoutsNow()

    suspend fun rememberRing(mac: String, name: String) =
        dao.upsertRing(KnownRingEntity(mac, name, System.currentTimeMillis() / 1000))

    suspend fun saveWorkout(w: WorkoutEntity) = dao.insertWorkout(w)

    suspend fun persist(result: SyncResult) {
        dao.insertSamples(result.samples.map { SampleEntity(it.metric.key, it.epoch, it.value) })
        dao.insertSleep(result.sleep.map { SleepEntity(it.epoch, it.stage, it.durationMin) })
    }

    /** Dump the DB to CSVs in the app's files dir (so pull-data.ps1 / Share keep working). */
    suspend fun exportCsvs(): List<File> {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        val out = mutableListOf<File>()
        for (m in MetricType.entries) {
            val rows = dao.samplesNow(m.key)
            val header = "timestamp,epoch_s,${valueHeader(m)}\n"
            val sb = StringBuilder(header)
            for (r in rows) sb.append(fmt.format(Date(r.epoch * 1000))).append(',')
                .append(r.epoch).append(',').append(r.value).append('\n')
            out += File(context.filesDir, "ring_${m.key}.csv").apply { writeText(sb.toString()) }
        }
        val sleep = dao.sleepNow()
        val sb = StringBuilder("timestamp,epoch_s,stage,stage_label,duration_min\n")
        for (r in sleep) sb.append(fmt.format(Date(r.epoch * 1000))).append(',').append(r.epoch)
            .append(',').append(r.stage).append(',').append(sleepLabel(r.stage)).append(',').append(r.durationMin).append('\n')
        out += File(context.filesDir, "ring_sleep.csv").apply { writeText(sb.toString()) }
        return out
    }

    private fun valueHeader(m: MetricType) = when (m) {
        MetricType.HR -> "bpm"; MetricType.SPO2 -> "spo2"; MetricType.HRV -> "hrv_ms"
        MetricType.STRESS -> "stress"; MetricType.STEPS -> "steps"
    }

    companion object {
        fun sleepLabel(s: Int) = when (s) { 2 -> "light"; 3 -> "deep"; 4 -> "rem"; 5 -> "awake"; else -> "?" }
    }
}
