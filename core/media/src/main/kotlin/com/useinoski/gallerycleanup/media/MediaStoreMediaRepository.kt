package com.useinoski.gallerycleanup.media

import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import com.useinoski.gallerycleanup.model.MediaItem
import com.useinoski.gallerycleanup.model.MediaType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads counts and pages from MediaStore on a background dispatcher.
 *
 * `withContext(Dispatchers.IO)` is the coroutine equivalent of handing work to
 * an executor. The difference from a Java `Executor.submit` is that cancelling
 * the caller, for example when the ViewModel is cleared, cancels this work too.
 * A query that is already inside the system call still finishes, but its result
 * is dropped instead of being delivered to a dead screen.
 *
 * `cursor.use { }` is try-with-resources. The cursor is closed on every exit,
 * including when the block throws.
 */
class MediaStoreMediaRepository(
    private val contentResolver: ContentResolver,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : MediaRepository {

    override suspend fun count(readable: ReadableMedia): MediaCounts = withContext(ioDispatcher) {
        assembleCounts(readable) { query -> contentResolver.count(query) }
    }

    override suspend fun page(request: MediaPageRequest): MediaPage {
        require(request.limit > 0) { "Page limit must be positive." }
        require(request.offset >= 0) { "Page offset cannot be negative." }
        return withContext(ioDispatcher) {
            val fetched = contentResolver.load(pageQuery(request))
            pageOf(fetched, request.offset, request.limit)
        }
    }

    override suspend fun scanPage(request: ScanPageRequest): ScanPage = withContext(ioDispatcher) {
        val fetched = contentResolver.loadScan(scanPageQuery(request))
        scanPageOf(fetched, request.limit)
    }

    override suspend fun countScan(request: ScanCountRequest): Int = withContext(ioDispatcher) {
        contentResolver.count(scanCountQuery(request))
    }

    override suspend fun pageIds(request: IdPageRequest): IdPage = withContext(ioDispatcher) {
        val fetched = contentResolver.loadIds(idPageQuery(request))
        idPageOf(fetched, request.limit)
    }
}

private val columns = arrayOf(
    MediaStore.MediaColumns._ID,
    MediaStore.MediaColumns.DATE_ADDED,
    MediaStore.MediaColumns.SIZE,
    MediaStore.MediaColumns.MIME_TYPE,
    MediaStore.MediaColumns.RELATIVE_PATH,
    MediaStore.MediaColumns.DISPLAY_NAME,
)

private val scanColumns = arrayOf(
    MediaStore.MediaColumns._ID,
    MediaStore.MediaColumns.DATE_ADDED,
    MediaStore.MediaColumns.DATE_MODIFIED,
    MediaStore.MediaColumns.SIZE,
    MediaStore.MediaColumns.MIME_TYPE,
    MediaStore.MediaColumns.RELATIVE_PATH,
    MediaStore.MediaColumns.DISPLAY_NAME,
    MediaStore.MediaColumns.GENERATION_MODIFIED,
)

private fun StoreQuery.collectionUri(): Uri = when (type) {
    MediaType.Image -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    MediaType.Video -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    MediaType.Audio -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
}

private fun StoreQuery.toBundle(): Bundle = Bundle().apply {
    putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
    sortOrder?.let { order -> putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, order) }
    limit?.let { rowLimit -> putInt(ContentResolver.QUERY_ARG_LIMIT, rowLimit) }
    offset?.let { rowOffset -> putInt(ContentResolver.QUERY_ARG_OFFSET, rowOffset) }
}

private fun ContentResolver.count(spec: StoreQuery): Int {
    return query(spec.collectionUri(), arrayOf(MediaStore.MediaColumns._ID), spec.toBundle(), null)
        ?.use { cursor -> cursor.count }
        ?: 0
}

private fun ContentResolver.load(spec: StoreQuery): List<MediaItem> {
    val uri = spec.collectionUri()
    query(uri, columns, spec.toBundle(), null)?.use { cursor ->
        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
        val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
        val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
        val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
        val relativeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
        val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
        val items = ArrayList<MediaItem>(cursor.count)
        while (cursor.moveToNext()) {
            val id = cursor.getLong(idColumn)
            items += MediaItem(
                id = id,
                uri = ContentUris.withAppendedId(uri, id).toString(),
                type = spec.type,
                dateAdded = cursor.getLong(dateColumn),
                size = cursor.getLong(sizeColumn),
                path = relativeItemPath(cursor.getString(relativeColumn), cursor.getString(nameColumn)),
                mimeType = cursor.getString(mimeColumn),
            )
        }
        return items
    }
    return emptyList()
}

private fun ContentResolver.loadScan(spec: StoreQuery): List<ScannedRow> {
    val uri = spec.collectionUri()
    query(uri, scanColumns, spec.toBundle(), null)?.use { cursor ->
        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
        val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
        val dateModifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
        val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
        val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
        val relativeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
        val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
        val generationColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.GENERATION_MODIFIED)
        val rows = ArrayList<ScannedRow>(cursor.count)
        while (cursor.moveToNext()) {
            val id = cursor.getLong(idColumn)
            rows += ScannedRow(
                item = MediaItem(
                    id = id,
                    uri = ContentUris.withAppendedId(uri, id).toString(),
                    type = spec.type,
                    dateAdded = cursor.getLong(dateAddedColumn),
                    size = cursor.getLong(sizeColumn),
                    path = relativeItemPath(cursor.getString(relativeColumn), cursor.getString(nameColumn)),
                    mimeType = cursor.getString(mimeColumn),
                ),
                dateModified = cursor.getLong(dateModifiedColumn),
                generationModified = cursor.getLong(generationColumn),
            )
        }
        return rows
    }
    return emptyList()
}

private fun ContentResolver.loadIds(spec: StoreQuery): List<Long> {
    return query(spec.collectionUri(), arrayOf(MediaStore.MediaColumns._ID), spec.toBundle(), null)
        ?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val ids = ArrayList<Long>(cursor.count)
            while (cursor.moveToNext()) {
                ids += cursor.getLong(idColumn)
            }
            ids
        }
        ?: emptyList()
}
