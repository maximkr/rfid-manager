# RFID Manager

Android app for writing UHF RFID tags using the Chainway C5 rugged device. Scans a QR/barcode and writes the hex data to an RFID tag in one step.

## Project structure

```
app/src/main/java/com/trackstudio/rfidmanager/
  MainActivity.kt          # Main entry: Host for fragments, hardware owner, shared state, trigger handling
  WriteFragment.kt         # "Scan & Write" screen: barcode input, history tags, writing power
  RadarFragment.kt         # "Radar" screen: target search with intensity list and auto-gain sound
  LogFragment.kt           # "Log" screen: Full-screen system activity history
  SettingsFragment.kt      # Configuration screen: UHF frequency region, reconnect
  RadarGraphView.kt        # Custom view: Scrolling real-time RSSI history
  SharedViewModel.kt       # Global state: Connection status observer
  ErrorCodeManager.kt      # Utility: UHF hardware error code mapping

app/src/main/res/
  layout/activity_main.xml          # Host layout with BottomNav and Global Status Bar
  layout/fragment_write.xml         # Scan & Write UI (instructions, input, history, power slider)
  layout/fragment_radar.xml         # Radar UI (targeting, power presets, intensity list, small graph)
  layout/fragment_log.xml           # Activity Log UI (full-screen scrollable area)
  layout/fragment_settings.xml      # Settings UI (UHF frequency, reconnect btn)
  layout/history_tag.xml            # Template for dynamic history chips
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
- **Build:** Gradle 9.4.1, AGP 9.2.0, Kotlin DSL, JVM Target 11
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
- **Validation**: Strict HEX-only input. Prevents writing invalid data.
- **Power Control**: Slider for "Writing Power" (5-30 dBm). Default 10 dBm. Restores original power after write.
- **History**: Horizontal tag bar showing recent success (Green) or error (Red) results.

### 3. Radar (Target Search)
- **Targeting**: User-defined EPC substring. Supports Paste.
- **Adaptive Power Scanning**: The hardware uses a 3-step sliding window to rapidly cycle power levels (e.g., `26, 22, 18`). The window dynamically slides down to lower powers (up to 5 dBm) when the target is near, filtering out background reflections, and slides back up to 30 dBm when the target is lost.
- **Smart Hardware Math**: The app calculates an "equivalent 30 dBm distance" by compensating +1.0 dBm for every 1 dBm drop in transmission power. This ensures the visual graph doesn't jump wildly during physical power switches.
- **EMA Trend Graph**: A full-screen visualizer displaying two lines: the fast current signal and a slow Exponential Moving Average (EMA). The area between lines turns **Green** (Getting Closer) or **Red** (Moving Away).
- **Directional Audio**: A steady audio tick that changes pitch based on your trend. It plays an "OK" beep while in the Green zone and an "Error" beep when in the Red zone, allowing completely blind navigation.
- **Panic Recovery**: If the tag completely disappears from the current low-power window, the radar pauses the graph decay and makes a massive 540ms "rescue sweep" at `[30, 24, 19] dBm` to quickly re-acquire the target without destroying the visual trend.

### 4. Activity Log
- **Dedicated View**: Full-screen log. Newest entries at the top. Max 30 entries.

### 5. Settings
- **UHF Region**: Select frequency standard (Europe 0x04, USA 0x08, etc.).
- **Hardware Reconnect**: Manual re-initialization of UHF and Barcode modules.

## Hardware Logic

- **Language**: Kotlin + Coroutines (`lifecycleScope`, `Dispatchers.IO`).
- **Inventory Mode**: Radar uses `startInventoryTag()` in a polling loop for maximum reliability.
- **Power Management**: Independent power levels for Scan/Write and Radar modes.
- **RSSI Extraction**: Handles raw dBm (e.g. -65) and centi-dBm (e.g. -6523) formats automatically.
