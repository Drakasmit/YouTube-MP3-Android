package com.drakasmit.youtubemp3

import android.Manifest
import android.app.Activity
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.ProgressBar
import android.content.ContentValues
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.MediaStore
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {
    private lateinit var urlInput: EditText
    private lateinit var archiveCheck: CheckBox
    private lateinit var startButton: Button
    private lateinit var clearButton: Button
    private lateinit var pasteButton: Button
    private lateinit var logView: TextView
    private lateinit var logToggle: Button
    private lateinit var logScroll: ScrollView
    private lateinit var progressCard: LinearLayout
    private lateinit var progressTitle: TextView
    private lateinit var progressStatus: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var openMp3Button: Button
    private lateinit var qualityButtons: List<Button>
    private var lastDownloadedUri: Uri? = null
    private var selectedQuality = 0
    private var darkTheme = false
    private var activeDownload: Future<*>? = null
    private val stopRequested = AtomicBoolean(false)
    private val downloadProcessId = "yt_mp3_download"
    private var downloadActive = false

    private val bgColor get() = Color.parseColor(if (darkTheme) "#131314" else "#F8FAFD")
    private val cardColor get() = Color.parseColor(if (darkTheme) "#1E1F20" else "#FFFFFF")
    private val fieldColor get() = Color.parseColor(if (darkTheme) "#282A2C" else "#F1F3F4")
    private val textColor get() = Color.parseColor(if (darkTheme) "#E3E3E3" else "#191C20")
    private val secondaryTextColor get() = Color.parseColor(if (darkTheme) "#C4C7C5" else "#444746")
    private val accentColor get() = Color.parseColor(if (darkTheme) "#A8C7FA" else "#0B57D0")
    private val accentTextColor get() = Color.parseColor(if (darkTheme) "#062E6F" else "#FFFFFF")
    private val selectedChipColor get() = Color.parseColor(if (darkTheme) "#004A77" else "#D3E3FD")
    private val outlineColor get() = Color.parseColor("#444746")
    private val progressTrackColor get() = Color.parseColor(if (darkTheme) "#3C4043" else "#DADCE0")

    @Volatile
    private var engineReady = false

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val storagePermissionRequest = 4102

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        darkTheme = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        applySystemBars()
        buildUi()
        handleIncomingShare(intent)
        startButton.isEnabled = false
        setStatus("Подготовка движка yt-dlp и FFmpeg…")
        append("Инициализация yt-dlp и FFmpeg...")

        executor.execute {
            try {
                YoutubeDL.getInstance().init(applicationContext)
                FFmpeg.getInstance().init(applicationContext)
                append("Движки yt-dlp и FFmpeg инициализированы.")

                try {
                    append("Проверка обновлений yt-dlp (канал NIGHTLY)...")
                    when (
                        YoutubeDL.getInstance().updateYoutubeDL(
                            applicationContext,
                            YoutubeDL.UpdateChannel._NIGHTLY
                        )
                    ) {
                        YoutubeDL.UpdateStatus.DONE ->
                            append("Обновление yt-dlp: DONE")
                        YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE ->
                            append("Обновление yt-dlp: ALREADY_UP_TO_DATE")
                        null ->
                            append("Обновление yt-dlp: сервер не вернул статус.")
                    }
                } catch (updateError: Exception) {
                    append(
                        "Не удалось обновить yt-dlp: ${updateError.message ?: updateError.javaClass.simpleName}"
                    )
                    append("Продолжаю с уже установленной версией.")
                }

                val actualVersion = try {
                    YoutubeDL.getInstance().version(applicationContext)
                } catch (versionError: Exception) {
                    append(
                        "Не удалось определить версию yt-dlp: ${versionError.message ?: versionError.javaClass.simpleName}"
                    )
                    null
                }
                append("Фактическая версия yt-dlp: ${actualVersion ?: "не определена"}")

                engineReady = true
                append("Движок готов к загрузке.")
                runOnUiThread { if (!downloadActive) { startButton.isEnabled = true; setStatus("Готово к загрузке") } }
            } catch (error: Exception) {
                append("Ошибка инициализации: ${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    private fun applySystemBars() {
        window.statusBarColor = bgColor
        window.navigationBarColor = bgColor
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            var flags = window.decorView.systemUiVisibility
            flags = if (darkTheme) {
                flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv() and
                    View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            } else {
                flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                    View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }
            window.decorView.systemUiVisibility = flags
        }
    }

    private fun handleIncomingShare(source: android.content.Intent?) {
        if (source?.action != android.content.Intent.ACTION_SEND || source.type != "text/plain") return
        val sharedText = source.getStringExtra(android.content.Intent.EXTRA_TEXT)?.trim()?.takeIf { it.isNotEmpty() } ?: return
        urlInput.setText(sharedText)
        urlInput.setSelection(urlInput.text.length)
        setStatus("Ссылка получена через «Поделиться»")
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingShare(intent)
    }

    private fun buildUi() {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(20))
            setBackgroundColor(bgColor)
        }
        page.addView(TextView(this).apply {
            text = "YouTube  →  MP3"; textSize = 27f
            setTypeface(null, android.graphics.Typeface.BOLD); setTextColor(textColor)
            gravity = Gravity.CENTER
        }, matchWrap())
        page.addView(TextView(this).apply {
            text = "Скачивание аудио из общедоступных видео и плейлистов"
            textSize = 13f; setTextColor(secondaryTextColor); gravity = Gravity.CENTER
            setPadding(0, dp(5), 0, dp(18))
        }, matchWrap())

        val inputCard = card().apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(14)) }
        inputCard.addView(sectionLabel("Ссылка на YouTube"))
        val inputRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        urlInput = EditText(this).apply {
            isSingleLine = true; hint = "https://www.youtube.com/…"; textSize = 14f
            setTextColor(textColor); setHintTextColor(secondaryTextColor)
            background = roundedDrawable(fieldColor, dp(14))
            setPadding(dp(10), dp(2), dp(10), dp(2))
        }
        inputRow.addView(urlInput, LinearLayout.LayoutParams(0, dp(50), 1f))
        clearButton = smallButton("✕").apply { contentDescription = "Очистить ссылку"; setOnClickListener { urlInput.text.clear() } }
        inputRow.addView(clearButton, LinearLayout.LayoutParams(dp(44), dp(42)).apply { leftMargin = dp(6) })
        pasteButton = smallButton("Вставить").apply { setOnClickListener { pasteFromClipboard() } }
        inputRow.addView(pasteButton, LinearLayout.LayoutParams(-2, dp(42)).apply { leftMargin = dp(6) })
        inputCard.addView(inputRow, matchWrap())
        page.addView(inputCard, matchWrap())

        val qualityCard = card().apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(14)) }
        qualityCard.addView(sectionLabel("Качество MP3"))
        val chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        qualityButtons = listOf("Лучшее", "320K", "256K", "192K", "128K").mapIndexed { index, label ->
            Button(this).apply {
                text = label; textSize = 11f; isAllCaps = false; minWidth = 0; minimumWidth = 0
                setPadding(dp(5), 0, dp(5), 0)
                setOnClickListener { selectedQuality = index; refreshQualityButtons() }
            }.also { chipRow.addView(it, LinearLayout.LayoutParams(0, dp(42), 1f).apply { if (index > 0) leftMargin = dp(4) }) }
        }
        qualityCard.addView(chipRow, matchWrap())
        page.addView(qualityCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        refreshQualityButtons()

        archiveCheck = CheckBox(this).apply {
            text = "Пропускать уже скачанные видео"; textSize = 13f
            setTextColor(textColor); isChecked = true
            buttonTintList = android.content.res.ColorStateList.valueOf(accentColor)
        }
        page.addView(archiveCheck, matchWrap())
        startButton = Button(this).apply {
            text = "Скачать MP3"; isAllCaps = false; textSize = 16f
            setTextColor(accentTextColor); background = roundedDrawable(accentColor, dp(50))
            setOnClickListener { if (downloadActive) stopDownload() else startDownload() }
        }
        page.addView(startButton, LinearLayout.LayoutParams(-1, dp(54)).apply { topMargin = dp(4) })
        page.addView(TextView(this).apply {
            text = "Файлы сохраняются в Music/YTDWNLD"; textSize = 12f
            setTextColor(secondaryTextColor); gravity = Gravity.CENTER; setPadding(0, dp(9), 0, dp(12))
        }, matchWrap())

        progressCard = card().apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(14)); visibility = View.GONE }
        progressTitle = TextView(this).apply { text = "Текущая загрузка"; textSize = 15f; setTypeface(null, android.graphics.Typeface.BOLD); setTextColor(textColor) }
        progressCard.addView(progressTitle, matchWrap())
        progressStatus = TextView(this).apply { text = "Подготовка…"; textSize = 12f; setTextColor(secondaryTextColor); setPadding(0, dp(8), 0, dp(8)) }
        progressCard.addView(progressStatus, matchWrap())
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progress = 0
            val track = roundedDrawable(progressTrackColor, dp(50))
            val fill = roundedDrawable(accentColor, dp(50))
            progressDrawable = android.graphics.drawable.LayerDrawable(arrayOf(
                track,
                android.graphics.drawable.ClipDrawable(fill, Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL)
            )).apply {
                setId(0, android.R.id.background)
                setId(1, android.R.id.progress)
            }
        }
        progressCard.addView(progressBar, LinearLayout.LayoutParams(-1, dp(8)))
        openMp3Button = smallButton("Открыть MP3").apply {
            visibility = View.GONE
            setTextColor(accentColor)
            background = roundedDrawable(fieldColor, dp(50))
            setOnClickListener { lastDownloadedUri?.let { openMp3(it) } }
        }
        progressCard.addView(openMp3Button, LinearLayout.LayoutParams(-2, dp(40)).apply { topMargin = dp(10) })
        page.addView(progressCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        val logControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        logToggle = Button(this).apply {
            text = "Показать технический лог"; isAllCaps = false; textSize = 12f
            setTextColor(accentColor); background = roundedDrawable(cardColor, dp(50))
            setOnClickListener { setLogExpanded(logScroll.visibility != View.VISIBLE) }
        }
        logControls.addView(logToggle, LinearLayout.LayoutParams(0, dp(44), 1f))
        val copyLogButton = smallButton("Скопировать лог").apply {
            textSize = 11f
            setTextColor(accentColor)
            background = roundedDrawable(selectedChipColor, dp(50))
            setOnClickListener { copyTechnicalLog() }
        }
        logControls.addView(copyLogButton, LinearLayout.LayoutParams(-2, dp(40)).apply { leftMargin = dp(8) })
        page.addView(logControls, matchWrap())
        logView = TextView(this).apply {
            textSize = 11f
            setTextColor(secondaryTextColor)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setTextIsSelectable(true)
        }
        logScroll = ScrollView(this).apply { visibility = View.GONE; background = roundedDrawable(cardColor, dp(10)); addView(logView) }
        page.addView(logScroll, LinearLayout.LayoutParams(-1, dp(170)).apply { topMargin = dp(6) })
        val root = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(bgColor)
            addView(page)
        }
        root.setOnApplyWindowInsetsListener { view, insets ->
            val safe = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
            } else null
            val left = safe?.left ?: insets.systemWindowInsetLeft
            val top = safe?.top ?: insets.systemWindowInsetTop
            val right = safe?.right ?: insets.systemWindowInsetRight
            val bottom = safe?.bottom ?: insets.systemWindowInsetBottom
            view.setPadding(left, top, right, bottom)
            insets
        }
        setContentView(root)
        root.requestApplyInsets()
    }

    private fun pasteFromClipboard() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        val clip = clipboard?.primaryClip
        if (clip != null && clip.itemCount > 0) {
            urlInput.setText(clip.getItemAt(0).coerceToText(this@MainActivity)?.toString().orEmpty())
            urlInput.setSelection(urlInput.text.length)
        } else Toast.makeText(this, "Буфер обмена пуст.", Toast.LENGTH_SHORT).show()
    }

    private fun refreshQualityButtons() {
        if (!::qualityButtons.isInitialized) return
        qualityButtons.forEachIndexed { index, button ->
            val selected = index == selectedQuality
            val qualityLabels = listOf("Лучшее", "320K", "256K", "192K", "128K")
            button.text = if (selected) "✓ " + qualityLabels[index] else qualityLabels[index]
            button.background = if (selected) roundedDrawable(selectedChipColor, dp(50)) else GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = dp(50).toFloat()
                setStroke(dp(1), outlineColor)
            }
            button.setTextColor(if (selected) (if (darkTheme) Color.WHITE else Color.rgb(4, 48, 98)) else textColor)
        }
    }

    private fun card() = LinearLayout(this).apply { background = roundedDrawable(cardColor, dp(16)); elevation = dp(2).toFloat() }
    private fun sectionLabel(value: String) = TextView(this).apply {
        text = value; textSize = 13f; setTypeface(null, android.graphics.Typeface.BOLD)
        setTextColor(textColor); setPadding(0, 0, 0, dp(9))
    }
    private fun smallButton(value: String) = Button(this).apply {
        text = value; textSize = 12f; isAllCaps = false; minWidth = 0; minimumWidth = 0
        setPadding(dp(7), 0, dp(7), 0); setTextColor(textColor); background = roundedDrawable(cardColor, dp(10))
    }
    private fun roundedDrawable(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = radius.toFloat() }
    private fun matchWrap() = LinearLayout.LayoutParams(-1, -2)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun copyTechnicalLog() {
        val logText = logView.text?.toString().orEmpty()
        if (logText.isEmpty()) {
            Toast.makeText(this, "Технический лог пуст.", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Технический лог", logText))
        Toast.makeText(this, "Лог скопирован в буфер обмена", Toast.LENGTH_SHORT).show()
    }

    private fun setLogExpanded(expanded: Boolean) {
        if (!::logScroll.isInitialized) return
        logScroll.visibility = if (expanded) View.VISIBLE else View.GONE
        logToggle.text = if (expanded) "Скрыть технический лог" else "Показать технический лог"
    }

    private fun updateProgress(progress: Float, eta: Long, line: String) {
        runOnUiThread {
            if (!downloadActive) return@runOnUiThread
            progressCard.visibility = View.VISIBLE
            val percent = progress.coerceIn(0f, 100f)
            progressBar.progress = percent.toInt()
            val item = Regex("""Downloading item\s+(\d+)\s+of\s+(\d+)""", RegexOption.IGNORE_CASE).find(line)
            if (item != null) progressTitle.text = "Трек " + item.groupValues[1] + " из " + item.groupValues[2]
            val stage = when {
                line.contains("ffmpeg", true) || line.contains("converting", true) || line.contains("ExtractAudio", true) -> "Конвертация в MP3…"
                line.contains("Destination:", true) -> "Подготовка файла…"
                else -> "Загрузка: " + String.format(Locale.US, "%.1f%%", percent) +
                    if (eta >= 0 && percent < 100f) " • Осталось " + eta + "с" else ""
            }
            progressStatus.text = stage
        }
    }

    private fun setStatus(value: String, error: Boolean = false) {
        if (!::progressStatus.isInitialized) return
        progressCard.visibility = if (downloadActive || error) View.VISIBLE else View.GONE
        progressStatus.text = value
        progressStatus.setTextColor(if (error) accentColor else secondaryTextColor)
    }

    private data class NormalizedUrl(
        val value: String,
        val isPlaylist: Boolean,
        val isMix: Boolean = false
    )

    private fun normalizeYouTubeUrl(raw: String): NormalizedUrl {
        val uri = Uri.parse(raw.trim())
        val host = uri.host?.lowercase(Locale.ROOT)
            ?: throw IllegalArgumentException("Некорректный адрес ссылки")

        val allowedHosts = setOf(
            "youtube.com", "www.youtube.com", "m.youtube.com",
            "music.youtube.com", "youtu.be", "www.youtu.be"
        )
        require(host in allowedHosts) {
            "Поддерживаются только ссылки YouTube"
        }

        val videoId = if (host.endsWith("youtu.be")) {
            uri.lastPathSegment
        } else {
            uri.getQueryParameter("v")
        }
        val playlistId = uri.getQueryParameter("list")

        if (!videoId.isNullOrBlank()) {
            require(videoId.matches(Regex("[A-Za-z0-9_-]{11}"))) {
                "Некорректный ID видео"
            }
        }

        if (!playlistId.isNullOrBlank()) {
            val isPersonalRadioMix =
                playlistId.startsWith("RD") &&
                !playlistId.startsWith("RDCLAK") &&
                !playlistId.startsWith("RDMM")

            if (isPersonalRadioMix) {
                // A personal Mix is parsed from its watch URL, not the blocked /playlist endpoint.
                val mixVideoId = videoId ?: run {
                    require(playlistId.length >= 13) {
                        "В ID персонального микса не найден ID исходного видео"
                    }
                    playlistId.substring(2, 13)
                }
                require(mixVideoId.matches(Regex("[A-Za-z0-9_-]{11}"))) {
                    "Некорректный ID видео в автомиксе"
                }
                append("Обнаружен персональный автомикс YouTube: исходное видео $mixVideoId")
                return NormalizedUrl(
                    "https://www.youtube.com/watch?v=$mixVideoId&list=$playlistId",
                    true,
                    true
                )
            }

            // Static playlists, including RDCLAK and RDMM, are downloaded in full.
            return NormalizedUrl(
                "https://www.youtube.com/playlist?list=$playlistId",
                true
            )
        }

        require(!videoId.isNullOrBlank()) {
            "В ссылке не найден ID видео или плейлиста"
        }

        return NormalizedUrl(
            "https://www.youtube.com/watch?v=$videoId",
            false
        )
    }

    private fun startDownload() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), storagePermissionRequest)
            Toast.makeText(this, "Разреши доступ к файлам и нажми «Скачать MP3» ещё раз.", Toast.LENGTH_LONG).show()
            return
        }
        val url = urlInput.text.toString().trim()
        if (url.isEmpty()) { Toast.makeText(this, "Вставь ссылку.", Toast.LENGTH_SHORT).show(); return }
        val normalized = try { normalizeYouTubeUrl(url) } catch (error: Exception) {
            Toast.makeText(this, error.message ?: "Некорректная ссылка", Toast.LENGTH_LONG).show(); return
        }
        if (!engineReady) { Toast.makeText(this, "Движок ещё не готов.", Toast.LENGTH_SHORT).show(); return }

        val quality = listOf("0", "320K", "256K", "192K", "128K").getOrElse(selectedQuality) { "0" }
        val useArchive = archiveCheck.isChecked
        stopRequested.set(false)
        downloadActive = true
        progressTitle.text = if (normalized.isPlaylist) "Подготовка плейлиста" else "Текущая загрузка"
        progressBar.progress = 0
        progressCard.visibility = View.VISIBLE
        progressStatus.text = "Запуск загрузки…"
        startButton.text = "Остановить"
        startButton.background = roundedDrawable(accentColor, dp(14))
        startButton.isEnabled = true
        append("\nЗапуск: " + url)
        append("Тип ссылки: " + if (normalized.isPlaylist) "плейлист" else "одно видео")

        DownloadForegroundService.start(this, "Подготовка загрузки…")
        activeDownload = executor.submit {
            val outputDirectory = File(cacheDir, "YTDWNLD")
            var failed = false
            var wakeLock: PowerManager.WakeLock? = null
            val publishedUris = mutableListOf<Uri>()
            var savedCount = 0
            val savedPaths = mutableSetOf<String>()
            try {
                val powerManager = getSystemService(POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:YtMp3Download")
                wakeLock.acquire(60L * 60L * 1000L)
                append("CPU WakeLock активен (максимум 60 минут).")
                if (outputDirectory.exists()) outputDirectory.deleteRecursively()
                if (!outputDirectory.mkdirs() && !outputDirectory.isDirectory) error("Не удалось создать временную папку загрузки")
                val outputTemplate = File(outputDirectory, if (normalized.isPlaylist) "%(playlist_index)s - %(title)s.%(ext)s" else "%(title)s.%(ext)s").absolutePath
                val request = YoutubeDLRequest(normalized.value)
                    .addOption("-o", outputTemplate)
                    .addOption("--ignore-errors")
                    .addOption("--no-overwrites")
                    .addOption(if (normalized.isPlaylist) "--yes-playlist" else "--no-playlist")
                    .addOption("-x").addOption("--audio-format", "mp3")
                    .addOption("--audio-quality", quality).addOption("--newline")
                    .addOption("--postprocessor-args", "ffmpeg:-threads 4 -loglevel error")
                if (normalized.isMix) {
                    request.addOption("--playlist-end", "25")
                    append("Загрузка персонального микса YouTube (лимит: 25 треков)...")
                }
                if (useArchive) request.addOption("--download-archive", File(filesDir, "downloaded.txt").absolutePath)
                append("Временная папка: cache/YTDWNLD")
                append("Архив истории: внутреннее хранилище приложения")
                append("Параметры запуска yt-dlp:")
                request.buildCommand().forEachIndexed { index, argument -> append("  " + (index + 1) + ". " + argument) }

                var pendingConvertedFile: File? = null
                fun saveCompletedFile(file: File?) {
                    if (file == null || !file.exists() || !file.isFile ||
                        !file.extension.equals("mp3", true) ||
                        file.absolutePath in savedPaths || stopRequested.get()
                    ) return
                    try {
                        val uri = publishMp3(file)
                        publishedUris.add(uri)
                        savedPaths.add(file.absolutePath)
                        savedCount++
                        file.delete()
                        append("Сохранён сразу после конвертации ($savedCount): " + file.name)
                        runOnUiThread {
                            progressTitle.text = "Сохранено треков: $savedCount"
                            progressStatus.text = "Файл перенесён в Music/YTDWNLD"
                            if (!normalized.isPlaylist) {
                                lastDownloadedUri = uri
                                openMp3Button.visibility = View.VISIBLE
                            }
                        }
                    } catch (copyError: Exception) {
                        append("Не удалось сохранить " + file.name + ": " + copyError.message)
                    }
                }

                val response = YoutubeDL.getInstance().execute(request, downloadProcessId, false) { progress, etaInSeconds, line ->
                    if (line.isNotBlank()) {
                        append(String.format(Locale.US, "%.1f%% | ETA %ds | %s", progress, etaInSeconds, line))
                        updateProgress(progress, etaInSeconds.toLong(), line)
                        DownloadForegroundService.update(this, line.take(110))
                        if (line.contains("[ExtractAudio] Destination:", true)) {
                            val destination = line.substringAfter("Destination:", "").trim().trim('"')
                            if (destination.isNotEmpty()) pendingConvertedFile = File(destination)
                        } else if (line.contains("Deleting original file", true)) {
                            // yt-dlp emits this only after FFmpeg has completed the extraction.
                            saveCompletedFile(pendingConvertedFile)
                            pendingConvertedFile = null
                        } else if (Regex("""Downloading item\\s+\\d+\\s+of\\s+\\d+""", RegexOption.IGNORE_CASE).containsMatchIn(line)) {
                            // The next playlist item starts only after the previous post-processing step.
                            saveCompletedFile(pendingConvertedFile)
                            pendingConvertedFile = null
                        }
                    }
                }
                saveCompletedFile(pendingConvertedFile)
                if (stopRequested.get() || Thread.currentThread().isInterrupted) throw InterruptedException("Остановлено пользователем")

                if (response.exitCode == 0) {
                    val remainingFiles = outputDirectory.walkTopDown()
                        .filter { it.isFile && it.extension.equals("mp3", true) && it.absolutePath !in savedPaths }
                        .toList()
                    remainingFiles.forEach { file ->
                        if (stopRequested.get() || Thread.currentThread().isInterrupted) throw InterruptedException("Остановлено пользователем")
                        saveCompletedFile(file)
                    }
                    if (savedCount == 0) append("Загрузка завершилась, но готовые MP3-файлы не найдены.")
                    else append("Всего сохранено MP3: $savedCount")
                    val singleTrackUri = if (!normalized.isPlaylist && publishedUris.size == 1) publishedUris.first() else null
                    runOnUiThread {
                        lastDownloadedUri = singleTrackUri
                        openMp3Button.visibility = if (singleTrackUri != null) View.VISIBLE else View.GONE
                        progressBar.progress = 100
                    }
                } else {
                    failed = true
                    append("yt-dlp завершился с кодом: " + response.exitCode)
                    if (response.err.isNotBlank()) { append("Сообщения об ошибках yt-dlp:"); append(response.err.takeLast(12000)) }
                    throw IllegalStateException("yt-dlp завершился с кодом " + response.exitCode)
                }
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                if (!stopRequested.get()) { failed = true; append("Задача прервана: " + interrupted.message) }
            } catch (error: Exception) {
                failed = true
                append("Ошибка загрузки: " + (error.message ?: error.javaClass.simpleName))
            } finally {
                try {
                    if (wakeLock?.isHeld == true) wakeLock.release()
                } catch (releaseError: Exception) {
                    append("Не удалось освободить WakeLock: " + releaseError.message)
                }
                outputDirectory.deleteRecursively()
                DownloadForegroundService.stop(this)
                runOnUiThread {
                    downloadActive = false
                    activeDownload = null
                    startButton.text = "Скачать MP3"
                    startButton.background = roundedDrawable(accentColor, dp(14))
                    startButton.isEnabled = engineReady
                    if (stopRequested.get()) {
                        progressBar.progress = 0
                        progressTitle.text = "Загрузка остановлена"
                        progressStatus.text = "Загрузка остановлена пользователем"
                        progressCard.visibility = View.VISIBLE
                    } else if (failed) {
                        progressTitle.text = "Ошибка загрузки"
                        progressStatus.text = "Произошла ошибка. Причина указана в техническом логе."
                        progressStatus.setTextColor(accentColor)
                        progressCard.visibility = View.VISIBLE
                        setLogExpanded(true)
                    } else {
                        progressTitle.text = "Готово"
                        progressStatus.text = "Загрузка и сохранение завершены"
                        progressBar.progress = 100
                        progressCard.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    private fun stopDownload() {
        if (!downloadActive) return
        stopRequested.set(true)
        startButton.isEnabled = false
        progressTitle.text = "Остановка загрузки…"
        progressStatus.text = "Завершаю процесс и очищаю временные файлы"
        progressBar.progress = 0
        append("Запрошена остановка загрузки пользователем.")
        try { YoutubeDL.getInstance().destroyProcessById(downloadProcessId) }
        catch (error: Exception) { append("Не удалось завершить процесс yt-dlp: " + error.message) }
        activeDownload?.cancel(true)
        progressTitle.text = "Загрузка остановлена"
        progressStatus.text = "Загрузка остановлена пользователем"
        append("Загрузка остановлена пользователем.")
    }

    private fun publishMp3(source: File): Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) publishWithMediaStore(source)
        else publishWithLegacyStorage(source)
    }

    private fun openMp3(uri: Uri) {
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "audio/mpeg")
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (_: android.content.ActivityNotFoundException) {
            Toast.makeText(this, "Не найдено приложение для воспроизведения MP3", Toast.LENGTH_LONG).show()
        }
    }

    private fun publishWithMediaStore(source: File): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, source.name)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/YTDWNLD")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }

        val uri = contentResolver.insert(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            values
        ) ?: error("MediaStore не создал запись для ${source.name}")

        try {
            contentResolver.openOutputStream(uri, "w")?.use { output ->
                FileInputStream(source).use { input -> input.copyTo(output) }
            } ?: error("Не удалось открыть файл MediaStore для записи")

            val publishValues = ContentValues().apply {
                put(MediaStore.Audio.Media.IS_PENDING, 0)
            }
            contentResolver.update(uri, publishValues, null, null)
            MediaScannerConnection.scanFile(
                this,
                arrayOf("/storage/emulated/0/Music/YTDWNLD/${source.name}"),
                arrayOf("audio/mpeg"),
                null
            )
            return uri
        } catch (error: Exception) {
            contentResolver.delete(uri, null, null)
            throw error
        }
    }

    @Suppress("DEPRECATION")
    private fun publishWithLegacyStorage(source: File): Uri {
        val musicDirectory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            "YTDWNLD"
        )
        if (!musicDirectory.exists() && !musicDirectory.mkdirs()) {
            error("Не удалось создать публичную папку Music/YTDWNLD")
        }

        var destination = File(musicDirectory, source.name)
        if (destination.exists()) {
            val baseName = source.nameWithoutExtension
            var suffix = 1
            do {
                destination = File(musicDirectory, "$baseName ($suffix).mp3")
                suffix++
            } while (destination.exists())
        }

        FileInputStream(source).use { input ->
            FileOutputStream(destination).use { output -> input.copyTo(output) }
        }
        MediaScannerConnection.scanFile(
            this,
            arrayOf(destination.absolutePath),
            arrayOf("audio/mpeg"),
            null
        )
        return Uri.fromFile(destination)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == storagePermissionRequest) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(
                    this,
                    "Доступ разрешён. Нажми «Скачать MP3» ещё раз.",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(
                    this,
                    "Без разрешения приложение не сможет сохранить MP3 в общую папку.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun append(line: String) {
        runOnUiThread {
            if (::logView.isInitialized) {
                logView.append(line + "\n")
                if (::logScroll.isInitialized && logScroll.visibility == View.VISIBLE) logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    override fun onDestroy() {
        if (downloadActive) {
            stopRequested.set(true)
            try { YoutubeDL.getInstance().destroyProcessById(downloadProcessId) } catch (_: Exception) { }
            activeDownload?.cancel(true)
            DownloadForegroundService.stop(this)
        }
        executor.shutdownNow()
        super.onDestroy()
    }
}
