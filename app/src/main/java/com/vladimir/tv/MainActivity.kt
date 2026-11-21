
package com.vladimir.tv

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.KeyEvent
import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.content.res.Configuration
import android.widget.*
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.UUID
import java.net.URLEncoder
import android.content.pm.ActivityInfo
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import android.text.format.DateFormat
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import kotlin.concurrent.thread

class MainActivity : Activity() {

    data class Entry(
        val title: String,
        val url: String,
        val group: String,
        val logo: String = "",
        val type: String = "tv",
        val epgId: String = ""
    )

    private val prefs by lazy {
        getSharedPreferences("vladimir2", MODE_PRIVATE)
    }

    private val items = mutableListOf<Entry>()
    private val vodItems = mutableListOf<Entry>()
    private var currentEntry: Entry? = null
    data class Programme(val channel: String, val start: Long, val stop: Long, val title: String, val description: String)
    private val epgNow = mutableMapOf<String, String>()
    private val epgSchedule = mutableMapOf<String, MutableList<Programme>>()
    private val epgChannelNames = mutableMapOf<String, String>()
    private var playerFrame: FrameLayout? = null
    private var sidePanel: View? = null
    private val clockHandler = Handler(Looper.getMainLooper())
    private val lists = mutableListOf<Pair<String, String>>()

    private var current = "home"
    private var lang = "ru"
    private var activeList = -1

    private lateinit var base: LinearLayout
    private lateinit var content: LinearLayout

    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null

    private var remoteServer: ServerSocket? = null
    private val remoteToken = UUID.randomUUID()
        .toString().replace("-", "")

    @Volatile
    private var remoteRunning = false

    private val bg = Color.rgb(12, 17, 29)
    private val panel = Color.rgb(28, 36, 53)
    private val accent: Int
        get() = when (prefs.getString("theme", "blue")) {
            "red" -> Color.rgb(229, 35, 66)
            "green" -> Color.rgb(34, 171, 111)
            "purple" -> Color.rgb(148, 90, 222)
            "gold" -> Color.rgb(211, 155, 50)
            else -> Color.rgb(48, 124, 232)
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun isPhone(): Boolean {
        val mode = resources.configuration.uiMode and
            Configuration.UI_MODE_TYPE_MASK
        val width = resources.displayMetrics.widthPixels /
            resources.displayMetrics.density
        return mode != Configuration.UI_MODE_TYPE_TELEVISION &&
            width < 700
    }

    private fun tr(ru: String, de: String, en: String): String =
        when (lang) {
            "de" -> de
            "en" -> en
            else -> ru
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!isPhone()) {
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }

        lang = prefs.getString("language", "ru") ?: "ru"

        try {
            val stored = prefs.getString("playlists", "[]") ?: "[]"
            val array = JSONArray(stored)

            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                lists.add(
                    obj.optString("name") to obj.optString("url")
                )
            }
        } catch (_: Exception) {
        }

        activeList = prefs.getInt(
            "active",
            if (lists.isEmpty()) -1 else 0
        )

        player = ExoPlayer.Builder(this).build()
        player?.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                runOnUiThread { toast(tr("Ошибка воспроизведения: ", "Wiedergabefehler: ", "Playback error: ") + error.errorCodeName) }
            }
        })

        startRemoteServer()
        showHome()

        if (activeList in lists.indices) {
            loadPlaylist(activeList)
        }
        loadVodPlaylist()
        loadEpg()
        if (prefs.getString("xtream_server", "").orEmpty().isNotBlank()) loadXtream()
    }

    private fun saveLists() {
        val array = JSONArray()

        lists.forEach {
            array.put(
                JSONObject()
                    .put("name", it.first)
                    .put("url", it.second)
            )
        }

        prefs.edit()
            .putString("playlists", array.toString())
            .putInt("active", activeList)
            .apply()
    }

    private fun background(color: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(12).toFloat()
        }

    private fun label(
        value: String,
        size: Float = 19f,
        bold: Boolean = false
    ): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(9), dp(12), dp(9))

            if (bold) {
                setTypeface(null, Typeface.BOLD)
            }
        }

    private fun button(
        value: String,
        action: () -> Unit
    ): Button =
        Button(this).apply {
            text = value
            textSize = 16f
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = background(panel)
            isFocusable = true
            setOnFocusChangeListener { _, hasFocus ->
                background = background(if (hasFocus) accent else panel)
            }
            setOnClickListener { action() }
        }

    private fun artwork(url: String, width: Int, height: Int): ImageView =
        ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(width), dp(height)).apply {
                marginEnd = dp(10)
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.rgb(35, 45, 63))
            contentDescription = tr("Изображение канала или фильма", "Sender- oder Filmbild", "Channel or movie artwork")
            if (url.startsWith("https://") || url.startsWith("http://")) {
                Glide.with(this@MainActivity).load(url)
                    .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                    .centerInside().into(this)
            } else {
                setImageResource(android.R.drawable.ic_menu_gallery)
            }
        }

    private fun sectionTitle(value: String) {
        content.addView(label(value, 25f, true))
    }

    private fun tile(
        title: String,
        subtitle: String,
        action: () -> Unit
    ): View {
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = true
            isClickable = true
            setPadding(dp(16), dp(18), dp(16), dp(18))

            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(
                    Color.rgb(48, 58, 83),
                    Color.rgb(20, 26, 42)
                )
            ).apply {
                cornerRadius = dp(18).toFloat()
            }

            setOnClickListener { action() }
        }

        view.addView(label(title, 22f, true))
        view.addView(label(subtitle, 13f))
        return view
    }

    private fun scaffold(page: String) {
        if (isPhone()) window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        current = page

        base = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }

        setContentView(base)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        header.addView(
            label("VladiMir TV 3.1", 26f, true),
            LinearLayout.LayoutParams(0, -2, 1f)
        )

        header.addView(
            button("⚙") { showSettings() }
        )

        if (prefs.getBoolean("show_clock", true)) {
            val clock = label("", 17f, true)
            header.addView(clock)
            val refresh = object : Runnable {
                override fun run() {
                    if (clock.parent != null) {
                        clock.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                        clockHandler.postDelayed(this, 30000)
                    }
                }
            }
            refresh.run()
        }
        base.addView(header)

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val menu = listOf(
            Triple(
                "home",
                tr("Главная", "Start", "Home"),
                { showHome() }
            ),
            Triple(
                "tv",
                tr("ТВ", "Fernsehen", "Live TV"),
                { showCatalog("tv") }
            ),
            Triple(
                "favorites",
                tr("Избранное", "Favoriten", "Favorites"),
                { showCatalog("favorites") }
            )
        )

        if (page != "settings" && page != "remote" && page != "playlists") menu.forEach { (id, name, action) ->
            val b = button(name, action)
            b.background = background(
                if (id == page) accent else panel
            )

            nav.addView(
                b,
                LinearLayout.LayoutParams(
                    dp(if (isPhone()) 132 else 170),
                    dp(55)
                )
            )
        }

        val navScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }

        navScroll.addView(nav)
        base.addView(navScroll)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
        }

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(20))
        }

        scroll.addView(content)

        base.addView(
            scroll,
            LinearLayout.LayoutParams(-1, 0, 1f)
        )
    }

    private fun showHome() {
        scaffold("home")

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))

            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(
                    accent,
                    Color.rgb(21, 28, 48)
                )
            ).apply {
                cornerRadius = dp(18).toFloat()
            }
        }

        hero.addView(label("VLADIMIR TV 3.1", 32f, true))

        hero.addView(
            label(
                tr(
                    "Твой домашний кинотеатр",
                    "Dein Heimkino",
                    "Your home cinema"
                ),
                23f,
                true
            )
        )

        hero.addView(
            button(
                tr(
                    "▶ Смотреть ТВ",
                    "▶ Fernsehen",
                    "▶ Watch TV"
                )
            ) {
                showCatalog("tv")
            }
        )

        content.addView(hero)

        sectionTitle(
            tr(
                "Что будем смотреть?",
                "Was möchtest du sehen?",
                "What would you like to watch?"
            )
        )

        val sections = listOf(
            Triple(
                tr("ТЕЛЕКАНАЛЫ", "FERNSEHEN", "LIVE TV"),
                tr("Все каналы", "Alle Sender", "All channels"),
                "tv"
            ),
            Triple(
                tr("ИЗБРАННОЕ", "FAVORITEN", "FAVORITES"),
                tr("Любимые", "Favoriten", "Favorites"),
                "favorites"
            ),
            Triple(tr("ТЕЛЕПРОГРАММА", "PROGRAMM", "TV GUIDE"), tr("Программа передач", "Fernsehprogramm", "TV schedule"), "guide"),
            Triple(
                tr("НАСТРОЙКИ", "EINSTELLUNGEN", "SETTINGS"),
                tr("Источники и QR", "Quellen und QR", "Sources and QR"),
                "settings"
            )
        )

        if (isPhone()) {
            sections.forEach { (name, sub, id) ->
                content.addView(
                    tile(name, sub) { openSection(id) },
                    LinearLayout.LayoutParams(-1, dp(116))
                )
            }
        } else {
            sections.chunked(3).forEach { group ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                }

                group.forEach { (name, sub, id) ->
                    row.addView(
                        tile(name, sub) { openSection(id) },
                        LinearLayout.LayoutParams(
                            0,
                            dp(150),
                            1f
                        )
                    )
                }

                content.addView(row)
            }
        }

        if (lists.isEmpty()) {
            content.addView(
                label(
                    tr(
                        "Добавь первый M3U-плейлист",
                        "Erste M3U-Playlist hinzufügen",
                        "Add your first M3U playlist"
                    )
                )
            )
        } else {
            content.addView(
                label("${items.size} каналов и видео")
            )
        }
    }

    private fun openSection(id: String) {
        when (id) {
            "playlists" -> showPlaylists()
            "remote" -> showRemote()
            "settings" -> showSettings()
            "guide" -> showCatalog("tv")
            else -> showCatalog(id)
        }
    }

    private fun showCatalog(type: String) {
        scaffold(type)

        sectionTitle(
            when (type) {
                "tv" -> tr("Телеканалы", "Fernsehen", "Live TV")
                "movies" -> tr("Фильмы", "Filme", "Movies")
                else -> tr("Избранное", "Favoriten", "Favorites")
            }
        )

        if ((if (type == "movies") vodItems else items).isEmpty()) {
            content.addView(
                label(
                    tr(
                        "Сначала добавь M3U-плейлист",
                        "Bitte M3U-Playlist hinzufügen",
                        "Please add an M3U playlist"
                    )
                )
            )

            content.addView(
                button("+ M3U") { showPlaylists() }
            )
            return
        }

        val favorites =
            prefs.getStringSet("favorites", emptySet())
                ?: emptySet()

        val candidates = (if (type == "movies") vodItems else items).filter {
            when (type) {
                "favorites" -> favorites.contains(it.url)
                "tv" -> it.type == "tv"
                "movies" -> true
                else -> it.type == type
            }
        }

        if (candidates.isEmpty()) {
            content.addView(
                label(
                    tr(
                        "Нет данных",
                        "Keine Einträge",
                        "No entries"
                    )
                )
            )
            return
        }

        val orderedCandidates = if (type == "tv" && prefs.getBoolean("sort_channels_alpha", false))
            candidates.sortedBy { it.title.lowercase(Locale.ROOT) }
        else candidates

        val groups = orderedCandidates.map { it.group }
            .distinct()
            .sorted()

        val options = listOf(
            tr(
                "Все категории",
                "Alle Kategorien",
                "All categories"
            )
        ) + groups

        val filter = Spinner(this)

        filter.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            options
        )

        content.addView(filter)

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        content.addView(list)

        fun fill(group: String) {
            list.removeAllViews()

            orderedCandidates
                .filter {
                    group.isEmpty() || it.group == group
                }
                .take(1500)
                .forEach { entry ->

                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                    }

                    row.gravity = Gravity.CENTER_VERTICAL
                    if (prefs.getBoolean("show_artwork", true)) {
                        val mode = if (type == "tv") prefs.getInt("channel_layout", 0) else 0
                        val logoWidth = when (mode) { 1 -> 144; 2 -> 52; else -> if (type == "movies") 66 else 78 }
                        val logoHeight = when (mode) { 1 -> 96; 2 -> 42; else -> if (type == "movies") 94 else 58 }
                        row.addView(artwork(entry.logo, logoWidth, logoHeight))
                    }
                    val guide = epgNow[entry.epgId.ifBlank { entry.title }] ?: ""
                    row.addView(
                        button("▶ ${entry.title}" + if (guide.isNotBlank() && type != "movies") "  •  $guide" else "") {
                            play(entry)
                        },
                        LinearLayout.LayoutParams(
                            0,
                            dp(58),
                            1f
                        )
                    )

                    row.addView(
                        button(
                            if (favorites.contains(entry.url))
                                "★" else "☆"
                        ) {
                            toggleFav(entry)
                            showCatalog(type)
                        }
                    )

                    list.addView(row)
                }
        }

        filter.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {

                override fun onNothingSelected(
                    parent: AdapterView<*>?
                ) {
                }

                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    fill(
                        if (position == 0) ""
                        else groups[position - 1]
                    )
                }
            }
    }

    private fun toggleFav(entry: Entry) {
        val set = (
            prefs.getStringSet("favorites", emptySet())
                ?: emptySet()
        ).toMutableSet()

        if (!set.add(entry.url)) {
            set.remove(entry.url)
        }

        prefs.edit()
            .putStringSet("favorites", set)
            .apply()
    }

    private fun showPlaylists() {
        scaffold("playlists")

        sectionTitle(
            tr(
                "Управление плейлистами",
                "Playlisten verwalten",
                "Manage playlists"
            )
        )

        content.addView(
            button(
                tr(
                    "+ Добавить M3U",
                    "+ M3U hinzufügen",
                    "+ Add M3U"
                )
            ) {
                editList(-1)
            }
        )

        lists.toList().forEachIndexed { index, pair ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            row.addView(
                button(
                    (if (index == activeList) "✓ " else "") +
                        pair.first
                ) {
                    activeList = index
                    saveLists()
                    loadPlaylist(index)
                    showHome()
                },
                LinearLayout.LayoutParams(0, dp(60), 1f)
            )

            row.addView(
                button("✎") {
                    editList(index)
                }
            )

            row.addView(
                button("×") {
                    AlertDialog.Builder(this)
                        .setMessage(
                            tr(
                                "Удалить плейлист?",
                                "Playlist löschen?",
                                "Delete playlist?"
                            )
                        )
                        .setPositiveButton("OK") { _, _ ->
                            lists.removeAt(index)
                            activeList =
                                if (lists.isEmpty()) -1 else 0

                            saveLists()

                            if (activeList >= 0) {
                                loadPlaylist(activeList)
                            } else {
                                items.clear()
                            }

                            showPlaylists()
                        }
                        .setNegativeButton(
                            tr("Отмена", "Abbrechen", "Cancel"),
                            null
                        )
                        .show()
                }
            )

            content.addView(row)
        }
    }

    private fun editList(index: Int) {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }

        val name = EditText(this).apply {
            hint = tr("Название", "Name", "Name")
            setSingleLine(true)
            setText(lists.getOrNull(index)?.first ?: "")
        }

        val url = EditText(this).apply {
            hint = "https://..."
            setSingleLine(true)
            setText(lists.getOrNull(index)?.second ?: "")
        }

        form.addView(name)
        form.addView(url)

        AlertDialog.Builder(this)
            .setTitle("M3U")
            .setView(form)
            .setPositiveButton(
                tr("Сохранить", "Speichern", "Save")
            ) { _, _ ->

                val value = url.text.toString().trim()

                if (!value.startsWith("https://") &&
                    !value.startsWith("http://")
                ) {
                    toast("HTTP(S) URL required")
                    return@setPositiveButton
                }

                val title = name.text.toString()
                    .trim()
                    .ifEmpty { "Playlist ${lists.size + 1}" }

                if (index >= 0) {
                    lists[index] = title to value
                } else {
                    lists.add(title to value)
                }

                activeList =
                    if (index >= 0) index else lists.lastIndex

                saveLists()
                loadPlaylist(activeList)
                showPlaylists()
            }
            .setNegativeButton(
                tr("Отмена", "Abbrechen", "Cancel"),
                null
            )
            .show()
    }

    private fun loadPlaylist(index: Int) {
        val url = lists.getOrNull(index)?.second ?: return

        thread {
            try {
                val connection =
                    URL(url).openConnection() as HttpURLConnection

                connection.connectTimeout = 15000
                connection.readTimeout = 30000
                connection.instanceFollowRedirects = true

                val body = connection.inputStream
                    .bufferedReader()
                    .use { it.readText() }

                connection.disconnect()

                val parsed = parseM3u(body)

                runOnUiThread {
                    if (index == activeList) {
                        items.removeAll { it.url !in parsed.map { entry -> entry.url } && !it.url.contains("/live/") }
                        items.addAll(parsed.filter { entry -> items.none { it.url == entry.url } })
                        toast("${parsed.size} items")
                        showHome()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    toast("M3U: ${e.message}")
                }
            }
        }
    }

    private fun parseM3u(raw: String): List<Entry> {
        val result = ArrayList<Entry>()

        var title = ""
        var group = ""
        var logo = ""
        var epgId = ""

        val attributes = Regex(
            "([\\w-]+)=\"([^\"]*)\""
        )

        for (line in raw.lineSequence().map { it.trim() }) {
            if (line.startsWith("#EXTINF", true)) {
                val attrs = attributes.findAll(line)
                    .associate {
                        it.groupValues[1] to it.groupValues[2]
                    }

                title = line.substringAfterLast(
                    ',',
                    attrs["tvg-name"] ?: "Stream"
                ).trim()

                group = attrs["group-title"] ?: "Other"
                logo = attrs["tvg-logo"] ?: ""
                epgId = attrs["tvg-id"] ?: ""

            } else if (
                line.startsWith("http://") ||
                line.startsWith("https://")
            ) {
                // A TV playlist's group-title is a category, not proof of VOD.
                val type = "tv"

                result.add(
                    Entry(
                        title.ifBlank { "Stream" },
                        line,
                        group.ifBlank { "Other" },
                        logo,
                        type,
                        epgId
                    )
                )

                title = ""
                group = ""
                logo = ""
                epgId = ""
            }
        }

        return result
    }

    private fun play(entry: Entry) {
        currentEntry = entry
        requestedOrientation = if (isPhone()) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        prefs.edit().putString("recent", entry.url).apply()
        current = "player"
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        val frame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val view = PlayerView(this).apply {
            useController = true
            controllerAutoShow = false
            isFocusable = true
            isFocusableInTouchMode = true
            resizeMode = when (prefs.getString("video_scale", "fit")) {
                "zoom" -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                "fill" -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
                else -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
            player = this@MainActivity.player
            setOnClickListener { showChannelOverlay() }
            setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_MENU -> { showChannelOverlay(); true }
                    KeyEvent.KEYCODE_DPAD_LEFT -> { showSideGuide(); true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { showChannelOverlay(); true }
                    KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_MEDIA_NEXT -> { changeChannel(1); true }
                    KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { changeChannel(-1); true }
                    else -> false
                }
            }
        }
        playerView = view
        frame.addView(view, FrameLayout.LayoutParams(-1, -1))
        playerFrame = frame
        sidePanel = null
        if (isPhone()) {
            val left = View(this).apply {
                setBackgroundColor(Color.TRANSPARENT)
                setOnClickListener { showSideGuide() }
            }
            val right = View(this).apply {
                setBackgroundColor(Color.TRANSPARENT)
                setOnClickListener { showChannelOverlay() }
            }
            frame.addView(left, FrameLayout.LayoutParams(dp(42), -1, Gravity.START))
            frame.addView(right, FrameLayout.LayoutParams(dp(42), -1, Gravity.END))
        }
        val channelButton = button(tr("Каналы", "Sender", "Channels")) { showChannelOverlay() }
        frame.addView(channelButton, FrameLayout.LayoutParams(dp(155), dp(55), Gravity.TOP or Gravity.END))
        if (prefs.getBoolean("show_clock", true)) {
            val clock = android.widget.TextClock(this).apply {
                format24Hour = "HH:mm"; format12Hour = "hh:mm a"
                setTextColor(Color.WHITE); textSize = 19f
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setBackgroundColor(0x99000000.toInt())
            }
            frame.addView(clock, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
        }
        setContentView(frame)
        view.requestFocus()
        try {
            player?.setMediaItem(MediaItem.fromUri(Uri.parse(entry.url)))
            player?.prepare()
            player?.play()
        } catch (e: Exception) { toast(e.message ?: "Playback error") }
    }

    private fun changeChannel(direction: Int) {
        val channels = items.filter { it.type == "tv" }
        if (channels.isEmpty()) return
        val index = channels.indexOfFirst { it.url == currentEntry?.url }
        play(channels[(index + direction + channels.size) % channels.size])
    }

    private fun closeSidePanel() {
        sidePanel?.let { panelView -> (panelView.parent as? FrameLayout)?.removeView(panelView) }
        sidePanel = null
        playerView?.requestFocus()
    }

    private fun openPanel(left: Boolean, heading: String, fill: (LinearLayout) -> Unit) {
        val frame = playerFrame ?: return
        closeSidePanel()
        val panelWidth = (resources.displayMetrics.widthPixels * 0.42f).toInt()
            .coerceAtMost(dp(480)).coerceAtLeast(dp(250))
        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.rgb(27, 39, 66), Color.rgb(10, 17, 31))
            ).apply { cornerRadius = dp(18).toFloat(); setStroke(dp(2), accent) }
            isFocusableInTouchMode = true
        }
        val headingRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        headingRow.addView(label(heading, 21f, true), LinearLayout.LayoutParams(0, -2, 1f))
        headingRow.addView(button("✕") { closeSidePanel() })
        shell.addView(headingRow)
        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body)
        shell.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        frame.addView(shell, FrameLayout.LayoutParams(panelWidth, -1,
            if (left) Gravity.START else Gravity.END))
        sidePanel = shell
        fill(body)
        shell.requestFocus()
        val focus = body.getChildAt(0)
        if (focus != null) focus.requestFocus()
    }

    private fun showChannelOverlay() {
        val all = items.filter { it.type == "tv" }
        if (all.isEmpty()) { toast(tr("Нет каналов", "Keine Sender", "No channels")); return }
        val channels = if (prefs.getBoolean("sort_channels_alpha", false))
            all.sortedBy { it.title.lowercase(Locale.ROOT) } else all
        val groups = channels.map { it.group.ifBlank { "Other" } }.distinct().sorted()
        openPanel(false, tr("Каналы →", "Sender →", "Channels →")) { body ->
            val categoryList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val channelList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            body.addView(categoryList)
            body.addView(channelList)
            fun display(group: String?) {
                channelList.removeAllViews()
                val filtered = channels.filter { group == null || it.group.ifBlank { "Other" } == group }
                channelList.addView(label("${filtered.size} " + tr("каналов", "Sender", "channels"), 15f))
                filtered.take(1500).forEach { entry ->
                    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                    row.addView(artwork(entry.logo, 65, 46))
                    row.addView(button(entry.title) { play(entry) }, LinearLayout.LayoutParams(0, dp(54), 1f))
                    channelList.addView(row)
                }
            }
            categoryList.addView(button(tr("Все каналы", "Alle Sender", "All channels")) { display(null) })
            groups.forEach { group -> categoryList.addView(button(group) { display(group) }) }
            display(currentEntry?.group)
        }
    }

    private fun guideEntries(entry: Entry): List<Programme> {
        val id = entry.epgId
        val byId = epgSchedule[id]
        if (!byId.isNullOrEmpty()) return byId.sortedBy { it.start }
        val normalized = entry.title.trim().lowercase(Locale.ROOT)
        val match = epgChannelNames.entries.firstOrNull { it.value.trim().lowercase(Locale.ROOT) == normalized }
        return epgSchedule[match?.key].orEmpty().sortedBy { it.start }
    }

    private fun showSideGuide() {
        val entry = currentEntry ?: return
        val now = System.currentTimeMillis()
        openPanel(true, tr("← Телепрограмма", "← Programm", "← TV guide")) { body ->
            body.addView(label(entry.title, 19f, true))
            val schedule = guideEntries(entry)
            if (schedule.isEmpty()) {
                body.addView(label(tr("Нет EPG для этого канала. Добавь XMLTV в настройках.",
                    "Kein EPG für diesen Sender. XMLTV in Einstellungen hinzufügen.",
                    "No EPG for this channel. Add XMLTV in settings.")))
            }
            val format = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
            schedule.filter { it.stop > now - 7L * 86400000 && it.start < now + 7L * 86400000 }
                .take(400).forEach { programme ->
                    val past = programme.stop < now
                    val text = format.format(Date(programme.start)) + "  " + programme.title +
                        if (past) "  ◷" else ""
                    body.addView(button(text) {
                        AlertDialog.Builder(this)
                            .setTitle(programme.title)
                            .setMessage(format.format(Date(programme.start)) + " – " +
                                format.format(Date(programme.stop)) + "\n\n" + programme.description +
                                if (past) "\n\n" + tr(
                                    "Для воспроизведения записи требуется Catch-up провайдера.",
                                    "Zur Wiedergabe ist Anbieter-Catch-up erforderlich.",
                                    "Playback requires provider Catch-up support.") else "")
                            .setPositiveButton("OK", null).show()
                    })
                }
        }
    }

    private fun loadVodPlaylist() {
        val address = prefs.getString("vod_url", "")?.trim().orEmpty()
        if (!address.startsWith("http://") && !address.startsWith("https://")) return
        thread {
            try {
                val conn = URL(address).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                val raw = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val parsed = parseM3u(raw).map { it.copy(type = "movies") }
                runOnUiThread {
                    vodItems.removeAll { it.url !in parsed.map { entry -> entry.url } && !it.url.contains("/movie/") }
                    vodItems.addAll(parsed.filter { entry -> vodItems.none { it.url == entry.url } })
                    if (current == "movies") showCatalog("movies")
                }
            } catch (e: Exception) { runOnUiThread { toast("VOD: ${e.message}") } }
        }
    }

    private fun editVodUrl() {
        val field = EditText(this).apply {
            hint = "https://..."
            setSingleLine(true)
            setText(prefs.getString("vod_url", ""))
        }
        AlertDialog.Builder(this)
            .setTitle(tr("Ссылка видеотеки", "Mediathek-URL", "Video library URL"))
            .setView(field)
            .setPositiveButton(tr("Сохранить", "Speichern", "Save")) { _, _ ->
                val url = field.text.toString().trim()
                if (url.isNotEmpty() && !url.startsWith("https://") && !url.startsWith("http://")) {
                    toast("HTTP(S) URL required")
                } else {
                    prefs.edit().putString("vod_url", url).apply()
                    vodItems.clear()
                    loadVodPlaylist()
                }
            }
            .setNegativeButton(tr("Отмена", "Abbrechen", "Cancel"), null)
            .show()
    }

    private fun showSettings() {
        scaffold("settings")

        sectionTitle(
            tr(
                "Настройки",
                "Einstellungen",
                "Settings"
            )
        )

        content.addView(
            label(
                tr(
                    "Язык интерфейса",
                    "Sprache",
                    "Interface language"
                )
            )
        )

        val languages = listOf(
            "Русский" to "ru",
            "Deutsch" to "de",
            "English" to "en"
        )

        languages.forEach { (name, code) ->
            content.addView(
                button(
                    (if (lang == code) "✓ " else "") + name
                ) {
                    lang = code
                    prefs.edit()
                        .putString("language", code)
                        .apply()
                    showSettings()
                }
            )
        }

        content.addView(button(tr("Цветовая тема", "Farbschema", "Color theme")) {
            val names = arrayOf(tr("Синяя", "Blau", "Blue"), tr("Красная", "Rot", "Red"),
                tr("Зелёная", "Grün", "Green"), tr("Фиолетовая", "Lila", "Purple"),
                tr("Золотая", "Gold", "Gold"))
            val ids = arrayOf("blue", "red", "green", "purple", "gold")
            AlertDialog.Builder(this).setTitle(tr("Оформление", "Design", "Appearance"))
                .setItems(names) { _, index ->
                    prefs.edit().putString("theme", ids[index]).apply()
                    showSettings()
                }.show()
        })
        content.addView(button(tr("Видеотека (отдельно от ТВ)", "Mediathek (getrennt vom TV)",
            "Video library (separate from TV)")) { showCatalog("movies") })
        content.addView(
            button(
                tr(
                    "Управление плейлистами",
                    "Playlisten verwalten",
                    "Manage playlists"
                )
            ) {
                showPlaylists()
            }
        )

        content.addView(button(tr("Размер логотипов: обычные / крупные / компактные",
            "Logogröße: normal / groß / kompakt",
            "Logo size: normal / large / compact")) {
            val labels = arrayOf(tr("Список", "Liste", "List"),
                tr("Крупные логотипы", "Große Logos", "Large logos"),
                tr("Компактные логотипы", "Kompakte Logos", "Compact logos"))
            AlertDialog.Builder(this)
                .setTitle(tr("Вид каналов", "Senderansicht", "Channel view"))
                .setSingleChoiceItems(labels, prefs.getInt("channel_layout", 0)) { dialog, index ->
                    prefs.edit().putInt("channel_layout", index).apply()
                    dialog.dismiss()
                    toast(tr("Вид сохранён", "Ansicht gespeichert", "Layout saved"))
                }.setNegativeButton(tr("Отмена", "Abbrechen", "Cancel"), null).show()
        })
        content.addView(button(tr("Сортировка каналов: плейлист / по алфавиту",
            "Sender sortieren: Playlist / Alphabet",
            "Channel sorting: playlist / alphabetical")) {
            val alphabetical = !prefs.getBoolean("sort_channels_alpha", false)
            prefs.edit().putBoolean("sort_channels_alpha", alphabetical).apply()
            toast(if (alphabetical) tr("По алфавиту", "Alphabetisch", "Alphabetical")
                else tr("Порядок плейлиста", "Playlist-Reihenfolge", "Playlist order"))
        })
        content.addView(button(tr("Xtream Codes: сервер и доступ", "Xtream Codes: Server und Zugang", "Xtream Codes: server and login")) { editXtream() })
        content.addView(button(tr("EPG: адрес XMLTV", "EPG: XMLTV-Adresse", "EPG: XMLTV URL")) { editEpg() })
        content.addView(button(tr("Обновить ТВ и видеотеку", "TV und Mediathek aktualisieren", "Refresh TV and library")) {
            if (activeList in lists.indices) loadPlaylist(activeList)
            loadVodPlaylist()
            loadXtream()
            loadEpg()
        })
        content.addView(
            button(tr("Отдельная ссылка видеотеки", "Separate Mediathek-URL", "Separate video library URL")) {
                editVodUrl()
            }
        )
        content.addView(
            button(tr("Логотипы и постеры: вкл. / выкл.", "Logos und Poster: ein / aus", "Logos and posters: on / off")) {
                val enabled = prefs.getBoolean("show_artwork", true)
                prefs.edit().putBoolean("show_artwork", !enabled).apply()
                showSettings()
            }
        )
        content.addView(
            button(tr("Часы: включить / выключить", "Uhr ein / aus", "Clock on / off")) {
                val enabled = prefs.getBoolean("show_clock", true)
                prefs.edit().putBoolean("show_clock", !enabled).apply()
                showSettings()
            }
        )
        content.addView(
            button(tr("Масштаб видео: оригинал / заполнить / растянуть", "Videogröße: Original / Zoom / Strecken", "Video scale: Fit / Zoom / Stretch")) {
                val currentScale = prefs.getString("video_scale", "fit") ?: "fit"
                val next = when (currentScale) { "fit" -> "zoom"; "zoom" -> "fill"; else -> "fit" }
                prefs.edit().putString("video_scale", next).apply()
                toast(next)
            }
        )
        content.addView(
            button("QR / Phone") {
                showRemote()
            }
        )
    }

    private fun editXtream() {
        val wrapper = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        fun field(hint: String, key: String, secret: Boolean = false): EditText = EditText(this).apply {
            this.hint = hint
            setSingleLine(true)
            setText(prefs.getString(key, ""))
            if (secret) inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            wrapper.addView(this)
        }
        val server = field(tr("Сервер https://...", "Server https://...", "Server https://..."), "xtream_server")
        val user = field(tr("Логин", "Benutzername", "Username"), "xtream_user")
        val pass = field(tr("Пароль", "Passwort", "Password"), "xtream_pass", true)
        AlertDialog.Builder(this).setTitle("Xtream Codes").setView(wrapper)
            .setPositiveButton(tr("Сохранить", "Speichern", "Save")) { _, _ ->
                val host = server.text.toString().trim().trimEnd('/')
                if (host.isNotBlank() && !host.startsWith("http://") && !host.startsWith("https://")) {
                    toast(tr("Нужен адрес HTTP(S)", "HTTP(S)-Adresse erforderlich", "HTTP(S) address required"))
                } else {
                    prefs.edit().putString("xtream_server", host).putString("xtream_user", user.text.toString().trim())
                        .putString("xtream_pass", pass.text.toString()).apply()
                    if (host.isNotBlank()) loadXtream()
                }
            }.setNegativeButton(tr("Отмена", "Abbrechen", "Cancel"), null).show()
    }

    private fun loadXtream() {
        val server = prefs.getString("xtream_server", "").orEmpty().trimEnd('/')
        val user = prefs.getString("xtream_user", "").orEmpty()
        val pass = prefs.getString("xtream_pass", "").orEmpty()
        if (server.isBlank() || user.isBlank() || pass.isBlank()) return
        thread {
            try {
                fun encode(s: String) = URLEncoder.encode(s, "UTF-8")
                val query = "username=${encode(user)}&password=${encode(pass)}"
                fun fetch(action: String): JSONArray {
                    val url = URL("$server/player_api.php?$query&action=$action")
                    val c = url.openConnection() as HttpURLConnection
                    c.connectTimeout = 15000; c.readTimeout = 30000
                    try { return JSONArray(c.inputStream.bufferedReader().use { it.readText() }) }
                    finally { c.disconnect() }
                }
                val liveGroups = mutableMapOf<String, String>()
                val vodGroups = mutableMapOf<String, String>()
                fun categories(action: String, target: MutableMap<String, String>) {
                    val array = fetch(action)
                    for (i in 0 until array.length()) {
                        val obj = array.getJSONObject(i)
                        target[obj.optString("category_id")] = obj.optString("category_name", "Other")
                    }
                }
                categories("get_live_categories", liveGroups)
                categories("get_vod_categories", vodGroups)
                val live = mutableListOf<Entry>()
                val streams = fetch("get_live_streams")
                for (i in 0 until streams.length()) {
                    val o = streams.getJSONObject(i)
                    val id = o.optString("stream_id")
                    if (id.isNotBlank()) live.add(Entry(o.optString("name", "TV"),
                        "$server/live/${encode(user)}/${encode(pass)}/$id.ts",
                        liveGroups[o.optString("category_id")] ?: "Other", o.optString("stream_icon"), "tv", o.optString("epg_channel_id")))
                }
                val films = mutableListOf<Entry>()
                val vod = fetch("get_vod_streams")
                for (i in 0 until vod.length()) {
                    val o = vod.getJSONObject(i)
                    val id = o.optString("stream_id")
                    if (id.isNotBlank()) films.add(Entry(o.optString("name", "Movie"),
                        "$server/movie/${encode(user)}/${encode(pass)}/$id.${o.optString("container_extension", "mp4")}",
                        vodGroups[o.optString("category_id")] ?: "Other", o.optString("stream_icon"), "movies"))
                }
                runOnUiThread {
                    // Xtream is an additional source; do not silently overwrite M3U content.
                    items.removeAll { it.url.startsWith("$server/live/") }
                    vodItems.removeAll { it.url.startsWith("$server/movie/") }
                    items.addAll(live); vodItems.addAll(films)
                    toast("Xtream: ${live.size} TV / ${films.size} VOD")
                    if (current != "player") showHome()
                }
            } catch (e: Exception) {
                runOnUiThread { toast("Xtream: ${e.message ?: "Connection error"}") }
            }
        }
    }

    private fun editEpg() {
        val field = EditText(this).apply {
            hint = "https://.../epg.xml"; setSingleLine(true)
            setText(prefs.getString("epg_url", ""))
        }
        AlertDialog.Builder(this).setTitle("XMLTV EPG").setView(field)
            .setPositiveButton(tr("Сохранить", "Speichern", "Save")) { _, _ ->
                val address = field.text.toString().trim()
                if (address.isEmpty() || address.startsWith("http://") || address.startsWith("https://")) {
                    prefs.edit().putString("epg_url", address).apply(); loadEpg()
                } else toast("HTTP(S) URL required")
            }.setNegativeButton(tr("Отмена", "Abbrechen", "Cancel"), null).show()
    }

    private fun loadEpg() {
        val address = prefs.getString("epg_url", "").orEmpty()
        if (!address.startsWith("https://") && !address.startsWith("http://")) return
        thread {
            try {
                val conn = URL(address).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000; conn.readTimeout = 45000
                val schedule = mutableMapOf<String, MutableList<Programme>>()
                val names = mutableMapOf<String, String>()
                val currentTitles = mutableMapOf<String, String>()
                val now = System.currentTimeMillis()
                try {
                    val factory = javax.xml.parsers.SAXParserFactory.newInstance()
                    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                    factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
                    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                    val parser = factory.newSAXParser()
                    val raw = conn.inputStream
                    val source = if (address.substringBefore('?').lowercase(Locale.ROOT).endsWith(".gz") ||
                        conn.contentEncoding.equals("gzip", true)) java.util.zip.GZIPInputStream(raw) else raw
                    source.use { input ->
                        parser.parse(input, object : org.xml.sax.helpers.DefaultHandler() {
                            var channel = ""; var start = 0L; var stop = 0L
                            var channelId = ""; var title = ""; var description = ""
                            var field = ""; val chars = StringBuilder()
                            fun xmlTime(value: String): Long {
                                return try {
                                    val rawTime = value.trim()
                                    val fmt = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
                                    val offset = rawTime.substringAfter(' ', "+0000")
                                    fmt.timeZone = java.util.TimeZone.getTimeZone("GMT$offset")
                                    fmt.parse(rawTime.take(14))?.time ?: 0L
                                } catch (_: Exception) { 0L }
                            }
                            override fun startElement(uri: String?, localName: String?, qName: String?, attrs: org.xml.sax.Attributes) {
                                when (qName) {
                                    "channel" -> channelId = attrs.getValue("id") ?: ""
                                    "programme" -> {
                                        channel = attrs.getValue("channel") ?: ""
                                        start = xmlTime(attrs.getValue("start") ?: "")
                                        stop = xmlTime(attrs.getValue("stop") ?: "")
                                        title = ""; description = ""
                                    }
                                    "title", "desc", "display-name" -> { field = qName; chars.setLength(0) }
                                }
                            }
                            override fun characters(ch: CharArray, index: Int, length: Int) {
                                if (field.isNotEmpty()) chars.append(ch, index, length)
                            }
                            override fun endElement(uri: String?, localName: String?, qName: String?) {
                                if (qName == field) {
                                    when (field) {
                                        "title" -> title = chars.toString().trim()
                                        "desc" -> description = chars.toString().trim()
                                        "display-name" -> if (channelId.isNotBlank() && !names.containsKey(channelId)) names[channelId] = chars.toString().trim()
                                    }
                                    field = ""
                                }
                                if (qName == "programme" && channel.isNotBlank() && start > 0L && stop > start &&
                                    stop > now - 7L * 86400000 && start < now + 7L * 86400000) {
                                    val programmes = schedule.getOrPut(channel) { mutableListOf() }
                                    if (programmes.size < 800) programmes.add(Programme(channel, start, stop, title, description))
                                    if (now in start until stop) currentTitles[channel] = title
                                }
                                if (qName == "channel") channelId = ""
                            }
                        })
                    }
                } finally { conn.disconnect() }
                runOnUiThread {
                    epgSchedule.clear(); epgSchedule.putAll(schedule)
                    epgChannelNames.clear(); epgChannelNames.putAll(names)
                    epgNow.clear(); epgNow.putAll(currentTitles)
                    if (current == "tv") showCatalog("tv")
                }
            } catch (e: Exception) { runOnUiThread { toast("EPG: ${e.message}") } }
        }
    }

    private fun localIp(): String? {
        return try {
            val interfaces =
                NetworkInterface.getNetworkInterfaces()

            var ip: String? = null

            while (interfaces.hasMoreElements() && ip == null) {
                val network = interfaces.nextElement()

                if (!network.isLoopback && network.isUp) {
                    val addresses = network.inetAddresses

                    while (addresses.hasMoreElements()) {
                        val address = addresses.nextElement()

                        if (
                            address is Inet4Address &&
                            !address.isLoopbackAddress &&
                            !address.isLinkLocalAddress
                        ) {
                            ip = address.hostAddress
                            break
                        }
                    }
                }
            }

            ip
        } catch (_: Exception) {
            null
        }
    }

    private fun remoteUrl(): String? =
        localIp()?.let {
            "http://$it:8765/?key=$remoteToken"
        }

    private fun showRemote() {
        scaffold("remote")

        sectionTitle(
            tr(
                "Управление с телефона",
                "Steuerung mit Handy",
                "Phone control"
            )
        )

        val url = remoteUrl()

        if (url == null || !remoteRunning) {
            content.addView(
                label(
                    tr(
                        "Подключи телефон и ТВ к одному Wi-Fi",
                        "TV und Handy mit demselben WLAN verbinden",
                        "Connect TV and phone to the same Wi-Fi"
                    )
                )
            )
            return
        }

        content.addView(
            label(
                tr(
                    "Сканируй QR-код телефоном",
                    "QR-Code mit Handy scannen",
                    "Scan QR with phone"
                )
            )
        )

        try {
            val matrix = MultiFormatWriter().encode(
                url,
                BarcodeFormat.QR_CODE,
                240,
                240
            )

            val bitmap = Bitmap.createBitmap(
                240,
                240,
                Bitmap.Config.ARGB_8888
            )

            for (y in 0 until 240) {
                for (x in 0 until 240) {
                    bitmap.setPixel(
                        x,
                        y,
                        if (matrix.get(x, y))
                            Color.BLACK else Color.WHITE
                    )
                }
            }

            val image = ImageView(this)
            image.setImageBitmap(bitmap)

            content.addView(
                image,
                LinearLayout.LayoutParams(dp(240), dp(240)).apply { gravity = Gravity.CENTER_HORIZONTAL }
            )

        } catch (e: Exception) {
            content.addView(
                label("QR error: ${e.message}")
            )
        }

        content.addView(label(url, 14f))
    }

    private fun startRemoteServer() {
        if (remoteRunning) return

        thread(name = "VladiMirRemote") {
            try {
                val server = ServerSocket(8765)
                remoteServer = server
                remoteRunning = true

                while (!server.isClosed) {
                    val client = server.accept()
                    thread {
                        handleRemote(client)
                    }
                }
            } catch (_: Exception) {
                remoteRunning = false
            }
        }
    }

    private fun escapeHtml(value: String): String =
        value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

    private fun handleRemote(socket: Socket) {
        socket.use { client ->
            try {
                client.soTimeout = 5000

                val input = client.getInputStream()
                    .bufferedReader()

                val first = input.readLine() ?: return
                val parts = first.split(' ')

                if (parts.size < 2) return

                val uri = Uri.parse(parts[1])
                val path = uri.path ?: "/"
                val key = uri.getQueryParameter("key")

                var body = ""
                var status = "200 OK"

                if (key != remoteToken) {
                    status = "403 Forbidden"
                    body = "Forbidden"

                } else if (path == "/play") {
                    val index = uri.getQueryParameter("id")
                        ?.toIntOrNull()

                    if (index != null) {
                        runOnUiThread {
                            items.getOrNull(index)?.let {
                                play(it)
                            }
                        }
                    }

                    body = """
                        <p>Sent to TV</p>
                        <a href="/?key=$remoteToken">Back</a>
                    """.trimIndent()

                } else if (path == "/add") {
                    val name = uri.getQueryParameter("name")
                        ?.take(100)?.trim().orEmpty()

                    val url = uri.getQueryParameter("url")
                        ?.take(4096)?.trim().orEmpty()

                    if (!url.startsWith("https://") &&
                        !url.startsWith("http://")
                    ) {
                        status = "400 Bad Request"
                        body = "Invalid URL"
                    } else {
                        runOnUiThread {
                            lists.add(
                                name.ifBlank {
                                    "Playlist ${lists.size + 1}"
                                } to url
                            )

                            activeList = lists.lastIndex
                            saveLists()
                            loadPlaylist(activeList)
                        }

                        body = """
                            <meta charset="utf-8">
                            <p>Playlist added</p>
                            <a href="/?key=$remoteToken">Back</a>
                        """.trimIndent()
                    }

                } else if (path == "/" || path == "/search") {
                    val query =
                        uri.getQueryParameter("q") ?: ""

                    val matches = items.toList()
                        .withIndex()
                        .filter {
                            query.isBlank() ||
                            it.value.title.contains(query, true) ||
                            it.value.group.contains(query, true)
                        }
                        .take(80)

                    val links = matches.joinToString("<hr>") {
                        (index, entry) ->
                        """
                        <a href="/play?key=$remoteToken&id=$index">
                        ▶ ${escapeHtml(entry.title)}
                        </a>
                        <small>${escapeHtml(entry.group)}</small>
                        """.trimIndent()
                    }

                    body = """
                    <!doctype html>
                    <html lang="ru">
                    <head>
                      <meta charset="utf-8">
                      <meta name="viewport"
                            content="width=device-width,initial-scale=1">
                      <title>VladiMir-TV Remote</title>
                      <style>
                        body {
                          background:#0c111d;
                          color:white;
                          font:18px sans-serif;
                          padding:18px;
                        }
                        input,button {
                          font:20px sans-serif;
                          padding:12px;
                          max-width:95%;
                        }
                        a {
                          color:#9ac6ff;
                          display:inline-block;
                          padding:8px;
                        }
                      </style>
                    </head>
                    <body>
                      <h2>VladiMir-TV</h2>
                      <p>Поиск / Suche / Search</p>
                      <form action="/search">
                        <input type="hidden"
                               name="key"
                               value="$remoteToken">
                        <input name="q"
                               value="${escapeHtml(query)}"
                               placeholder="Канал / Film / Channel">
                        <button>Поиск / Search</button>
                      </form>
                      <p>${matches.size} результатов</p>
                      $links
                      <hr>
                      <h3>Добавить M3U / Add playlist</h3>
                      <form action="/add">
                        <input type="hidden"
                               name="key"
                               value="$remoteToken">
                        <input name="name"
                               placeholder="Название / Name">
                        <input name="url"
                               type="url"
                               placeholder="https://..."
                               required>
                        <button>Добавить / Add</button>
                      </form>
                    </body>
                    </html>
                    """.trimIndent()

                } else {
                    status = "404 Not Found"
                    body = "Not found"
                }

                val bytes = body.toByteArray(Charsets.UTF_8)

                val headers = """
                    HTTP/1.1 $status
                    Content-Type: text/html; charset=utf-8
                    Content-Length: ${bytes.size}
                    Cache-Control: no-store
                    Connection: close

                """.trimIndent().replace("\n", "\r\n") + "\r\n"

                client.getOutputStream().write(
                    headers.toByteArray(Charsets.UTF_8)
                )

                client.getOutputStream().write(bytes)
                client.getOutputStream().flush()

            } catch (_: Exception) {
            }
        }
    }

    private fun toast(value: String) {
        Toast.makeText(
            this,
            value,
            Toast.LENGTH_LONG
        ).show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (current == "player" && sidePanel != null) {
            closeSidePanel()
            return
        }
        if (current == "player") {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            player?.pause()
            showCatalog(currentEntry?.type ?: "tv")
        } else {
            showHome()
        }
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        clockHandler.removeCallbacksAndMessages(null)
        remoteServer?.close()
        remoteRunning = false
        playerView?.player = null
        player?.release()
        player = null
        super.onDestroy()
    }
}
