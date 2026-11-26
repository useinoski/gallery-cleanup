# :core:data

Owned by developer 2.

Room database of scan results. One row per library file. The row holds the MediaStore id, the content URI, the media type, one category, a confidence score, the model or rule-set version, the scan time, whether analysis scored the file or failed, and whether the user has kept it, trashed it, or not decided. Image bytes are not stored.

Nothing here opens a socket. Do not add `INTERNET`, a sync adapter, or a backup agent. The app manifest already sets `allowBackup="false"` and excludes the database domain. Leave that alone.

`RoomScanResultStore` implements `ScanStore` from `:core:model`. It also keeps the review rows (category, score, keep / trash). This module does not declare a second scan interface. Callers do not touch `ScanResultDao`, `ScanStoreDao`, or `GalleryDatabase`.

```kotlin
fun roomScanResultStore(context: Context): RoomScanResultStore
```

`:app` depends on this module. There is no Hilt. `ScanRuntime.store(context)` returns `roomScanResultStore(context)`, and that object is the `ScanStore` the worker and the home screen share.

Keys are `model.MediaKey` (`type`, then `id`). There is no `data.MediaKey`. The review table still uses the columns `media_store_id` and `media_type`.

## Tables

Version 1 is `scan_results` only. Version 2 adds the scan tables and does not rewrite `scan_results`. `MIGRATION_1_2` is that path. Version 3 adds `scan_results.analysis_outcome` and `failure_count`. `MIGRATION_2_3` keeps every existing row. Old pending `Unflagged` rows whose model version is `none` become `Failed`, because version 2 used that pair both for a clean photo and for a decode that threw. Kept and trashed rows stay `Scored`. Schema JSON for all three versions is checked in under `schemas/` because `exportSchema = true`. Do not enable destructive migration. Kept rows have to survive a schema change.

| Table | Role |
| --- | --- |
| `scan_results` | One review row per file: category, score, keep / trash, and whether analysis scored or failed. |
| `media_item` | One `MediaItem` per file. `removed` is 1 when MediaStore no longer returns it. |
| `scan_checkpoint` | At most one row (`singleton` = 1). The keyset cursor for the pass in progress. |
| `scan_run` | At most one row. Phase, version, generation, detail. A missing row is `ScanRun.IDLE`. |
| `scan_type_progress` | One row per collection in the current run. `position` is the list order the worker wrote. |

`scan_type_progress.position` is not in the interface's suggested `CREATE TABLE`. SQLite will not promise insert order on a later read, and `ScanRun.byType` is ordered. The suggested `CHECK (singleton = 1)` is also absent: a Room entity cannot declare it. The store always writes primary key 1.

## Table `scan_results`

| Column | Meaning |
| --- | --- |
| `media_store_id` + `media_type` | Primary key. Ids are only unique inside one MediaStore collection, so image 5 and video 5 are two rows. |
| `content_uri` | Unique. The string `MediaItem.uri`, used later by `createTrashRequest`. |
| `category` | One of `Sensitive`, `Immodest`, `Screenshot`, `People`, `Video`, `Audio`, `AlcoholGambling`, `Unflagged`. |
| `confidence` | Score for that category. Probabilities are expected between 0 and 1. |
| `model_version` | Model or rule-set name, for example `nsfw-1` or `screenshot-rules-1`. A detector identity is stored as `detectorId\|modelVersion`. There is no separate detector-id column. Use `none` when nothing classified the file, and as the placeholder on a failed row. |
| `scanned_at_epoch_millis` | When the scan wrote the row, not the file's date. |
| `review_status` | `Pending`, `Kept`, or `Trashed`. |
| `analysis_outcome` | `Scored` or `Failed`. Added in version 3. `Failed` is not a clean photo and is not counted as `Unflagged`. |
| `failure_count` | Failures since the last score. `recordFailure` adds one. A scored `upsert` sets it back to 0. The rescan query does not read it. |

`Video` and `Audio` as categories are review buckets. `MediaType` is the file type. A video of a person can be stored as category `People`. The caller chooses one category before `upsert`.

Memes and chat-folder images are not their own category yet. File them under `Screenshot` until a category is added.

## Scan worker

`RoomScanResultStore` is a `ScanStore`: `upsertBatch`, `markRemoved`, `presentKeys`, one checkpoint, one run, and `observeProgress`. The KDoc on `ScanStore` is the spec. `:app` binds `ScanRuntime.store(context)` to this implementation.

`markRemoved` flags a `media_item` row. It does not delete the file, and it does not delete the review row. `remove` is the review method, and it runs only after `createTrashRequest` succeeds.

## What the review screen calls

Scores are one category per file. A reasonable order when several detectors match, strongest first: `Sensitive`, `Immodest`, `AlcoholGambling`, `People`, `Screenshot`, `Video`, `Audio`, otherwise `Unflagged`. The database does not enforce it. Memes can sit in `Screenshot` until they have a category.

```kotlin
val store = roomScanResultStore(context)
store.observePending(ScanCategory.Sensitive)   // Flow, newest scan first
store.observePendingCounts()                   // Flow, every category, zeros included
store.markKept(keys)
store.markTrashed(keys)   // hide while the system dialog is up
store.remove(keys)        // only after createTrashRequest succeeds
store.markPending(keys)   // the user cancelled the dialog
store.resetReviewed()     // settings: kept rows become pending again

// Decode or the detector threw. Not an Unflagged photo.
store.recordFailure(key = MediaKey(item.type, item.id), contentUri = item.uri)

// Rows to run again: failures, plus scored rows whose model_version is not
// one of these. Kept rows and removed media items are left out.
val again = store.keysNeedingRescan(
    currentModelVersions = setOf(
        "mlkit-face-count|face-v2",
        "marqo-nsfw-384|marqo-v1",
        "video-manual-review|v1",
        "none",
    ),
)
```

`upsert` stores `Pending` and `Scored` for a new row and for a row that was already pending. It ignores `ScanResult.reviewStatus` and `ScanResult.analysisOutcome`. A row that is already `Kept` or `Trashed` keeps that decision; the score, model version, and outcome are still updated, and `failure_count` goes back to 0. `keptKeys()` is the skip set for a later detector pass. It does not include pending or trashed ids. `recordFailure` is the only writer of `Failed`. It leaves a kept row alone, and it leaves a trashed row trashed while counting the failure.

`remove` deletes the row. It does not delete the photo. `resetReviewed` does not touch trashed rows.

`observePending` and `observePendingCounts()` skip kept, trashed, and failed rows. They also skip a row whose `media_item` is marked removed, and they include that row again when a later `upsertBatch` clears the flag. A review row with no `media_item` row is included. `CategoryCounts.flagged` also skips `Unflagged`.

`keysNeedingRescan` returns failed rows and scored rows whose `model_version` is not in the set you pass. Pass every version the current pipeline still writes, including `none` if a clean photo should stay put. Omit `none`, or pass the new detector identity, when a model change should score old pending rows again. Kept rows, trashed rows, and removed media items are not returned.

File dates are not in this table. Sorting screenshots oldest-first has to use `MediaItem.dateAdded` from `:core:media`.

A walk through the Kotlin, for someone coming from Java, is in [docs/core-data-kotlin-notes.md](../../docs/core-data-kotlin-notes.md).
