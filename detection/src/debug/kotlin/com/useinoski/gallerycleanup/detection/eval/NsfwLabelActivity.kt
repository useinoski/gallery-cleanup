package com.useinoski.gallerycleanup.detection.eval

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.useinoski.gallerycleanup.detection.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Debug-only labeling screen. The release variant does not compile this class.
 *
 * Photos come from MediaStore with the grant the main app already requested.
 * This activity does not request a permission of its own. Labels go to
 * [NsfwLabelStore]. The only text copied to the clipboard is
 * [NsfwEvalMetrics.summary].
 */
class NsfwLabelActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var store: NsfwLabelStore
    private lateinit var photos: NsfwPhotoQueue
    private lateinit var pass: NsfwEvaluationPass

    private lateinit var statusView: TextView
    private lateinit var progressView: TextView
    private lateinit var breakdownView: TextView
    private lateinit var positionView: TextView
    private lateinit var currentView: TextView
    private lateinit var doneView: TextView
    private lateinit var photoView: ImageView
    private lateinit var hintView: TextView
    private lateinit var explicitButton: Button
    private lateinit var notExplicitButton: Button
    private lateinit var unsureButton: Button
    private lateinit var immodestButton: Button
    private lateinit var previousButton: Button
    private lateinit var nextButton: Button
    private lateinit var runButton: Button
    private lateinit var summaryView: TextView
    private lateinit var copyButton: Button

    private var ids: List<Long> = emptyList()
    private val labels = mutableMapOf<Long, NudityLabel>()
    private var index = 0
    private var generation = 0
    private var running = false
    private var alive = true
    private var latestSummary: String? = null
    private var sharpBitmap: Bitmap? = null
    private var softBitmap: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appContext = applicationContext
        store = NsfwLabelStore(appContext)
        photos = NsfwPhotoQueue(appContext.contentResolver)
        pass = NsfwEvaluationPass(appContext)
        buildViews()
        if (!canReadImages()) {
            showStatus(R.string.nsfw_labeler_need_access)
            updateChrome()
            return
        }
        showStatus(R.string.nsfw_labeler_loading)
        scope.launch { loadLibrary() }
    }

    override fun onDestroy() {
        alive = false
        scope.cancel()
        photoView.setImageDrawable(null)
        recycleBitmaps()
        super.onDestroy()
    }

    private suspend fun loadLibrary() {
        val loaded = try {
            withContext(Dispatchers.IO) { photos.imageIds() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (revoked: SecurityException) {
            if (alive) showStatus(R.string.nsfw_labeler_access_lost)
            return
        } catch (ignored: Exception) {
            if (alive) showStatus(R.string.nsfw_labeler_list_failed)
            return
        }
        val stored = withContext(Dispatchers.IO) { store.read() }
        if (!alive) return
        ids = loaded
        labels.clear()
        val visible = loaded.toHashSet()
        for ((id, label) in stored) {
            if (id in visible) labels[id] = label
        }
        if (ids.isEmpty()) {
            showStatus(R.string.nsfw_labeler_empty)
            updateChrome()
            return
        }
        val firstUnlabeled = ids.indexOfFirst { it !in labels }
        index = if (firstUnlabeled == -1) 0 else firstUnlabeled
        showStatus(R.string.nsfw_labeler_blur_hint)
        showCurrent()
    }

    private fun showCurrent() {
        recycleBitmaps()
        photoView.setImageDrawable(null)
        val mediaId = ids.getOrNull(index) ?: return
        val gen = ++generation
        updateChrome()
        hintView.setText(R.string.nsfw_labeler_blur_hint)
        scope.launch {
            val decoded = try {
                withContext(Dispatchers.IO) { photos.decode(mediaId) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (revoked: SecurityException) {
                if (isCurrent(gen)) showStatus(R.string.nsfw_labeler_access_lost)
                return@launch
            } catch (ignored: Exception) {
                null
            }
            if (!isCurrent(gen)) {
                decoded?.let { recycleQuietly(it) }
                return@launch
            }
            if (decoded == null) {
                hintView.setText(R.string.nsfw_labeler_unreadable)
                return@launch
            }
            val blurred = try {
                withContext(Dispatchers.Default) { blurForLabel(decoded) }
            } catch (cancelled: CancellationException) {
                recycleQuietly(decoded)
                throw cancelled
            }
            if (!isCurrent(gen)) {
                recycleQuietly(decoded)
                if (blurred !== decoded) recycleQuietly(blurred)
                return@launch
            }
            sharpBitmap = decoded
            softBitmap = blurred
            photoView.setImageBitmap(blurred)
        }
    }

    private fun reveal() {
        val sharp = sharpBitmap ?: return
        if (sharp.isRecycled) return
        photoView.setImageBitmap(sharp)
        hintView.setText(R.string.nsfw_labeler_blur_hint)
    }

    private fun saveAndAdvance(label: NudityLabel) {
        if (running) return
        val mediaId = ids.getOrNull(index) ?: return
        store.put(mediaId, label)
        labels[mediaId] = label
        if (index < ids.lastIndex) index += 1
        showCurrent()
    }

    private fun step(delta: Int) {
        if (running) return
        val next = index + delta
        if (next !in ids.indices) return
        index = next
        showCurrent()
    }

    private fun runEvaluation() {
        if (running) return
        val labeled = buildMap {
            for (id in ids) {
                val label = labels[id] ?: continue
                put(id, label)
            }
        }
        if (labeled.isEmpty()) {
            showStatus(R.string.nsfw_labeler_need_labels)
            return
        }
        running = true
        latestSummary = null
        summaryView.setText(R.string.nsfw_labeler_running_placeholder)
        updateChrome()
        scope.launch {
            try {
                val report = withContext(Dispatchers.Default) {
                    pass.run(labeled) { done, total ->
                        scope.launch {
                            if (alive) {
                                statusView.text = getString(R.string.nsfw_labeler_running, done, total)
                            }
                        }
                    }
                }
                if (!alive) return@launch
                latestSummary = NsfwEvalMetrics.summary(report)
                summaryView.text = latestSummary
                showStatus(R.string.nsfw_labeler_finished)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (revoked: SecurityException) {
                if (alive) showStatus(R.string.nsfw_labeler_access_lost)
            } catch (ignored: Exception) {
                if (alive) showStatus(R.string.nsfw_labeler_failed)
            } finally {
                running = false
                if (alive) updateChrome()
            }
        }
    }

    private fun copySummary() {
        val text = latestSummary ?: return
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.nsfw_labeler_clip_label), text))
        showStatus(R.string.nsfw_labeler_copied)
    }

    private fun updateChrome() {
        val total = ids.size
        val labeledCount = ids.count { it in labels }
        progressView.text = getString(R.string.nsfw_labeler_progress, labeledCount, total)
        val present = ids.mapNotNull { labels[it] }
        val counts = NsfwEvalMetrics.countsOf(present)
        breakdownView.text = getString(
            R.string.nsfw_labeler_breakdown,
            counts.explicit,
            counts.notExplicit,
            counts.unsure,
            counts.immodest,
        )
        if (total == 0) {
            positionView.text = ""
            currentView.text = ""
        } else {
            positionView.text = getString(R.string.nsfw_labeler_position, index + 1, total)
            val label = labels[ids[index]]
            currentView.text = if (label == null) {
                getString(R.string.nsfw_labeler_none)
            } else {
                getString(R.string.nsfw_labeler_current, labelText(label))
            }
        }
        doneView.visibility = if (total > 0 && labeledCount == total) View.VISIBLE else View.GONE
        val hasPhoto = total > 0 && !running
        explicitButton.isEnabled = hasPhoto
        notExplicitButton.isEnabled = hasPhoto
        unsureButton.isEnabled = hasPhoto
        immodestButton.isEnabled = hasPhoto
        previousButton.isEnabled = hasPhoto && index > 0
        nextButton.isEnabled = hasPhoto && index < total - 1
        runButton.isEnabled = !running && labeledCount > 0
        copyButton.isEnabled = latestSummary != null && !running
    }

    private fun labelText(label: NudityLabel): String = when (label) {
        NudityLabel.EXPLICIT -> getString(R.string.nsfw_labeler_explicit)
        NudityLabel.NOT_EXPLICIT -> getString(R.string.nsfw_labeler_not_explicit)
        NudityLabel.UNSURE -> getString(R.string.nsfw_labeler_unsure)
        NudityLabel.IMMODEST -> getString(R.string.nsfw_labeler_immodest)
    }

    private fun canReadImages(): Boolean {
        fun granted(permission: String): Boolean =
            checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            granted(Manifest.permission.READ_MEDIA_IMAGES) ||
                (
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                        granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                    )
        } else {
            granted(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun showStatus(text: Int) {
        statusView.setText(text)
    }

    private fun isCurrent(gen: Int): Boolean = alive && gen == generation && !isDestroyed

    private fun recycleBitmaps() {
        val sharp = sharpBitmap
        val soft = softBitmap
        sharpBitmap = null
        softBitmap = null
        recycleQuietly(sharp)
        if (soft !== sharp) recycleQuietly(soft)
    }

    private fun buildViews() {
        val pad = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(heading(getString(R.string.nsfw_labeler_heading)))
        root.addView(body(getString(R.string.nsfw_labeler_privacy)))
        statusView = body("").apply { id = R.id.nsfw_labeler_status }
        progressView = body("").apply { id = R.id.nsfw_labeler_progress }
        breakdownView = body("").apply { id = R.id.nsfw_labeler_breakdown }
        positionView = body("").apply { id = R.id.nsfw_labeler_position }
        currentView = body("").apply { id = R.id.nsfw_labeler_current }
        doneView = body(getString(R.string.nsfw_labeler_all_labeled)).apply {
            id = R.id.nsfw_labeler_done
            visibility = View.GONE
        }
        root.addView(statusView)
        root.addView(progressView)
        root.addView(breakdownView)
        root.addView(positionView)
        root.addView(currentView)
        root.addView(doneView)
        photoView = ImageView(this).apply {
            id = R.id.nsfw_labeler_photo
            contentDescription = getString(R.string.nsfw_labeler_photo_description)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(0xFFBDBDBD.toInt())
            setOnClickListener { reveal() }
        }
        root.addView(
            photoView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(320)),
        )
        hintView = body(getString(R.string.nsfw_labeler_blur_hint)).apply { id = R.id.nsfw_labeler_hint }
        root.addView(hintView)
        explicitButton = labelButton(R.string.nsfw_labeler_explicit, R.id.nsfw_labeler_explicit) {
            saveAndAdvance(NudityLabel.EXPLICIT)
        }
        notExplicitButton = labelButton(R.string.nsfw_labeler_not_explicit, R.id.nsfw_labeler_not_explicit) {
            saveAndAdvance(NudityLabel.NOT_EXPLICIT)
        }
        unsureButton = labelButton(R.string.nsfw_labeler_unsure, R.id.nsfw_labeler_unsure) {
            saveAndAdvance(NudityLabel.UNSURE)
        }
        immodestButton = labelButton(R.string.nsfw_labeler_immodest, R.id.nsfw_labeler_immodest) {
            saveAndAdvance(NudityLabel.IMMODEST)
        }
        root.addView(explicitButton, matchWidth())
        root.addView(notExplicitButton, matchWidth())
        root.addView(unsureButton, matchWidth())
        root.addView(immodestButton, matchWidth())
        previousButton = labelButton(R.string.nsfw_labeler_previous, R.id.nsfw_labeler_previous) { step(-1) }
        nextButton = labelButton(R.string.nsfw_labeler_next, R.id.nsfw_labeler_next) { step(1) }
        root.addView(row(previousButton, nextButton))
        runButton = labelButton(R.string.nsfw_labeler_run, R.id.nsfw_labeler_run) { runEvaluation() }
        root.addView(runButton, matchWidth())
        summaryView = body(getString(R.string.nsfw_labeler_summary_empty)).apply {
            id = R.id.nsfw_labeler_summary
            setTextIsSelectable(true)
        }
        root.addView(summaryView)
        copyButton = labelButton(R.string.nsfw_labeler_copy, R.id.nsfw_labeler_copy) { copySummary() }
        root.addView(copyButton, matchWidth())
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
        updateChrome()
    }

    private fun heading(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 22f
        setPadding(0, 0, 0, dp(8))
    }

    private fun body(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 16f
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun labelButton(text: Int, viewId: Int, onClick: () -> Unit): Button {
        return Button(this).apply {
            id = viewId
            setText(text)
            setOnClickListener { onClick() }
        }
    }

    private fun row(first: Button, second: Button): LinearLayout {
        val gap = dp(4)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(first, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = gap
            })
            addView(second, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = gap
            })
        }
    }

    private fun matchWidth(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}

private fun recycleQuietly(bitmap: Bitmap?) {
    if (bitmap != null && !bitmap.isRecycled) bitmap.recycle()
}
