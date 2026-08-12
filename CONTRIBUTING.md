# Contributing to RFID Manager

Thanks for your interest. This document covers building the project, what to keep in mind when changing it, and how to open a pull request.

More detailed architecture and style notes live in [`AGENTS.md`](AGENTS.md).

## Building

Requires **JDK 17** and the Android SDK. Gradle comes with the wrapper.

```bash
git clone https://github.com/maximkr/rfid-manager.git
cd rfid-manager

./gradlew testDebugUnitTest   # unit tests
./gradlew assembleDebug       # debug APK -> app/build/outputs/apk/debug/
./gradlew assembleRelease     # unsigned release APK
```

JDK 17 is what AGP 8.13 runs on. The app module itself targets `jvmTarget = 11` — that is the bytecode level of the app, not the JVM that runs Gradle.

Install onto a device over ADB:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## About the vendor SDK

`app/libs/DeviceAPI_ver20230301_release.aar` is Chainway's proprietary device SDK, checked into the repository because it is not published to any Maven repository. It is **not** covered by this project's Apache-2.0 licence — see [NOTICE](NOTICE).

Everything that talks to the SDK is isolated behind the `UhfReader`, `UhfReaderFactory` and `UhfScannerCleaner` interfaces (declared in `UhfConnectionController.kt`), with the concrete implementations in `ChainwayUhfAdapters.kt`. That is why the connection logic can be unit-tested without a device. Please keep it that way: new hardware calls belong behind an adapter, not scattered through fragments.

## Testing

The project has real JVM unit tests in `app/src/test/`, and they run on every pull request:

- `EpcTargetNormalizerTest` — EPC normalisation, non-hex rejection, padding for 6- and 8-word EPCs
- `RadarPowerWindowControllerTest` — radar power window logic
- `UhfConnectionControllerTest` — reader init ordering, freeing the old reader, retry with backoff
- `BarcodeSoundPolicyTest` — suppressing vendor scanner sounds

```bash
./gradlew testDebugUnitTest
```

New logic should come with tests. If something cannot be tested without hardware, that is usually a hint it should be split — pull the decision-making into a plain class and keep the device call behind an adapter, as the existing controllers do.

Instrumented tests in `app/src/androidTest/` require a device or emulator and are not run in CI.

## Testing on hardware

Behaviour that genuinely needs a C5 in hand:

- writing to tags at various power levels (5–30 dBm) — especially that neighbouring tags are not overwritten;
- physical trigger button handling;
- radar behaviour as you approach a target, including audio feedback;
- barcode scanner integration and sound suppression.

If your change touches any of these, say in the pull request whether you verified it on a device, and on which one.

## Pull requests

1. Branch from `main`, name it meaningfully: `fix/...`, `feature/...`, `docs/...`.
2. Make sure `./gradlew testDebugUnitTest assembleDebug` passes locally.
3. Describe what changes and why. For UI changes, attach before/after screenshots.
4. Wait for CI to go green.
5. One pull request — one logical change.

## Licence

By submitting a pull request you agree that your contribution is licensed under the [Apache License 2.0](LICENSE).
