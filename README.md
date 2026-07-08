# Ring Set

A tiny native **Android** app that sets the **heart-rate logging interval** on a
Colmi R0x-family smart ring (the ones sold with the **QRing** app) directly over
Bluetooth LE — with one-tap presets and a custom setter.

The official QRing app only lets you log heart rate every **30 or 60 minutes**.
This app writes the ring's raw BLE command, so you can pick **any interval from
1 to 255 minutes** (e.g. every 1 or 5 minutes for much denser HRV/HR data).

<p align="center">
  <img src="docs/screenshot.png" width="320" alt="Ring Set app screenshot">
</p>

## Features

- Quick presets: **1 · 3 · 5 · 10 · 30 · 60 min**
- **Custom** interval setter (1–255 minutes)
- **Check current interval on ring** — reads the value straight from the ring
- **Reconnect** (manual button + "reconnect after setting" toggle) — drops and
  re-establishes the BLE link and re-applies the interval, which makes the ring
  commit the change (otherwise it can revert to its previous value)
- **Sync heart-rate log** — pulls the ring's stored HR log over BLE and **merges** it
  into a CSV on the phone (history accumulates across syncs); **Share**/export it, or
  copy it to a PC with [`pull-data.ps1`](pull-data.ps1) (see [AGENTS.md](AGENTS.md))
- Connects directly by MAC, no account/cloud, works offline
- Material 3 dark UI, adaptive launcher icon

> **Note:** blood-oxygen (SpO2), stress and HRV are on/off toggles on this hardware
> with no separate interval — they sample alongside the HR cycle, so lowering the
> HR interval effectively increases how often they're taken. Sleep is auto-detected.

## Build & install

The app is tied to a specific ring's BLE address, so there's no generic prebuilt APK —
you build it with **your** ring's MAC (see [Configure your ring](#configure-your-ring)).

Requires a JDK 17+ and the Android SDK (`ANDROID_HOME` set, or a `local.properties`
with `sdk.dir=...`). The Gradle wrapper fetches Gradle itself:

```bash
RING_MAC=AA:BB:CC:DD:EE:FF ./gradlew assembleDebug   # gradlew.bat on Windows
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Copy that APK to your phone and open it (enable *Install unknown apps* if prompted),
or with a phone connected over USB (Developer options → USB debugging):

```bash
RING_MAC=AA:BB:CC:DD:EE:FF ./gradlew installDebug
```

On Windows there's also a convenience script: `./build-and-install.ps1` (reads the MAC
from `local.properties`).

## Configure your ring

The ring's BLE address is **not** stored in this repo — you supply your own at build
time (find it in the QRing app, or scan with a BLE tool like nRF Connect). Provide it
either way:

- **Environment variable:**

  ```bash
  RING_MAC=AA:BB:CC:DD:EE:FF ./gradlew assembleDebug
  ```

- **or `local.properties`** (git-ignored) in the project root:

  ```properties
  ring.mac=AA:BB:CC:DD:EE:FF
  ```

It's injected into `BuildConfig.RING_MAC` and read by the app at runtime. Without it,
the build falls back to a placeholder (`00:00:00:00:00:00`) that connects to nothing.

## Usage

1. **Wake the ring** — take it off the charger / put it on / move it, so it advertises.
2. **Close the QRing app** — BLE allows only one connection at a time, so if the
   official app is connected this one can't reach the ring (and vice-versa).
3. Open **Ring Set**, tap a preset (or type a value and hit **Set**).
4. First launch asks for the *Nearby devices* (Bluetooth) permission — allow it.
5. Tap **Check current interval on ring** any time to confirm what's actually set.

If you later open QRing and it re-syncs its own interval to the ring, just tap the
preset again.

## Your data

Tap **Sync heart rate** to read the ring's stored HR log and merge it into
`ring_hr.csv` (schema: `timestamp,epoch_s,bpm`). The ring only keeps a small rolling
buffer, so sync regularly — the merge keeps everything you've already pulled.

Get it off the phone either way:
- **Share** button → Android share sheet (Drive, email, Files…), no PC needed.
- **`pull-data.ps1`** → copies every CSV to a folder on this PC (default
  `Desktop\ring-data`); it uses `adb run-as`, which works because this is a debug
  build. See [AGENTS.md](AGENTS.md) for the manual `adb` commands too.

> SpO2, steps, and sleep syncing are on the roadmap and will appear as extra CSVs
> that `pull-data.ps1` picks up automatically.

## How it works

The ring exposes a Nordic-UART-style GATT service. Commands are 16-byte packets
`[cmd, …subdata…, checksum]` where `checksum = sum(bytes[0:15]) % 255`.

| | value |
|---|---|
| Service | `6E40FFF0-B5A3-F393-E0A9-E50E24DCCA9E` |
| Write (RX) | `6E400002-B5A3-F393-E0A9-E50E24DCCA9E` |
| Notify (TX) | `6E400003-B5A3-F393-E0A9-E50E24DCCA9E` |
| Set interval | `0x16 0x02 0x01 <minutes>` |
| Read interval | `0x16 0x01` → notify `[0x16, .., enabled, minutes, …]` |

The app connects by MAC (`getRemoteDevice(...).connectGatt`), enables notifications
on the TX characteristic, and writes to RX. Only `BLUETOOTH_CONNECT` (Android 12+)
is required — no location, no scanning.

## Tech

Kotlin · Android Views + Material 3 · view binding · no third-party BLE library.
Gradle 8.9 · AGP 8.5.2 · compileSdk 35 · minSdk 26 (Android 8.0+).

## Credits

Protocol reverse-engineering by
[tahnok/colmi_r02_client](https://github.com/tahnok/colmi_r02_client) and
[Gadgetbridge](https://codeberg.org/Freeyourgadget/Gadgetbridge). This app is an
independent hobby project and is not affiliated with Colmi or QRing.

## License

[MIT](LICENSE)
