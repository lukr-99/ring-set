# Architecture

Ring Set is a small, layered **MVVM** Android app. Everything runs on-device; there is no backend.

```
┌─────────────┐   BLE    ┌──────────────┐        ┌──────────────┐      ┌───────────────┐
│  the ring   │◀────────▶│    ble/      │──────▶ │    data/     │────▶ │   ui/ (MVVM)  │
│ (Colmi R0x) │  GATT    │  RingBle     │ result │ Repository   │ Room │ ViewModel +   │
└─────────────┘          │  RingProtocol│        │ + Room       │ Flow │ Compose UI    │
                         └──────────────┘        └──────────────┘      └───────────────┘
                                                        ▲                      │
                                                 domain/ (pure analytics) ◀────┘
```

## Packages

```
com.krejci.qringset
├─ MainActivity.kt          Compose host, permissions (BLUETOOTH_CONNECT/SCAN, POST_NOTIFICATIONS),
│                           immersive full-screen, CSV share intent.
├─ Notifier.kt              HR-alert notification channel + poster.
│
├─ ble/
│  ├─ RingBle.kt            Stateful BLE manager: connect/reconnect, the multi-stage sync state
│  │                        machine (HR→steps→SpO₂→sleep→stress→HRV), live real-time HR, and the
│  │                        StateFlows the UI observes (conn, battery, interval, sync, liveHr…).
│  └─ RingProtocol.kt       Pure, stateless wire-format codec: 16-byte packet + checksum builder,
│                           little-endian readers, epoch/date helpers. No Android/BLE state.
│
├─ data/
│  ├─ Entities.kt           Room @Entity: samples, sleep, known_rings, workouts.
│  ├─ RingDao.kt, RingDb.kt Room DAO + database (migration v1→v2 adds workouts).
│  ├─ RingRepository.kt     Single API over the store: Room for the app, CSV files for export.
│  ├─ Models.kt             In-flight models (MetricSample, SleepSegment, SyncResult, Point).
│  ├─ MetricType.kt         The charted metrics (HR/SpO₂/HRV/Stress/Steps) + labels/units.
│  ├─ UserProfile.kt        Profile + SharedPreferences store (age, goals, bedtime/wake window).
│  └─ Workout.kt            Activity types + workout summary.
│
├─ domain/                  Pure analytics — no Android, easily unit-testable:
│  ├─ Stats.kt              StatsEngine: summaries, resting HR, time-in-range, daily rollups,
│  │                        sleep totals, and the 0–100 activity score.
│  ├─ Sleep.kt              SleepEngine: isolates the latest night, stage totals, efficiency, score.
│  ├─ Interpretation.kt     Turns summaries + profile into plain-language, ranged insights.
│  └─ Alerts.kt             AlertEngine: HR spike / prolonged-high detection outside activity.
│
└─ ui/
   ├─ App.kt                Top-level scaffold: the Screen enum + floating nav + tab routing.
   ├─ RingViewModel.kt      AndroidViewModel: exposes repo/BLE state, actions, profile, workouts.
   ├─ Theme.kt              Material 3 dark/light theme, per-metric colors, SleepColor.
   ├─ components/           Reusable component library (no screen-specific logic):
   │  ├─ ScreenHeader.kt    Title + subtitle + eye/info dialog, used by every screen.
   │  ├─ ArcGauge.kt        Tachometer-style progress gauge (score, steps, sleep).
   │  ├─ MetricChart.kt     Scrub-to-zoom Canvas line chart with a range brush.
   │  ├─ Hypnogram.kt       Interactive sleep-stage chart (+ MiniLine sparkline).
   │  ├─ SleepGoalDial.kt   Draggable 24-hour bedtime/wake ring.
   │  └─ Widgets.kt         Small shared pieces (SectionLabel, ChoiceChip).
   └─ screens/              One file per tab, each a stateless-ish @Composable(vm):
      OverviewScreen · StatsScreen · ActivityScreen · SleepScreen ·
      DataScreen · RingScreen · ControlScreen · ProfileScreen
```

## Data flow

1. **Read** — `RingBle` connects by MAC, walks the sync state machine, and returns a `SyncResult`.
2. **Persist** — `RingViewModel` hands it to `RingRepository`, which writes **Room** and refreshes
   the `ring_*.csv` exports. Room emits **Flows**.
3. **Observe** — screens `collectAsStateWithLifecycle` the repository/BLE flows via the ViewModel.
4. **Analyse** — screens call `domain/` engines (StatsEngine, SleepEngine, Interpretation,
   AlertEngine) on the collected data. These are pure functions, so new stats/insights are added
   here without touching the UI.

## Conventions

- **UI is declarative and mostly stateless**: each screen is `fun XScreen(vm: RingViewModel)`; state
  lives in the ViewModel/Room, transient view state in `remember`.
- **Reusable visuals go in `ui/components/`**; anything used by a single screen stays private in that
  screen file.
- **No Android types in `domain/`** — it takes primitives/models and returns models, so it can be
  reasoned about (and unit-tested) in isolation.
- **BLE protocol split**: `RingProtocol` is the pure codec; `RingBle` owns the stateful GATT
  connection and the sync/live-HR flows.

## Threading

All BLE responses and the sync accumulator are routed through the main `Handler` so parsing and
state updates happen on one thread (this fixed an early cross-thread race where fast HR samples were
dropped). Room and CSV writes run in `viewModelScope` coroutines.
