# RFID Manager

Android app for writing UHF RFID tags using the Chainway C5 rugged device. Scans a QR/barcode and writes the hex data to an RFID tag in one step.

## Project structure

```
app/src/main/java/com/trackstudio/rfidmanager/
  MainActivity.kt          # Main entry: Host for fragments, hardware owner, shared state, trigger handling
  WriteFragment.kt         # "Scan & Write" screen: barcode input, history tags, writing power
  RadarFragment.kt         # "Radar" screen: target search with EMA trend graph and adaptive sound
  LogFragment.kt           # "Log" screen: Full-screen system activity history
  SettingsFragment.kt      # Configuration screen: UHF frequency region, reconnect
  RadarGraphView.kt        # Custom view: Scrolling real-time RSSI trend graph (fast line + EMA slow line)
  RadarMapView.kt          # Custom view: Directional radar map (unused / reserved for future use)
  SharedViewModel.kt       # Global state: Connection status observer
  ErrorCodeManager.kt      # Utility: UHF hardware error code mapping

app/src/main/res/
  layout/activity_main.xml          # Host layout with BottomNav and Global Status Bar
  layout/fragment_write.xml         # Scan & Write UI (instructions, input, history, power slider)
  layout/fragment_radar.xml         # Radar UI (targeting, EMA trend graph, dBm overlay)
  layout/fragment_log.xml           # Activity Log UI (full-screen scrollable area)
  layout/fragment_settings.xml      # Settings UI (UHF frequency, reconnect btn)
  layout/history_tag.xml            # Template for dynamic history chips
  layout-land/activity_main.xml     # Landscape layout (split-panel: input+settings / log)
  menu/bottom_nav_menu.xml          # Navigation menu (Scan & Write, Radar, Log, Settings)
  navigation/nav_graph.xml          # Jetpack Navigation definition
  drawable/                         # Vector icons, backgrounds
  values/colors.xml                 # Green Material 3 palette
  values/themes.xml                 # Theme.RFIDManager (Material3.Light.NoActionBar)
  values/strings.xml                # Externalized strings
  raw/                              # Audio: barcodebeep.ogg, serror.ogg
```

## Build & dependencies

- **Language:** Kotlin (migrated from Java)
- **Asynchronous Logic:** Kotlin Coroutines (`lifecycleScope`, `Dispatchers.IO`, `withContext`)
- **Architecture:** Single Activity + Fragments + Jetpack Navigation + Shared ViewModel
- **Build:** Gradle 8.13, AGP 8.10.1, Kotlin 2.1.0, Kotlin DSL, JVM Target 11
- **View Binding:** Enabled for all layouts
- **Hardware SDK:** Chainway DeviceAPI (AAR)

## Key hardware APIs (from DeviceAPI AAR)

- `RFIDWithUHFUART` — UHF reader/writer (init, readData, writeData, setPower, getPower, getErrCode)
- `BarcodeDecoder` / `BarcodeFactory` — barcode scanner (open, setDecodeCallback, close)
- `IUHF.Bank_EPC` — EPC memory bank constant
- `UhfBase.ErrorCode.*` — error code constants

## Interface requirements & logic

### 1. Navigation & Layout
- **Bottom Navigation**: Four tabs — "Scan & Write", "Radar", "Log", and "Settings".
- **Global Status Bar**: Persistent header showing `CONNECTED` (Green) or `DISCONNECTED` (Red) status.

### 2. Scan & Write (Programming)
**Code format**: The code being written represents **hex data** that gets written to the RFID tag's EPC memory bank.

**Algorithm**:
1. **Input**: Barcode scan (auto) or manual text input — a hex string to write.
2. **EPC Size Detection**: Read the current tag's EPC bank to determine its size (6 or 8 words = 12 or 16 hex chars). Abort if tag unreadable.
3. **Length Check**: Verify the hex code length does not exceed the detected EPC bank size. No hex format validation is performed — non-hex characters are passed to hardware as-is.
4. **Zero-Padding**: Right-pad the code with zeros (`0`) to fill the entire EPC bank size (using `String.format` with space-to-zero replacement).
5. **Power Setup**: Temporarily set UHF power to the user-selected "Writing Power" level (5–30 dBm, default 10 dBm).
6. **Write**: Call `writeData(Bank_EPC, 0, paddedHexCode)` to write the hex data to the EPC memory bank.
7. **Verification**: Read back the EPC bank and perform a case-insensitive `startsWith` check against the original code. Treat as write error if verification fails.
8. **Restore Power**: Reset UHF power to its original level.
9. **History**: Record result in horizontal tag bar (last 20 entries). Green = success, Red = failure.

**Trigger behavior**:
- **Auto-scan**: Barcode scanner activates automatically when tab is active. A scanned barcode fills the input and immediately triggers steps 2–9.
- **Manual Write**: User types a hex code and taps **Write RFID** to trigger steps 2–9.

### 3. Radar (Target Search)
**Targeting**: User-defined EPC substring. Supports Paste.

**Algorithm**:
1. **Input**: User enters a target EPC substring to search for.
2. **Start Inventory**: Call `startInventoryTag()` and begin polling loop for maximum reliability.
3. **Adaptive Power Scanning**: Use a 3-step sliding window to rapidly cycle power levels (e.g., `26, 22, 18`):
   - When target is near: window slides down to lower powers (up to 5 dBm drop) to filter out background reflections.
   - When target is lost: window slides back up to 30 dBm to re-acquire.
4. **Tag Detection**: For each power level in the window, poll for tags and extract RSSI values. Handle both raw dBm (e.g. -65) and centi-dBm (e.g. -6523) formats automatically.
5. **Target Matching**: Find the tag whose EPC contains the user-defined substring. Track its RSSI.
6. **Smart Hardware Math**: Calculate "equivalent 30 dBm distance" by compensating +1.0 dBm for every 1 dBm drop in transmission power. This prevents visual graph jumps during power switches.
7. **Trend Calculation**: Compute fast current signal and slow Exponential Moving Average (EMA). Determine trend direction (Green = Getting Closer, Red = Moving Away).
8. **EMA Trend Graph**: Update full-screen visualizer with two lines and colored area between them.
9. **Directional Audio**: Play steady audio tick with pitch based on trend. "OK" beep in Green zone, "Error" beep in Red zone for blind navigation.
10. **Panic Recovery**: If tag disappears from current low-power window, pause graph decay and perform 540ms "rescue sweep" at `[30, 24, 19] dBm` to re-acquire target without destroying visual trend.
11. **Stop**: On user request, stop inventory and cleanup.

### 4. Activity Log
- **Dedicated View**: Full-screen log. Newest entries at the top. Max 30 entries.

### 5. Settings
- **UHF Region**: Select frequency standard (Europe 0x04, USA 0x08, etc.).
- **Hardware Reconnect**: Manual re-initialization of UHF and Barcode modules.

## Hardware Logic

- **Language**: Kotlin + Coroutines (`lifecycleScope`, `Dispatchers.IO`).
- **Inventory Mode**: Radar uses `startInventoryTag()` in a polling loop for maximum reliability. All hardware init (`setPower`, `stopInventory`, `setFilter`, `setEPCMode`, `startInventoryTag`) runs on `Dispatchers.IO` to avoid blocking the UI thread.
- **Power Management**: Independent power levels for Scan/Write and Radar modes.
- **RSSI Extraction**: Handles raw dBm (e.g. -65) and centi-dBm (e.g. -6523) formats automatically.

## Concurrency model

The UHF hardware (`RFIDWithUHFUART`) communicates over a UART serial bus. All commands to it must be serialized — issuing concurrent calls from multiple threads corrupts the bus and silently breaks inventory. The following rules are enforced throughout the codebase:

### Thread ownership of UART

| Operation | Thread | Mechanism |
|---|---|---|
| `stopInventory`, `setPower`, `setFilter`, `setEPCMode`, `startInventoryTag` | IO | `lifecycleScope.launch(Dispatchers.IO)` |
| `readTagFromBuffer` (polling loop) | IO | same coroutine as startup |
| `setDynamicRadarPower` | IO | always via `launch(Dispatchers.IO)`, never called directly from Main |
| `stopRadar` (stop flag + hardware stop) | Main sets flag; IO stop via `launch(Dispatchers.IO)` | `isInventoryRunning.set(false)` then `launch(IO) { stopInventory() }` |
| UI updates, callbacks | Main | `withContext(Dispatchers.Main)` |

### `isInventoryRunning` — `AtomicBoolean`

Written on the IO thread (`set(true)` after `startInventoryTag()` succeeds), read on the Main thread (inside `setDynamicRadarPower`), written again on Main at stop. Declared as `AtomicBoolean` to guarantee cross-thread visibility without synchronized blocks.

### `currentScanPower` — `AtomicInteger`

The current active power level is written by `cycleTicker` on the **Main thread** (Handler) and read by the IO polling loop as a snapshot at the moment a tag is detected. Using `AtomicInteger` ensures the IO thread always sees the latest value without locking.

Power snapshot pattern in the polling loop:
```kotlin
val powerSnapshot = currentScanPower.get()   // read on IO thread
val info = reader.readTagFromBuffer()
// ...
onValueReceived(rssiRaw, displayRssi, epc, powerSnapshot)  // delivered to Main with correct power
```

This eliminates the race where `cycleTicker` changes power between detection and callback delivery, which would cause tags to be recorded with the wrong power key in `rssiMap`.

### Radar startup sequence

The cycle ticker must not start until `startInventoryTag()` has actually succeeded on the IO thread. Starting it earlier causes `setDynamicRadarPower` calls to race against hardware initialization. The correct sequence:

```
[Main thread]                         [IO thread]
startRadar() called
  setDynamicRadarPower(30)            →  launch(IO): setPower(30)
  handler.post(graphTicker)
  handler.post(soundTicker)
  handler.post(cleanupRunnable)
  launch(IO): startRadar(...)         →  setPower(30)
                                         stopInventory()
                                         Thread.sleep(100)
                                         setFilter(...)
                                         setEPCMode()
                                         startInventoryTag() ← succeeds
                                         isInventoryRunning.set(true)
                                      ↓  withContext(Main):
  onInventoryReady() called  ←           appendLog(...)
  handler.postDelayed(cycleTicker, 180ms) ← cycle ticker starts HERE
                                         while (isInventoryRunning.get()) {
                                           powerSnapshot = currentScanPower.get()
                                           readTagFromBuffer()
                                           ...
                                         }
```

`cycleTicker` is posted only from `onInventoryReady()`, which is called on the Main thread after the IO coroutine confirms inventory is running. This guarantees the first `setDynamicRadarPower` from the ticker finds `isInventoryRunning == true` and correctly restarts inventory at the new power level.

### `cycleTicker` phase structure

Each tick sets the power for the **current** phase index, then increments `cycleIndex`. This ensures every phase gets a full `phaseDurationMs` (180 ms) window, including phase 0:

```
tick 0: set power = allPowers[windowIndex + 0], cycleIndex → 1, wait 180ms
tick 1: set power = allPowers[windowIndex + 1], cycleIndex → 2, wait 180ms
tick 2: set power = allPowers[windowIndex + 2], cycleIndex → 3, wait 180ms
tick 3: cycleIndex >= 3 → evaluate cycle, reset cycleIndex = 0
        set power = allPowers[windowIndex + 0], cycleIndex → 1, wait 180ms
        ...
```

Previously `cycleIndex++` was at the top of the runnable, which skipped phase 0 on the first tick and produced a malformed initial evaluation cycle.
