# :core:data

Owned by developer 2.

Room database of scan results. One row per library file. The row holds the MediaStore id, the content URI, the media type, one category, a confidence score, the model or rule-set version, the scan time, and whether the user has kept it, trashed it, or not decided. Image bytes are not stored.

Nothing here opens a socket. Do not add `INTERNET`, a sync adapter, or a backup agent. The app manifest already sets `allowBackup="false"` and excludes the database domain. Leave that alone.

`RoomScanResultStore` implements `ScanStore` from `:core:model`. It also keeps the review rows (category, score, keep / trash). This module does not declare a second scan interface. Callers do not touch `ScanResultDao`, `ScanStoreDao`, or `GalleryDatabase`.

```kotlin
fun roomScanResultStore(context: Context): RoomScanResultStore
```

Nothing in `:app` depends on this module yet. There is no Hilt. `ScanRuntime.store()` still returns `InMemoryScanStore`. After this lands, the `:app` owner can swap that binding to `roomScanResultStore(context)`, because the returned object is a `ScanStore`.

Keys are `model.MediaKey` (`type`, then `id`). There is no `data.MediaKey`. The review table still uses the columns `media_store_id` and `media_type`.

## Tables

Version 1 is `scan_results` only. Version 2 adds the scan tables and does not rewrite `scan_results`. `MIGRATION_1_2` is the path. Schema JSON for both versions is checked in under `schemas/` because `exportSchema = true`. Do not enable destructive migration. Kept rows have to survive a schema change.

| Table | Role |
| --- | --- |
| `scan_results` | One review row per file: category, score, keep / trash. Unchanged since version 1. |
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
| `model_version` | Model or rule-set name, for example `nsfw-1` or `screenshot-rules-1`. Use `none` when nothing classified the file. |
| `scanned_at_epoch_millis` | When the scan wrote the row, not the file's date. |
| `review_status` | `Pending`, `Kept`, or `Trashed`. |

`Video` and `Audio` as categories are review buckets. `MediaType` is the file type. A video of a person can be stored as category `People`. The caller chooses one category before `upsert`.

Memes and chat-folder images are not their own category yet. File them under `Screenshot` until a category is added.

## Scan worker

`RoomScanResultStore` is a `ScanStore`: `upsertBatch`, `markRemoved`, `presentKeys`, one checkpoint, one run, and `observeProgress`. The KDoc on `ScanStore` is the spec. `:app` has not switched `ScanRuntime.store()` over yet, so a running build still uses `InMemoryScanStore`.

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
```

`upsert` stores `Pending` and ignores `ScanResult.reviewStatus`. It does not modify a row that is already `Kept`. `keptKeys()` is the skip set for a later detector pass. It does not include pending or trashed ids.

`remove` deletes the row. It does not delete the photo. `resetReviewed` does not touch trashed rows.

`observePendingCounts()` skips kept and trashed rows. `CategoryCounts.flagged` also skips `Unflagged`.

File dates are not in this table. Sorting screenshots oldest-first has to use `MediaItem.dateAdded` from `:core:media`.

A walk through the Kotlin, for someone coming from Java, is in [docs/core-data-kotlin-notes.md](../../docs/core-data-kotlin-notes.md).
