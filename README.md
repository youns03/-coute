<div align="center">

<img src=".github/assets/ecoute-banner.svg" alt="Écoute — Read, listen, understand" width="100%"/>

[![Android CI](https://github.com/youns03/-coute/actions/workflows/android-ci.yml/badge.svg?branch=main)](https://github.com/youns03/-coute/actions/workflows/android-ci.yml)
[![Latest release](https://img.shields.io/github/v/release/youns03/-coute?display_name=tag&color=48b6a8)](https://github.com/youns03/-coute/releases/latest)
[![Android](https://img.shields.io/badge/Android-API%2024%2B-3DDC84?logo=android&logoColor=white)](https://developer.android.com/)
[![Kotlin](https://img.shields.io/badge/Kotlin-Compose-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)

### Read with attention. Listen with intention. Understand more.

**Écoute** is an Android reading and language-learning workspace built around original text, natural speech, and Arabic translation.

قارئ للكتب والنصوص، استماع صوتي محلي، وترجمة إلى العربية — في مساحة واحدة تساعدك على القراءة والفهم والتعلّم.

[Download latest release](https://github.com/youns03/-coute/releases/latest) · [Architecture](docs/ARCHITECTURE.md) · [Changelog](CHANGELOG.md) · [Report an issue](https://github.com/youns03/-coute/issues)

</div>

---

## The idea

Reading in another language should feel connected, not fragmented. Écoute brings the essential parts of that experience together:

- Read the original text in a calm, paragraph-based reader.
- Listen to locally generated speech while the reader follows the active sentence.
- Reveal Arabic translations alongside the source text.
- Keep books, reading position, vocabulary, and downloaded models on the device.
- Import supported content and use dedicated background workflows where available.

The current language direction is **English/French → Arabic**. Exact model and device requirements vary by engine.

## What you can do

| Area | What it provides |
|---|---|
| **Reading library** | Import supported reading material, organize books, resume progress, and save vocabulary. |
| **Reader** | Natural paragraph presentation, sentence-aware reading position, and Arabic translation below the original text. |
| **Synchronized listening** | Local text-to-speech, active-sentence highlighting, scroll-follow, and playback controls. |
| **Neural speech** | Kokoro and Piper/Sherpa-ONNX engines, with device-aware model selection and CPU fallback where supported. |
| **Offline translation** | Downloadable English→Arabic and French→Arabic ML Kit language packs shared by the reader and file-translation flow. |
| **Audio transcription** | Local Sherpa-ONNX speech recognition for supported downloaded ASR models. |
| **Model management** | Download, verify, inspect compatibility, install, and remove model packages separately from the APK. |
| **Document workflows** | Document ingestion and background translation components are being expanded; check the current release notes for what is available in a published build. |

> **Offline means prepared first.** Download the required language and speech models while connected. Once the relevant packs are installed, supported reading, speech, and translation paths can run locally. Not every setup or import step is guaranteed to be offline.

## A local-first design

Model weights are **not bundled in the APK**. The app contains the runtime and model-management code; large model packages are obtained separately, checked, and stored in app-managed storage. This keeps the initial installation smaller and lets you choose which engines and languages to keep.

The app coordinates expensive local work with shared managers and task state to reduce competing inference workloads on memory-constrained phones. See the [architecture guide](docs/ARCHITECTURE.md) for the current component map and important boundaries.

## Download

The latest published version is **v1.0.4**. The main branch may contain newer work that has not yet been packaged into a release.

<div align="center">

[![Get Écoute from GitHub Releases](https://img.shields.io/badge/GET%20THE%20LATEST%20APK-GitHub%20Releases-183b4a?style=for-the-badge&logo=github)](https://github.com/youns03/-coute/releases/latest)

</div>

| Package | Recommendation |
|---|---|
| **ARM64 APK** | Recommended for most modern Android phones with a 64-bit ARM processor. |
| **Universal APK** | Larger compatibility build for the other ABIs included in the release. |

Before installing an update, use a build signed with the same release certificate as the installed app. Android will not install an update signed with a different key.

Each release includes SHA-256 checksums. Verify downloaded files against `SHA256SUMS.txt` when possible.

## Technology

- **Language and UI:** Kotlin, Jetpack Compose, Material 3
- **Android foundation:** AndroidX, Room, DataStore, Lifecycle, Navigation, WorkManager
- **Audio:** Media3, Android audio APIs, Kokoro LiteRT, Piper through Sherpa-ONNX
- **Speech recognition:** Sherpa-ONNX offline recognizers
- **Translation:** ML Kit on-device translation
- **Build and quality:** Gradle, Kotlin tooling, GitHub Actions, JVM tests, Android Lint

## Build from source

### Requirements

- JDK 21
- Android SDK platform 36 and build tools 36.0.0
- Android Gradle Plugin and Gradle Wrapper versions pinned by this repository
- Android 7.0 (API 24) or newer for the application baseline; individual models may require a newer API level or specific hardware

### Build and verify

The debug build expects a local `debug.keystore` in the repository root. It is intentionally not committed.

Create a standard debug keystore if your environment does not already have one:

```bash
keytool -genkeypair -v \
  -keystore debug.keystore \
  -storepass android \
  -alias androiddebugkey \
  -keypass android \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -dname "CN=Android Debug,O=Android,C=US"
```

Then run:

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:compileDebugKotlin
./gradlew :app:assembleDebug
```

On a clean clone, Gradle downloads the official Sherpa-ONNX AAR when needed and verifies its SHA-256 before compilation.

### CI and release signing

The Android CI workflow runs on pushes to `main` and pull requests targeting `main`. It checks bundled font coverage, runs JVM unit tests and Android Lint, compiles Kotlin, and assembles a debug APK.

The release workflow creates signed ABI-specific and universal APKs. Release signing is configured through GitHub Actions secrets:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Keep the release keystore private and backed up. Never commit keystores, passwords, API keys, private documents, or model files.

## Project status

Écoute is actively evolving. A green CI run verifies the checks configured in that workflow; it does not prove speech quality, thermal behavior, long-session memory stability, or every model/device combination. Those still need real-device testing.

The latest GitHub Release is the reliable source for installable builds. Features visible only on `main` should be treated as development work until a release is published.

## Contributing

Focused bug reports and improvement proposals are welcome. Read [CONTRIBUTING.md](CONTRIBUTING.md) before opening a pull request, and use the issue templates where available.

## Security and privacy

Please do not publish credentials, signing material, private books, audio recordings, or personal documents in issues. For vulnerability reports, follow [SECURITY.md](SECURITY.md).

---

<div align="center">

**Écoute** · Lire avec attention. Écouter avec intention. Comprendre durablement.

<sub>Built for thoughtful reading and language learning.</sub>

</div>
