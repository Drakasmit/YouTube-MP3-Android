package com.drakasmit.youtubemp3

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var urlInput: EditText
    private lateinit var qualitySpinner: Spinner
    private lateinit var archiveCheck: CheckBox
    private lateinit var startButton: Button
    private lateinit var logView: TextView

    @Volatile
    private var engineReady = false

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        startButton.isEnabled = false
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
                runOnUiThread { startButton.isEnabled = true }
            } catch (error: Exception) {
                append("Ошибка инициализации: ${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 28, 24, 20)
            setBackgroundColor(0xFFF5F5F5.toInt())
        }

        val title = TextView(this).apply {
            text = "YouTube → MP3"
            textSize = 26f
            setTextColor(0xFF171717.toInt())
            gravity = Gravity.CENTER
        }
        root.addView(title, LinearLayout.LayoutParams(-1, -2))

        val hint = TextView(this).apply {
            text = "Ссылка на общедоступное видео или плейлист"
            textSize = 14f
            setPadding(0, 24, 0, 8)
        }
        root.addView(hint)

        urlInput = EditText(this).apply {
            isSingleLine = true
            this.hint = "https://www.youtube.com/…"
        }
        root.addView(urlInput, LinearLayout.LayoutParams(-1, -2))

        val pasteButton = Button(this).apply {
            text = "Вставить из буфера"
            setOnClickListener {
                val clipboard =
                    getSystemService(CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                val clip = clipboard?.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    urlInput.setText(
                        clip.getItemAt(0).coerceToText(this@MainActivity)?.toString().orEmpty()
                    )
                }
            }
        }
        root.addView(pasteButton)

        val qualityLabel = TextView(this).apply {
            text = "Качество MP3"
            setPadding(0, 18, 0, 4)
        }
        root.addView(qualityLabel)

        qualitySpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("Лучшее доступное", "128K", "192K", "256K", "320K")
            )
        }
        root.addView(qualitySpinner)

        archiveCheck = CheckBox(this).apply {
            text = "Пропускать уже скачанные видео"
            isChecked = true
        }
        root.addView(archiveCheck)

        startButton = Button(this).apply {
            text = "Скачать MP3"
            setOnClickListener { startDownload() }
        }
        root.addView(startButton)

        val folder = TextView(this).apply {
            text = "Сохранение: Music/YouTube_MP3 (папка приложения)"
            textSize = 12f
            setPadding(0, 8, 0, 12)
        }
        root.addView(folder)

        logView = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFF222222.toInt())
        }
        val scroll = ScrollView(this).apply { addView(logView) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        setContentView(root)
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
            if (playlistId.startsWith("RD") && !videoId.isNullOrBlank()) {
                return NormalizedUrl(
                    "https://www.youtube.com/watch?v=$videoId",
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
        val url = urlInput.text.toString().trim()
        if (url.isEmpty()) {
            Toast.makeText(this, "Вставь ссылку.", Toast.LENGTH_SHORT).show()
            return
        }

        val normalized = try {
            normalizeYouTubeUrl(url)
        } catch (error: Exception) {
            Toast.makeText(
                this,
                error.message ?: "Некорректная ссылка",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        if (!engineReady) {
            Toast.makeText(this, "Движок ещё не готов.", Toast.LENGTH_SHORT).show()
            return
        }

        val quality = listOf("0", "128K", "192K", "256K", "320K")
            .getOrElse(qualitySpinner.selectedItemPosition) { "0" }
        val useArchive = archiveCheck.isChecked

        startButton.isEnabled = false
        append("\\nЗапуск: $url")
        append("Тип ссылки: ${if (normalized.isPlaylist) "плейлист" else "одно видео"}")

        executor.execute {
            try {
                val musicDirectory = getExternalFilesDir(Environment.DIRECTORY_MUSIC)
                    ?: error("Папка Music недоступна")
                val outputDirectory = File(musicDirectory, "YouTube_MP3")
                if (!outputDirectory.exists() && !outputDirectory.mkdirs()) {
                    error("Не удалось создать папку загрузок")
                }

                val outputTemplate =
                    File(outputDirectory, "%(title)s.%(ext)s").absolutePath
                val request = YoutubeDLRequest(normalized.value)
                    .addOption("-o", outputTemplate)
                    .addOption("--ignore-errors")
                    .addOption("--no-overwrites")
                    .addOption(
                        if (normalized.isPlaylist) "--yes-playlist" else "--no-playlist"
                    )
                    .addOption("-x")
                    .addOption("--audio-format", "mp3")
                    .addOption("--audio-quality", quality)
                    .addOption("--newline")

                if (useArchive) {
                    request.addOption(
                        "--download-archive",
                        File(outputDirectory, "downloaded.txt").absolutePath
                    )
                }

                append("Параметры запуска yt-dlp:")
                request.buildCommand().forEachIndexed { index, argument ->
                    append("  ${index + 1}. $argument")
                }

                val response = YoutubeDL.getInstance().execute(request, null, false) {
                        progress, etaInSeconds, line ->
                    if (line.isNotBlank()) {
                        append(
                            String.format(
                                Locale.US,
                                "%.1f%% | ETA %ds | %s",
                                progress,
                                etaInSeconds,
                                line
                            )
                        )
                    }
                }

                if (response.exitCode == 0) {
                    append("Готово. Загрузка завершена.")
                } else {
                    append("yt-dlp завершился с кодом: ${response.exitCode}")
                    if (response.err.isNotBlank()) {
                        append("Сообщения об ошибках yt-dlp:")
                        append(response.err.takeLast(12000))
                    }
                }
            } catch (error: Exception) {
                append("Ошибка загрузки: ${error.message ?: error.javaClass.simpleName}")
            } finally {
                runOnUiThread { startButton.isEnabled = engineReady }
            }
        }
    }

    private fun append(line: String) {
        runOnUiThread {
            if (::logView.isInitialized) logView.append("$line\\n")
        }
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }
}
