# :feature:review

Owned by developer 2.

The per-item review grid. The only public entry is:

```kotlin
@Composable
fun ReviewRoute(category: ScanCategory, onBack: () -> Unit)
```

`category` is [ScanCategory](../../core/data/src/main/kotlin/com/useinoski/gallerycleanup/data/ScanCategory.kt) for now. `ScanCategory.toReviewCategory()` is the one mapping to a title and the blur flag. When MediaCategory lands, change that function and the parameter. The grid does not switch on the enum.

The screen lists rows from `RoomScanResultStore.observePending`: still pending, scored, and not removed. Kept, trashed, failed, and removed rows are absent. Sensitive (nudity / NSFW) thumbnails start blurred. API 30, where `Modifier.blur` is a no-op, draws a heavily downscaled bitmap, or an opaque placeholder when the thumbnail did not load. Tap-to-reveal is remembered only while the screen is open. A selected sensitive cell stays blurred.

Long-press starts multi-select. Tap then toggles a cell. The top bar shows the count, Select all for this category, Clear, and Keep. Back clears the selection before it leaves the screen. The selected keys are stored in the ViewModel `SavedStateHandle` (`Image:5` style strings), so a rotation keeps them. Keep calls `RoomScanResultStore.markKept` once for the whole selection. Those rows leave the grid through `observePending`. A snackbar says "N kept" and Undo calls `markPending`. A later scan upsert does not turn a kept row back into pending.

`ReviewRoute` sets `FLAG_SECURE` on the host window while it is composed and clears the flag on dispose. Home is screenshot-able.

Trash and the locked folder are not in this module. Do not request `MANAGE_EXTERNAL_STORAGE` or `MANAGE_MEDIA`. Do not add `INTERNET`. The route still takes `ScanCategory`. Switching it to `MediaCategory` is a later change.
