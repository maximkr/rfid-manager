# RFID Manager

Android app for writing UHF RFID tags using the Chainway C5 rugged device. Scans a QR/barcode and writes the hex data to an RFID tag in one step.

## Project structure

```
app/src/main/java/com/trackstudio/rfidmanager/
  MainActivity.kt          # Main entry: Host for fragments, hardware owner, shared state, trigger handling
  WriteFragment.kt         # "Scan & Write" screen: barcode input, history tags, writing power
  RadarFragment.kt         # "Radar" screen: target search with EMA trend graph and adaptive sound
  ChainwayUhfAdapters.kt   # DeviceAPI adapters for UHF init and scanner cleanup
  EpcTargetNormalizer.kt   # EPC input normalization, hex validation, and zero-padding
  UhfConnectionController.kt # UHF startup cleanup/retry orchestration
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
- `RFIDWithUHFUART.startLocation` / `stopLocation` — SDK locate mode used by Radar
- `BarcodeDecoder` / `BarcodeFactory` — barcode scanner (open, setDecodeCallback, close)
- `ScannerUtility` — stops the vendor UHF scanner function before direct SDK initialization
- `IUHF.Bank_EPC` — EPC memory bank constant
- `IUHFLocationCallback` — location signal callback used by Radar
- `UhfBase.ErrorCode.*` — error code constants

## Interface requirements & logic

### 1. Navigation & Layout
- **Bottom Navigation**: Four tabs — "Scan & Write", "Radar", "Log", and "Settings".
- **Global Status Bar**: Persistent header showing `CONNECTED` (Green) or `DISCONNECTED` (Red) status.

### 2. Scan & Write (Programming)
**Code format**: The code being written represents **hex data** that gets written to the RFID tag's EPC memory bank.

**Algorithm**:
1. **Input**: Barcode scan (auto) or manual text input — a hex string to write.
2. **Normalization**: Trim whitespace, convert to uppercase, and reject empty or non-hex input. Valid characters are `0-9` and `A-F`.
3. **EPC Size Detection**: Read the current tag's EPC bank to determine whether the writable EPC payload is 8 or 6 words (32 or 24 hex chars). Abort if tag unreadable.
4. **Length Check**: Verify the normalized hex code length does not exceed the detected EPC bank size.
5. **Zero-Padding**: Right-pad the normalized code with zeros (`0`) to fill the entire detected EPC bank size.
6. **Power Setup**: Temporarily set UHF power to the user-selected "Writing Power" level (5–30 dBm, default 10 dBm).
7. **Write**: Call `writeData("00000000", Bank_EPC, 2, epcSize, paddedHexCode)` to write the hex data to EPC memory after the PC word.
8. **Verification**: Read back the EPC bank and perform a case-insensitive `startsWith` check against the normalized original code. Treat as write error if verification fails.
9. **Restore Power**: Reset UHF power to its original level.
10. **History**: Record result in horizontal tag bar (last 20 entries). Green = success, Red = failure.

**Trigger behavior**:
- **Auto-scan**: Barcode scanner activates automatically when tab is active. A scanned barcode fills the input and immediately triggers steps 2–9.
- **Manual Write**: User types a hex code and taps **Write RFID** to trigger steps 2–9.

### 3. Radar (Target Search)
**Targeting**: User-defined hex EPC target. Supports Paste.

**Algorithm**:
1. **Input**: User enters or pastes a target EPC hex value.
2. **Normalization**: Trim whitespace, convert to uppercase, and reject empty or non-hex input using `EpcTargetNormalizer`.
3. **Power Setup**: Start Locate at the saved Radar power level, then hand active power control to the dynamic Radar power window.
4. **Start Locate**: Call `startLocation(context, targetLabel, Bank_EPC, 32, callback)` and let the Chainway SDK perform target acquisition.
5. **Location Callback**: Receive `IUHFLocationCallback.getLocationValue(value, found)` from the SDK and deliver updates to the UI thread.
6. **Signal Mapping**: Convert the SDK location value (`0..100`) into a graph score in the `-90..-10 dBm` display range. When the tag is not found, decay the target score toward `-90 dBm`.
7. **Trend Calculation**: Smooth the displayed score and compute a slow Exponential Moving Average (EMA). Green means the current score is at or above the EMA; red means it is below the EMA.
8. **Dynamic Power Window**: Cycle the active Locate power through a 3-level window once per second. Every 3 seconds, keep the window when the target is visible at high/mid power and hidden at low power; move lower when it is visible at all three levels; enter upward recovery when it is missing at high or mid power.
9. **EMA Trend Graph**: Update the full-screen visualizer with the current score, slow EMA, and stable max power of the current Radar window. Do not visualize internal one-second phase jumps.
10. **Directional Audio**: Play a steady OK/error tick based on the current-vs-EMA trend for eyes-free navigation.
11. **Stop**: On user request or fragment pause/destroy, call `stopLocation()` and restore the original reader power.

### 4. Activity Log
- **Dedicated View**: Full-screen log. Newest entries at the top. Max 30 entries.

### 5. Settings
- **UHF Region**: Select frequency standard (Europe 0x04, USA 0x08, etc.).
- **Hardware Reconnect**: Manual re-initialization of UHF and Barcode modules.

## Hardware Logic

- **Language**: Kotlin + Coroutines (`lifecycleScope`, `Dispatchers.IO`).
- **UHF Initialization**: Startup and reconnect use `UhfConnectionController`, which stops the vendor scanner UHF function, frees the previous reader if present, waits for hardware to settle, then retries direct SDK initialization.
- **Locate Mode**: Radar uses Chainway SDK `startLocation()` / `stopLocation()` instead of manual inventory polling. All UHF hardware calls run on `Dispatchers.IO` to avoid blocking the UI thread.
- **Power Management**: Independent power levels for Scan/Write and Radar modes. Radar dynamically probes adjacent power levels while Locate is active.
- **Signal Display**: Radar displays SDK location values as synthetic dBm-like scores for continuity with the existing EMA graph UI.

## Concurrency model

The UHF hardware (`RFIDWithUHFUART`) communicates over a UART serial bus. All commands to it must be serialized — issuing concurrent calls from multiple threads corrupts the bus and silently breaks UHF operations. The codebase enforces this with a single `Mutex` (`uhfMutex`) around reader initialization, settings changes, Write operations, Locate startup, Locate stop, and cleanup.

### Thread ownership of UART

| Operation | Thread | Mechanism |
|---|---|---|
| UHF initialization/reconnect | IO | `lifecycleScope.launch(Dispatchers.IO)` + `uhfMutex.withLock` |
| `setFrequencyMode`, `setPower`, `readData`, `writeData` | IO | serialized through `uhfMutex` |
| `startLocation`, `stopLocation` | IO | serialized through `uhfMutex` |
| `setDynamicRadarPower` | IO | sets idle power only when Locate is not running |
| `setActiveRadarPower` | IO | updates current Radar Locate power during dynamic probing |
| `stopRadar` | IO | `isLocationRunning.set(false)`, `stopLocation()`, restore original power |
| UI updates, callbacks | Main | `withContext(Dispatchers.Main)` |

### `isLocationRunning` — `AtomicBoolean`

Written after `startLocation()` succeeds and cleared during `stopRadar()` / destroy cleanup. Read by `setDynamicRadarPower` so saved Radar power changes do not interrupt an active Locate session. Declared as `AtomicBoolean` to guarantee cross-thread visibility without synchronized blocks.

### UHF initialization sequence

Startup and reconnect use the same cleanup/retry controller:

```
[IO thread]
completeUhfInitialization(reason)
  uhfMutex.withLock {
    ScannerUtility.isUhfWorking(context)
    ScannerUtility.stopScan(context, FUNCTION_UHF)
    previousReader.free()
    delay(cleanupSettleDelayMs)
    repeat retryDelaysMs.size attempts:
      reader = RFIDWithUHFUART.getInstance()
      reader.setPowerOnBySystem(context)
      reader.init(context)
      if connectStatus == CONNECTED:
        mReader = reader.reader
        applySavedSettings()
        viewModel.setConnectionStatus(true)
        syncSettingsToFragment()
        return
      reader.free()
      delay(nextRetryDelayMs)
  }
```

Each attempt logs init result, connect status, power state, error code, scanner cleanup status, retry delay, and init path.

When changing initialization code, keep the sequence centralized in `UhfConnectionController`. Do not call `RFIDWithUHFUART.init()` directly from fragments or UI handlers. A valid reconnect must first stop the vendor scanner UHF function, release the previous reader instance, wait for the cleanup settle delay, and only then retry direct SDK initialization under `uhfMutex`.

Treat initialization as exclusive ownership of the UART bus. While init or reconnect is running, no Radar locate, write, power, or frequency operation may run outside the same mutex. If startup behavior changes, update `UhfConnectionControllerTest` with the expected cleanup, retry, and failure-ordering behavior instead of relying on device-only manual verification.

### Radar startup sequence

Radar graph/audio tickers start only after SDK Locate mode reports successful startup:

```
[Main thread]                         [IO thread]
startRadar() called
  normalize target
  disable target/paste UI
  clear graph
  launch(IO): startRadar(...)         →  uhfMutex.withLock {
                                           originalRadarPower = reader.power
                                           reader.setPower(savedRadarPower)
                                           startLocation(...)
                                           isLocationRunning.set(true)
                                         }
                                      ↓  withContext(Main):
  onStartResult(true)       ←           appendLog(...)
  handler.post(soundTicker)
  handler.post(graphTicker)
  SDK callback              ←           getLocationValue(value, found)
  update target score
```

`setDynamicRadarPower` never restarts inventory or Locate. It only updates idle reader power when Locate mode is not active. `setActiveRadarPower` is the dedicated active-Locate path used by Radar's 3-second dynamic window.

### Dynamic Radar power window

Radar power probing is controlled by `RadarPowerWindowController`. The ordered power ladder is:

```
[30, 24, 19, 15, 12, 10, 8, 7, 6, 5]
```

The active window always contains three adjacent levels. Radar applies one level per second, but the UI displays only the maximum power of the current window so the visual indicator does not jump between internal probe phases. Radar evaluates the 3-second result:

| Result over high/mid/low window | Next action |
|---|---|
| high=yes, mid=yes, low=no | keep the current window |
| high=yes, mid=yes, low=yes | move one window lower |
| high=no or mid=no | enter upward recovery |

The target condition is intentionally based on SDK Locate `found` callbacks, not synthetic graph dBm. This keeps power adaptation tied to whether the tag is actually acquired at each probe level.

When Radar enters upward recovery, it probes stronger power levels one by one until the SDK reports `found=true`. The recovered power becomes the center of the next 3-level window when possible. Example: if recovery finds the tag at `12 dBm`, the new window becomes `[15, 12, 10]`; if it finds the tag at `30 dBm`, the window clamps to `[30, 24, 19]`.

### Radar graph and sound tickers

```
graphTicker every 50ms:
  emaSlowScore = 0.02 * accumulatedScore + 0.98 * emaSlowScore
  radarGraph.addValue(accumulatedScore, powerController.displayPower())
  accumulatedScore += (targetAccumulatedScore - accumulatedScore) * 0.6

powerTicker every 1000ms:
  if powerController.isRecovering():
    activeRadarPower = powerController.advancePhase()
  else after 3 phases:
    decision = powerController.evaluateWindow()
    activeRadarPower = powerController.currentPower()
  setActiveRadarPower(activeRadarPower)

soundTicker every 300ms while signal is visible:
  accumulatedScore >= emaSlowScore -> OK beep
  accumulatedScore < emaSlowScore -> error beep
```
