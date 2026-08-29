# Resonance workspace guidance

## Android builds

Use the checked-in Android Gradle wrapper through `android/build-debug.sh`.
It selects the known-compatible local Java 21 runtime and Android SDK before
invoking Gradle. Do not use Android Studio's bundled Java 25 for this project:
Kotlin 2.0.21 rejects it during Gradle configuration.

The Android SDK path is intentionally kept in the ignored `android/local.properties`
file so Android Studio and Gradle can discover it without committing a host-specific path.
