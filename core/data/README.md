# :core:data

Owned by developer 2.

Room database of scan results. One row per library file. The row holds the MediaStore id, the content URI, the media type, one category, a confidence score, the model or rule-set version, the scan time, and whether the user has kept it, trashed it, or not decided. Image bytes are not stored.

Nothing here opens a socket. Do not add `INTERNET`, a sync adapter, or a backup agent. The app manifest already sets `allowBackup="false"` and excludes the database domain. Leave that alone.

The shared scan contract is `ScanStore` in `:core:model` (PR #2, not merged). This module does not declare a second one. `RoomScanResultStore` is the Room implementation side. It will implement `ScanStore` once that type is on `main`. Until then the scan worker keeps `InMemoryScanStore` and does not bind this class.

What `RoomScanResultStore` stores today is the review row: category, score, and keep / trash. The review screen calls it. Callers do not touch `ScanResultDao` or `GalleryDatabase`.

```kotlin
fun roomScanResultStore(context: Context): RoomScanResultStore
```

Nothing in `:app` depends on this module yet. There is no Hilt. After `RoomScanResultStore` implements `ScanStore` and uses `model.MediaKey`, the `:app` owner swaps `ScanRuntime.store()` to this factory.

## Table `scan_results` (version 1)

| Column | Meaning |
| --- | --- |
| `media_store_id` + `media_type` | Primary key. Ids are only unique inside one MediaStore collection, so image 5 and video 5 are two rows. |
| `content_uri` | Unique. The string `MediaItem.uri`, used later by `createTrashRequest`. |
| `category` | One of `Sensitive`, `Immodest`, `Screenshot`, `People`, `Video`, `Audio`, `AlcoholGambling`, `Unflagged`. |
| `confidence` | Score for that category. Probabilities are expected between 0 and 1. |
| `model_version` | Model or rule-set name, for example `nsfw-1` or `screenshot-rules-1`. Use `none` when nothing classified the file. |
| `scanned_at_epoch_millis` | When the scan wrote the row, not the file's date. |
| `review_status` | `Pending`, `Kept`, or `Trashed`. |

Schema JSON is checked in under `schemas/` because `exportSchema = true`. A later migration test should read those files. Do not enable destructive migration. Kept rows have to survive a schema change.

`Video` and `Audio` as categories are review buckets. `MediaType` is the file type. A video of a person can be stored as category `People`. The caller chooses one category before `upsert`.

Memes and chat-folder images are not their own category yet. File them under `Screenshot` until a category is added.

## Scan worker

Do not point the worker at `RoomScanResultStore`. Its interface is `ScanStore`: upsert `MediaItem`s, mark rows MediaStore no longer returns, save one checkpoint, save the run, and observe progress. The KDoc on that interface is the spec, including the suggested `media_item`, `scan_checkpoint`, `scan_run`, and `scan_type_progress` tables. `InMemoryScanStore` is the stand-in until this module implements the interface.

`data.MediaKey` (`mediaStoreId`, `mediaType`) is not `model.MediaKey` (`type`, `id`). The Room code will switch to the model key when it implements `ScanStore`.

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
