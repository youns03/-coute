# Changelog

## v1.0.4 — 2026-11-26

### Reader and interface
- Refine shared UI components, screen layouts, and icon usage for a more consistent experience.
- Optimize bundled font assets and validate the remaining font resources during tests.

### Audio and reliability
- Improve audio playback stability and cache cleanup during application startup.
- Check coroutine cancellation/context while writing audio samples to avoid stale or interrupted work continuing.
- Keep Room schema output configured for reliable database migration tracking.
- Standardize navigation labels through Android string resources.

### Build and verification
- Upgrade the GitHub Actions build environment to Java 21.
- Strengthen automated checks for the current Android source and release build.

### Notes
- Existing reader, synchronized listening, Kokoro/Piper speech, translation, library, and offline model-management functionality is retained.
- Speech and translation model packs remain separately downloadable and are not bundled into the APK.

# Changelog

## v1.0.3 — 2026-11-24

### Reader and typography
- Improve the Arabic typography foundation and verify required OpenType shaping features.
- Follow the active audio sentence while reading, while respecting manual scrolling.
- Add a clear return-to-listening-position action.
- Move reader interface labels into Android string resources.

### Playback and library
- Add a global mini-player on top-level screens outside the reader and full player.
- Add an immersive reading mode that hides app-level chrome without removing reader controls.
- Add typographic book covers and explicit start/continue-reading actions.

### Notes
- No speech-engine, translation-engine, or book-storage schema replacement is included in this release.
- Required speech and translation model packs remain separately downloadable; they are not bundled in the APK.
