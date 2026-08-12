# Security Policy

## Supported Versions

This project is developed against **Chainway C5** and compatible UHF RFID handhelds. Only the latest release on `main` receives fixes.

## Reporting a Vulnerability

**Please do not open a public issue for security problems.**

Use GitHub's private channel instead: [**Security → Report a vulnerability**](https://github.com/maximkr/rfid-manager/security/advisories/new). Reports there are visible only to the maintainers.

If the private form is unavailable, contact the maintainer — [@maximkr](https://github.com/maximkr).

### What to include

- app version (from the release page or `versionName` in `app/build.gradle.kts`);
- device model and Android version;
- steps to reproduce;
- what an attacker gains;
- whether physical access to the device or proximity to the RFID reader is required.

### Response times

- **acknowledgement** — within 5 business days;
- **initial assessment** — within 14 days;
- fix and advisory — by agreement, depending on severity.

Please keep details private until a fix is released.

## Scope

Relevant areas for this app:

- unintended tag writes — in particular anything that could overwrite EPC memory of neighbouring tags;
- handling of scanned barcode data before it reaches the tag;
- exported Android components (activities, services, receivers) reachable by other apps on the device;
- permission and intent handling;
- data written to logs or exported files.

## Out of scope

- **RFID protocol weaknesses themselves.** EPC Gen2 tags are, by design, readable and often writable by anyone with a reader in range. This is a property of the standard, not a defect in this app.
- **Vendor SDK internals.** `app/libs/DeviceAPI_ver20230301_release.aar` is a proprietary binary supplied by Chainway. Issues inside it must be reported to the vendor; we can only work around them.
- **Physical access attacks** on an unlocked device.
