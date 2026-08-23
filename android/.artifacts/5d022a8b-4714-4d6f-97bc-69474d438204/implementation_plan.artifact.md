# Implementation Plan: Android Auto Compatibility

This plan details the steps to make the Resonance app compatible with Android Auto by implementing a Media3 `MediaLibraryService`. This allows the app's media content to be browsed and controlled from an Android Auto head unit.

## User Review Required

> [!IMPORTANT]
> The `ExoPlayer` instance will be moved from `ResonanceViewModel` to a new `ResonanceMediaService`. This ensures that playback continues in the background and is accessible by the system.
>
> The `ResonanceViewModel` will now communicate with the player via a `MediaController`.

## Proposed Changes

### Build Configuration

#### [MODIFY] [build.gradle.kts](file:///home/mjstrong/resonance/android/app/build.gradle.kts)
- Add `androidx.media3:media3-session` dependency.

---

### Resources & Manifest

#### [NEW] [automotive_app_desc.xml](file:///home/mjstrong/resonance/android/app/src/main/res/xml/automotive_app_desc.xml)
- Define media support for Android Auto.

#### [MODIFY] [AndroidManifest.xml](file:///home/mjstrong/resonance/android/app/src/main/AndroidManifest.xml)
- Add `com.google.android.gms.car.application` metadata.
- Declare the `ResonanceMediaService` with the required intent filters.

---

### Media Implementation

#### [NEW] [ResonanceMediaService.kt](file:///home/mjstrong/resonance/android/app/src/main/java/com/resonance/android/ResonanceMediaService.kt)
- Implement `MediaLibraryService`.
- Manage `ExoPlayer` and `MediaLibrarySession`.
- Handle session callbacks for browsing (root, children) and playback commands.

#### [MODIFY] [MainActivity.kt](file:///home/mjstrong/resonance/android/app/src/main/java/com/resonance/android/MainActivity.kt)
- **ViewModel Refactoring**:
    - Replace direct `ExoPlayer` usage with a `MediaController`.
    - Implement session connection logic.
    - Sync ViewModel state (queue, now playing) with the Media Session.

---

## Verification Plan

### Automated Tests
- Build the project to ensure no compilation errors.
- (Optional) Unit tests for the media session callbacks if infrastructure allows.

### Manual Verification
- Deploy to an Android device.
- Open the **DHU (Desktop Head Unit)** or an Android Auto emulator to verify:
    - The "Resonance" app appears in the media list.
    - The queue is browsable.
    - Playback can be started, paused, and skipped from the Auto UI.
    - Metadata (Title, Artist, Album Art) is correctly displayed on the head unit.
