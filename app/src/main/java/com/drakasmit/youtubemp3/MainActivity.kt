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
    private lateinit var qualityButtons: List<Button>
    private var selectedQuality = 0
    private var darkTheme = false
    private var activeDownload: Future<*>? = null
    private val stopRequested = AtomicBoolean(false)
    private val downloadProcessId = "yt_mp3_download"
    private var downloadActive = false

    private val bgColor get() = if (darkTheme) Color.rgb(18,18,18) else Color.rgb(247,247,249)
    private val cardColor get() = if (darkTheme) Color.rgb(30,30,30) else Color.WHITE
    private val textColor get() = if (darkTheme) Color.rgb(245,245,245) else Color.rgb(30,30,34)
    private val secondaryTextColor get() = if (darkTheme) Color.rgb(180,180,185) else Color.rgb(105,105,115)
    private val accentColor = Color.rgb(255,68,68)

    @Volatile
    private var engineReady = false

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val storagePermissionRequest = 4102

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        darkTheme = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        applySystemBars()
        buildUi()
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
            background = roundedDrawable(if (darkTheme) Color.rgb(43,43,43) else Color.rgb(245,245,247), dp(10))
            setPadding(dp(10), dp(2), dp(10), dp(2))
        }
        inputRow.addView(urlInput, LinearLayout.LayoutParams(0, dp(50), 1f))
        clearButton = smallButton("✕").apply { contentDescription = "Очистить ссылку"; setOnClickListener { urlInput.text.clear() } }
        inputRow.addView(clearButton, LinearLayout.LayoutParams(dp(44), dp(48)).apply { leftMargin = dp(6) })
        pasteButton = smallButton("Вставить").apply { setOnClickListener { pasteFromClipboard() } }
        inputRow.addView(pasteButton, LinearLayout.LayoutParams(-2, dp(48)).apply { leftMargin = dp(6) })
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
            setTextColor(Color.WHITE); background = roundedDrawable(accentColor, dp(14))
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
            progressTintList = android.content.res.ColorStateList.valueOf(accentColor)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(if (darkTheme) Color.rgb(65,65,65) else Color.rgb(225,225,230))
        }
        progressCard.addView(progressBar, LinearLayout.LayoutParams(-1, dp(8)))
        page.addView(progressCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        logToggle = Button(this).apply {
            text = "Показать технический лог"; isAllCaps = false; textSize = 12f
            setTextColor(accentColor); background = roundedDrawable(cardColor, dp(10))
            setOnClickListener { setLogExpanded(logScroll.visibility != View.VISIBLE) }
        }
        page.addView(logToggle, LinearLayout.LayoutParams(-1, dp(44)))
        logView = TextView(this).apply { textSize = 11f; setTextColor(secondaryTextColor); setPadding(dp(10), dp(10), dp(10), dp(10)) }
        logScroll = ScrollView(this).apply { visibility = View.GONE; background = roundedDrawable(cardColor, dp(10)); addView(logView) }
        page.addView(logScroll, LinearLayout.LayoutParams(-1, dp(170)).apply { topMargin = dp(6) })
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(page) })
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
            button.background = roundedDrawable(if (selected) accentColor else cardColor, dp(11))
            button.setTextColor(if (selected) Color.WHITE else textColor)
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
        val isPlaylist: Boolean
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
            val embeddedMixVideoId = if (
                playlistId.startsWith("RD") && playlistId.length >= 13
            ) {
                playlistId.substring(2, 13).takeIf {
                    it.matches(Regex("[A-Za-z0-9_-]{11}"))
                }
            } else {
                null
            }

            // RDMM / RDCLAK and other RD-prefixed IDs without an embedded
            // 11-character video ID remain ordinary playlist URLs.
            if (embeddedMixVideoId != null) {
                val mixVideoId = videoId ?: embeddedMixVideoId
                append("Обнаружен автомикс YouTube (RD): извлечён исходный трек $mixVideoId")
                return NormalizedUrl(
                    "https://www.youtube.com/watch?v=$mixVideoId",
                    false
                )
            }

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

        activeDownload = executor.submit {
            val outputDirectory = File(cacheDir, "YTDWNLD")
            var failed = false
            try {
                if (outputDirectory.exists()) outputDirectory.deleteRecursively()
                if (!outputDirectory.mkdirs() && !outputDirectory.isDirectory) error("Не удалось создать временную папку загрузки")
                val outputTemplate = File(outputDirectory, "%(title)s.%(ext)s").absolutePath
                val request = YoutubeDLRequest(normalized.value)
                    .addOption("-o", outputTemplate)
                    .addOption("--ignore-errors")
                    .addOption("--no-overwrites")
                    .addOption(if (normalized.isPlaylist) "--yes-playlist" else "--no-playlist")
                    .addOption("-x").addOption("--audio-format", "mp3")
                    .addOption("--audio-quality", quality).addOption("--newline")
                if (useArchive) request.addOption("--download-archive", File(filesDir, "downloaded.txt").absolutePath)
                append("Временная папка: cache/YTDWNLD")
                append("Архив истории: внутреннее хранилище приложения")
                append("Параметры запуска yt-dlp:")
                request.buildCommand().forEachIndexed { index, argument -> append("  " + (index + 1) + ". " + argument) }

                val response = YoutubeDL.getInstance().execute(request, downloadProcessId, false) { progress, etaInSeconds, line ->
                    if (line.isNotBlank()) {
                        append(String.format(Locale.US, "%.1f%% | ETA %ds | %s", progress, etaInSeconds, line))
                        updateProgress(progress, etaInSeconds.toLong(), line)
                    }
                }
                if (stopRequested.get() || Thread.currentThread().isInterrupted) throw InterruptedException("Остановлено пользователем")

                if (response.exitCode == 0) {
                    val mp3Files = outputDirectory.walkTopDown().filter { it.isFile && it.extension.equals("mp3", true) }.toList()
                    if (mp3Files.isEmpty()) append("Загрузка завершилась, но MP3-файлы не найдены.")
                    else {
                        runOnUiThread {
                            progressTitle.text = "Сохранение готовых файлов"
                            progressStatus.text = "Перенос MP3 в Music/YTDWNLD…"
                            progressBar.progress = 100
                        }
                        append("Перенос готовых MP3 в общую папку Music/YTDWNLD...")
                        var copied = 0
                        mp3Files.forEach { file ->
                            if (stopRequested.get() || Thread.currentThread().isInterrupted) throw InterruptedException("Остановлено пользователем")
                            try { publishMp3(file); copied++; append("Сохранён: " + file.name) }
                            catch (copyError: Exception) { append("Не удалось сохранить " + file.name + ": " + copyError.message) }
                        }
                        append("Перенесено MP3: " + copied + " из " + mp3Files.size)
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
                outputDirectory.deleteRecursively()
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
        File(cacheDir, "YTDWNLD").deleteRecursively()
        progressTitle.text = "Загрузка остановлена"
        progressStatus.text = "Загрузка остановлена пользователем"
        append("Загрузка остановлена пользователем.")
    }

    private fun publishMp3(source: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            publishWithMediaStore(source)
        } else {
            publishWithLegacyStorage(source)
        }
    }

    private fun publishWithMediaStore(source: File) {
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
        } catch (error: Exception) {
            contentResolver.delete(uri, null, null)
            throw error
        }
    }

    @Suppress("DEPRECATION")
    private fun publishWithLegacyStorage(source: File) {
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
        }
        executor.shutdownNow()
        super.onDestroy()
    }
}
