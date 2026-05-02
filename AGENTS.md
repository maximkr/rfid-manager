# RFID Manager

Android app for writing UHF RFID tags using the Chainway C5 rugged device. Scans a QR/barcode and writes the hex data to an RFID tag in one step.

## Project structure

```
app/src/main/java/com/trackstudio/rfidmanager/
  MainActivity.java        # Single activity: UHF init, barcode scan callback, RFID write/verify
  ErrorCodeManager.java    # Maps Chainway UHF error codes to human-readable messages

app/src/main/res/
  layout/activity_main.xml          # Portrait layout (Material 3 cards, TextInput, log area)
  layout-land/activity_main.xml     # Landscape variant
  layout/history_tag.xml            # Template for dynamic history chips
  drawable/                         # Vector icons (ic_rfid, ic_write, ic_log), backgrounds
  values/colors.xml                 # Green Material 3 palette
  values/themes.xml                 # Theme.RFIDManager (Material3.Light.NoActionBar)
  values/strings.xml                # Externalized strings and example codes
  raw/                              # Audio: barcodebeep.ogg (success), serror.ogg (error)
  xml/configuration_6603.xml        # Chainway device config

app/libs/
  DeviceAPI_ver20230301_release.aar  # Chainway proprietary SDK (UHF + barcode)
```

## Build & dependencies

- **Language:** Java (no Kotlin)
- **Build:** Gradle 9.4.1, AGP 9.2.0, Kotlin DSL
- **SDK:** minSdk 33, targetSdk 33, compileSdk 37
- **Dependencies:** AndroidX AppCompat 1.7.1, Material 1.12.0, ConstraintLayout 2.2.1, Navigation 2.9.0
- **Version catalog:** `gradle/libs.versions.toml`
- **View Binding:** enabled

## Key hardware APIs (from DeviceAPI AAR)

- `RFIDWithUHFUART` — UHF reader/writer (init, readData, writeData, setPower, getPower, getErrCode)
- `BarcodeDecoder` / `BarcodeFactory` — barcode scanner (open, setDecodeCallback, close)
- `IUHF.Bank_EPC` — EPC memory bank constant
- `UhfBase.ErrorCode.*` — error code constants

## Interface requirements & logic

### 1. Data Input & Validation
- **Input field**: Contains an example code (e.g., `e0000001`) by default via `@string/example_code`.
- **Hex validation**: Only characters `0-9` and `A-F` (case-insensitive) are allowed. Non-hex characters block the writing process with an error message in the log.
- **Length check**: Before writing, the app detects if the tag is 6 or 8 words (EPC bank). If the input hex string is longer than the tag's capacity (24 or 32 chars), the write is cancelled with an error.

### 2. History Strip (Tag Bar)
- **Visuals**: A horizontal scrollable area below the input card.
- **Behavior**: New entries appear on the far left, pushing older ones to the right.
- **Color coding**: 
    - **Green tag**: Successful RFID write and verification.
    - **Red tag**: Any error (validation failed, tag not found, write error).
- **Interactivity**: Supports finger-swiping; scrollbars are hidden for a clean look.

### 3. Activity Log
- **Sorting**: Newest entries are always at the top.
- **Capacity**: Maximum 30 entries (uses `LinkedList` to manage history). Older entries are automatically removed.
- **Auto-scroll**: Automatically scrolls to the top (latest entry) when a new message is added.
- **Format**: `HH:mm:ss - [Message]`.

### 4. Hardware Execution (RFID Write Flow)
- **Background Threading**: All hardware interactions (RFID read/write) **must** run in a background thread to prevent Application Not Responding (ANR) errors.
- **UI Thread**: All interface updates (logging, history tags, toast/status updates) must use `runOnUiThread`.
- **Write sequence**:
    1. Temporarily set power to 10 dBm (safe low-range writing).
    2. Detect EPC size (try reading 8 words, then 6).
    3. Validate input length against detected size.
    4. Write data at offset 2 of EPC bank.
    5. Read back and compare for verification.
    6. Restore original power level in `finally` block.
    7. Play sound (ID 1 for success, ID 2 for error).

## Conventions

- **No Kotlin**: Maintain all logic in Java.
- **Material 3**: Use Material 3 components, colors, and themes.
- **Color scheme**: Primary Green (#2E7D32), Error Red (#D32F2F).
- **Externalization**: Use `strings.xml` for all user-facing text and formatters.
- **Logging**: Use the internal `appendLog()` method instead of standard `Log.d` for user visibility.
