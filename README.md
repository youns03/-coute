<div align="center">

<img src=".github/assets/ecoute-banner.svg" alt="Écoute — Read, listen, understand" width="100%"/>

[![Android CI](https://github.com/youns03/-coute/actions/workflows/android-ci.yml/badge.svg?branch=main)](https://github.com/youns03/-coute/actions/workflows/android-ci.yml)
[![Latest Release](https://img.shields.io/github/v/release/youns03/-coute?display_name=tag&color=2c9c86)](https://github.com/youns03/-coute/releases/latest)
[![Platform](https://img.shields.io/badge/platform-Android-3DDC84?logo=android&logoColor=white)](https://www.android.com/)

**A focused Android reading and listening companion for language learners.**

قارئ للكتب، استماع بالنطق العصبي، وترجمة محلية مصممة لمساعدة متعلمي الفرنسية والإنجليزية الناطقين بالعربية.

[Latest APK](https://github.com/youns03/-coute/releases/latest) · [Releases](https://github.com/youns03/-coute/releases) · [Report a bug](https://github.com/youns03/-coute/issues)

</div>

---

## About Écoute

Écoute brings reading, synchronized listening, and translation into one native Android app. Its interface is built around the original text: readable paragraphs first, with Arabic translation directly below each paragraph when requested.

The app is designed for **French and English learning with Arabic support**. Speech and translation models are managed separately from the app and can be downloaded for local use; model files are not bundled into the APK.

## Highlights

- **A comfortable reader:** original text presented as natural paragraphs, with paragraph-level Arabic translation.
- **Listen while reading:** sentence-aware playback highlighting and automatic scroll-follow, with manual-scroll control and a return-to-listening action.
- **Neural speech:** local speech engines including Kokoro and Piper through Sherpa-ONNX.
- **Offline translation:** on-device translation packs can be downloaded and used locally where supported.
- **A global mini-player:** playback controls remain accessible outside the reader.
- **Immersive reading:** hide app-level navigation chrome while keeping reader controls available.
- **A personal library:** book progress, continue-reading actions, and vocabulary saving.
- **Background file translation:** a separate workflow for document translation and result export.

> Offline use depends on downloading the required language/model packs first. Some setup, downloads, and integrations require an internet connection.

## Current development milestone

The current main branch includes the latest reader-following, typography, playback, immersive-reading, and library improvements. The implementation history and user-facing changes are tracked in the [changelog](CHANGELOG.md).

## Download

Get the newest published build from **[GitHub Releases](https://github.com/youns03/-coute/releases/latest)**.

| Build | Intended use |
|---|---|
| ARM64 APK | Recommended for most modern Android phones |
| Universal APK | Larger fallback build for other supported ABIs |

Always install updates from the same release signing identity. Android will reject an update if its signing certificate differs from the installed copy.

## Technology

- Kotlin and Jetpack Compose
- Material 3
- AndroidX Navigation, Lifecycle, Room, DataStore, and WorkManager
- Media3 playback
- Sherpa-ONNX for local speech pipelines
- Kokoro and Piper speech models
- ML Kit on-device translation
- Gradle and GitHub Actions

## Build from source

### Requirements

- JDK 17 or newer
- Android SDK platform/build tools 36
- Android Gradle Plugin and Gradle versions pinned in the project
- Android minSdk 24, targetSdk 36

### Local debug build

The debug signing configuration expects a local debug.keystore in the repository root. It is intentionally excluded from Git.

Create a standard Android debug keystore if your environment does not already have one:

~~~bash
keytool -genkeypair -v \
  -keystore debug.keystore \
  -storepass android \
  -alias androiddebugkey \
  -keypass android \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -dname "CN=Android Debug,O=Android,C=US"
~~~

Then run:

~~~bash
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:assembleDebug
~~~

A clean clone automatically downloads the official Sherpa-ONNX AAR when it is missing and verifies its SHA-256 before compilation.

## Automated checks and releases

Every push to main and every pull request targeting main runs:

- Font coverage and Arabic OpenType shaping checks
- JVM unit tests
- Android lint
- Kotlin/Compose compilation

A signed Android release is published by pushing a SemVer tag such as v1.0.4, or by a main-branch commit whose subject contains an explicit marker such as [release v1.0.4].

The release workflow requires these GitHub Actions secrets:

- ANDROID_KEYSTORE_BASE64
- ANDROID_KEYSTORE_PASSWORD
- ANDROID_KEY_ALIAS
- ANDROID_KEY_PASSWORD

**Keep the release keystore private and backed up.** Every update intended to install over an existing release must be signed with the same keystore.

## Project status

Écoute is under active development. CI verifies source-level checks and Android compilation; it does not replace testing on physical devices. In particular, speech quality, long-session memory use, model downloads, background execution, and Arabic text rendering should be checked on real Android hardware.

There is currently no repository license declaration. Do not assume the source code is available for redistribution or reuse until a license is added.

## Contributing and feedback

Bug reports and focused improvement proposals are welcome through [GitHub Issues](https://github.com/youns03/-coute/issues). Please include the Android version, device architecture, steps to reproduce, and relevant logs—never upload private books, API keys, signing keys, or personal documents.

---

<div align="center">

**Écoute** · Lire avec attention. Écouter avec intention. Comprendre durablement.

</div>
