package com.ibthe.videoencoder

import android.content.ContentValues
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.*
import java.io.File

data class Preset(val label: Int, val height: Int, val bitrate: Int, val crop: Boolean = false, val audio: Boolean = false)

@androidx.annotation.OptIn(UnstableApi::class)
class MainActivity : AppCompatActivity() {
    private val presets = listOf(
        Preset(R.string.p_yt1080, 1080, 12_000_000),
        Preset(R.string.p_yt4k, 2160, 35_000_000),
        Preset(R.string.p_vertical, 1920, 10_000_000, crop = true),
        Preset(R.string.p_small, 720, 3_000_000),
        Preset(R.string.p_audio, 0, 0, audio = true)
    )
    private lateinit var lic: Licensing
    private lateinit var licText: TextView
    private lateinit var licBtn: Button
    private lateinit var pickBtn: Button
    private lateinit var encodeBtn: Button
    private lateinit var cancelBtn: Button
    private lateinit var presetSpinner: Spinner
    private lateinit var listView: TextView
    private lateinit var statusView: TextView
    private lateinit var progress: ProgressBar

    private val files = mutableListOf<Uri>()
    private val statuses = mutableMapOf<Uri, String>()
    private val handler = Handler(Looper.getMainLooper())
    private var transformer: Transformer? = null
    private var poll: Runnable? = null
    private var curTmp: File? = null
    private var busy = false
    private var cancelled = false
    private var lockedHit = false
    private var okCount = 0
    private var failCount = 0

    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty() && !busy) {
            files.clear(); files.addAll(uris); statuses.clear()
            files.forEach { statuses[it] = getString(R.string.queued) }
            renderList(); encodeBtn.isEnabled = true
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun style(b: Button, bg: Int, fg: Int) {
        b.background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(bg) }
        b.setTextColor(fg); b.isAllCaps = false; b.textSize = 16f
        b.minimumHeight = dp(48)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(24))
        }
        val scroll = ScrollView(this).apply { setBackgroundColor(0xFFF5F5F7.toInt()); isFillViewport = true; addView(root) }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val s = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, s.top, 0, s.bottom); insets
        }
        setContentView(scroll)

        fun gap() = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(14)) }
        val title = TextView(this).apply { text = getString(R.string.app_name); textSize = 24f; setTextColor(0xFF1D1D1F.toInt()); setTypeface(typeface, android.graphics.Typeface.BOLD) }
        licText = TextView(this).apply { textSize = 13f; setTextColor(0xFF6E6E73.toInt()) }
        licBtn = Button(this).also { style(it, 0xFFFFFFFF.toInt(), 0xFF2B59FF.toInt()) }
        pickBtn = Button(this).apply { text = getString(R.string.pick) }.also { style(it, 0xFFFFFFFF.toInt(), 0xFF2B59FF.toInt()) }
        presetSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, presets.map { getString(it.label) })
        }
        listView = TextView(this).apply { textSize = 14f; setTextColor(0xFF1D1D1F.toInt()); text = getString(R.string.empty) }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        statusView = TextView(this).apply { textSize = 13f; setTextColor(0xFF6E6E73.toInt()); gravity = android.view.Gravity.CENTER }
        encodeBtn = Button(this).apply { text = getString(R.string.encode); isEnabled = false }.also { style(it, 0xFF2B59FF.toInt(), 0xFFFFFFFF.toInt()) }
        cancelBtn = Button(this).apply { text = getString(R.string.cancel); visibility = View.GONE }.also { style(it, 0xFFFFFFFF.toInt(), 0xFFD93025.toInt()) }

        listOf(title, licText, licBtn, gap(), pickBtn, presetSpinner, gap(), listView, gap(), progress, statusView, gap(), encodeBtn, cancelBtn)
            .forEach { root.addView(it) }

        lic = createLicensing(this) { refreshLic() }
        licBtn.setOnClickListener { lic.onButton() }
        pickBtn.setOnClickListener { picker.launch(arrayOf("video/*")) }
        encodeBtn.setOnClickListener { runQueue() }
        cancelBtn.setOnClickListener { cancelAll() }
        refreshLic()
    }

    private fun refreshLic() {
        licText.text = lic.label()
        licBtn.text = lic.buttonText()
        licBtn.visibility = if (lic.isLicensed()) View.GONE else View.VISIBLE
    }

    private fun renderList() {
        listView.text = files.joinToString("\n") { "${displayName(it)}  —  ${statuses[it] ?: ""}" }
    }

    private fun displayName(uri: Uri): String =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "video"

    private fun setBusyUi(b: Boolean) {
        pickBtn.isEnabled = !b; presetSpinner.isEnabled = !b
        encodeBtn.visibility = if (b) View.GONE else View.VISIBLE
        cancelBtn.visibility = if (b) View.VISIBLE else View.GONE
    }

    private fun runQueue() {
        busy = true; cancelled = false; lockedHit = false; okCount = 0; failCount = 0
        setBusyUi(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val jobs = files.toList()
        val preset = presets[presetSpinner.selectedItemPosition]
        fun next(i: Int) {
            if (cancelled) return
            if (i >= jobs.size) { finishQueue(); return }
            if (!lic.isLicensed() && lic.trialLeft() <= 0) { lockedHit = true; finishQueue(); return }
            statusView.text = getString(R.string.file_of, i + 1, jobs.size)
            progress.progress = 0
            encodeOne(jobs[i], preset) { success ->
                statuses[jobs[i]] = getString(if (success) R.string.ok else R.string.failed)
                if (success) { okCount++; if (!lic.isLicensed()) lic.consumeTrial() } else failCount++
                renderList(); refreshLic()
                next(i + 1)
            }
        }
        next(0)
    }

    private fun finishQueue() {
        poll?.let { handler.removeCallbacks(it) }
        busy = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setBusyUi(false); progress.progress = 0
        statusView.text = when {
            lockedHit -> getString(R.string.locked)
            cancelled -> getString(R.string.stopped, okCount)
            else -> getString(R.string.done, okCount, failCount)
        }
    }

    private fun cancelAll() {
        cancelled = true
        transformer?.cancel()
        curTmp?.delete()
        finishQueue()
    }

    private fun videoHeight(uri: Uri): Int {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(this, uri)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: 0
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toInt() ?: 0
            if (rot == 90 || rot == 270) w else h
        } catch (e: Exception) { 0 } finally { r.release() }
    }

    private fun encodeOne(uri: Uri, p: Preset, done: (Boolean) -> Unit) {
        val name = displayName(uri).substringBeforeLast('.')
        val ext = if (p.audio) "m4a" else "mp4"
        val tmp = File(cacheDir, "out_${System.nanoTime()}.$ext")
        curTmp = tmp

        val item = EditedMediaItem.Builder(MediaItem.fromUri(uri))
        if (p.audio) item.setRemoveVideo(true)
        else {
            val fx = mutableListOf<Effect>()
            if (p.crop) fx.add(Presentation.createForWidthAndHeight(1080, 1920, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP))
            else if (videoHeight(uri) > p.height) fx.add(Presentation.createForHeight(p.height))
            item.setEffects(Effects(emptyList(), fx))
        }

        val encoder = DefaultEncoderFactory.Builder(this)
            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(if (p.bitrate > 0) p.bitrate else 3_000_000).build())
            .build()
        lateinit var t: Transformer
        t = Transformer.Builder(this)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(encoder)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    poll?.let { handler.removeCallbacks(it) }
                    val saved = saveToMovies(tmp, name, ext, p.audio)
                    tmp.delete(); done(saved)
                }
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    poll?.let { handler.removeCallbacks(it) }
                    tmp.delete(); done(false)
                }
            }).build()
        transformer = t
        t.start(item.build(), tmp.absolutePath)

        val holder = ProgressHolder()
        val r = object : Runnable {
            override fun run() {
                if (t.getProgress(holder) != Transformer.PROGRESS_STATE_NOT_STARTED) progress.progress = holder.progress
                handler.postDelayed(this, 500)
            }
        }
        poll = r; handler.post(r)
    }

    private fun saveToMovies(src: File, name: String, ext: String, audio: Boolean): Boolean = try {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.$ext")
            put(MediaStore.MediaColumns.MIME_TYPE, if (audio) "audio/mp4" else "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, (if (audio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES) + "/Processed")
        }
        val collection = if (audio) MediaStore.Audio.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val out = contentResolver.insert(collection, values)!!
        contentResolver.openOutputStream(out)!!.use { o -> src.inputStream().use { it.copyTo(o) } }
        true
    } catch (e: Exception) { false }
}
