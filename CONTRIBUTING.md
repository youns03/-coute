# Contributing to Écoute

Thank you for helping improve Écoute. The project is actively evolving, so focused changes with clear verification are easier to review and safer to integrate.

## Before opening a change

1. Check the current [README](README.md), [architecture guide](docs/ARCHITECTURE.md), and [changelog](CHANGELOG.md).
2. Search existing issues and pull requests to avoid duplicating work.
3. Keep application behavior and visual design unchanged unless the task explicitly requires changing them.
4. Avoid broad refactors mixed with feature work.

## Development setup

- JDK 21
- Android SDK platform 36 and build tools 36.0.0
- Android Studio or an equivalent Gradle environment

Create a local debug keystore if needed (see the README). Do not commit it.

## Before submitting

Run the checks relevant to your change:

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:compileDebugKotlin
```

For UI, audio, model-storage, or device-compatibility changes, include the Android version and device/ABI used for manual verification. If a check could not be run, say so explicitly rather than marking it as passed.

## Pull request expectations

A good pull request should:

- Solve one coherent problem.
- Explain user-visible behavior and important implementation decisions.
- Include tests for logic and regression cases where practical.
- Update documentation or the changelog when behavior or setup changes.
- Avoid committing APKs, downloaded model weights, local configuration, signing files, or personal data.
- Preserve database migration compatibility and existing user data.

## Model and dependency changes

Before adding or updating a model or native dependency:

- Record the upstream source and exact version.
- Verify license and redistribution conditions.
- Record expected size and a trustworthy SHA-256 digest.
- Document API, ABI, RAM, language, and accelerator requirements.
- Keep model weights out of the repository and APK unless the project explicitly approves an exception.

## Reports

Use GitHub Issues for reproducible bugs and focused feature proposals. Never attach private books, recordings, credentials, keystores, or personal documents.
