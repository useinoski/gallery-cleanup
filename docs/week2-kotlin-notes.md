# Week 2, for a Java developer

Week 1's note is still the right place for `val`, null safety, sealed classes, and Compose. This note is the four Kotlin ideas the scan adds. The code to open beside it:

- `core/model/src/main/kotlin/com/useinoski/gallerycleanup/model/ScanStore.kt`
- `core/media/src/main/kotlin/com/useinoski/gallerycleanup/media/LibraryScanner.kt`
- `app/src/main/java/com/useinoski/gallerycleanup/scan/LibraryScanWorker.kt`
- `app/src/main/java/com/useinoski/gallerycleanup/ui/home/HomeViewModel.kt`

## 1. A Kotlin interface is a Java interface, with less noise

`ScanStore` is an `interface`. From Java that is a Java interface. Implement it with `class RoomScanStore : ScanStore`, which is `class RoomScanStore implements ScanStore`. There is no `implements` keyword and no `public abstract` on the methods: a method on an interface is already public and open.

```kotlin
interface ScanStore {
    suspend fun upsertBatch(items: List<MediaItem>)
    fun observeProgress(): Flow<ScanProgress>
}
```

Two differences from the interfaces you are used to:

- A `suspend` method is not a normal Java method. The compiler adds a hidden `Continuation` parameter. You call it from another `suspend` function, or from `viewModelScope.launch { }`. You do not call it from a Java click listener. Room generates the `Continuation` for a `suspend` DAO function, which is why Dev 2's implementation can be a DAO that still satisfies this interface.
- `fun interface` is the one-method case, the same job as a Java SAM type (`Runnable`, `Consumer`). `ScanBatchObserver` is one. You can pass a lambda instead of a class. `ScanBatchObserver.NONE` is the lambda that does nothing, which is what the app binds until a detector exists. A Java functional interface would have been an anonymous class.

The interface has no Android types in it. `MediaItem.uri` is a `String`. That is why `:core:model` does not depend on the Android SDK, and why a JVM test can construct a store without a phone.

## 2. Data classes

`ScanCheckpoint`, `ScanRun`, and `TypeProgress` are `data class`es. The compiler writes `equals`, `hashCode`, `toString`, and `copy`. Two checkpoints with the same fields are equal, which is what you want for a value you save and read back.

```kotlin
val next = checkpoint.copy(scanned = checkpoint.scanned + page.rows.size)
```

`copy` returns a new object. The original is unchanged. That replaces the Java pattern of a mutable bean with setters, where a caller keeps a reference and changes a field under you. `InMemoryScanStore.saveRun` also copies the progress list (`run.byType.toList()`) so a caller who keeps the `MutableList` and edits it does not edit the row the store is holding.

An `init` block on the data class is a constructor check. A negative id throws `IllegalArgumentException` before it can be saved. Room should trust the same checks: they live on the type, not only in the in-memory stand-in.

`ScanPhase` is an enum. Persist `ScanPhase.name` (`"Running"`), not `ordinal`. Inserting a constant in the middle would otherwise renumber every saved row.

## 3. Coroutines inside a CoroutineWorker

`LibraryScanWorker` extends `CoroutineWorker`. `doWork` is a `suspend` function. WorkManager starts it on a background coroutine and cancels that coroutine when the user cancels the unique work. You do not build an `Executor` for the scan, and you do not poll a `Future`.

```kotlin
override suspend fun doWork(): Result {
    return try {
        scanner.scan()
        Result.success()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        Result.failure()
    }
}
```

`CancellationException` is the one to rethrow. It extends `Exception`, so a bare `catch (Exception)` would swallow the cancel, the worker would return `success` or `failure`, and WorkManager would think the job had finished on its own. The same rule was in the week 1 note for the home screen load. The scanner uses it too: on cancel it writes `ScanPhase.Cancelled` inside `withContext(NonCancellable)` and then rethrows.

`withContext(NonCancellable)` is the part that has no Java twin. A cancelled coroutine is not allowed to suspend, and `saveRun` suspends. `NonCancellable` is a scope that still lets that one write finish. The checkpoint from the last batch is already stored by then. The phase write is the extra line the screen needs so the button does not stay on Cancel after the job is gone.

`suspend fun scan()` on `LibraryScanner` can call `repository.scanPage` and `store.upsertBatch` one after another and they read like blocking code. Each one leaves the thread while MediaStore or the store is working. `coroutineContext.ensureActive()` between pages is the cooperative check: if the worker was cancelled during the previous page, the next page does not start.

The worker is scheduled once:

```kotlin
workManager.enqueueUniqueWork(
    UNIQUE_LIBRARY_SCAN,
    ExistingWorkPolicy.KEEP,
    OneTimeWorkRequestBuilder<LibraryScanWorker>().build(),
)
```

`KEEP` means a second Start, while the first scan is still queued or running, is dropped. That is the "only one scan" rule. It is not a lock you take by hand.

The work is not expedited and it never calls `setForeground`. A long scan that promotes itself to a foreground service would need an Android 14 service type and a Play review. Stopping is cheap: the checkpoint is the last row already saved, and the next Start continues from there.

## 4. Flow is how the screen hears about progress

`ScanStore.observeProgress()` returns a `Flow<ScanProgress>`. A `Flow` is a stream you collect, not a one-shot callback and not a list you poll. `StateFlow`, which the in-memory store uses, always has a current value. A new collector gets that value immediately, then later values. The home screen survives rotation because the ViewModel collects the flow and keeps the latest `ScanProgress` in its own state:

```kotlin
init {
    viewModelScope.launch {
        scans.progress.collect { progress ->
            _state.update { current -> current.copy(scan = progress) }
        }
    }
}
```

`viewModelScope` is cancelled in `onCleared()`, which cancels this `collect`. You do not unregister a listener in `onDestroy`.

The contract is picky about what emits. `saveRun` emits. Upserts, removals, and checkpoint writes do not. The screen cares about the phase and the counts, and those live on the run. A Room implementation can be a cold `Flow` that queries the run row and re-queries when that row changes. A new collector must still see the current row straight away, which is what Room's `Flow` query does. Identical snapshots may be skipped. `ScanProgress` is a data class, so "identical" is structural equality, the thing the compiler wrote for you.

`ScanProgress.scanned` and `total` are sums, not stored fields. The screen must not divide by `total` when it is zero. An empty library and the moment before the first count are both zero, and they are not the same event as a failure. The phase says which one it is.
