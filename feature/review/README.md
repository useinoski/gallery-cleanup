# :feature:review

Owned by developer 2.

The per-item review grid. The only public entry is:

```kotlin
@Composable
fun ReviewRoute(category: ScanCategory, onBack: () -> Unit)
```

`category` is [ScanCategory](../../core/data/src/main/kotlin/com/useinoski/gallerycleanup/data/ScanCategory.kt) for now. `ScanCategory.toReviewCategory()` is the one mapping to a title and the blur flag. When MediaCategory lands, change that function and the parameter. The grid does not switch on the enum.

The screen lists rows from `RoomScanResultStore.observePending`: still pending, scored, and not removed. Kept, trashed, failed, and removed rows are absent. Sensitive (nudity / NSFW) thumbnails start blurred. API 30, where `Modifier.blur` is a no-op, draws a heavily downscaled bitmap, or an opaque placeholder when the thumbnail did not load. Tap-to-reveal is remembered only while the screen is open.

Keep, trash, multi-select, and the locked folder are not in this module yet. Do not request `MANAGE_EXTERNAL_STORAGE` or `MANAGE_MEDIA`. Do not add `INTERNET`.
