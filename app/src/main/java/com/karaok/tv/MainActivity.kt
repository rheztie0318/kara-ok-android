package com.karaok.tv

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.util.UnstableApi
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.karaok.engine.Cdg
import com.karaok.engine.KarLyrics
import com.karaok.engine.Lang
import com.karaok.engine.Library
import com.karaok.engine.Lyrics
import com.karaok.engine.MidiFile
import com.karaok.engine.MidiSong
import com.karaok.engine.RemoteServer
import com.karaok.engine.Sfx
import com.karaok.engine.Song
import com.karaok.engine.SoundFont
import com.karaok.engine.Synth
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

@UnstableApi
class MainActivity : Activity(), KaraPlayer.Listener {

    companion object {
        val SCORE_STYLES = listOf("Classic", "Stars", "Party (Tagalog)", "Off")
        val LYRIC_STYLES = listOf(StageView.VIDEOKE, StageView.SCROLLING)
        val TEMPOS = listOf(0.8f, 0.85f, 0.9f, 0.95f, 1.0f, 1.05f, 1.1f, 1.15f, 1.2f)
        val PARTY = listOf(95 to "Pang-Champion! 🏆", 90 to "Grabe, idol ka talaga! 🔥", 85 to "Bigay-todo! 👏",
            80 to "Ang galing! Isa pa! 🎤", 75 to "Pwede na! 😄", 0 to "Konting praktis pa! 😅")
        val MIC_PRESETS = linkedMapOf(          // echo %, delay ms, reverb %
            "Videoke" to Triple(45, 260, 30), "Studio" to Triple(12, 140, 22),
            "Concert hall" to Triple(20, 320, 60), "Slapback" to Triple(35, 110, 10), "Dry" to Triple(0, 260, 0),
        )
        const val REQ_STORAGE = 11
        const val REQ_MIC = 12
    }

    private class Pending(val song: Song, val singer: String) { var count = 3; var ready = false }
    private class Prepared(
        val file: File, val alt: File?, val lyrics: Lyrics?, val cdg: Cdg?, val midi: Boolean,
        val hasMelody: Boolean, val lang: String?, val enc: String?,
    )

    private val main = Handler(Looper.getMainLooper())
    private val work = Executors.newSingleThreadExecutor()
    private val background = Executors.newSingleThreadExecutor()
    private lateinit var prefs: SharedPreferences
    private lateinit var lib: Library
    private lateinit var player: KaraPlayer
    private lateinit var bgPlayer: BackgroundPlayer
    private lateinit var stage: StageView
    private lateinit var surface: SurfaceView
    private lateinit var bar: LinearLayout
    private lateinit var barScroll: HorizontalScrollView
    private lateinit var browser: LinearLayout
    private lateinit var songList: ListView
    private lateinit var searchBox: EditText
    private lateinit var singerBox: EditText
    private lateinit var langSpinner: Spinner
    private lateinit var dedupeBox: CheckBox
    private lateinit var browserTitle: TextView
    private lateinit var queuePanel: LinearLayout
    private lateinit var queueList: ListView
    private val buttons = HashMap<String, Button>()

    private val mic = MicEngine()
    private var remote: RemoteServer? = null
    @Volatile private var remoteState = "{}"
    private var soundPool: SoundPool? = null
    private val sfxIds = LinkedHashMap<String, Int>()

    private val queue = ArrayList<Pair<Song, String>>()
    private var shown: List<Song> = emptyList()
    private var current: Song? = null
    private var singer = ""
    private var pending: Pending? = null
    private val loadToken = AtomicInteger(0)
    private var loaded = -1
    private var prepared: Prepared? = null
    private var key = 0
    private var tempo = 1f
    private var guide = true
    private var hasMelody = false
    private var cdg: Cdg? = null
    private var cdgBitmap: Bitmap? = null
    private var lyrHint = -1
    private var finished = true
    private var scoreTotal = 0.0
    private var scoreActive = 0.0
    private var lastTick = System.nanoTime()
    private var lastInput = System.currentTimeMillis()
    private var synth: Pair<String, Synth>? = null
    private val midiCache = HashMap<String, MidiSong>()
    private var hadStorage = false

    private val dp get() = resources.displayMetrics.density

    // ------------------------------------------------------------------ setup
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs = getSharedPreferences("karaok", Context.MODE_PRIVATE)
        lib = Library(File(filesDir, "library"))
        buildUi()
        player = KaraPlayer(this, this)
        player.attachSurface(surface)
        bgPlayer = BackgroundPlayer(this)
        guide = prefs.getBoolean("guide", true)
        stage.lyricStyle = prefs.getString("lyric_style", StageView.VIDEOKE) ?: StageView.VIDEOKE
        applyMicPrefs()
        setupSoundEffects()
        startRemote()
        refreshLibrary()
        hadStorage = hasStorage()
        if (!hadStorage) askStorage(firstRun = true) else rescan()
        main.post(tick)
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        if (!hadStorage && hasStorage()) { hadStorage = true; rescan() }
    }

    override fun onPause() {
        super.onPause()
        if (isFinishing) return
        player.pause()
        bgPlayer.pause()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        loadToken.incrementAndGet()
        remote?.stop()
        mic.stop()
        player.release()
        bgPlayer.release()
        soundPool?.release()
        work.shutdownNow()
        background.shutdownNow()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
    }

    // ------------------------------------------------------------------ UI
    private fun px(v: Float) = (v * dp).toInt()

    private fun focusBg(): StateListDrawable {
        fun shape(color: Int, stroke: Int) = GradientDrawable().apply {
            cornerRadius = 12 * dp; setColor(color)
            if (stroke != 0) setStroke(px(2f), stroke)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(Color.rgb(255, 79, 163), Color.WHITE))
            addState(intArrayOf(android.R.attr.state_pressed), shape(Color.rgb(124, 92, 255), 0))
            addState(intArrayOf(), shape(Color.argb(220, 28, 25, 56), Color.rgb(44, 40, 82)))
        }
    }

    private fun button(label: String, id: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        background = focusBg()
        setPadding(px(16f), px(8f), px(16f), px(8f))
        isFocusable = true
        setOnClickListener { touch(); onClick() }
        buttons[id] = this
    }

    private fun label(s: String, size: Float = 16f, color: Int = StageView.TEXT) = TextView(this).apply {
        text = s; setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
    }

    private fun panel(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(18f), px(16f), px(18f), px(16f))
        background = GradientDrawable().apply { setColor(Color.argb(240, 20, 18, 42)) }
        visibility = View.GONE
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        surface = SurfaceView(this)
        root.addView(surface, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        stage = StageView(this)
        root.addView(stage, FrameLayout.LayoutParams(-1, -1))

        // control bar
        bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(px(8f), px(8f), px(8f), px(8f)) }
        fun gap() = View(this).apply { layoutParams = LinearLayout.LayoutParams(px(8f), 1) }
        val items = listOf<Triple<String, String, () -> Unit>>(
            Triple("🎵 Songs", "songs") { openBrowser() },
            Triple("📋 Queue", "queue") { openQueue() },
            Triple("⏯", "play") { togglePlay() },
            Triple("⏭ Next", "next") { nextInQueue() },
            Triple("♭ Key", "keydown") { changeKey(-1) },
            Triple("♯ Key", "keyup") { changeKey(1) },
            Triple("Tempo", "tempo") { tempoDialog() },
            Triple("🎶 Guide", "guide") { toggleGuide() },
            Triple("🎤 Vocal cut", "vocal") { toggleVocalCut() },
            Triple("🎙 Mic", "mic") { micDialog() },
            Triple("✨ Effects", "sfx") { sfxDialog() },
            Triple("📱 Phone", "qr") { stage.showQr = !stage.showQr },
            Triple("⚙ Settings", "settings") { settingsDialog() },
        )
        for ((i, it) in items.withIndex()) {
            if (i > 0) bar.addView(gap())
            bar.addView(button(it.first, it.second, it.third))
        }
        barScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(bar)
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(Color.argb(230, 10, 9, 20), Color.argb(0, 10, 9, 20)))
            setPadding(px(12f), px(28f), px(12f), px(10f))
            visibility = View.GONE
        }
        root.addView(barScroll, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        // song browser
        browser = panel()
        browserTitle = label("Songs", 22f, StageView.HL)
        browser.addView(browserTitle)
        searchBox = EditText(this).apply {
            hint = "Search title, artist or number"; setSingleLine(); setTextColor(Color.WHITE); setHintTextColor(StageView.MUTE)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) = renderBrowser()
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        browser.addView(searchBox, LinearLayout.LayoutParams(-1, -2))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        langSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, listOf("All languages") + Lang.LANGUAGES).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = renderBrowser()
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        row.addView(langSpinner, LinearLayout.LayoutParams(0, -2, 1f))
        dedupeBox = CheckBox(this).apply {
            text = "Hide duplicates"; setTextColor(Color.WHITE)
            isChecked = prefs.getBoolean("dedupe", false)
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("dedupe", on).apply(); renderBrowser() }
        }
        row.addView(dedupeBox)
        browser.addView(row)
        singerBox = EditText(this).apply {
            hint = "Singer name (for the queue)"; setSingleLine(); setTextColor(Color.WHITE); setHintTextColor(StageView.MUTE)
            setText(prefs.getString("singer", ""))
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { prefs.edit().putString("singer", s.toString()).apply() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        browser.addView(singerBox, LinearLayout.LayoutParams(-1, -2))
        songList = ListView(this).apply {
            adapter = songAdapter
            selector = focusBg()
            setOnItemClickListener { _, _, pos, _ -> touch(); shown.getOrNull(pos)?.let { songActions(it) } }
        }
        browser.addView(songList, LinearLayout.LayoutParams(-1, 0, 1f))
        browser.addView(label("OK = options · number keys = reserve by number · BACK = close", 13f, StageView.MUTE))
        root.addView(browser, FrameLayout.LayoutParams((resources.displayMetrics.widthPixels * 0.48f).toInt(), -1, Gravity.END))

        // queue
        queuePanel = panel()
        queuePanel.addView(label("Queue", 22f, StageView.HL))
        queueList = ListView(this).apply {
            adapter = queueAdapter
            selector = focusBg()
            setOnItemClickListener { _, _, pos, _ -> touch(); queueActions(pos) }
        }
        queuePanel.addView(queueList, LinearLayout.LayoutParams(-1, 0, 1f))
        queuePanel.addView(label("OK = move up / remove / sing now · BACK = close", 13f, StageView.MUTE))
        root.addView(queuePanel, FrameLayout.LayoutParams((resources.displayMetrics.widthPixels * 0.42f).toInt(), -1, Gravity.END))

        setContentView(root)
    }

    private val songAdapter = object : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(p: Int) = shown[p]
        override fun getItemId(p: Int) = shown[p].number.toLong()
        override fun getView(p: Int, convert: View?, parent: ViewGroup?): View {
            val tv = (convert as? TextView) ?: TextView(this@MainActivity).apply {
                setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                setPadding(px(10f), px(8f), px(10f), px(8f))
            }
            val s = shown[p]
            val meta = listOfNotNull(s.artist.ifEmpty { null }, s.kind, s.lang).joinToString(" · ")
            tv.text = "${s.number}   ${s.title}\n$meta"
            tv.setTextColor(if (s === current) StageView.HL else Color.WHITE)
            return tv
        }
    }

    private val queueAdapter = object : BaseAdapter() {
        override fun getCount() = queue.size
        override fun getItem(p: Int) = queue[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, convert: View?, parent: ViewGroup?): View {
            val tv = (convert as? TextView) ?: TextView(this@MainActivity).apply {
                setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                setPadding(px(10f), px(8f), px(10f), px(8f))
            }
            val (s, who) = queue[p]
            tv.text = "${p + 1}. $who\n${s.number}  ${s.title}"
            return tv
        }
    }

    private fun dialog() = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun touch() { lastInput = System.currentTimeMillis() }

    private fun showBar() {
        touch()
        if (barScroll.visibility != View.VISIBLE) {
            barScroll.visibility = View.VISIBLE
            buttons["songs"]?.requestFocus()
        }
    }

    private fun hideBar() { barScroll.visibility = View.GONE }

    private fun openBrowser() {
        queuePanel.visibility = View.GONE
        hideBar()
        browser.visibility = View.VISIBLE
        renderBrowser()
        songList.requestFocus()
    }

    private fun openQueue() {
        browser.visibility = View.GONE
        hideBar()
        queuePanel.visibility = View.VISIBLE
        queueAdapter.notifyDataSetChanged()
        if (queue.isEmpty()) toast("The queue is empty") else queueList.requestFocus()
    }

    private fun closePanels(): Boolean {
        val open = browser.visibility == View.VISIBLE || queuePanel.visibility == View.VISIBLE
        browser.visibility = View.GONE
        queuePanel.visibility = View.GONE
        return open
    }

    private fun renderBrowser() {
        if (!::songList.isInitialized) return
        val pos = langSpinner.selectedItemPosition
        val lang = if (pos <= 0) null else Lang.LANGUAGES.getOrNull(pos - 1)
        shown = lib.search(searchBox.text.toString(), lang, dedupeBox.isChecked)
        browserTitle.text = "Songs (${shown.size} of ${lib.songs.size})"
        songAdapter.notifyDataSetChanged()
    }

    private fun renderQueue() {
        queueAdapter.notifyDataSetChanged()
        stage.nextUp = queue.firstOrNull()?.let { "${it.second} — ${it.first.title}" } ?: ""
    }

    private fun singerName() = singerBox.text.toString().trim().ifEmpty { "Guest" }

    // ------------------------------------------------------------------ dialogs
    private fun songActions(s: Song) {
        dialog().setTitle("${s.number}  ${s.name}")
            .setItems(arrayOf("▶ Sing now", "＋ Reserve", "⇧ Reserve first", "✕ Remove from list")) { _, which ->
                when (which) {
                    0 -> { closePanels(); startEntry(s, singerName()) }
                    1 -> reserve(s, singerName(), false)
                    2 -> reserve(s, singerName(), true)
                    3 -> if (s !== current) { lib.remove(s); queue.removeAll { it.first === s }; renderQueue(); refreshLibrary() }
                }
            }.show()
    }

    private fun queueActions(pos: Int) {
        val item = queue.getOrNull(pos) ?: return
        dialog().setTitle("${item.second} — ${item.first.title}")
            .setItems(arrayOf("↑ Move up", "✕ Remove", "▶ Sing now")) { _, which ->
                when (which) {
                    0 -> if (pos > 0) { queue.removeAt(pos); queue.add(pos - 1, item) }
                    1 -> queue.removeAt(pos)
                    2 -> { queue.removeAt(pos); closePanels(); startEntry(item.first, item.second) }
                }
                renderQueue()
            }.show()
    }

    private fun numberDialog(first: String) {
        touch()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(24f), px(12f), px(24f), 0) }
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
            setTextColor(StageView.HL)
            setText(first); setSelection(first.length)
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        val title = label("", 18f)
        fun update() {
            val s = lib.find(input.text.toString())
            title.text = s?.name ?: if (input.text.isEmpty()) "Type the song number" else "No song with that number"
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = update()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        box.addView(input); box.addView(title)
        update()
        val d = dialog().setTitle("Song number").setView(box)
            .setPositiveButton("Reserve") { _, _ -> lib.find(input.text.toString())?.let { reserve(it, singerName(), false) } ?: toast("No song with that number") }
            .setNeutralButton("Reserve first") { _, _ -> lib.find(input.text.toString())?.let { reserve(it, singerName(), true) } ?: toast("No song with that number") }
            .setNegativeButton("Cancel", null)
            .create()
        input.setOnEditorActionListener { _, _, _ ->
            lib.find(input.text.toString())?.let { reserve(it, singerName(), false) } ?: toast("No song with that number")
            d.dismiss(); true
        }
        d.show()
        input.requestFocus()
    }

    private fun tempoDialog() {
        val labels = TEMPOS.map { "${(it * 100).toInt()}%" + if (it == tempo) "  ✓" else "" }.toTypedArray()
        dialog().setTitle("Tempo").setItems(labels) { _, i -> setTempo(TEMPOS[i]) }.show()
    }

    private fun sfxDialog() {
        val names = sfxIds.keys.toTypedArray()
        if (names.isEmpty()) { toast("Sound effects are still loading"); return }
        dialog().setTitle("Sound effects (colour keys play the first four)").setItems(names) { _, i -> playSfx(names[i]) }.show()
    }

    private fun micDialog() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(24f), px(8f), px(24f), px(8f)) }
        val toggle = button(if (mic.running) "🎙 Mic is ON — turn off" else "🎙 Turn mic on", "micToggle") {}
        box.addView(toggle)
        fun slider(name: String, prefKey: String, max: Int, def: Int, unit: String, onChange: (Int) -> Unit) {
            val v = prefs.getInt(prefKey, def)
            val lbl = label("$name: $v$unit", 16f)
            val sb = SeekBar(this).apply {
                this.max = max; progress = v; isFocusable = true
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                        lbl.text = "$name: $p$unit"; prefs.edit().putInt(prefKey, p).apply(); onChange(p)
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            }
            box.addView(lbl); box.addView(sb)
        }
        slider("Mic volume", "mic_gain", 200, 100, "%") { mic.fx.gain = it / 100f }
        slider("Echo", "mic_echo", 100, 45, "%") { mic.fx.echo = it / 100f }
        slider("Delay", "mic_delay", 800, 260, " ms") { mic.fx.delaySec = it.coerceAtLeast(60) / 1000f }
        slider("Reverb", "mic_reverb", 100, 30, "%") { mic.fx.reverb = it / 100f }
        box.addView(label("Presets", 16f, StageView.MUTE))
        val presets = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val d = dialog().setTitle("Microphone").setView(box).setPositiveButton("Done", null).create()
        for ((name, v) in MIC_PRESETS) {
            presets.addView(button(name, "preset_$name") {
                prefs.edit().putInt("mic_echo", v.first).putInt("mic_delay", v.second).putInt("mic_reverb", v.third).apply()
                applyMicPrefs(); toast("Mic: $name"); d.dismiss()
            })
        }
        box.addView(HorizontalScrollView(this).apply { addView(presets) })
        toggle.setOnClickListener { d.dismiss(); toggleMic() }
        d.show()
    }

    private fun settingsDialog() {
        val sfName = soundFontFile().nameWithoutExtension
        val items = arrayOf(
            "Lyrics style: ${stage.lyricStyle}",
            "Score after each song: ${prefs.getString("score_style", "Classic")}",
            "Guide melody by default: ${if (prefs.getBoolean("guide", true)) "On" else "Off"}",
            "MIDI sound (SoundFont): $sfName",
            "Background videos: ${if (prefs.getBoolean("bg_on", true)) "On" else "Off"}",
            "Look for new songs now",
            "Allow access to songs on this device / USB",
            "How to add songs",
        )
        dialog().setTitle("Settings").setItems(items) { _, which ->
            when (which) {
                0 -> {
                    val next = LYRIC_STYLES[(LYRIC_STYLES.indexOf(stage.lyricStyle) + 1) % LYRIC_STYLES.size]
                    stage.lyricStyle = next; prefs.edit().putString("lyric_style", next).apply(); toast("Lyrics: $next")
                }
                1 -> dialog().setTitle("Score style").setItems(SCORE_STYLES.toTypedArray()) { _, i ->
                    prefs.edit().putString("score_style", SCORE_STYLES[i]).apply()
                }.show()
                2 -> prefs.edit().putBoolean("guide", !prefs.getBoolean("guide", true)).apply()
                3 -> chooseSoundFont()
                4 -> prefs.edit().putBoolean("bg_on", !prefs.getBoolean("bg_on", true)).apply()
                5 -> rescan()
                6 -> askStorage(firstRun = false)
                7 -> help()
            }
        }.show()
    }

    private fun help() {
        dialog().setTitle("Adding songs").setMessage(
            "Easiest: on your phone or computer (same Wi-Fi), open ${remote?.url ?: "the phone remote"} " +
                "→ Upload tab → choose song files.\n\n" +
                "Or copy songs to a folder named Kara-OK (or Karaoke, Music, Download, SongHub) on this device " +
                "or a USB drive, then Settings → Look for new songs.\n\n" +
                "Formats: MIDI karaoke (.mid .kar), MP3/MP4 and other audio/video, CD+G (.mp3 + .cdg, or .zip). " +
                "Lyrics: an .lrc file with the same name as the song.\n\n" +
                "Better MIDI sound: upload a General MIDI SoundFont (.sf2, e.g. GeneralUser GS), then pick it in Settings."
        ).setPositiveButton("OK", null).show()
    }

    // ------------------------------------------------------------------ storage & library
    private fun hasStorage(): Boolean = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
    else checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun askStorage(firstRun: Boolean) {
        if (hasStorage()) { if (!firstRun) toast("Access already allowed"); rescan(); return }
        if (Build.VERSION.SDK_INT < 30) {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), REQ_STORAGE)
            return
        }
        dialog().setTitle("Find songs on this device?")
            .setMessage("To play songs from this device's storage or a USB drive, Kara-OK needs \"All files access\". " +
                "You can skip this and upload songs from your phone instead.")
            .setPositiveButton("Allow") { _, _ ->
                val pkg = Uri.parse("package:$packageName")
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkg))
                } catch (_: ActivityNotFoundException) {
                    try { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) } catch (_: ActivityNotFoundException) {
                        toast("This TV can't grant file access. Upload songs from your phone instead.")
                    }
                }
            }
            .setNegativeButton("Not now") { _, _ -> rescan() }.show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val ok = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        when (requestCode) {
            REQ_STORAGE -> { hadStorage = ok; rescan() }
            REQ_MIC -> if (ok) toggleMic() else toast("The mic needs permission to record audio")
        }
    }

    private val uploadDir get() = File(getExternalFilesDir(null) ?: filesDir, "Songs")

    @Suppress("DEPRECATION")
    private fun songRoots(): List<File> {
        val roots = arrayListOf(uploadDir)
        if (hasStorage()) {
            val ext = Environment.getExternalStorageDirectory()
            for (n in listOf("Kara-OK", "KaraOK", "Karaoke", "SongHub", "Music", "Download", "Movies")) roots.add(File(ext, n))
            // USB drives and SD cards
            File("/storage").listFiles()?.forEach { if (it.isDirectory && it.name != "emulated" && it.name != "self") roots.add(it) }
            File("/mnt/media_rw").listFiles()?.forEach { if (it.isDirectory) roots.add(it) }
        }
        return roots
    }

    private fun rescan() {
        stage.hint = "Looking for songs…"
        background.execute {
            val added = try { lib.scan(songRoots()) } catch (_: Exception) { 0 }
            main.post {
                refreshLibrary()
                stage.hint = if (lib.songs.isEmpty()) "No songs yet — upload from your phone (scan the QR code) or copy them to a Kara-OK folder" else ""
                if (added > 0) toast("Found $added new song${if (added == 1) "" else "s"}")
            }
            detectLanguages()
        }
    }

    /** Work out the language (and MIDI text encoding) of songs that don't have one yet. Runs in the background. */
    private fun detectLanguages() {
        val todo = lib.undetected()
        if (todo.isEmpty()) return
        for ((i, s) in todo.withIndex()) {
            if (isDestroyed) return
            try {
                if (s.isMidi) { val m = MidiFile.load(s.file); s.enc = m.encoding; s.lang = m.language } else s.lang = s.detectLanguage()
            } catch (_: Exception) { s.lang = "Other" }
            if (i % 100 == 99) main.post { stage.hint = "Sorting songs by language… ${i + 1} of ${todo.size}" }
        }
        lib.save()
        main.post { stage.hint = ""; refreshLibrary() }
    }

    private fun refreshLibrary() {
        renderBrowser()
        if (lib.songs.isNotEmpty() && stage.hint.startsWith("No songs")) stage.hint = ""
    }

    private fun soundFontFiles(): List<File> {
        val out = ArrayList<File>()
        val builtin = File(filesDir, "TimGM6mb.sf2")
        if (!builtin.isFile || builtin.length() < 1_000_000) {
            try { assets.open("TimGM6mb.sf2").use { i -> builtin.outputStream().use { i.copyTo(it) } } } catch (_: Exception) {}
        }
        out.add(builtin)
        for (r in songRoots()) {
            r.listFiles()?.filter { it.extension.equals("sf2", true) }?.let { out.addAll(it) }
            File(r, "SoundFonts").listFiles()?.filter { it.extension.equals("sf2", true) }?.let { out.addAll(it) }
        }
        return out.distinctBy { it.absolutePath }
    }

    private fun soundFontFile(): File {
        val want = prefs.getString("soundfont", "") ?: ""
        val all = soundFontFiles()
        return all.firstOrNull { it.absolutePath == want } ?: all.first()
    }

    private fun chooseSoundFont() {
        val all = soundFontFiles()
        dialog().setTitle("MIDI sound (SoundFont)")
            .setItems(all.map { it.nameWithoutExtension + if (it == all[0]) " (built-in)" else "" }.toTypedArray()) { _, i ->
                prefs.edit().putString("soundfont", all[i].absolutePath).apply()
                synth = null
                toast("MIDI songs will use ${all[i].nameWithoutExtension}")
            }.show()
    }

    // ------------------------------------------------------------------ sound effects
    private fun setupSoundEffects() {
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        val pool = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(attrs).build()
        soundPool = pool
        background.execute {
            val files = try {
                val sf = try { SoundFont(soundFontFile()) } catch (_: Exception) { null }
                Sfx.makeBuiltin(File(filesDir, "sfx"), 44100, sf)
            } catch (_: Exception) { LinkedHashMap() }
            val own = File(uploadDir, "Sound Effects").listFiles()?.filter { it.extension.lowercase() in setOf("wav", "ogg", "mp3") }
                ?.take(8)?.associate { "🔊 " + it.nameWithoutExtension.take(18) to it } ?: emptyMap()
            main.post {
                for ((name, f) in files + own) sfxIds[name] = pool.load(f.absolutePath, 1)
            }
        }
    }

    private fun playSfx(name: String) {
        val id = sfxIds[name] ?: return
        soundPool?.play(id, 0.9f, 0.9f, 1, 0, 1f)
    }

    // ------------------------------------------------------------------ phone remote
    private fun startRemote() {
        val page = try { assets.open("remote.html").bufferedReader().readText() } catch (_: Exception) { "<h1>Kara-OK</h1>" }
        val srv = RemoteServer(page, uploadDir, object : RemoteServer.Handler {
            override fun songs() = lib.songs
            override fun state() = remoteState
            override fun reserve(number: Int, singer: String, first: Boolean) {
                main.post { lib.find(number)?.let { reserve(it, singer, first) } }
            }
            override fun control(action: String, arg: String) { main.post { remoteControl(action, arg) } }
            override fun uploaded(file: File) {
                background.execute {
                    if (file.extension.lowercase() in Library.SONG_EXT) lib.addFiles(listOf(file))
                    else if (file.extension.lowercase() in setOf("lrc", "txt")) lib.songs.filter { it.file.nameWithoutExtension == file.nameWithoutExtension }.forEach { it.loadLyrics() }
                    detectLanguages()
                    main.post { refreshLibrary(); toast("Received ${file.name}") }
                }
            }
        })
        try {
            srv.start()
            remote = srv
            stage.qrUrl = srv.url
            stage.qr = makeQr(srv.url)
        } catch (e: Exception) {
            toast("Phone remote unavailable: ${e.message}")
        }
    }

    private fun makeQr(text: String): Bitmap? = try {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 1))
        val bmp = Bitmap.createBitmap(m.width, m.height, Bitmap.Config.ARGB_8888)
        for (y in 0 until m.height) for (x in 0 until m.width) bmp.setPixel(x, y, if (m.get(x, y)) Color.BLACK else Color.WHITE)
        bmp
    } catch (_: Exception) { null }

    private fun remoteControl(action: String, arg: String) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        when (action) {
            "playpause" -> togglePlay()
            "next" -> nextInQueue()
            "restart" -> player.seekTo(0.0)
            "stop" -> { player.pause(); player.seekTo(0.0) }
            "key_up" -> changeKey(1)
            "key_down" -> changeKey(-1)
            "key_reset" -> changeKey(0)
            "vol_up" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            "vol_down" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            "melody" -> toggleGuide()
            "vocal" -> toggleVocalCut()
            "score" -> {
                val cur = prefs.getString("score_style", "Classic")
                val next = if (cur == "Off") prefs.getString("last_score", "Classic") else "Off"
                if (cur != "Off") prefs.edit().putString("last_score", cur).apply()
                prefs.edit().putString("score_style", next).apply()
                notice(if (next == "Off") "Score off" else "Score: $next")
            }
            "sfx" -> playSfx(arg)
        }
    }

    private fun updateRemoteState() {
        val cur = current
        val q = queue.joinToString(",", "[", "]") {
            "{\"singer\":${RemoteServer.q(it.second)},\"number\":${it.first.number},\"title\":${RemoteServer.q(it.first.title)}}"
        }
        val sfx = sfxIds.keys.joinToString(",", "[", "]") { RemoteServer.q(it) }
        remoteState = "{\"song\":${RemoteServer.q(if (cur != null) "${cur.number} ${cur.title}" else "")}," +
            "\"singer\":${RemoteServer.q(singer)},\"key\":$key,\"playing\":${player.isPlayingOrBuffering},\"queue\":$q,\"sfx\":$sfx}"
    }

    // ------------------------------------------------------------------ singing
    private fun reserve(s: Song, who: String, first: Boolean) {
        if (first) queue.add(0, s to who) else queue.add(s to who)
        renderQueue()
        notice("${if (first) "Reserved FIRST" else "Reserved"}: ${s.number} ${s.title} — $who")
        val idleNow = current == null || (finished && !player.isPlayingOrBuffering)
        if (idleNow && pending == null && queue.size == 1) nextInQueue()
    }

    private fun nextInQueue() {
        if (queue.isEmpty()) { notice("The queue is empty"); return }
        val (s, who) = queue.removeAt(0)
        renderQueue()
        startEntry(s, who)
    }

    private fun startEntry(s: Song, who: String) {
        main.removeCallbacks(countdown); main.removeCallbacks(revealScore); main.removeCallbacks(afterScore)
        finished = false
        singer = who
        stage.singer = who
        pending = Pending(s, who)
        stage.overlay = StageView.Overlay.UpNext(who, s.title, 3)
        load(s)
        main.postDelayed(countdown, 1000)
    }

    private val countdown = object : Runnable {
        override fun run() {
            val p = pending ?: return
            p.count--
            if (p.count <= 0) tryBegin(false)
            else { stage.overlay = StageView.Overlay.UpNext(p.singer, p.song.title, p.count); main.postDelayed(this, 1000) }
        }
    }

    private fun tryBegin(force: Boolean) {
        val p = pending ?: return
        if (!p.ready) { stage.overlay = StageView.Overlay.UpNext(p.singer, p.song.title, 0); return }
        if (p.count > 0 && !force) return
        main.removeCallbacks(countdown)
        pending = null
        stage.overlay = null
        scoreTotal = 0.0; scoreActive = 0.0
        player.start()
        bgPlayer.resume()
    }

    private fun load(s: Song) {
        player.stop()
        bgPlayer.stop()
        player.attachSurface(surface)
        current = s
        key = 0; tempo = 1f
        player.setKeyTempo(0, 1f)
        guide = prefs.getBoolean("guide", true)
        stage.song = s; stage.idle = false; stage.videoShowing = false
        stage.lyrics = null; stage.cdg = null
        cdg = null; lyrHint = -1; prepared = null; hasMelody = false
        renderBrowser()
        val token = loadToken.incrementAndGet()
        val cacheDir = cacheDir
        work.execute {
            try {
                val p = if (s.isMidi) prepareMidi(s, token) else {
                    val (audio, cdgFile) = s.mediaFiles(cacheDir)
                    Prepared(audio, null, s.lyrics, cdgFile?.let { Cdg.load(it) }, false, false, null, null)
                }
                if (p != null) main.post { onPrepared(token, p) }
            } catch (e: Exception) {
                main.post { onFailed(token, e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    /** Render a MIDI song to WAV (cached), with and without the guide melody. Runs on the work thread. */
    private fun prepareMidi(s: Song, token: Int): Prepared? {
        val ms = midiCache[s.file.absolutePath] ?: MidiFile.load(s.file, s.enc).also {
            if (midiCache.size > 20) midiCache.clear()
            midiCache[s.file.absolutePath] = it
        }
        val sfFile = soundFontFile()
        val id = Library.sha1("${s.file.absolutePath}|${s.file.length()}|${s.file.lastModified()}|${sfFile.absolutePath}|${sfFile.length()}").take(20)
        val dir = File(cacheDir, "render").apply { mkdirs() }
        val on = File(dir, "$id-on.wav")
        val off = File(dir, "$id-off.wav")
        if (!(on.isFile && off.isFile && on.length() > 1000 && off.length() > 1000)) {
            val sy = synth?.takeIf { it.first == sfFile.absolutePath }?.second
                ?: Synth(SoundFont(sfFile), 44100).also { synth = sfFile.absolutePath to it }
            val tmpOn = File(dir, "$id-on.tmp"); val tmpOff = File(dir, "$id-off.tmp")
            val r = sy.render(ms.notes, ms.melodyChannel, tmpOn, tmpOff,
                progress = { f -> main.post { onProgress(token, f) } },
                cancelled = { loadToken.get() != token })
            if (r == null) { tmpOn.delete(); tmpOff.delete(); return null }
            tmpOn.renameTo(on); tmpOff.renameTo(off)
            trimRenderCache(dir)
        } else {
            on.setLastModified(System.currentTimeMillis()); off.setLastModified(System.currentTimeMillis())
        }
        val lyr = s.lyrics ?: if (ms.hasWords) KarLyrics(ms) else null
        val melody = ms.melodyChannel >= 0
        return Prepared(if (guide || !melody) on else off, if (guide || !melody) off else on, lyr, null, true, melody, ms.language, ms.encoding)
    }

    private fun trimRenderCache(dir: File) {
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        var total = 0L
        for (f in files) { total += f.length(); if (total > 900L * 1024 * 1024) f.delete() }
    }

    private fun onProgress(token: Int, f: Float) {
        if (token != loadToken.get()) return
        val p = pending
        if (p != null) stage.overlay = StageView.Overlay.UpNext(p.singer, p.song.title, p.count)
        notice("Preparing MIDI song… ${(f * 100).toInt()}%", 1500)
    }

    private fun onFailed(token: Int, msg: String) {
        if (token != loadToken.get()) return
        main.removeCallbacks(countdown)
        pending = null
        stage.overlay = null
        dialog().setTitle("Couldn't play this song").setMessage("${current?.name}\n\n$msg").setPositiveButton("OK", null).show()
        finished = true
    }

    private fun onPrepared(token: Int, p: Prepared) {
        if (token != loadToken.get()) return
        val s = current ?: return
        loaded = token
        prepared = p
        if (s.isMidi && (s.lang == null || s.enc != p.enc)) { s.lang = p.lang; s.enc = p.enc; background.execute { lib.save() } }
        stage.lyrics = p.lyrics
        cdg = p.cdg?.also { it.reset() }
        cdgBitmap = if (p.cdg != null) Bitmap.createBitmap(Cdg.W, Cdg.H, Bitmap.Config.ARGB_8888) else null
        hasMelody = p.hasMelody
        player.load(p.file)
        if (p.cdg == null) maybeBackground(s)
        notice("")
        val pend = pending
        if (pend != null) { pend.ready = true; if (pend.count <= 0) tryBegin(false) }
    }

    private fun maybeBackground(s: Song) {
        if (s.hasVideo || !prefs.getBoolean("bg_on", true)) return
        val dirs = songRoots().map { File(it, "Backgrounds") } + File(uploadDir, "Backgrounds")
        val vids = dirs.flatMap { d -> d.listFiles()?.filter { it.extension.lowercase() in Library.VIDEO_EXT } ?: emptyList() }
        if (vids.isEmpty()) return
        player.attachSurface(null)
        bgPlayer.start(vids, surface)
        bgPlayer.pause()
        stage.videoShowing = true
    }

    override fun onEnded() {
        if (current == null) return
        bgPlayer.pause()
        val style = prefs.getString("score_style", "Classic") ?: "Classic"
        if (style == "Off") { afterScore.run(); return }
        stage.overlay = StageView.Overlay.DrumRoll(singer.ifEmpty { "Singer" })
        playSfx(Sfx.NAMES[2])
        main.postDelayed(revealScore, 2300)
    }

    private val revealScore = Runnable {
        val score = if (scoreTotal >= 10) (62 + 38 * minOf(1.0, scoreActive / scoreTotal / 0.85)).toInt() else Random.nextInt(76, 100)
        val style = prefs.getString("score_style", "Classic") ?: "Classic"
        val msg = if (style == "Party (Tagalog)") PARTY.first { score >= it.first }.second else when {
            score >= 95 -> "🌟 Superstar!"; score >= 88 -> "🔥 Amazing!"; score >= 80 -> "👏 Great job!"
            score >= 70 -> "🎶 Nice one!"; else -> "😄 Brave performance!"
        }
        val shownScore = if (style == "Stars") { val n = (score / 20.0).toInt().coerceIn(1, 5); "★".repeat(n) + "☆".repeat(5 - n) } else score.toString()
        stage.overlay = StageView.Overlay.Score(singer.ifEmpty { "Singer" }, shownScore, msg)
        playSfx(if (score >= 88) Sfx.NAMES[3] else Sfx.NAMES[0])
        main.postDelayed(afterScore, 6000)
    }

    private val afterScore = Runnable {
        if (stage.overlay is StageView.Overlay.Score || stage.overlay is StageView.Overlay.DrumRoll) stage.overlay = null
        if (queue.isNotEmpty()) nextInQueue() else finished = true
    }

    override fun onHasVideo(hasVideo: Boolean) {
        if (hasVideo) { bgPlayer.stop(); player.attachSurface(surface); stage.videoShowing = true }
        else if (!bgPlayer.active) stage.videoShowing = false
    }

    override fun onVideoSize(width: Int, height: Int, ratio: Float) {
        val sw = resources.displayMetrics.widthPixels.toFloat()
        val sh = resources.displayMetrics.heightPixels.toFloat()
        val aspect = width * ratio / height
        val (w, h) = if (sw / sh > aspect) (sh * aspect) to sh else sw to (sw / aspect)
        surface.layoutParams = FrameLayout.LayoutParams(w.toInt(), h.toInt(), Gravity.CENTER)
    }

    override fun onError(message: String) {
        onFailed(loadToken.get(), message)
    }

    private fun togglePlay() {
        if (pending != null) { tryBegin(true); return }
        if (current == null) { if (queue.isNotEmpty()) nextInQueue() else openBrowser(); return }
        player.toggle()
        if (player.isPlayingOrBuffering) bgPlayer.resume() else bgPlayer.pause()
    }

    private fun changeKey(delta: Int) {
        if (current == null) return
        key = if (delta == 0) 0 else (key + delta).coerceIn(-6, 6)
        player.setKeyTempo(key, tempo)
        notice(if (key == 0) "Original key" else "Key ${if (key > 0) "+" else ""}$key")
    }

    private fun setTempo(t: Float) {
        tempo = t
        player.setKeyTempo(key, tempo)
        notice("Tempo ${(t * 100).toInt()}%")
    }

    private fun toggleGuide() {
        val p = prepared
        val s = current
        if (s == null || !s.isMidi || p == null || !hasMelody || p.alt == null) { notice("Guide melody is for MIDI songs"); return }
        guide = !guide
        val now = p.alt
        prepared = Prepared(now, p.file, p.lyrics, p.cdg, p.midi, p.hasMelody, p.lang, p.enc)
        player.swap(now)
        notice(if (guide) "Guide melody on" else "Guide melody off")
    }

    private fun toggleVocalCut() {
        val on = player.vocal.amount < 0.5f
        player.vocal.amount = if (on) 1f else 0f
        notice(if (on) "Vocal cut on" else "Vocal cut off")
    }

    private fun applyMicPrefs() {
        mic.fx.gain = prefs.getInt("mic_gain", 100) / 100f
        mic.fx.echo = prefs.getInt("mic_echo", 45) / 100f
        mic.fx.delaySec = prefs.getInt("mic_delay", 260).coerceAtLeast(60) / 1000f
        mic.fx.reverb = prefs.getInt("mic_reverb", 30) / 100f
    }

    private fun toggleMic() {
        if (mic.running) { mic.stop(); notice("Mic off"); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        val err = mic.start()
        if (err != null) dialog().setTitle("Microphone").setMessage(err).setPositiveButton("OK", null).show()
        else notice("Mic on — keep it away from the speakers")
    }

    private fun notice(s: String, ms: Long = 2500) {
        stage.notice = s
        main.removeCallbacks(clearNotice)
        if (s.isNotEmpty()) main.postDelayed(clearNotice, ms)
    }

    private val clearNotice = Runnable { stage.notice = "" }

    // ------------------------------------------------------------------ main loop
    private var lastRemote = 0L

    private val tick = object : Runnable {
        override fun run() {
            val now = System.nanoTime()
            val dt = (now - lastTick) / 1e9
            lastTick = now
            val s = current
            if (s != null && loaded == loadToken.get()) {
                val t = player.positionSec
                val dur = player.durationSec
                stage.progress = if (dur > 0) (t / dur).toFloat().coerceIn(0f, 1f) else 0f
                val c = cdg
                val bmp = cdgBitmap
                if (c != null && bmp != null) {
                    if (c.advanceTo(t) || stage.cdg == null) {
                        bmp.setPixels(c.render(), 0, Cdg.W, 0, 0, Cdg.W, Cdg.H)
                        stage.cdg = bmp
                    }
                }
                val lyr = stage.lyrics
                var singing = c != null || lyr != null
                if (lyr != null && lyr.synced) {
                    val st = lyr.stateAt(t, lyrHint)
                    lyrHint = st.index
                    stage.lyricState = st
                    singing = st.singing
                }
                if (mic.running && player.isPlaying && singing && dt < 0.25) {
                    scoreTotal += dt
                    if (mic.level > 0.015f) scoreActive += dt
                }
            }
            // hide the control bar a while after the last key press
            if (barScroll.visibility == View.VISIBLE && System.currentTimeMillis() - lastInput > 9000 && player.isPlaying) hideBar()
            if (now - lastRemote > 500_000_000L) { lastRemote = now; updateRemoteState() }
            stage.invalidate()
            main.postDelayed(this, 33)
        }
    }

    // ------------------------------------------------------------------ remote control keys
    private fun digitOf(code: Int): String? = when (code) {
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> (code - KeyEvent.KEYCODE_0).toString()
        in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> (code - KeyEvent.KEYCODE_NUMPAD_0).toString()
        else -> null
    }

    @SuppressLint("GestureBackNavigation")
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        touch()
        val panelsOpen = browser.visibility == View.VISIBLE || queuePanel.visibility == View.VISIBLE
        val typing = currentFocus is EditText
        digitOf(keyCode)?.let { d -> if (!typing) { numberDialog(d); return true } }
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                if (closePanels()) { showBar(); return true }
                if (barScroll.visibility == View.VISIBLE) { hideBar(); return true }
                dialog().setTitle("Exit Kara-OK?").setPositiveButton("Exit") { _, _ -> finish() }.setNegativeButton("Stay", null).show()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> { togglePlay(); return true }
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { nextInQueue(); return true }
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> { changeKey(1); return true }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> { changeKey(-1); return true }
            KeyEvent.KEYCODE_PROG_RED -> { sfxIds.keys.elementAtOrNull(0)?.let { playSfx(it) }; return true }
            KeyEvent.KEYCODE_PROG_GREEN -> { sfxIds.keys.elementAtOrNull(1)?.let { playSfx(it) }; return true }
            KeyEvent.KEYCODE_PROG_YELLOW -> { sfxIds.keys.elementAtOrNull(2)?.let { playSfx(it) }; return true }
            KeyEvent.KEYCODE_PROG_BLUE -> { sfxIds.keys.elementAtOrNull(3)?.let { playSfx(it) }; return true }
            KeyEvent.KEYCODE_MENU -> { if (barScroll.visibility == View.VISIBLE) hideBar() else showBar(); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (pending != null && !panelsOpen && barScroll.visibility != View.VISIBLE) { tryBegin(true); return true }
                if (stage.overlay is StageView.Overlay.Score && !panelsOpen) {
                    main.removeCallbacks(afterScore); afterScore.run(); return true
                }
                if (!panelsOpen && barScroll.visibility != View.VISIBLE) { showBar(); return true }
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (!panelsOpen && barScroll.visibility != View.VISIBLE) { showBar(); return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}
