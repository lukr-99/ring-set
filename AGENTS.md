# AGENTS.md — how to pull the ring's data off the phone

Instructions for an automated agent (or a human) to export the data that the
**Ring Set** app has synced from the ring, and copy it to the computer.

## What/where
- The app reads the ring's stored logs over BLE and writes/merges them into CSVs in its
  private files dir on the phone: `/data/data/com.krejci.qringset/files/`.
  - `ring_hr.csv` — `timestamp,epoch_s,bpm`
  - `ring_steps.csv` — `timestamp,epoch_s,steps,calories,distance_m` (hourly)
  - `ring_spo2.csv` — `timestamp,epoch_s,spo2` (hourly %)
  - `ring_sleep.csv` — `timestamp,epoch_s,stage,stage_label,duration_min`
  - `ring_stress.csv` — `timestamp,epoch_s,stress` (30-min)
  - `ring_hrv.csv` — `timestamp,epoch_s,hrv_ms`
- `timestamp` is local ISO-8601, `epoch_s` is Unix seconds.
- Each sync **merges** into the existing CSVs keyed by timestamp, so history accumulates
  across syncs even beyond the ring's small rolling buffer.

## Prerequisites
1. Phone connected over USB with **USB debugging** authorized (`adb devices` shows `device`).
2. The app installed as a **debug** build (it is, from this repo) — this is what lets
   `adb run-as` read the app's private files without root.
3. In the app, tap **"Sync heart rate"** first so the CSV is up to date.

## Pull it (recommended)
```powershell
.\pull-data.ps1                          # -> %USERPROFILE%\Desktop\ring-data\ring_hr.csv
.\pull-data.ps1 -Dest D:\health\ring     # custom destination folder
```

## Manual equivalent
```bash
adb exec-out run-as com.krejci.qringset ls files
adb exec-out run-as com.krejci.qringset cat files/ring_hr.csv > ring_hr.csv
```
(`run-as` reads relative to the app's data dir, so the path is `files/ring_hr.csv`.)

## Alternative: in-app Share
Tap **Share** in the app to send `ring_hr.csv` via the Android share sheet (Drive,
email, Files, etc.) — useful when the phone isn't tethered to this PC.

## Notes / gotchas
- `run-as` only works because this is a **debug** build. A release-signed build would
  need the in-app Share or a `MediaStore`/public-Downloads export instead.
- All six metrics (HR, steps, SpO2, sleep, stress, HRV) sync in one "Sync data" pass.
  `pull-data.ps1` copies every `*.csv` in the app's files dir automatically.
