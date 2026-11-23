# Dependencies & Requirements

This page covers both **operator (runtime) requirements** for running
TAK-XVoice on Android devices and **developer (build) dependencies** for
compiling the plugin from source.

---

## Operator Requirements

### Android & ATAK Versions

| Component | Requirement | Notes |
| --- | --- | --- |
| **Android OS** | **Android 8.0+ (API 26)** | Compiled and targeted against **API 36** (`targetSdk = 36`, required by Google Play). |
| **ATAK CIV** | **5.6 / 5.7 / 5.8** | The plugin's `plugin-api` manifest metadata must match your installed ATAK baseline. Play Store / TPP builds are published per supported baseline. |
| **TAK Server** | Enrolled in ATAK | Device must have a valid TAK Server connection and TLS enrollment certificate configured in ATAK. |
| **Mumble Server** | **Same hostname as TAK Server, port `64738` (TCP/UDP)** | Standard Mumble (Murmur) server reachable at your TAK Server's hostname with a valid TLS certificate. See [Server & Channels](server-setup.md). |

### Android Permissions

TAK-XVoice requests only the permissions required for voice capture, Bluetooth
routing, VoIP call management, and background reliability:

| Permission | Purpose |
| --- | --- |
| `RECORD_AUDIO`, `MODIFY_AUDIO_SETTINGS` | Microphone capture and audio routing during active PTT / calls. |
| `INTERNET`, `ACCESS_NETWORK_STATE` | Mumble TLS/UDP transport and observing Wi-Fi ↔ cellular handoffs. |
| `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE` | Acquiring Android's `MulticastLock` when alpha mesh-voice failover is enabled. |
| `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN` *(Android 12+)* / `BLUETOOTH`, `BLUETOOTH_ADMIN` *(Android ≤ 11)* | Discovering and connecting to Bluetooth speaker-mics and BLE PTT buttons (`neverForLocation` flag set — no location data accessed). |
| `MANAGE_OWN_CALLS`, `CALL_PHONE`, `READ_PHONE_STATE` | Android self-managed VoIP (`ConnectionService`) for clean audio focus arbitration and Bluetooth SCO routing. |
| `FOREGROUND_SERVICE` (`MICROPHONE`, `CONNECTED_DEVICE`, `PHONE_CALL`) | Keeping voice sessions and private calls alive when ATAK is backgrounded or the screen is off. |
| `POST_NOTIFICATIONS`, `USE_FULL_SCREEN_INTENT` | Foreground voice notification and full-screen incoming direct-call ring UI. |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `WAKE_LOCK` | Preventing Android Doze from killing active voice sockets and handling earpiece proximity screen-off. |
| **Accessibility Service** *(Optional)* | Opt-in only for background / screen-off hardware PTT keys on ruggedized handsets; reads no screen content. |

---

## Developer & Build Dependencies

### Toolchain

- **JDK 17** (`jvmTarget = 17`)
- **Gradle Wrapper** (`./gradlew` / `.\gradlew.bat`, included in the repository)
- **Android SDK** (`compileSdk = 36`, `ndkVersion = 25.1.8937393` — pinned to
  match TAK TPP build hosts; XV has no JNI code)
- **ATAK CIV SDK (`main.jar`)** — not bundled in this repository; obtain it
  separately from [tak.gov](https://tak.gov) under its own license terms.

### Core Runtime Libraries

| Library | Role |
| --- | --- |
| **Kotlin stdlib & Coroutines (`kotlinx-coroutines-android`)** | Core language runtime and async audio/network pipelines. |
| **AndroidX (`core-ktx`, `appcompat`)** | Android platform compatibility helpers. |
| **Concentus** *(pure-Java Opus, via JitPack)* | Opus audio encoding/decoding without native NDK/JNI dependencies. |
| **Protobuf Java Lite (`protobuf-javalite`)** | Mumble wire protocol serialization with minimal APK overhead. |
| **ZXing Core (`compileOnly`)** | QR code encode/decode for offline comms-plan sharing (provided at runtime by ATAK; degrades gracefully to share sheet if absent). |

### Building from Source

1. Place the matching **ATAK CIV SDK** `main.jar` into `app/libs/main.jar`
   (gitignored):

   ```powershell
   Copy-Item C:\path\to\ATAK-CIV-5.7.0-SDK\main.jar app\libs\main.jar
   ```

2. Run tests, style checks, and build tasks:

   ```powershell
   .\gradlew test                 # Unit tests
   .\gradlew ktlintCheck          # Kotlin style check
   .\gradlew assembleCivDebug     # Debug APK (loads on Developer SDK ATAK)
   .\gradlew assembleCivRelease   # Release APK (for TPP signing pipeline)
   .\gradlew tppArchive           # Source zips for TPP upload (5.6 / 5.7 / 5.8)
   ```

!!! note "Plugin Signing for Play Store ATAK"
    Play Store ATAK requires plugins to be signed through the **TAK Third-Party
    Plugin (TPP)** pipeline. For Google Play releases, upload the
    `app-civ-release.aab` returned by TPP **unmodified** so ATAK can validate
    the embedded TPP code-transparency signature. You can verify a bundle
    before publishing with `scripts/verify-play-aab.ps1`.
