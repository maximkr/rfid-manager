# RFID Manager

Android app for writing UHF RFID tags using the Chainway C5 rugged device. Scans a QR/barcode and writes the hex data to an RFID tag in one step.

## Project structure

```
app/src/main/java/com/trackstudio/rfidmanager/
  MainActivity.kt          # Main entry: Host for fragments, hardware owner, shared state
  WriteFragment.kt         # "Scan & Write" screen: barcode input, log, history
  SettingsFragment.kt      # Configuration screen: UHF power, frequency, reconnect
  SharedViewModel.kt       # Global status management (connected/disconnected)
  ErrorCodeManager.kt      # Maps Chainway UHF error codes to human-readable messages

app/src/main/res/
  layout/activity_main.xml          # Host layout with BottomNav and Global Status Bar
  layout/fragment_write.xml         # Scan & Write UI (instructions, input, tags, log)
  layout/fragment_settings.xml      # Settings UI (cards for config, reconnect btn)
  layout/history_tag.xml            # Template for dynamic history chips
  menu/bottom_nav_menu.xml          # Navigation menu (Scan & Write, Settings)
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
- **Bottom Navigation**: Two tabs — "Scan & Write" and "Settings".
- **Global Status Bar**: Fixed header in `MainActivity` showing connection status (`CONNECTED` with green dot / `DISCONNECTED` with red dot).

### 2. Scan & Write (Main Screen)
- **Instructions**: Explicit text guide for the user at the top.
- **Data Input**: Hex-only validation (0-9, A-F). Example code `e0000001` loaded by default.
- **History Strip**: Horizontal scrollable list of recent tags. Green = Success, Red = Error. New tags appear on the left.
- **Activity Log**: Newest entries at the top, max 30 entries, automatic scroll to top.

### 3. Settings Screen
- **UHF Config**: Dropdown for frequency mode, slider for output power (5-30 dBm).
- **Reconnect**: Manual hardware re-initialization button.

### 4. Hardware Execution (RFID Write Flow)
- **Threading**: All hardware calls run on `Dispatchers.IO`. UI updates use `Dispatchers.Main`.
- **Write sequence**:
    1. Temporarily drop power to 10 dBm for safe writing.
    2. Detect EPC size (try reading 8 words, then 6).
    3. Validate input length against detected size.
    4. Write data at offset 2 of EPC bank.
    5. Read back and compare for verification.
    6. Restore original power level in `finally` block.
    7. Play success (ID 1) or error (ID 2) sound.

## Conventions

- **Kotlin First**: Use modern Kotlin idioms (property access, `apply`, `let`, `when`).
- **Safety**: Use nullable types for hardware instances (`barcodeDecoder?`, `mReader?`).
- **Material 3**: Follow M3 color palette and component guidelines.
- **Externalization**: No hardcoded strings in code; use `strings.xml`.
- **Log Visibility**: Use `appendLog()` for user-facing feedback, managed via the Activity to persist during navigation.
