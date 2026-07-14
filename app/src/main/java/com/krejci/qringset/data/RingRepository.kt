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

    /** Newest stored epoch (seconds) for a metric, or null if none — used to detect stale HR. */
    suspend fun newestEpoch(m: MetricType): Long? = dao.newestEpoch(m.key)

    /** Store a single reading (phone-clock timestamped), e.g. a live HR top-up. */
    suspend fun insertSample(m: MetricType, epoch: Long, value: Int) =
        dao.insertSamples(listOf(SampleEntity(m.key, epoch, value)))

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
        out += exportJson(fmt, sleep)
        return out
    }

    /**
     * A single machine-readable snapshot of everything the app holds, for scripted/agent pulls
     * (pull-data.ps1). One self-describing file beats stitching six CSVs: it carries the export
     * time, the ring, per-metric units, and every sample with both epoch seconds and ISO time.
     */
    private suspend fun exportJson(fmt: SimpleDateFormat, sleep: List<SleepEntity>): File {
        val root = org.json.JSONObject()
        root.put("app", "ring-set")
        root.put("schema", 1)
        val now = System.currentTimeMillis() / 1000
        root.put("exported_epoch_s", now)
        root.put("exported_at", fmt.format(Date(now * 1000)))
        dao.ringsNow().firstOrNull()?.let { r ->
            root.put("ring", org.json.JSONObject().put("mac", r.mac).put("name", r.name))
        }
        val metrics = org.json.JSONObject()
        for (m in MetricType.entries) {
            val rows = dao.samplesNow(m.key)
            val arr = org.json.JSONArray()
            for (r in rows) arr.put(org.json.JSONObject().put("e", r.epoch).put("t", fmt.format(Date(r.epoch * 1000))).put("v", r.value))
            metrics.put(m.key, org.json.JSONObject().put("label", m.label).put("unit", m.unit).put("count", rows.size).put("samples", arr))
        }
        root.put("metrics", metrics)
        val sleepArr = org.json.JSONArray()
        for (r in sleep) sleepArr.put(
            org.json.JSONObject().put("e", r.epoch).put("t", fmt.format(Date(r.epoch * 1000)))
                .put("stage", r.stage).put("label", sleepLabel(r.stage)).put("duration_min", r.durationMin)
        )
        root.put("sleep", org.json.JSONObject().put("count", sleep.size).put("segments", sleepArr))
        return File(context.filesDir, "ring_export.json").apply { writeText(root.toString(2)) }
    }

    private fun valueHeader(m: MetricType) = when (m) {
        MetricType.HR -> "bpm"; MetricType.SPO2 -> "spo2"; MetricType.HRV -> "hrv_ms"
        MetricType.STRESS -> "stress"; MetricType.STEPS -> "steps"
    }

    companion object {
        fun sleepLabel(s: Int) = when (s) { 2 -> "light"; 3 -> "deep"; 4 -> "rem"; 5 -> "awake"; else -> "?" }
    }
}
