package com.ibthe.videoencoder

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.ContentValues
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.*
import java.io.File

data class Preset(val label: Int, val height: Int, val bitrate: Int, val crop: Boolean = false, val audio: Boolean = false)

// ── Палитра ──────────────────────────────────────────────
private val BG = 0xFF07070C.toInt()
private val TEXT = 0xFFF4F4F8.toInt()
private val MUTED = 0xFF9A9AB0.toInt()
private val ACC1 = 0xFF7C5CFF.toInt()       // violet
private val ACC2 = 0xFF22D3EE.toInt()       // cyan
private val ACC_LIGHT = 0xFFA78BFA.toInt()
private val GREEN = 0xFF34D399.toInt()
private val RED = 0xFFFB7185.toInt()
private const val CARD = 0x0FFFFFFF
private const val CARD_STROKE = 0x1AFFFFFF

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

private const val S_QUEUED = 0
private const val S_RUN = 1
private const val S_OK = 2
private const val S_FAIL = 3

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
    private lateinit var licDot: View
    private lateinit var pickBtn: LinearLayout
    private lateinit var encodeBtn: Button
    private lateinit var cancelBtn: Button
    private lateinit var queueLabel: TextView
    private lateinit var queueBox: LinearLayout
    private lateinit var statusView: TextView
    private lateinit var progress: ProgressBar
    private lateinit var content: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private val presetRows = mutableListOf<LinearLayout>()
    private val presetRadios = mutableListOf<TextView>()
    private val glowAnims = mutableListOf<ObjectAnimator>()
    private var selectedPreset = 0
    private var insetTop = 0
    private var insetBottom = 0

    private val files = mutableListOf<Uri>()
    private val states = mutableMapOf<Uri, Int>()
    private val names = mutableMapOf<Uri, String>()
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
            files.clear(); files.addAll(uris); states.clear(); names.clear()
            files.forEach { states[it] = S_QUEUED; names[it] = displayName(it) }
            renderList(animate = true)
            setEnabledUi(encodeBtn, true)
            setStatus("")
        }
    }

    // ── UI helpers ───────────────────────────────────────
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun shape(fill: Int, radius: Int, strokeW: Int = 0, stroke: Int = 0, dashed: Boolean = false) =
        GradientDrawable().apply {
            cornerRadius = dp(radius).toFloat()
            setColor(fill)
            if (strokeW > 0) {
                if (dashed) setStroke(dp(strokeW), stroke, dp(6).toFloat(), dp(5).toFloat())
                else setStroke(dp(strokeW), stroke)
            }
        }

    private fun gradient(radius: Int) =
        GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(ACC1, ACC2)).apply {
            cornerRadius = dp(radius).toFloat()
        }

    private fun ripple(content: Drawable, radius: Int): Drawable =
        RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, shape(0xFFFFFFFF.toInt(), radius))

    private fun glow(color: Int, sizeDp: Int) = GradientDrawable().apply {
        setGradientType(GradientDrawable.RADIAL_GRADIENT)
        setGradientRadius(dp(sizeDp / 2).toFloat())
        setColors(intArrayOf(color, color and 0x00FFFFFF))
    }

    private fun tv(txt: CharSequence?, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = txt; textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun lp(w: Int, h: Int, top: Int = 0, start: Int = 0) =
        LinearLayout.LayoutParams(w, h).apply { topMargin = top; marginStart = start }

    private fun sectionLabel(text: String) = tv(text.uppercase(), 12f, MUTED, true).apply {
        letterSpacing = 0.14f
        setPadding(dp(4), 0, 0, 0)
    }

    private fun setEnabledUi(v: View, enabled: Boolean) {
        v.isEnabled = enabled
        v.alpha = if (enabled) 1f else 0.4f
    }

    private fun pressable(v: View) {
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.97f).scaleY(0.97f).setDuration(90).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
            }
            false
        }
    }

    private fun primaryButton(b: Button, text: String) = b.apply {
        this.text = text; isAllCaps = false; textSize = 17f
        setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
        stateListAnimator = null; elevation = 0f
        background = ripple(gradient(18), 18)
        pressable(this)
    }

    private fun setStatus(s: String) {
        statusView.text = s
        statusView.visibility = if (s.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun meta(p: Preset) = when {
        p.audio -> getString(R.string.meta_audio)
        p.crop -> getString(R.string.meta_vertical, p.bitrate / 1_000_000)
        else -> getString(R.string.meta_video, p.height, p.bitrate / 1_000_000)
    }

    // ── Screen ───────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        val frame = FrameLayout(this).apply { setBackgroundColor(BG) }

        // мягкие светящиеся пятна на фоне
        val glowA = View(this).apply {
            background = glow(0x557C5CFF, 520)
            layoutParams = FrameLayout.LayoutParams(dp(520), dp(520), Gravity.TOP or Gravity.END)
                .apply { topMargin = -dp(240); marginEnd = -dp(220) }
        }
        val glowB = View(this).apply {
            background = glow(0x3322D3EE, 460)
            layoutParams = FrameLayout.LayoutParams(dp(460), dp(460), Gravity.BOTTOM or Gravity.START)
                .apply { bottomMargin = -dp(240); marginStart = -dp(220) }
        }
        frame.addView(glowA); frame.addView(glowB)
        listOf(glowA to 5200L, glowB to 6400L).forEach { (v, d) ->
            glowAnims.add(ObjectAnimator.ofFloat(v, View.ALPHA, 0.55f, 1f).apply {
                duration = d; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE; start()
            })
        }

        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            isFillViewport = true; overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false; addView(content)
        }
        frame.addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))

        // ── Header
        val logo = TextView(this).apply {
            text = "▶"; gravity = Gravity.CENTER; textSize = 18f; setTextColor(Color.WHITE)
            background = gradient(15)
        }
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tv(getString(R.string.app_name), 26f, TEXT, true).apply { letterSpacing = -0.01f })
            addView(tv(getString(R.string.subtitle), 13f, MUTED))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(logo, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(titles, lp(WRAP, WRAP, start = dp(14)))
        }

        // ── License card
        licDot = View(this)
        licText = tv("", 14f, TEXT, true)
        licBtn = Button(this).apply {
            isAllCaps = false; textSize = 14f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(ACC_LIGHT); stateListAnimator = null; elevation = 0f
            background = ripple(shape(0x267C5CFF, 14, 1, 0x557C5CFF), 14)
            pressable(this)
        }
        val licRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(licDot, LinearLayout.LayoutParams(dp(8), dp(8)))
            addView(licText, lp(WRAP, WRAP, start = dp(10)))
        }
        val licCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = shape(CARD, 20, 1, CARD_STROKE)
            addView(licRow)
            addView(licBtn, lp(MATCH, dp(46), top = dp(12)))
        }

        // ── Pick zone
        pickBtn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(22), dp(28), dp(22), dp(26))
            isClickable = true; isFocusable = true
            background = ripple(shape(0x0DFFFFFF, 26, 2, 0x55A78BFA, dashed = true), 26)
            val plus = TextView(this@MainActivity).apply {
                text = "+"; gravity = Gravity.CENTER; textSize = 30f; setTextColor(Color.WHITE)
                background = gradient(30)
            }
            addView(plus, LinearLayout.LayoutParams(dp(60), dp(60)))
            addView(tv(getString(R.string.pick), 18f, TEXT, true), lp(WRAP, WRAP, top = dp(14)))
            addView(tv(getString(R.string.empty), 13f, MUTED).apply {
                gravity = Gravity.CENTER; setLineSpacing(0f, 1.15f)
            }, lp(MATCH, WRAP, top = dp(6)))
            pressable(this)
        }

        // ── Presets
        val presetLabel = sectionLabel(getString(R.string.section_format))
        val presetsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        presets.forEachIndexed { i, p ->
            val radio = TextView(this).apply {
                gravity = Gravity.CENTER; textSize = 12f; setTextColor(Color.WHITE)
                setTypeface(typeface, Typeface.BOLD)
            }
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(tv(getString(p.label), 15f, TEXT, true))
                addView(tv(meta(p), 12f, MUTED), lp(WRAP, WRAP, top = dp(2)))
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                isClickable = true; isFocusable = true
                setOnClickListener { if (!busy) selectPreset(i) }
                addView(radio, LinearLayout.LayoutParams(dp(24), dp(24)))
                addView(col, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(14) })
                pressable(this)
            }
            presetRows.add(row); presetRadios.add(radio)
            presetsBox.addView(row, lp(MATCH, WRAP, top = if (i == 0) 0 else dp(8)))
        }
        selectPreset(0)

        // ── Queue
        queueLabel = sectionLabel(getString(R.string.section_queue)).apply { visibility = View.GONE }
        queueBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        content.addView(header)
        content.addView(licCard, lp(MATCH, WRAP, top = dp(22)))
        content.addView(pickBtn, lp(MATCH, WRAP, top = dp(14)))
        content.addView(presetLabel, lp(MATCH, WRAP, top = dp(26)))
        content.addView(presetsBox, lp(MATCH, WRAP, top = dp(10)))
        content.addView(queueLabel, lp(MATCH, WRAP, top = dp(26)))
        content.addView(queueBox, lp(MATCH, WRAP, top = dp(10)))

        // ── Bottom bar (CTA)
        statusView = tv("", 13f, MUTED).apply { gravity = Gravity.CENTER; visibility = View.GONE }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; visibility = View.INVISIBLE
            val track = shape(0x1FFFFFFF, 6)
            val bar = ClipDrawable(gradient(6), Gravity.START, ClipDrawable.HORIZONTAL)
            progressDrawable = LayerDrawable(arrayOf<Drawable>(track, bar)).apply {
                setId(0, android.R.id.background); setId(1, android.R.id.progress)
            }
        }
        encodeBtn = primaryButton(Button(this), getString(R.string.encode))
        setEnabledUi(encodeBtn, false)
        cancelBtn = Button(this).apply {
            text = getString(R.string.cancel); isAllCaps = false; textSize = 16f
            setTypeface(typeface, Typeface.BOLD); setTextColor(RED)
            stateListAnimator = null; elevation = 0f; visibility = View.GONE
            background = ripple(shape(0x1AFB7185, 18, 1, 0x66FB7185), 18)
            pressable(this)
        }
        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x0007070C, 0xE607070C.toInt(), 0xFF07070C.toInt())
            )
            addView(statusView, lp(MATCH, WRAP))
            addView(progress, lp(MATCH, dp(6), top = dp(8)))
            addView(encodeBtn, lp(MATCH, dp(56), top = dp(14)))
            addView(cancelBtn, lp(MATCH, dp(56), top = dp(14)))
        }
        frame.addView(bottomBar, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))

        fun applyPadding(barH: Int) {
            content.setPadding(dp(20), insetTop + dp(16), dp(20), barH + dp(12))
            bottomBar.setPadding(dp(20), dp(36), dp(20), insetBottom + dp(14))
        }
        ViewCompat.setOnApplyWindowInsetsListener(frame) { _, insets ->
            val s = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            insetTop = s.top; insetBottom = s.bottom
            applyPadding(bottomBar.height)
            insets
        }
        bottomBar.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val h = bottom - top
            if (content.paddingBottom != h + dp(12)) applyPadding(h)
        }
        applyPadding(dp(120))
        setContentView(frame)

        lic = createLicensing(this) { refreshLic() }
        licBtn.setOnClickListener { lic.onButton() }
        pickBtn.setOnClickListener { picker.launch(arrayOf("video/*")) }
        encodeBtn.setOnClickListener { runQueue() }
        cancelBtn.setOnClickListener { cancelAll() }
        refreshLic()

        // плавное появление секций
        listOf<View>(header, licCard, pickBtn, presetLabel, presetsBox).forEachIndexed { i, v ->
            v.alpha = 0f; v.translationY = dp(18).toFloat()
            v.animate().alpha(1f).translationY(0f).setStartDelay(70L * i).setDuration(480)
                .setInterpolator(DecelerateInterpolator()).start()
        }
    }

    override fun onDestroy() {
        glowAnims.forEach { it.cancel() }
        super.onDestroy()
    }

    private fun selectPreset(i: Int) {
        selectedPreset = i
        presetRows.forEachIndexed { j, r ->
            val sel = j == i
            r.background = ripple(
                if (sel) shape(0x1F7C5CFF, 18, 1, 0x667C5CFF) else shape(CARD, 18, 1, CARD_STROKE), 18
            )
            val rd = presetRadios[j]
            rd.text = if (sel) "✓" else ""
            rd.background = if (sel)
                GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(ACC1, ACC2))
                    .apply { setShape(GradientDrawable.OVAL) }
            else GradientDrawable().apply { setShape(GradientDrawable.OVAL); setStroke(dp(2), 0x40FFFFFF) }
        }
    }

    private fun refreshLic() {
        licText.text = lic.label()
        licBtn.text = lic.buttonText()
        licBtn.visibility = if (lic.isLicensed()) View.GONE else View.VISIBLE
        licDot.background = GradientDrawable().apply {
            setShape(GradientDrawable.OVAL); setColor(if (lic.isLicensed()) GREEN else ACC_LIGHT)
        }
    }

    private fun statusText(s: Int) = getString(
        when (s) {
            S_RUN -> R.string.running
            S_OK -> R.string.ok
            S_FAIL -> R.string.failed
            else -> R.string.queued
        }
    )

    private fun fileRow(u: Uri): View {
        val st = states[u] ?: S_QUEUED
        val (fg, bg) = when (st) {
            S_RUN -> ACC_LIGHT to 0x267C5CFF
            S_OK -> GREEN to 0x2634D399
            S_FAIL -> RED to 0x26FB7185
            else -> MUTED to 0x1AFFFFFF
        }
        val icon = TextView(this).apply {
            text = "▶"; gravity = Gravity.CENTER; textSize = 11f; setTextColor(MUTED)
            background = shape(0x14FFFFFF, 11)
        }
        val name = tv(names[u] ?: "video", 14f, TEXT).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        val pill = tv(statusText(st), 12f, fg, true).apply {
            setPadding(dp(10), dp(4), dp(10), dp(4)); background = shape(bg, 20)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = shape(CARD, 16, 1, CARD_STROKE)
            addView(icon, LinearLayout.LayoutParams(dp(34), dp(34)))
            addView(name, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(12); marginEnd = dp(10) })
            addView(pill)
        }
    }

    private fun renderList(animate: Boolean = false) {
        queueBox.removeAllViews()
        queueLabel.visibility = if (files.isEmpty()) View.GONE else View.VISIBLE
        queueLabel.text = "${getString(R.string.section_queue).uppercase()}  ·  ${files.size}"
        files.forEachIndexed { i, u ->
            val row = fileRow(u)
            queueBox.addView(row, lp(MATCH, WRAP, top = if (i == 0) 0 else dp(8)))
            if (animate && i < 12) {
                row.alpha = 0f; row.translationY = dp(12).toFloat()
                row.animate().alpha(1f).translationY(0f).setStartDelay(40L * i).setDuration(320)
                    .setInterpolator(DecelerateInterpolator()).start()
            }
        }
    }

    private fun displayName(uri: Uri): String =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "video"

    private fun setBusyUi(b: Boolean) {
        pickBtn.isEnabled = !b; pickBtn.alpha = if (b) 0.45f else 1f
        presetRows.forEach { it.alpha = if (b) 0.45f else 1f }
        encodeBtn.visibility = if (b) View.GONE else View.VISIBLE
        cancelBtn.visibility = if (b) View.VISIBLE else View.GONE
        progress.visibility = if (b) View.VISIBLE else View.INVISIBLE
    }

    private fun runQueue() {
        busy = true; cancelled = false; lockedHit = false; okCount = 0; failCount = 0
        setBusyUi(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val jobs = files.toList()
        val preset = presets[selectedPreset]
        fun next(i: Int) {
            if (cancelled) return
            if (i >= jobs.size) { finishQueue(); return }
            if (!lic.isLicensed() && lic.trialLeft() <= 0) { lockedHit = true; finishQueue(); return }
            setStatus(getString(R.string.file_of, i + 1, jobs.size))
            progress.progress = 0
            states[jobs[i]] = S_RUN; renderList()
            encodeOne(jobs[i], preset) { success ->
                states[jobs[i]] = if (success) S_OK else S_FAIL
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
        states.keys.toList().forEach { if (states[it] == S_RUN) states[it] = S_QUEUED }
        renderList()
        setBusyUi(false); progress.progress = 0
        setStatus(
            when {
                lockedHit -> getString(R.string.locked)
                cancelled -> getString(R.string.stopped, okCount)
                else -> getString(R.string.done, okCount, failCount)
            }
        )
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
                if (t.getProgress(holder) != Transformer.PROGRESS_STATE_NOT_STARTED) progress.setProgress(holder.progress, true)
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
