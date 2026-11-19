# Gallery Cleanup

Gallery Cleanup is a privacy-first gallery cleanup app for Android. It counts the photos, videos, and audio already on the phone, and later it will flag items the user may want to remove. The user decides, per item, whether to keep it, move it to a locked folder, or send it to the system trash.

Nothing is uploaded. There is no account, no backend, and no analytics. The app does not declare a usable `INTERNET` permission.

Week 1 is the shell: a privacy promise, a permission flow that understands Android 11 through Android 16, and on-device counts. Nothing is deleted yet.

## Run it on a phone

You need Android Studio Meerkat (2024.3.1) or newer, JDK 17 or newer, and Android SDK platform 36.

1. Open this directory in Android Studio and let it download the Gradle wrapper's dependencies.
2. On the phone, enable Developer options and USB debugging.
3. Plug the phone in, pick the `app` run configuration, and press Run.

From a terminal, with the phone connected:

```bash
./gradlew :app:installDebug
```

The debug build also installs with Android Studio's Run button. The first screen is the privacy promise. No permission dialog appears until you tap **Allow photos and videos** on the next screen. Audio is a separate choice on the home screen, **Turn on audio scanning**.

`local.properties` is created by Android Studio and points at your SDK. It is not committed.

## Build and test

```bash
./gradlew assembleDebug
./gradlew test
./gradlew :app:lintDebug
```

`assembleDebug` produces `app/build/outputs/apk/debug/app-debug.apk`.

`test` runs JVM unit tests. It does not need a phone. The Compose UI tests live in `app/src/testDebug` because the Compose test manifest is a debug artifact, so they run as part of `testDebugUnitTest`. `test` also runs the merged-manifest guard described below.

## Manifest guard

Gallery Cleanup must not ship these permissions, even if a later library tries to add them:

- `android.permission.INTERNET`
- `android.permission.ACCESS_NETWORK_STATE`
- `android.permission.MANAGE_EXTERNAL_STORAGE`
- `android.permission.MANAGE_MEDIA`

The app manifest removes each one with `tools:node="remove"`. After the manifest merge, this task fails the build if any of them are still present:

```bash
./gradlew :app:checkDebugMergedManifest
```

`./gradlew :app:assembleDebug` and `./gradlew :app:testDebugUnitTest` depend on that task, so a normal build and the unit tests both refuse a bad merge. The list lives in `gradle/forbidden-permissions.txt`. A unit test in `:app` checks the same rule against fixture XML and against the merged file.

`MANAGE_EXTERNAL_STORAGE` is all-files access. `MANAGE_MEDIA` lets an app change media without a system dialog. Week 2's trash action will use `MediaStore.createTrashRequest`, which asks the user and needs neither permission.

Auto Backup is off (`android:allowBackup="false"`). `res/xml/backup_rules.xml` and `res/xml/data_extraction_rules.xml` exclude every backup domain, because on Android 12 and higher the rules file overrides `allowBackup`. Scan results must not be copied to the user's Google Drive.

## Modules and ownership

| Module | Owner | What it is this week |
| --- | --- | --- |
| `:app` | Dev 1 | Activity, theme, onboarding, permission screens, home counts, scan controls |
| `:core:model` | Dev 1 | Shared types, including the `ScanStore` contract the scan writes through |
| `:core:media` | Dev 1 | Permission rules, MediaStore paging, and the library scanner |
| `:core:data` | Dev 2 | Room `ScanStore`, plus review rows for keep / trash. The app still binds the in-memory store |
| `:feature:review` | Dev 2 | Empty. Review UI, trash, keep, and the locked folder go here |
| `:detection` | ML specialist | Detector contract only. No model yet |

```
:app  -->  :core:media  -->  :core:model
:detection
:core:data
:feature:review
```

`:core:model` is plain Kotlin, with no Android SDK. `MediaItem.uri` is a `String` (`content://...`) so the type can be constructed in JVM tests and shared without pulling in `android.net.Uri`.

`:core:media` is the only module that talks to `MediaStore`. The interface is `MediaRepository`: `count` for the home screen, and `page` for the week 2 scan. New detectors and the Room database should not grow their own queries.

SDK levels are set once, in `build-logic/src/main/kotlin/com/useinoski/gallerycleanup/convention/GallerySdk.kt`: `minSdk` 30, `compileSdk` 36, `targetSdk` 36. Every Android module applies `gallery.android-application` or `gallery.android-library`. Versions of libraries live in `gradle/libs.versions.toml`.

## Permission behavior

| Device | Photos and videos | Audio |
| --- | --- | --- |
| Android 11–12 (API 30–32) | `READ_EXTERNAL_STORAGE`, manifest `maxSdkVersion="32"` | Same storage grant. The home screen still waits for **Turn on audio scanning** before it counts audio. |
| Android 13 (API 33) | `READ_MEDIA_IMAGES` and `READ_MEDIA_VIDEO` | `READ_MEDIA_AUDIO`, requested only from that button |
| Android 14+ (API 34–36) | The two read permissions plus `READ_MEDIA_VISUAL_USER_SELECTED`, so a partial selection is detected instead of compatibility mode | Same separate audio permission |

Partial access shows the counts for the selected items only, says so, and offers **Select more** (the system selection dialog again) and **Allow all** (app Settings, where Photos and videos can be set to Allow all). A permanent denial explains why access is needed and offers **Open Settings**. The grant is re-read whenever the screen resumes. It is not cached.

Google Play's photo and video policy allows broad library access for a gallery manager, which is what a cleanup app is, and still requires the app to work when the user grants only a selection. The photo picker is not enough here: it returns a one-time selection and cannot count the library. The in-app explanation is shown before the system dialog. `androidx.activity:activity-compose` 1.13 is above 1.7, so the photo picker contract is available later if a one-time import is added. It is not the way the library is scanned.

## What is left for later

- Deletion, trash, and the locked folder (`:feature:review`)
- Pointing `ScanRuntime.store()` at `roomScanResultStore` (`:core:data`). That class implements `ScanStore` and survives process death. The app still binds the in-memory stand-in, which does not. The worker already resumes from whatever `ScanStore` it is given.
- ML Kit (`:detection`). When it is added, use the bundled artifacts `com.google.mlkit:face-detection` and `com.google.mlkit:image-labeling`. Do not use `com.google.android.gms:play-services-mlkit-*`. The scan calls `ScanBatchObserver`, which is a no-op until a detector is bound there.

The scan notes for a Java developer are in [docs/week2-kotlin-notes.md](docs/week2-kotlin-notes.md).

## Toolchain

- Kotlin 2.2.21, Android Gradle Plugin 8.13.2, Gradle 8.13
- Jetpack Compose BOM `2026.06.01` (Compose 1.11) and Lifecycle 2.10. Newer Compose BOMs and Lifecycle 2.11 require `compileSdk` 37, and this app stays on 36 because Play requires API 36 for new apps as of 31 Aug 2026
- `minSdk` 30 because `MediaStore.createTrashRequest`, the week 2 trash API, was added in Android 11, and scoped storage is already the rule there

A walk through the Kotlin and the permission model, written for someone coming from Java, is in [docs/week1-kotlin-notes.md](docs/week1-kotlin-notes.md).
