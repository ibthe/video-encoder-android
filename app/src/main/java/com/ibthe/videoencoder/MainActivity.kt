package com.ibthe.videoencoder

import android.content.ContentValues
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
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
import android.util.Size
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.BundleCompat
import androidx.core.os.LocaleListCompat
import androidx.core.text.buildSpannedString
import androidx.core.text.color
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
import java.util.concurrent.Executors

data class Preset(val label: Int, val height: Int, val bitrate: Int, val crop: Boolean = false, val audio: Boolean = false)

// ── Палитра: чёрный + кислотный лайм ─────────────────────
private val BG = 0xFF0A0A0A.toInt()
private val SURF = 0xFF161616.toInt()
private val SURF2 = 0xFF222222.toInt()
private val TEXT = 0xFFF5F5F0.toInt()
private val MUTED = 0xFF8A8A85.toInt()
private val LIME = 0xFFD4FF3A.toInt()
private val INK = 0xFF0A0A0A.toInt()
private val INK_DIM = 0x99000000.toInt()
private val RED = 0xFFFF5C5C.toInt()

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
    private val tokens = listOf("1080", "4K", "9:16", "720", "M4A")

    private lateinit var lic: Licensing
    private lateinit var licText: TextView
    private lateinit var licBtn: TextView
    private lateinit var licDot: View
    private lateinit var hero: FrameLayout
    private lateinit var ring: RingView
    private lateinit var heroBig: TextView
    private lateinit var heroSmall: TextView
    private lateinit var encodeBtn: Button
    private lateinit var cancelBtn: Button
    private lateinit var queueLabel: TextView
    private lateinit var queueHeader: LinearLayout
    private lateinit var queueBox: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var presetScroll: HorizontalScrollView
    private val presetCards = mutableListOf<LinearLayout>()
    private val presetTexts = mutableListOf<List<TextView>>()
    private var selectedPreset = 0
    private var insetTop = 0
    private var insetBottom = 0

    private val io = Executors.newSingleThreadExecutor()
    private val thumbs = mutableMapOf<Uri, Bitmap>()
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
            files.clear(); files.addAll(uris); states.clear(); names.clear(); thumbs.clear()
            files.forEach { states[it] = S_QUEUED; names[it] = displayName(it) }
            renderList(animate = true)
            setEnabledUi(encodeBtn, true)
            ring.idle = true
            setHero(files.size.toString(), getString(R.string.selected_files))
            heroBig.animate().scaleX(1.12f).scaleY(1.12f).setDuration(120).withEndAction {
                heroBig.animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(OvershootInterpolator()).start()
            }.start()
        }
    }

    // ── helpers ──────────────────────────────────────────
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun shape(fill: Int, radius: Int, strokeW: Int = 0, stroke: Int = 0) =
        GradientDrawable().apply {
            cornerRadius = dp(radius).toFloat()
            setColor(fill)
            if (strokeW > 0) setStroke(dp(strokeW), stroke)
        }

    private fun ripple(content: Drawable, radius: Int): Drawable =
        RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, shape(0xFFFFFFFF.toInt(), radius))

    private fun tv(txt: CharSequence?, size: Float, color: Int, bold: Boolean = false, black: Boolean = false) =
        TextView(this).apply {
            text = txt; textSize = size; setTextColor(color)
            if (black) typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
            else if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun lp(w: Int, h: Int, top: Int = 0, start: Int = 0, end: Int = 0) =
        LinearLayout.LayoutParams(w, h).apply { topMargin = top; marginStart = start; marginEnd = end }

    /** Блок с боковыми полями 20dp */
    private fun block(top: Int, h: Int = WRAP) = lp(MATCH, h, top = top, start = dp(16), end = dp(16))

    private fun sectionLabel(text: String) = tv(text.uppercase(), 11f, MUTED, true).apply { letterSpacing = 0.14f }

    private fun setEnabledUi(v: View, enabled: Boolean) {
        v.isEnabled = enabled
        v.alpha = if (enabled) 1f else 0.35f
    }

    private fun pressable(v: View) {
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.96f).scaleY(0.96f).setDuration(90).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.animate().scaleX(1f).scaleY(1f).setDuration(180).start()
            }
            false
        }
    }

    private fun setHero(big: String, small: String) { heroBig.text = big; heroSmall.text = small }

    private fun onProgress(p: Int) {
        ring.setProgress(p / 100f, true)
        heroBig.text = "$p%"
    }

    private fun meta(p: Preset) = when {
        p.audio -> getString(R.string.meta_audio)
        p.crop -> getString(R.string.meta_vertical, p.bitrate / 1_000_000)
        else -> getString(R.string.meta_video, p.height, p.bitrate / 1_000_000)
    }

    // ── screen ───────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        val frame = FrameLayout(this).apply { setBackgroundColor(BG) }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            isFillViewport = true; overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false; addView(content)
        }
        frame.addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))

        // ── Header: огромный заголовок + статус лицензии
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (e: Exception) { "" }
        val title = tv(buildSpannedString {
            append(getString(R.string.app_name)); color(LIME) { append(".") }
        }, 26f, TEXT, black = true).apply { letterSpacing = -0.03f }
        val sub = tv("${getString(R.string.subtitle)}  ·  v$ver", 12f, MUTED)
        licDot = View(this)
        licText = tv("", 13f, TEXT, true)
        licBtn = tv("", 13f, LIME, true).apply {
            setPadding(0, dp(6), dp(16), dp(6)); isClickable = true; isFocusable = true
        }
        val licRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(licDot, LinearLayout.LayoutParams(dp(7), dp(7)))
            addView(licText, lp(WRAP, WRAP, start = dp(10)))
        }
        // переключатель языка EN / RU / CE
        val curLang = resources.configuration.locales[0].language.let { if (it == "ru" || it == "ce") it else "en" }
        val langBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(3), dp(3), dp(3), dp(3))
            background = shape(SURF, 16)
            listOf("en" to "EN", "ru" to "RU", "ce" to "CE").forEach { (code, lab) ->
                val sel = code == curLang
                addView(tv(lab, 11f, if (sel) INK else MUTED, true).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(10), dp(6), dp(10), dp(6))
                    background = if (sel) shape(LIME, 13) else null
                    setOnClickListener {
                        if (!busy && !sel) AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(code))
                    }
                })
            }
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(title, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(langBox)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(titleRow, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(sub, lp(WRAP, WRAP, top = dp(2)))
            addView(licRow, lp(WRAP, WRAP, top = dp(12)))
            addView(licBtn, lp(WRAP, WRAP, top = dp(2)))
        }

        // ── Hero: кольцо + крупная цифра
        ring = RingView(this)
        heroBig = tv("+", 40f, LIME, black = true).apply { gravity = Gravity.CENTER; includeFontPadding = false }
        heroSmall = tv(getString(R.string.pick), 12f, MUTED).apply {
            gravity = Gravity.CENTER; setLineSpacing(0f, 1.1f)
        }
        val heroCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            addView(heroBig, LinearLayout.LayoutParams(WRAP, WRAP))
            addView(heroSmall, lp(MATCH, WRAP, top = dp(4)))
        }
        hero = FrameLayout(this).apply {
            background = ripple(shape(SURF, 28), 28)
            isClickable = true; isFocusable = true
            addView(ring, FrameLayout.LayoutParams(dp(170), dp(170), Gravity.CENTER))
            addView(heroCol, FrameLayout.LayoutParams(dp(110), WRAP, Gravity.CENTER))
            pressable(this)
        }
        val hint = tv(getString(R.string.empty), 11f, MUTED).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.15f) }

        // ── Presets: горизонтальная карусель крупных карточек
        val presetLabel = sectionLabel(getString(R.string.section_format))
        val cardRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        presets.forEachIndexed { i, p ->
            val token = tv(tokens[i], 22f, TEXT, black = true).apply { letterSpacing = -0.02f; includeFontPadding = false }
            val spacer = View(this)
            val label = tv(getString(p.label), 12f, TEXT, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
            val m = tv(meta(p), 10f, MUTED).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
                isClickable = true; isFocusable = true
                setOnClickListener { if (!busy) selectPreset(i) }
                addView(token)
                addView(spacer, LinearLayout.LayoutParams(1, 0, 1f))
                addView(label)
                addView(m, lp(WRAP, WRAP, top = dp(2)))
                pressable(this)
            }
            presetCards.add(card); presetTexts.add(listOf(token, label, m))
            cardRow.addView(card, lp(dp(118), dp(116), start = if (i == 0) 0 else dp(8)))
        }
        presetScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false; setPadding(dp(16), 0, dp(16), 0)
            addView(cardRow)
        }
        selectPreset(0)

        // ── Queue
        queueLabel = sectionLabel(getString(R.string.section_queue))
        val clearBtn = tv(getString(R.string.clear_all), 12f, LIME, true).apply {
            setPadding(dp(12), dp(8), dp(4), dp(8)); isClickable = true; isFocusable = true
            setOnClickListener { if (!busy) { clearAll() } }
        }
        queueHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            addView(queueLabel, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(clearBtn)
        }
        queueBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        content.addView(header, block(0))
        content.addView(hero, block(dp(16), dp(210)))
        content.addView(hint, block(dp(10)))
        content.addView(presetLabel, block(dp(22)))
        content.addView(presetScroll, lp(MATCH, WRAP, top = dp(8)))
        content.addView(queueHeader, block(dp(14)))
        content.addView(queueBox, block(dp(6)))

        // ── Bottom CTA
        encodeBtn = Button(this).apply {
            text = getString(R.string.encode); isAllCaps = false; textSize = 15f
            setTypeface(typeface, Typeface.BOLD); setTextColor(INK)
            stateListAnimator = null; elevation = 0f
            background = ripple(shape(LIME, 26), 26)
            pressable(this)
        }
        setEnabledUi(encodeBtn, false)
        cancelBtn = Button(this).apply {
            text = getString(R.string.cancel); isAllCaps = false; textSize = 15f
            setTypeface(typeface, Typeface.BOLD); setTextColor(RED)
            stateListAnimator = null; elevation = 0f; visibility = View.GONE
            background = ripple(shape(SURF2, 26), 26)
            pressable(this)
        }
        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x000A0A0A, 0xE60A0A0A.toInt(), 0xFF0A0A0A.toInt())
            )
            addView(encodeBtn, lp(MATCH, dp(52)))
            addView(cancelBtn, lp(MATCH, dp(52)))
        }
        frame.addView(bottomBar, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))

        fun applyPadding(barH: Int) {
            content.setPadding(0, insetTop + dp(16), 0, barH + dp(8))
            bottomBar.setPadding(dp(16), dp(28), dp(16), insetBottom + dp(10))
        }
        ViewCompat.setOnApplyWindowInsetsListener(frame) { _, insets ->
            val s = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            insetTop = s.top; insetBottom = s.bottom
            applyPadding(bottomBar.height)
            insets
        }
        bottomBar.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val h = bottom - top
            if (content.paddingBottom != h + dp(8)) applyPadding(h)
        }
        applyPadding(dp(90))
        setContentView(frame)

        lic = createLicensing(this) { refreshLic() }
        licBtn.setOnClickListener { lic.onButton() }
        hero.setOnClickListener { if (!busy) picker.launch(arrayOf("video/*")) }
        encodeBtn.setOnClickListener { runQueue() }
        cancelBtn.setOnClickListener { cancelAll() }
        refreshLic()

        // восстановление выбора после смены языка / поворота
        savedInstanceState?.let { b ->
            try {
                selectPreset(b.getInt("preset", 0).coerceIn(0, presets.size - 1))
                BundleCompat.getParcelableArrayList(b, "files", Uri::class.java)?.let { saved ->
                    if (saved.isNotEmpty()) {
                        files.addAll(saved)
                        saved.forEach { states[it] = S_QUEUED; names[it] = displayName(it) }
                        renderList()
                        setEnabledUi(encodeBtn, true)
                        setHero(files.size.toString(), getString(R.string.selected_files))
                    }
                }
            } catch (e: Exception) { files.clear(); states.clear(); names.clear() }
        }

        // эффектное появление
        listOf<View>(header, hero, hint, presetLabel, presetScroll).forEachIndexed { i, v ->
            v.alpha = 0f; v.translationY = dp(18).toFloat()
            v.animate().alpha(1f).translationY(0f).setStartDelay(80L * i).setDuration(520)
                .setInterpolator(DecelerateInterpolator(1.6f)).start()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("preset", selectedPreset)
        if (!busy) outState.putParcelableArrayList("files", ArrayList(files))
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    private fun selectPreset(i: Int) {
        selectedPreset = i
        presetCards.forEachIndexed { j, c ->
            val sel = j == i
            c.background = ripple(shape(if (sel) LIME else SURF, 20), 20)
            val (tk, lb, mt) = presetTexts[j]
            tk.setTextColor(if (sel) INK else TEXT)
            lb.setTextColor(if (sel) INK else TEXT)
            mt.setTextColor(if (sel) INK_DIM else MUTED)
        }
    }

    private fun refreshLic() {
        licText.text = lic.label()
        licBtn.text = lic.buttonText() + "  →"
        licBtn.visibility = if (lic.isLicensed()) View.GONE else View.VISIBLE
        licDot.background = GradientDrawable().apply {
            setShape(GradientDrawable.OVAL); setColor(if (lic.isLicensed()) LIME else MUTED)
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

    private fun loadThumb(u: Uri, iv: ImageView) {
        thumbs[u]?.let { iv.setImageBitmap(it); return }
        io.execute {
            val bmp: Bitmap? = try {
                contentResolver.loadThumbnail(u, Size(200, 200), null)
            } catch (e: Exception) {
                try {
                    val r = MediaMetadataRetriever()
                    try { r.setDataSource(this, u); r.getFrameAtTime(0) } finally { r.release() }
                } catch (e2: Exception) { null }
            }
            if (bmp != null) runOnUiThread { thumbs[u] = bmp; iv.setImageBitmap(bmp) }
        }
    }

    private fun fileRow(u: Uri): View {
        val st = states[u] ?: S_QUEUED
        val (fg, bg) = when (st) {
            S_RUN -> INK to LIME
            S_OK -> LIME to 0x26D4FF3A
            S_FAIL -> RED to 0x26FF5C5C
            else -> MUTED to 0x1AFFFFFF
        }
        val thumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = shape(SURF2, 12); clipToOutline = true; outlineProvider = ViewOutlineProvider.BACKGROUND
        }
        loadThumb(u, thumb)
        val name = tv(names[u] ?: "video", 13f, TEXT, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val pill = tv(statusText(st), 10f, fg, true).apply {
            setPadding(dp(8), dp(2), dp(8), dp(2)); background = shape(bg, 20)
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(name)
            addView(pill, lp(WRAP, WRAP, top = dp(4)))
        }
        val remove = TextView(this).apply {
            text = "✕"; textSize = 13f; setTextColor(MUTED); gravity = Gravity.CENTER
            background = ripple(shape(0x14FFFFFF, 18), 18)
            isClickable = true; isFocusable = true
            setOnClickListener { if (!busy) removeFile(u) }
            visibility = if (busy) View.GONE else View.VISIBLE
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(10), dp(8))
            background = shape(if (st == S_RUN) SURF2 else SURF, 18)
            addView(thumb, LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(col, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(12) })
            addView(remove, LinearLayout.LayoutParams(dp(36), dp(36)))
        }
    }

    private fun renderList(animate: Boolean = false) {
        queueBox.removeAllViews()
        queueHeader.visibility = if (files.isEmpty()) View.GONE else View.VISIBLE
        queueLabel.text = "${getString(R.string.section_queue).uppercase()}  ·  ${files.size}"
        files.forEachIndexed { i, u ->
            val row = fileRow(u)
            queueBox.addView(row, lp(MATCH, WRAP, top = if (i == 0) 0 else dp(6)))
            if (animate && i < 12) {
                row.alpha = 0f; row.translationY = dp(12).toFloat()
                row.animate().alpha(1f).translationY(0f).setStartDelay(50L * i).setDuration(360)
                    .setInterpolator(DecelerateInterpolator()).start()
            }
        }
    }

    private fun removeFile(u: Uri) {
        files.remove(u); states.remove(u); names.remove(u); thumbs.remove(u)
        onFilesChanged()
    }

    private fun clearAll() {
        files.clear(); states.clear(); names.clear(); thumbs.clear()
        onFilesChanged()
    }

    private fun onFilesChanged() {
        renderList()
        ring.idle = true
        if (files.isEmpty()) {
            setEnabledUi(encodeBtn, false)
            setHero("+", getString(R.string.pick))
        } else {
            setHero(files.size.toString(), getString(R.string.selected_files))
        }
    }

    private fun displayName(uri: Uri): String =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "video"

    private fun setBusyUi(b: Boolean) {
        presetScroll.alpha = if (b) 0.35f else 1f
        encodeBtn.visibility = if (b) View.GONE else View.VISIBLE
        cancelBtn.visibility = if (b) View.VISIBLE else View.GONE
    }

    private fun runQueue() {
        busy = true; cancelled = false; lockedHit = false; okCount = 0; failCount = 0
        setBusyUi(true)
        ring.idle = false; ring.setProgress(0f, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val jobs = files.toList()
        val preset = presets[selectedPreset]
        fun next(i: Int) {
            if (cancelled) return
            if (i >= jobs.size) { finishQueue(); return }
            if (!lic.isLicensed() && lic.trialLeft() <= 0) { lockedHit = true; finishQueue(); return }
            ring.setProgress(0f, false)
            setHero("0%", getString(R.string.file_of, i + 1, jobs.size))
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
        setBusyUi(false)
        val msg = when {
            lockedHit -> getString(R.string.locked)
            cancelled -> getString(R.string.stopped, okCount)
            else -> getString(R.string.done, okCount, failCount)
        }
        val big = when {
            lockedHit -> "!"
            cancelled -> files.size.toString()
            okCount > 0 -> "✓"
            else -> "!"
        }
        if (!lockedHit && !cancelled && okCount > 0) ring.setProgress(1f, true) else ring.idle = true
        setHero(big, msg)
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
                if (t.getProgress(holder) != Transformer.PROGRESS_STATE_NOT_STARTED) onProgress(holder.progress)
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
