package com.example.callrecorderhelper

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.setPadding
import androidx.activity.ComponentActivity
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("recording_library", Context.MODE_PRIVATE) }
    private var filter = Filter.ALL
    private var query = ""
    private var player: MediaPlayer? = null
    private var playingPath: String? = null
    private var seekBar: SeekBar? = null
    private var playbackTime: TextView? = null
    private val playbackHandler = Handler(Looper.getMainLooper())
    private val playbackTicker = object : Runnable {
        override fun run() {
            val activePlayer = player ?: return
            if (activePlayer.isPlaying) {
                seekBar?.progress = activePlayer.currentPosition
                playbackTime?.text = "${formatTime(activePlayer.currentPosition)} / ${formatTime(activePlayer.duration)}"
                playbackHandler.postDelayed(this, 400)
            }
        }
    }
    private lateinit var content: LinearLayout
    private lateinit var counts: TextView

    private enum class Filter { ALL, FAVORITES, TRASH, SETTINGS }
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        toast(if (granted) "האישור ניתן" else "האישור לא ניתן. אפשר לשנות אותו בהגדרות המכשיר.")
        render()
    }
    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        prefs.edit().putBoolean("notification_permission_asked", true).apply()
        toast(if (granted) "ההתראות אושרו" else "אפשר להפעיל התראות בפרטי האפליקציה")
        render()
    }
    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        try {
            val name = queryName(uri) ?: "הקלטה מיובאת.m4a"
            val directory = recordingsDir().apply { mkdirs() }
            val destination = File(directory, "Imported_${System.currentTimeMillis()}_${name.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_")}")
            contentResolver.openInputStream(uri)?.use { input -> destination.outputStream().use(input::copyTo) }
            toast("ההקלטה נוספה לספרייה")
            render()
        } catch (_: Exception) { toast("לא ניתן לייבא את הקובץ") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(16, 23, 20)
        window.navigationBarColor = Color.rgb(17, 24, 20)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF101714.toInt()) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(18 + bars.left, bars.top, 18 + bars.right, bars.bottom)
            insets
        }
        root.addView(topBar())
        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 12, 0, 96) }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(bottomBar())
        setContentView(root)
        TrashPurgeJobService.schedule(this)
        render()
    }

    override fun onResume() {
        super.onResume()
        if (::content.isInitialized) render()
    }

    private fun topBar(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(2, 12, 2, 14)
        val logo = TextView(this@MainActivity).apply { text = "◉"; textSize = 22f; setTextColor(MINT); gravity = Gravity.CENTER; setBackgroundColor(0xFF29362B.toInt()) }
        addView(logo, LinearLayout.LayoutParams(42, 42))
        val title = TextView(this@MainActivity).apply { text = "  ההקלטות שלי"; textSize = 20f; setTextColor(WHITE); typeface = Typeface.DEFAULT_BOLD }
        addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        addView(textButton("⚙", "הגדרות") { filter = Filter.SETTINGS; render() })
    }

    private fun render() {
        purgeExpired()
        content.removeAllViews()
        when (filter) {
            Filter.SETTINGS -> renderSettings()
            else -> renderLibrary()
        }
    }

    private fun renderLibrary() {
        val all = recordings().filter { !isTrashed(it) }
        val favorites = all.filter { isFavorite(it) }
        content.addView(hero(all.size, favorites.size))
        playingPath?.let { path -> recordings().firstOrNull { it.absolutePath == path }?.let { content.addView(playbackPanel(it)) } }
        val title = TextView(this).apply {
            text = when (filter) { Filter.FAVORITES -> "המועדפים שלך"; Filter.TRASH -> "סל המחזור"; else -> "הספרייה שלך" }
            textSize = 21f; typeface = Typeface.DEFAULT_BOLD; setTextColor(WHITE); setPadding(3, 24, 0, 12)
        }
        content.addView(title)
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, 12) }
        tabs.addView(chip("הכול  ${all.size}", filter == Filter.ALL) { filter = Filter.ALL; render() }, LinearLayout.LayoutParams(0, 42, 1f))
        tabs.addView(chip("★  מועדפים ${favorites.size}", filter == Filter.FAVORITES) { filter = Filter.FAVORITES; render() }, LinearLayout.LayoutParams(0, 42, 1f))
        tabs.addView(chip("♲  סל ${trashFiles().size}", filter == Filter.TRASH) { filter = Filter.TRASH; render() }, LinearLayout.LayoutParams(0, 42, 1f))
        content.addView(tabs)
        if (filter != Filter.TRASH) {
            val search = EditText(this).apply {
                hint = "⌕  חיפוש הקלטה"; setSingleLine(); textSize = 14f; setTextColor(WHITE); setHintTextColor(MUTED)
                setPadding(14, 3, 14, 3); setBackground(roundBg(0xFF171F1B.toInt(), 12)); setText(query)
                addTextChangedListener(afterTextChanged = { query = it?.toString().orEmpty(); renderRows() })
            }
            content.addView(search, LinearLayout.LayoutParams(-1, 48).apply { bottomMargin = 12 })
        }
        counts = TextView(this)
        content.addView(counts)
        renderRows()
        if (filter == Filter.TRASH) {
            val empty = trashFiles().isEmpty()
            if (!empty) content.addView(textButton("רוקן סל מחזור", "מחיקה לצמיתות") {
                AlertDialog.Builder(this).setTitle("למחוק לצמיתות?").setMessage("כל ההקלטות שבסל יימחקו ולא ניתן יהיה לשחזר אותן.")
                    .setNegativeButton("ביטול", null).setPositiveButton("מחיקה") { _, _ -> trashFiles().forEach { it.delete(); clearMetadata(it) }; render() }.show()
            })
        }
        content.addView(noteCard(if (filter == Filter.TRASH) "הקבצים בסל נמחקים אוטומטית 30 יום לאחר העברתם." else "הקלטות נשמרות במכשיר שלך בלבד. אפשר לייבא גם קובצי שמע מהמכשיר."))
    }

    private fun renderRows() {
        if (!::counts.isInitialized) return
        counts.text = ""
        // Rebuild only the section following the counters; rows remain in the native view hierarchy.
        val host = counts.parent as LinearLayout
        val start = host.indexOfChild(counts) + 1
        while (host.childCount > start) host.removeViewAt(start)
        val list = when (filter) {
            Filter.TRASH -> trashFiles()
            Filter.FAVORITES -> recordings().filter { !isTrashed(it) && isFavorite(it) }
            else -> recordings().filter { !isTrashed(it) }
        }.filter { it.name.contains(query, true) }.sortedByDescending { it.lastModified() }
        counts.text = if (list.isEmpty()) "" else "${list.size} פריטים"
        if (list.isEmpty()) {
            val empty = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(20, 30, 20, 30); background = roundBg(0xFF171F1B.toInt(), 18) }
            addText(empty, if (filter == Filter.TRASH) "סל המחזור ריק" else if (filter == Filter.FAVORITES) "אין עדיין מועדפים" else "עוד אין כאן הקלטות", 18, WHITE, true)
            addText(empty, if (filter == Filter.TRASH) "הקלטות שתמחק יופיעו כאן למשך 30 יום." else "ייבא קובץ שמע או אפשר הקלטה אוטומטית בהגדרות.", 13, MUTED)
            if (filter != Filter.TRASH) empty.addView(primaryButton("＋  ייבוא הקלטה") { importAudio() }, LinearLayout.LayoutParams(-2, 48).apply { topMargin = 14 })
            host.addView(empty, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 10 })
        } else list.forEach { host.addView(recordCard(it), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 9 }) }
    }

    private fun hero(all: Int, favorites: Int): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(22, 21, 22, 20); background = roundBg(0xFF202B24.toInt(), 24)
        addText(this, "●  הספרייה הפרטית שלך", 12, MINT, true)
        addText(this, if (filter == Filter.TRASH) "הכל נשמר\nעד שיימחק." else "הרגעים החשובים\nבמקום אחד.", 29, WHITE, true, 1.04f)
        addText(this, "$all הקלטות   ·   $favorites מועדפים", 13, 0xFFBBC5BD.toInt())
        addView(primaryButton("＋  ייבוא הקלטה") { importAudio() }, LinearLayout.LayoutParams(-2, 46).apply { topMargin = 14 })
    }

    private fun recordCard(file: File): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(12, 11, 8, 11); background = roundBg(0xFF171F1B.toInt(), 16)
        if (filter == Filter.TRASH) {
            addView(roundIcon("♲", 0xFF29362B.toInt(), MINT) { restore(file) })
        } else addView(roundIcon(if (playingPath == file.absolutePath && player?.isPlaying == true) "Ⅱ" else "▶", 0xFF29362B.toInt(), MINT) { togglePlayback(file) })
        val info = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(11, 0, 4, 0) }
        val name = TextView(this@MainActivity).apply { text = file.nameWithoutExtension.removePrefix("Call_").replace('_', ' '); textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(WHITE); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        info.addView(name)
        val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(file.lastModified()))
        val detail = if (filter == Filter.TRASH) "נמחק בעוד ${daysLeft(file)} ימים" else "$date  ·  ${fileSize(file.length())}"
        info.addView(TextView(this@MainActivity).apply { text = detail; textSize = 10f; setTextColor(MUTED); setPadding(0, 4, 0, 0) })
        addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        if (filter == Filter.TRASH) {
            addView(textButton("↶", "שחזור") { restore(file) })
            addView(textButton("×", "מחיקה לצמיתות") { file.delete(); clearMetadata(file); render() })
        } else {
            addView(textButton(if (isFavorite(file)) "★" else "☆", "מועדפים") { setFavorite(file, !isFavorite(file)); render() })
            addView(textButton("⋯", "פעולות") { showActions(file) })
        }
    }

    private fun renderSettings() {
        content.addView(hero(recordings().count { !isTrashed(it) }, recordings().count { !isTrashed(it) && isFavorite(it) }))
        addText(content, "הגדרות והקלטה", 22, WHITE, true).setPadding(2, 25, 0, 10)
        content.addView(noteCard("הפעל כל אישור בנפרד. Android פותח את חלונות ההרשאה והנגישות שלו, והאישור הסופי נעשה שם."))
        content.addView(settingButton(statusLabel("מיקרופון", microphoneGranted()), if (microphoneGranted()) "האפליקציה יכולה להשתמש במיקרופון בזמן שהיא פעילה" else "נדרש להקלטה ולהשמעה מתוך האפליקציה") {
            if (!microphoneGranted()) permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) else openAppDetails()
        })
        content.addView(settingButton(statusLabel("גישה למצב שיחה", phoneStateGranted()), if (phoneStateGranted()) "האפליקציה יכולה לזהות תחילת וסיום שיחה" else "נדרש כדי לזהות מצב שיחה") {
            if (!phoneStateGranted()) permissionLauncher.launch(Manifest.permission.READ_PHONE_STATE) else openAppDetails()
        })
        content.addView(settingButton(statusLabel("התראות", notificationsEnabled()), if (notificationsEnabled()) "התראות ההקלטה מופעלות" else "נדרש כדי להציג התראת שירות בזמן הקלטה") { requestNotificationPermission() })
        content.addView(settingButton(statusLabel("שירות נגישות", accessibilityServiceEnabled()), if (accessibilityServiceEnabled()) "השירות מופעל בהגדרות המכשיר" else "לחץ כדי לבחור ולהפעיל את שירות ההקלטה") { openAccessibilitySettings() })
        if (!accessibilityServiceEnabled()) {
            content.addView(settingButton("פתיחת פרטי האפליקציה", "ב־Android 13 ומעלה בהתקנת APK: ⋮  ←  אפשר הגדרות מוגבלות") { openAppDetails() })
        }
        content.addView(settingButton("③  ביטול אופטימיזציית סוללה", "מסייע לשירות לפעול ברקע") { requestIgnoreBatteryOptimizations() })
        content.addView(settingButton("ייבוא הקלטה מהמכשיר", "בחירת קובץ שמע להוספה לספרייה") { importAudio() })
        content.addView(settingButton("סל המחזור", "שמורים עד 30 יום לפני מחיקה אוטומטית") { filter = Filter.TRASH; render() })
    }

    private fun bottomBar() = LinearLayout(this).apply {
        gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL; setPadding(0, 6, 0, 6); background = roundBg(0xFF171F1B.toInt(), 20)
        addView(navButton("☷", "הקלטות", filter == Filter.ALL) { filter = Filter.ALL; render() }, LinearLayout.LayoutParams(0, 58, 1f))
        addView(navButton("＋", "ייבוא", false) { importAudio() }, LinearLayout.LayoutParams(0, 58, 1f))
        addView(navButton("★", "מועדפים", filter == Filter.FAVORITES) { filter = Filter.FAVORITES; render() }, LinearLayout.LayoutParams(0, 58, 1f))
        addView(navButton("♲", "סל מחזור", filter == Filter.TRASH) { filter = Filter.TRASH; render() }, LinearLayout.LayoutParams(0, 58, 1f))
    }

    private fun showActions(file: File) {
        val options = arrayOf("שינוי שם", "העברה לסל המחזור", "שיתוף")
        AlertDialog.Builder(this).setItems(options) { _, which ->
            when (which) {
                0 -> rename(file)
                1 -> moveToTrash(file)
                2 -> share(file)
            }
        }.show()
    }

    private fun rename(file: File) {
        val input = EditText(this).apply { setText(file.nameWithoutExtension); setPadding(18, 12, 18, 12) }
        AlertDialog.Builder(this).setTitle("שינוי שם הקלטה").setView(input).setNegativeButton("ביטול", null)
            .setPositiveButton("שמירה") { _, _ ->
                val clean = input.text.toString().trim().replace(Regex("[/\\\\]"), "_")
                if (clean.isNotEmpty()) file.renameTo(File(file.parentFile, "$clean.${file.extension}"))
                render()
            }.show()
    }

    private fun moveToTrash(file: File) {
        prefs.edit().putLong("trash:${file.name}", System.currentTimeMillis()).apply()
        if (playingPath == file.absolutePath) stopPlayback()
        filter = Filter.TRASH; render(); toast("ההקלטה הועברה לסל המחזור")
    }

    private fun restore(file: File) { prefs.edit().remove("trash:${file.name}").apply(); render(); toast("ההקלטה שוחזרה") }

    private fun togglePlayback(file: File) {
        if (playingPath == file.absolutePath) {
            val activePlayer = player
            if (activePlayer != null) {
                if (activePlayer.isPlaying) activePlayer.pause() else try { activePlayer.start() } catch (_: IllegalStateException) { stopPlayback(); togglePlayback(file); return }
                render()
                if (activePlayer.isPlaying) playbackHandler.post(playbackTicker)
            }
            return
        }
        stopPlayback()
        try {
            playingPath = file.absolutePath
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnPreparedListener { prepared ->
                    prepared.start()
                    render()
                    playbackHandler.post(playbackTicker)
                }
                setOnCompletionListener {
                    try { it.seekTo(0) } catch (_: Exception) {}
                    render()
                }
                setOnErrorListener { _, _, _ -> stopPlayback(); render(); toast("לא ניתן להשמיע את הקובץ הזה"); true }
                prepareAsync()
            }
            render()
        } catch (_: Exception) { stopPlayback(); toast("לא ניתן להשמיע את ההקלטה") }
    }

    private fun stopPlayback() { playbackHandler.removeCallbacks(playbackTicker); try { player?.stop() } catch (_: Exception) {}; player?.release(); player = null; playingPath = null; seekBar = null; playbackTime = null }

    private fun playbackPanel(file: File): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(15, 12, 15, 10)
        background = roundBg(0xFF202B24.toInt(), 16)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = 12 }
        val header = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        addText(header, "♫  ${file.nameWithoutExtension.removePrefix("Call_").replace('_', ' ')}", 12, MINT, true)
        val spacer = View(this@MainActivity)
        header.addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))
        val duration = TextView(this@MainActivity).apply { textSize = 10f; setTextColor(0xFFBBC5BD.toInt()) }
        playbackTime = duration
        header.addView(duration)
        addView(header)
        val seek = SeekBar(this@MainActivity).apply {
            max = (player?.duration ?: 0).coerceAtLeast(1)
            progress = (player?.currentPosition ?: 0).coerceAtMost(max)
            progressTintList = android.content.res.ColorStateList.valueOf(MINT)
            thumbTintList = android.content.res.ColorStateList.valueOf(MINT)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0xFF455047.toInt())
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) duration.text = "${formatTime(progress)} / ${formatTime(player?.duration ?: 0)}"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) { seekBar?.let { try { player?.seekTo(it.progress) } catch (_: Exception) {} } }
            })
        }
        seekBar = seek
        addView(seek, LinearLayout.LayoutParams(-1, 42))
        duration.text = "${formatTime(player?.currentPosition ?: 0)} / ${formatTime(player?.duration ?: 0)}"
        setOnClickListener { togglePlayback(file) }
    }

    private fun formatTime(milliseconds: Int): String {
        val seconds = (milliseconds / 1000).coerceAtLeast(0)
        return "%d:%02d".format(seconds / 60, seconds % 60)
    }
    private fun share(file: File) {
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "audio/*"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }, "שיתוף הקלטה"))
    }

    private fun recordingsDir() = File(getExternalFilesDir(null), "Recordings")
    private fun recordings() = recordingsDir().listFiles()?.filter { it.isFile && it.extension.lowercase() in setOf("m4a", "amr", "3gp", "wav", "mp3", "aac", "ogg") }.orEmpty()
    private fun trashFiles() = recordings().filter { isTrashed(it) }
    private fun isTrashed(file: File) = prefs.contains("trash:${file.name}")
    private fun isFavorite(file: File) = prefs.getBoolean("favorite:${file.name}", false)
    private fun setFavorite(file: File, value: Boolean) { prefs.edit().putBoolean("favorite:${file.name}", value).apply() }
    private fun clearMetadata(file: File) { prefs.edit().remove("trash:${file.name}").remove("favorite:${file.name}").apply() }
    private fun purgeExpired() {
        val editor = prefs.edit(); val now = System.currentTimeMillis(); var changed = false
        recordings().filter { isTrashed(it) }.forEach { file ->
            val deletedAt = prefs.getLong("trash:${file.name}", now)
            if (now - deletedAt >= TimeUnit.DAYS.toMillis(30)) { file.delete(); editor.remove("trash:${file.name}").remove("favorite:${file.name}"); changed = true }
        }
        if (changed) editor.apply()
    }
    private fun daysLeft(file: File): Long = maxOf(0, 30 - TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - prefs.getLong("trash:${file.name}", System.currentTimeMillis())))

    private fun microphoneGranted() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
    private fun phoneStateGranted() = checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == android.content.pm.PackageManager.PERMISSION_GRANTED
    private fun notificationsEnabled(): Boolean = NotificationManagerCompat.from(this).areNotificationsEnabled()
    private fun accessibilityServiceEnabled(): Boolean {
        val manager = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any { info ->
            info.resolveInfo.serviceInfo.packageName == packageName && info.resolveInfo.serviceInfo.name == CallRecorderAccessibilityService::class.java.name
        }
    }
    private fun statusLabel(label: String, enabled: Boolean) = "${if (enabled) "✓" else "○"}  $label · ${if (enabled) "מאושר" else "נדרש אישור"}"
    private fun requestNotificationPermission() {
        if (notificationsEnabled()) { openNotificationSettings(); return }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            val asked = prefs.getBoolean("notification_permission_asked", false)
            if (asked && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) openNotificationSettings()
            else notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else openNotificationSettings()
    }
    private fun openAccessibilitySettings() {
        try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        catch (_: Exception) { openAppDetails() }
    }
    private fun openNotificationSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        try { startActivity(intent) } catch (_: Exception) { openAppDetails() }
    }
    private fun openAppDetails() {
        try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
        catch (_: Exception) { toast("פתח הגדרות Android ובחר את Call Recorder Helper") }
    }
    private fun requestIgnoreBatteryOptimizations() {
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!power.isIgnoringBatteryOptimizations(packageName)) startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(Uri.parse("package:$packageName")))
        else toast("כבר מוחרג מאופטימיזציית סוללה")
    }
    private fun importAudio() { importLauncher.launch(arrayOf("audio/*")) }
    private fun queryName(uri: Uri): String? = contentResolver.query(uri, null, null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 } ?: return@use null) else null }
    private fun fileSize(bytes: Long): String = if (bytes < 1024 * 1024) "${bytes / 1024} KB" else "${"%.1f".format(bytes / 1024f / 1024f)} MB"
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun chip(label: String, selected: Boolean, click: () -> Unit) = TextView(this).apply { text = label; gravity = Gravity.CENTER; textSize = 12f; typeface = Typeface.DEFAULT_BOLD; setTextColor(if (selected) 0xFF18260E.toInt() else MUTED); background = roundBg(if (selected) MINT else 0xFF1C2621.toInt(), 12); setOnClickListener { click() } }
    private fun primaryButton(label: String, click: () -> Unit) = TextView(this).apply { text = label; gravity = Gravity.CENTER; textSize = 13f; typeface = Typeface.DEFAULT_BOLD; setTextColor(0xFF18260E.toInt()); background = roundBg(MINT, 13); setOnClickListener { click() } }
    private fun settingButton(title: String, subtitle: String, click: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(16, 14, 16, 14); background = roundBg(0xFF171F1B.toInt(), 15)
        addText(this, title, 14, WHITE, true); addText(this, subtitle, 11, MUTED).setPadding(0, 4, 0, 0); setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 9 }
    }
    private fun noteCard(message: String) = TextView(this).apply { text = "ⓘ  $message"; textSize = 11f; setTextColor(0xFFD0C4A9.toInt()); setPadding(14, 13, 14, 13); background = roundBg(0xFF211E18.toInt(), 13); layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = 15; bottomMargin = 12 } }
    private fun roundIcon(label: String, bg: Int, color: Int, click: () -> Unit) = TextView(this).apply { text = label; gravity = Gravity.CENTER; textSize = 17f; typeface = Typeface.DEFAULT_BOLD; setTextColor(color); background = roundBg(bg, 13); setOnClickListener { click() }; layoutParams = LinearLayout.LayoutParams(42, 42) }
    private fun textButton(label: String, description: String, click: () -> Unit) = TextView(this).apply { text = label; contentDescription = description; gravity = Gravity.CENTER; textSize = 19f; typeface = Typeface.DEFAULT_BOLD; setTextColor(MUTED); setPadding(8, 5, 8, 5); setOnClickListener { click() } }
    private fun navButton(icon: String, label: String, selected: Boolean, click: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setOnClickListener { click() }
        addText(this, icon, 19, if (selected) MINT else MUTED, true); addText(this, label, 10, if (selected) MINT else MUTED)
    }
    private fun addText(parent: LinearLayout, text: String, size: Int, color: Int, bold: Boolean = false, spacing: Float = 1.1f): TextView = TextView(this).apply {
        this.text = text; textSize = size.toFloat(); setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD; setLineSpacing(0f, spacing); parent.addView(this)
    }
    private fun roundBg(color: Int, radius: Int) = android.graphics.drawable.GradientDrawable().apply { setColor(color); cornerRadius = radius.toFloat() }

    override fun onDestroy() { stopPlayback(); super.onDestroy() }

    companion object { private const val MINT = 0xFFBDF576.toInt(); private const val WHITE = 0xFFF3F5EF.toInt(); private const val MUTED = 0xFF929B93.toInt() }
}

private inline fun android.widget.EditText.addTextChangedListener(crossinline afterTextChanged: (android.text.Editable?) -> Unit) {
    addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: android.text.Editable?) = afterTextChanged(s)
    })
}
