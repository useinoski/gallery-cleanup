# Week 1, for a Java developer

Hearth is a small Android app written in Kotlin and Jetpack Compose. This note walks through the Kotlin that will feel new if you have been writing Java, and then the Android decisions: why the minimum SDK is 30, why we compile and target 36, and how partial photo access actually works.

The code to open beside this note:

- `core/model/src/main/kotlin/app/hearth/model/PermissionState.kt`
- `core/media/src/main/kotlin/app/hearth/media/PermissionStatusResolver.kt`
- `core/media/src/main/kotlin/app/hearth/media/MediaStoreMediaRepository.kt`
- `app/src/main/java/app/hearth/ui/home/HomeViewModel.kt`
- `app/src/main/java/app/hearth/ui/home/HomeScreen.kt`
- `app/src/main/java/app/hearth/ui/HearthApp.kt`

## 1. `val` and `var`

Java has `final` and then everything else. Kotlin splits that into two keywords, and you write one of them every time you introduce a name.

```kotlin
val sdk = 36          // cannot be reassigned, like final int sdk = 36
var loading = true    // can be reassigned
loading = false
```

`val` does not mean the object is immutable. It means the variable cannot point at a different object. A `val` list can still be mutated if the list itself allows it. Prefer `val`. Reach for `var` when the name really changes, which in this project is mostly inside tests and the preference flags.

Function parameters are always `val`. You cannot reassign `permission` inside `wasAsked`. That removes a class of bugs where a Java method overwrites its argument halfway down.

## 2. Null safety

In Java any reference might be null, and you find out with a `NullPointerException`. In Kotlin a type is non-null unless it ends with `?`.

```kotlin
val path: String? = item.path   // may be null
val count: Int? = counts.images // null means "we did not have access", not "zero items"
```

`counts.images` is `Int?` on purpose. Zero would mean the library was read and it was empty. Null means that collection was not queried. Showing a zero for a denied permission would be a lie.

Using a nullable value takes an explicit step:

- `count?.toString()` returns null if `count` is null, instead of throwing. The `?.` is a null-safe call.
- `count ?: 0` substitutes 0 only when you have decided that 0 is the right fallback. The `?:` is the Elvis operator, named because `?:` looks like Elvis's hair.
- `count!!` asserts non-null and throws if you were wrong. There is no `!!` in the app code. Tests use a real assertion instead.

`MediaItem.path` is `String?` because MediaStore sometimes has no relative path. `mimeType` is the same. `id` and `uri` are not nullable: a row without them is not a row we can show.

## 3. Data classes and sealed classes

`MediaItem` is a `data class`. The compiler writes `equals`, `hashCode`, `toString`, and `copy` from the properties in the constructor. In Java you would write those by hand, or have the IDE generate them, and then they would rot. A data class is the right tool when the type is a value: two items with the same fields are the same item.

```kotlin
val moved = item.copy(path = null) // a new item, original unchanged
```

`copy` is how you "change" an immutable value. You don't.

`PermissionState` is a `sealed class`. A sealed class is a closed hierarchy: the compiler knows every subclass, because they are all in the same module (here, the same file). A `when` that forgets one does not compile.

```kotlin
when (state) {
    PermissionState.Full -> ...
    PermissionState.Partial -> ...
    PermissionState.Denied -> ...
    PermissionState.PermanentlyDenied -> ...
}
```

In Java you would use an enum for this, and an enum would also be fine today, because none of the four states carries fields. A sealed class was chosen so that a later week can give `Partial` a field, for example the number of selected items, without forcing `Full` and `Denied` to have that field too. An enum constant cannot grow its own fields.

`data object Full` is a singleton with value equality. `PermissionState.Full == PermissionState.Full` is true. Think of it as an enum constant that is also a real class.

`LibraryAccess` groups the three states. `HearthDestination` is a `sealed interface` rather than a sealed class: same closed set, but the types share no constructor. `Loading` and `Onboarding` are objects. `Home` and `Permission` are data classes that carry the `LibraryAccess` they were decided from, so the screen does not take a nullable grant and then assert it.

`AudioScanRequest` is the same pattern for the audio button: ask for a permission, just remember the opt-in, send the user to Settings, or tell them that Android 11 needs the photo grant first.

## 4. Coroutines and Flow

A coroutine is a job you can suspend and cancel. `suspend fun` is a function that can pause without blocking the thread it was called on. From Java, the closest picture is a `Future` plus structured cancellation: when the parent job is cancelled, the children are cancelled too.

`MediaRepository.count` is `suspend` because it reads from disk via the content resolver. `MediaStoreMediaRepository` does this:

```kotlin
override suspend fun count(readable: ReadableMedia): MediaCounts = withContext(ioDispatcher) {
    assembleCounts(readable) { query -> contentResolver.count(query) }
}
```

`withContext(Dispatchers.IO)` moves the block onto the IO thread pool and comes back to the caller when it finishes. That is the coroutine version of `executor.submit(...)` followed by `future.get()`, except the caller does not block a thread while it waits, and if the ViewModel is thrown away the wait is cancelled.

Two Kotlin details in that file that map onto Java:

- `cursor.use { }` is try-with-resources. `use` is an extension function on `Closeable`. The cursor is closed on every exit, including a throw.
- `catch (cancelled: CancellationException) { throw cancelled }` is there because `CancellationException` extends `Exception`. A bare `catch (Exception)` would swallow cancellation and the load would look stuck. Catch the specific failure after rethrowing cancellation.

`HomeViewModel.state` is a `StateFlow<AppUiState>`. A `Flow` is a cold or hot stream of values. `StateFlow` is the hot kind that always has a current value, which is what a screen needs: after rotation the new screen collects the flow and immediately gets the latest counts, rather than waiting for a new event. In Java/Android terms it replaces `LiveData` for this job, with the difference that collection is a coroutine instead of an `Observer`.

The UI collects it with `collectAsStateWithLifecycle()`. Collection stops while the activity is stopped, the same reason you used a lifecycle-aware observer instead of a raw callback that outlived the screen.

`viewModelScope.launch { }` starts the coroutine in a scope that is cancelled in `onCleared()`. You do not keep your own `Executor` and remember to shut it down.

The counting rules themselves are not coroutines. `assembleCounts` is an ordinary function that takes another function:

```kotlin
internal fun assembleCounts(readable: ReadableMedia, fetch: (StoreQuery) -> Int): MediaCounts
```

`(StoreQuery) -> Int` is a function type. In Java you would declare a one-method interface, `Int fetch(StoreQuery query)`, and pass an anonymous class. The unit test passes a lambda that records the query and returns a canned number. The production code passes a lambda that runs the real query. That is why the tests do not need MediaStore.

## 5. `@Composable`

A `@Composable` function is a function the Compose compiler rewrites so it can be called again when state changes, skipping the parts whose inputs did not change. It is not a `View` subclass. There is no `findViewById`, and you do not hold child views in fields.

`OnboardingScreen` is a function from an event (`onContinue`) to UI. Calling it declares the UI. Compose calls it again when something it read has changed.

You cannot call a composable from an ordinary function. The compiler enforces that. You also should not do work with side effects directly in the body, such as launching a permission dialog during composition. A dialog during composition would reappear on every redraw. Dialogs and permission requests belong in click lambdas, which run when the user taps, not when the function is recomposed.

`rememberLauncherForActivityResult` in `HearthApp` is the exception that proves the rule. It has to be called during composition, on every pass, and not from inside `if` or a click listener. That is the same constraint as `registerForActivityResult` in an Activity: you register it in `onCreate`, unconditionally, and you only `launch` it later. Registering from a click crashes or drops the registration.

## 6. State hoisting

`HomeScreen` takes the grant, the counts, and a list of lambdas. It does not create a ViewModel, and it does not touch `MediaStore`. The state is "hoisted" to the caller.

That is the same split as a dumb View plus a controller, with one difference: the composable is both the view and a pure function of its parameters. The Compose test builds a `LibraryAccess` that says "partial" and checks that **Select more** and **Allow all** are on screen. No phone, no permission dialog, no content resolver.

`HearthApp` is the caller that owns the wiring. It reads the ViewModel, reads the device through `DevicePermissionReader`, and passes lambdas down. `OnboardingScreen` does not know that a permission system exists. Its only job is to show the promise and report that the user tapped Continue.

The destination decision is another pure function, `hearthDestination`, tested in `HearthDestinationTest`. Onboarding wins until the promise is accepted. After that, home is shown only when photos or videos can actually be read. A missing snapshot is a loading screen, not a permission prompt, so we never flash the system dialog while we are still reading the current grant.

## 7. ViewModel

`HomeViewModel` extends AndroidX `ViewModel`. The Activity is destroyed and recreated on rotation. The ViewModel is kept. That is why the counts survive turning the phone.

It is also why the ViewModel must not hold the Activity. `DevicePermissionReader` needs an Activity, because `shouldShowRequestPermissionRationale` is an Activity API. The reader is created in composition, next to the Activity, and the ViewModel only receives the `LibraryAccess` value the reader just produced. A value is safe to keep. An Activity is not. This is the same leak as storing an Activity in a retained fragment or a static field.

The screen observes `viewModel.state`. The ViewModel updates that state from `refresh`, `completeOnboarding`, and `setAudioScanningEnabled`. The UI does not write the counts itself.

`homeViewModelFactory` is the manual dependency wiring. There is no DI framework. The factory builds the repository and the preferences object once and hands them to the ViewModel. Tests skip the factory and pass fakes.

## 8. Why minSdk is 30

Android 11 (API 30) is the first release where scoped storage is mandatory and where `MediaStore.createTrashRequest` exists. Week 2 sends items to the system trash with that method. It shows a system dialog and does not need all-files access.

Setting the minimum to 30 means the code does not carry a second, legacy storage path for Android 10 and below (`requestLegacyExternalStorage`, raw file paths, `WRITE_EXTERNAL_STORAGE`). Every phone this app can be installed on already speaks MediaStore the way we query it: `IS_PENDING`, `RELATIVE_PATH`, and volume names.

The cost is that Android 10 devices cannot install it. That is accepted. Supporting them would mean a second permission model and a trash implementation we cannot share.

## 9. Why compileSdk and targetSdk are 36

`minSdk` is the oldest phone. `targetSdk` is the behavior contract: the system applies the rules of that API level to the app. `compileSdk` is the SDK the compiler checks against.

Play requires API 36 for new apps as of 31 August 2026, so `targetSdk` is 36. `compileSdk` is 36 as well, not 37. Compiling against a newer SDK would let a dependency call APIs this app has not decided to adopt, and the current Compose release (BOM `2026.11`, Compose 1.13) refuses to compile unless `compileSdk` is 37.1. We stay on Compose BOM `2026.06.01` (Compose 1.11), which still builds against API 36. The levels live in one object, `HearthSdk`, and the convention plugins apply them to every Android module so a new module cannot drift.

Targeting 36 also turns on the Android 16 photo-picker detail: if the user chooses a partial selection, photos this app itself created are preselected and the user can deselect them. We do not special-case that. We re-read the grant and we query MediaStore, which returns whatever the selection currently is.

## 10. How partial access works

From Android 13, photos, videos, and audio are three permissions. From Android 14, photos and videos can be granted in full or as a selection.

The system dialog offers Allow all, Select photos and videos, and Don't allow. If the user selects a subset:

- `READ_MEDIA_IMAGES` and `READ_MEDIA_VIDEO` are **not** granted
- `READ_MEDIA_VISUAL_USER_SELECTED` **is** granted
- MediaStore queries succeed but return only the selected rows

If the app does not declare `READ_MEDIA_VISUAL_USER_SELECTED`, Android runs a compatibility mode: the full permission looks granted for the session, and it is taken away later. The app then thinks it can see the whole library. Hearth declares the permission and treats "full permission missing, selected permission granted" as `PermissionState.Partial`. That check happens before the "permanently denied" check, because a partial grant also leaves the full permission denied and often reports no rationale, which is exactly the shape of a permanent denial.

`PermissionStatusResolver` is a pure function of the SDK integer, the set of granted permission names, whether rationale should be shown, and whether we have asked before. The tests in `PermissionStatusResolverTest` cover API 30–32, 33, and 34–36 without a device. `DevicePermissionReader` is the thin Android adapter: it asks `ContextCompat.checkSelfPermission` and passes the sets in. The decision does not live in the adapter.

"Never asked" and "permanently denied" both make `shouldShowRequestPermissionRationale` return false. The difference is whether we have shown the dialog before. That fact is stored in `SharedPreferences` under `asked_permissions`. The grant itself is not stored. Android can revoke a grant while the process is alive (permission reset, the user visiting Settings), so `HearthApp` calls `refresh` on every resume with a newly read snapshot. The official guidance says not to cache the grant. We don't.

What the user can do from a partial state:

- **Select more** requests the visual permissions again, including `READ_MEDIA_VISUAL_USER_SELECTED`. The system opens the selection UI. If only one of photos or videos is missing a full grant, only that type is requested, because the dialog shows the types you ask for.
- **Allow all** opens the app's Settings page. The three-option dialog does not reliably offer "Allow all" again after the user has chosen a selection. Settings does. The card says that in one sentence so the button is not a mystery.

A denial that still has rationale shows the request button again. A permanent denial shows **Open Settings** and does not launch a dialog that the system will immediately dismiss.

## 11. Why audio is a separate opt-in

`READ_MEDIA_AUDIO` is a different permission from photos, and Play's photo policy is specifically about photos and videos. The home screen's main request is photos and videos only. Audio stays as the line **Turn on audio scanning** until the user asks.

On Android 13 and higher that button requests `READ_MEDIA_AUDIO` and nothing else. `permissionsToRequest` has a test that the photo list does not contain the audio permission, and that the audio list contains only the audio permission.

On Android 11 and 12 there is no separate audio permission. `READ_EXTERNAL_STORAGE` covers photos, video, and audio together. Once that grant exists, the audio state is `Full`, but the counts still stay hidden until the user taps the button. The tap records the opt-in and does not raise a second dialog. If the storage grant is missing, the audio action is `NeedPhotoAccessFirst` rather than a storage request launched from the audio button, so the audio control cannot quietly grant photo access.

The count splits music from other audio with `MediaStore.Audio.Media.IS_MUSIC`. Ringtones, alarms, podcasts, and recordings land in "other". Pending downloads (`IS_PENDING = 1`) are excluded. Trashed files are excluded by MediaStore itself unless a query opts in, which we do not.

## 12. The repository, and what it does not do

`MediaRepository` is the boundary. `count` returns nullable ints for the collections the caller marked readable. `page` returns a `MediaPage` of `MediaItem`s, newest first, with `nextOffset` set when another page exists. The implementation asks MediaStore for one extra row so it can tell "exactly a full page" from "more remain" without a second round trip. Week 2's scan should walk `page`. It should not open its own cursor.

Paths are relative (`DCIM/Camera/IMG_0001.jpg`), from `RELATIVE_PATH` and `DISPLAY_NAME`. The deprecated `DATA` column is an absolute filesystem path, and chasing those paths is how apps end up requesting `MANAGE_EXTERNAL_STORAGE`. We do not.

`MediaItem.uri` is the content Uri as a string. The model module stays plain Kotlin. The media module is the only place that calls `ContentUris` and `ContentResolver`.

## 13. Network permissions, backup, and the modules

The app manifest removes `INTERNET`, `ACCESS_NETWORK_STATE`, `MANAGE_EXTERNAL_STORAGE`, and `MANAGE_MEDIA` with `tools:node="remove"`. A library merge cannot put them back without losing to the app manifest, and `checkDebugMergedManifest` fails if one survives anyway. ML Kit's Play Services artifacts add the network permissions; the bundled artifacts (`com.google.mlkit:face-detection`, `com.google.mlkit:image-labeling`) are the ones `:detection` is allowed to take later. The module is only the `MediaDetector` interface this week, so nothing pulls them in yet.

`allowBackup` is false, and both backup XML files exclude every domain. On Android 12 and higher a `dataExtractionRules` file replaces the `allowBackup` flag, so "false" alone is not enough. Local scan data should not appear in the user's Google Drive backup.

The module split is there so three people can work without rearranging packages:

- Dev 1 owns the app, the shared types, and MediaStore.
- Dev 2 owns Room (`:core:data`) and the review UI (`:feature:review`). Trash goes through `createTrashRequest`, not `MANAGE_MEDIA`.
- The ML specialist owns `:detection` and must not add a network client to reach a model.

Shared SDK settings live in the convention plugins under `build-logic`, not copied into each `build.gradle.kts`. Library versions live in `gradle/libs.versions.toml`.
