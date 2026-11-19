# The scan database, for a Java developer

This note is about `:core:data`. Week 1's note is [week1-kotlin-notes.md](week1-kotlin-notes.md). Read that one first if `val`, null safety, and `suspend` are still new. This one stays on the Kotlin that the Room code actually uses.

Open these files beside it:

- `core/data/src/main/kotlin/com/useinoski/gallerycleanup/data/ScanResult.kt`
- `core/data/src/main/kotlin/com/useinoski/gallerycleanup/data/RoomScanResultStore.kt`
- `core/data/src/main/kotlin/com/useinoski/gallerycleanup/data/ScanResultEntity.kt`
- `core/data/src/main/kotlin/com/useinoski/gallerycleanup/data/ScanResultDao.kt`
- `core/data/src/main/kotlin/com/useinoski/gallerycleanup/data/GalleryDatabase.kt`

## 1. Data classes

`ScanResult` and `MediaKey` are data classes. The compiler writes `equals`, `hashCode`, `toString`, and `copy` from the constructor properties. In Java you would write those by hand, or let the IDE generate them, and then they would drift.

```kotlin
data class MediaKey(
    val mediaStoreId: Long,
    val mediaType: MediaType,
)
```

```java
public final class MediaKey {
    private final long mediaStoreId;
    private final MediaType mediaType;

    public MediaKey(long mediaStoreId, MediaType mediaType) {
        this.mediaStoreId = mediaStoreId;
        this.mediaType = mediaType;
    }

    // getters, equals, hashCode, toString — all written by you
}
```

`equals` is why `keptKeys()` can return a `Set<MediaKey>`. Image 5 and video 5 are different keys because `mediaType` is part of the value. A hand-written Java `equals` that compared only the id would hide one of those files from the scanner.

`copy` is how you change one field of an immutable value. The original is left alone:

```kotlin
val updated = result.copy(confidence = 0.9f, category = ScanCategory.Sensitive)
```

Java has no equivalent. You call the constructor again and repeat every other argument.

A constructor parameter can have a default. `reviewStatus` defaults to `Pending`, so the scan worker does not pass it.

```kotlin
ScanResult(
    mediaStoreId = item.id,
    contentUri = item.uri,
    mediaType = item.type,
    category = chosen,
    confidence = score,
    modelVersion = "screenshot-rules-1",
    scannedAtEpochMillis = System.currentTimeMillis(),
)
```

In Java that is a second constructor that fills the missing argument in, and every call site has to live with the overload you happened to write. The names at the call site (`mediaStoreId = item.id`) are named arguments. Java does not have them. They matter once a constructor has many parameters of the same type: you cannot swap the score and the timestamp by accident and still compile, because the names would not match the types, and a reader can see which number is which.

`require` in the `init` block is the constructor check. It throws `IllegalArgumentException` with the message you give it. Same idea as `if (id <= 0) throw new IllegalArgumentException(...)` at the top of a Java constructor. The block runs for every constructor path, including `copy`.

`val key get() = MediaKey(...)` is a property with a getter and no field. Java would be `public MediaKey getKey()`. Call sites write `result.key`, not `result.getKey()`.

## 2. Enums

```kotlin
enum class ReviewStatus {
    Pending,
    Kept,
    Trashed,
}
```

This is a Java enum. The differences that matter here:

- Each constant is an object. `ReviewStatus.Pending` has the type `ReviewStatus`. You do not need a separate class.
- Kotlin enums can have functions and properties, same as Java enums can have methods.
- `ScanCategory.entries` is the list of constants. Java's `values()` still exists, but it returns a new array every call. `entries` is the list you should loop.

Room has built-in enum support. It stores the constant's name as text: `"Pending"`, not `0`. That is the same choice you would make in Java by writing `status.name()` into a `TEXT` column instead of `status.ordinal()`. An ordinal breaks when somebody inserts a constant at the top of the list. A renamed constant is still a migration, because the text in the old rows would no longer match. The names in this module are part of the schema.

## 3. `suspend` functions

```kotlin
suspend fun upsert(results: List<ScanResult>)
```

A `suspend` function can pause without blocking the thread it was called on. The closest Java picture is a `ListenableFuture<Void>` plus structured cancellation: when the parent job is cancelled, this work is cancelled too.

Room implements the function. Calling `upsert` from the main thread is allowed, because Room moves the SQL onto its own executor and resumes the caller when the write finishes. You do not create an `Executor`, and you do not call `future.get()` on the main thread. `future.get()` would freeze the screen. A suspend call does not.

You can only call a suspend function from another suspend function, or from a coroutine builder such as `viewModelScope.launch { }`. A plain Java-style method that is not `suspend` cannot call `upsert` directly. From a test, `runBlocking { store.upsert(...) }` starts a coroutine and waits, which is fine because a test thread is allowed to wait.

`CancellationException` is still special, as in the week 1 note. Room's transaction helper rethrows it and rolls the transaction back. Do not catch `Exception` around `upsert` without rethrowing cancellation first.

## 4. Flow

```kotlin
fun observePending(category: ScanCategory): Flow<List<ScanResult>>
```

`Flow` is a stream of values. This one is cold: constructing it does not query SQLite. The query starts when someone collects the flow, and it stops when that collection is cancelled. Cancel the collector (the screen's coroutine is cancelled in `onCleared`, or when the activity stops if you collect with the lifecycle) and Room stops delivering lists.

In Java and older Android you would return `LiveData<List<ScanResult>>`, or register a `ContentObserver`, or hand the caller a listener interface. `LiveData` is hot and always has a value. This `Flow` does not run until it is collected. Room's implementation queries once immediately, then again whenever the table changes. The review screen will collect it the same way the home screen collects `StateFlow`: `collectAsStateWithLifecycle()`.

A one-shot read, when you do not want to stay subscribed, is:

```kotlin
val counts = store.observePendingCounts().first()
```

`first()` is a suspend function. It collects one value and unsubscribes. That is the Flow version of `liveData.getValue()`, except it waits for the first emission instead of returning null when nothing has been posted yet.

`map` on a Flow transforms each emission. The store uses it to turn Room's entity rows into `ScanResult` values, and to fill in a zero for every category the `GROUP BY` query did not return. `map` on a `List` is the same idea for a list in memory. The two functions have the same name and different receivers, the way `Collections.emptyList()` and an instance method can share a name in Java.

## 5. Interfaces, and default methods

`RoomScanResultStore` is a class, not an interface. The shared interface belongs in `:core:model`, as `ScanStore`. That type is on an open pull request and is not on `main` yet, so this module cannot implement it today. Publishing a second interface here would give the scan worker two contracts. Callers ask `roomScanResultStore(context)` for the Room class. `:app` does not call it yet. When this class implements `ScanStore` and uses `model.MediaKey`, the `:app` owner points `ScanRuntime.store()` at it. Call sites that only need the scan contract will then depend on the interface, the same way a Java method can take the interface and ignore which class was constructed.

A Kotlin interface can have default method bodies. That is the same feature as a Java 8 `default` method. `ScanStore` does not use one. This is what the syntax looks like, so it is familiar when it shows up:

```kotlin
interface ScanStore {
    suspend fun markRemoved(keys: List<MediaKey>)

    suspend fun markRemoved(key: MediaKey) {
        markRemoved(listOf(key))
    }
}
```

```java
public interface ScanStore {
    void markRemoved(List<MediaKey> keys);

    default void markRemoved(MediaKey key) {
        markRemoved(List.of(key));
    }
}
```

`listOf(key)` is the Kotlin form of `List.of(key)`. A default method is compiled into the interface, so every implementation picks it up without writing it again. Same rule as Java. It is the right tool when every implementation would write the same one-line forwarding method. It is the wrong tool when each implementation has to do real work, which is why `upsert` and `markKept` are ordinary methods on the Room class.

The function type from week 1 (`(StoreQuery) -> Int`) is the other way to pass behavior. A default method is behavior that is the same for every implementation. A function parameter is behavior the caller supplies.

## 6. Room annotations in Kotlin

Room is an annotation processor that generates the SQL implementation. You write the table and the queries. Room writes the `SQLiteStatement` code.

The entity is an ordinary data class plus annotations:

```kotlin
@Entity(
    tableName = "scan_results",
    primaryKeys = ["media_store_id", "media_type"],
)
data class ScanResultEntity(
    @ColumnInfo(name = "media_store_id")
    val mediaStoreId: Long,
    // ...
)
```

`@Entity` is the `CREATE TABLE`. `primaryKeys` is the primary key. Two columns, because the id alone is not unique. `@ColumnInfo` is the SQL name. Kotlin property names stay camel case in the code, and the column names stay snake case in the database. In Java you would put the same annotations on fields or on getters. Room accepts either. On a Kotlin data class the annotation goes on the constructor property, which is both the field and the getter.

`@Dao` marks the query interface. `@Query` is a SQL string, checked against the entity when the project compiles. A typo in a column name fails the build, not the first run on a phone. `@Upsert` generates `INSERT ... ON CONFLICT DO UPDATE`, matched on the primary key. That is the SQL "insert or replace" that does not delete the row first. `@Insert(onConflict = REPLACE)` does delete and reinsert, which is a worse fit once foreign keys exist. We do not have foreign keys yet. Upsert is still the operation `RoomScanResultStore.upsert` is named for.

`suspend` on a DAO function makes the generated code run off the main thread. `Flow` on a DAO function makes the generated code register a table observer and re-query. You do not write that observer.

`@Database(entities = [...], version = 1, exportSchema = true)` is the database class. It is abstract. Room generates the subclass that creates the SQLite file `scan-results.db`. `exportSchema = true` asks Room to also write a JSON description of version 1. The file is checked in under `core/data/schemas/`. A future migration test opens that JSON and checks that the migration turns a version 1 database into the new shape. Without the file, that test has nothing to start from.

`Room.databaseBuilder(...).build()` does not run queries. The file is opened on first use. `GalleryDatabase.get` keeps one instance per process. Two callers of `roomScanResultStore` share that connection. The double-checked `instance` read is the same pattern as a Java static holder with `volatile` and a `synchronized` block. Kotlin's `synchronized(this)` is `synchronized` on the companion object.

`RoomScanResultStore`, not the DAO, is what the review screen calls. The DAO speaks entities and SQL. The store speaks `ScanResult` and `MediaKey`, skips kept rows inside a transaction, and chunks long `IN (...)` lists so SQLite's placeholder limit cannot reject a 2000-item trash batch. The scan worker's calls (`upsertBatch`, `saveCheckpoint`, `saveRun`) arrive when this class implements `ScanStore`.

## 7. KSP, and what an annotation processor used to be

An annotation processor reads annotations and generates source code. Room's processor reads `@Entity`, `@Dao`, and `@Database` and generates the SQLite implementation described above.

The old Android way is KAPT (`kotlin-kapt`). KAPT generates Java stubs of your Kotlin classes, then runs a `javac` annotation processor (`annotationProcessor "androidx.room:room-compiler"`) against those stubs. The stubs are slow to build, and they throw away some Kotlin-only information. You pay for a second compiler.

KSP (Kotlin Symbol Processing) skips the stubs. The processor reads the Kotlin compiler's own symbols. In this module the line is:

```kotlin
ksp(libs.androidx.room.compiler)
```

and the plugin is `com.google.devtools.ksp`, not `kotlin-kapt`. There is no `kapt` and no `annotationProcessor` configuration. Room's processor is a KSP processor.

KSP is a compiler plugin, so its version has to match the Kotlin version. This project uses Kotlin 2.2.21, and the KSP release built for that compiler is `2.2.21-2.0.5`. A KSP version from a different Kotlin line will not apply.

`:core:data` applies those plugins through the convention plugin `gallery.room`, which lives next to the Kotlin plugin in `build-logic`. Applying KSP from the module's own `plugins` block would load a second copy of the Kotlin plugin, and Gradle rejects that. The version numbers still come from `gradle/libs.versions.toml`.

The Room Gradle plugin (`androidx.room`) is separate from the processor. It tells the processor where to write the schema JSON (`room { schemaDirectory(...) }` in `core/data/build.gradle.kts`) and it makes that directory a real Gradle output, so the export is cached and repeatable. The annotation still has to say `exportSchema = true`. The plugin chooses the directory. The annotation chooses whether a file is written.

Versions live in `gradle/libs.versions.toml`. The module does not hard-code `2.8.5`. `room-ktx` is not a dependency: since Room 2.7 its coroutine helpers, including `withTransaction`, live in `room-runtime`.
