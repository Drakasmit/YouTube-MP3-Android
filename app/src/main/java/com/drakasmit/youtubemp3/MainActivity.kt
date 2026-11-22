package com.drakasmit.youtubemp3

import android.app.Activity
import android.content.Intent
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
import dev.ffmpegkit_maintained.ytdlp.YtDlp
import dev.ffmpegkit_maintained.ytdlp.YtDlpRequest
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
    private var cookiesFile: File? = null

    companion object {
        private const val REQUEST_COOKIES = 4102
    }

    @Volatile
    private var engineReady = false

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        startButton.isEnabled = false
        append("Инициализация движка загрузки...")

        executor.execute {
            try {
                YtDlp.init(applicationContext)
                engineReady = true
                append("Движок загрузки готов.")
                append("Версия Android-библиотеки yt-dlp: 2.0.2 (движок встроен в APK).")
                logBundledYtDlpVersion()
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
            text = "Ссылка на видео или плейлист, который ты вправе сохранять"
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
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                val clip = clipboard?.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    urlInput.setText(clip.getItemAt(0).coerceToText(this@MainActivity)?.toString().orEmpty())
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

        val cookiesButton = Button(this).apply {
            text = "Выбрать cookies.txt (необязательно)"
            setOnClickListener {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "text/plain"
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivityForResult(intent, REQUEST_COOKIES)
            }
        }
        root.addView(cookiesButton)

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
        // Only the hostname is case-insensitive. Video and playlist IDs are
        // case-sensitive and are copied without lowercasing.
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
            // Do not derive a video ID by slicing an RD... Mix playlist ID.
            // Preserve the exact Mix ID and let yt-dlp interpret the playlist.
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

    private fun logBundledYtDlpVersion() {
        executor.execute {
            try {
                append("Проверка фактической версии yt-dlp...")
                val versionRequest = YtDlpRequest("https://www.youtube.com")
                    .addOption("--version")
                val versionTask = YtDlp.executeAsync(versionRequest) {
                        _, _, line ->
                    if (!line.isNullOrBlank()) {
                        append("yt-dlp version: $line")
                    }
                }
                val versionResult = versionTask.get()
                if (!versionResult.isSuccess) {
                    append("Не удалось получить версию yt-dlp через --version (код ${versionResult.exitCode}).")
                }
            } catch (error: Exception) {
                append("Проверка версии не удалась: ${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_COOKIES || resultCode != RESULT_OK) return

        val sourceUri = data?.data ?: return
        try {
            val source = contentResolver.openInputStream(sourceUri)
                ?: error("Не удалось открыть выбранный файл")
            source.use { input ->
                val destination = File(filesDir, "youtube-cookies.txt")
                destination.outputStream().use { output -> input.copyTo(output) }
                cookiesFile = destination
            }
            append("Файл cookies.txt выбран и сохранён в закрытой папке приложения.")
            Toast.makeText(this, "Cookies подключены", Toast.LENGTH_SHORT).show()
        } catch (error: Exception) {
            cookiesFile = null
            Toast.makeText(
                this,
                "Не удалось прочитать cookies.txt: ${error.message}",
                Toast.LENGTH_LONG
            ).show()
        }
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
        append("\nЗапуск: $url")

        executor.execute {
            try {
                val musicDirectory = getExternalFilesDir(Environment.DIRECTORY_MUSIC)
                    ?: error("Папка Music недоступна")
                val outputDirectory = File(musicDirectory, "YouTube_MP3")
                if (!outputDirectory.exists() && !outputDirectory.mkdirs()) {
                    error("Не удалось создать папку загрузок")
                }

                val request = YtDlpRequest(normalized.value)
                    .setOutputTemplate(
                        File(outputDirectory, "%(title)s.%(ext)s").absolutePath
                    )
                    .addOption("--ignore-errors")
                    .addOption("--no-overwrites")
                    .addOption(if (normalized.isPlaylist) "--yes-playlist" else "--no-playlist")
                    .addOption("-x")
                    .addOption("--audio-format", "mp3")
                    .addOption("--audio-quality", quality)
                    // Try embedded-TV and mobile-web player clients.
                    // YouTube may still require valid account cookies or a PO Token.
                    .addOption(
                        "--extractor-args",
                        "youtube:player_client=tv_embedded,mweb"
                    )
                    .addOption("--force-ipv4")
                    .addOption("--newline")

                cookiesFile?.takeIf { it.isFile && it.length() > 0 }?.let { file ->
                    request.addOption("--cookies", file.absolutePath)
                    append("Используется выбранный cookies.txt (содержимое не выводится в лог).")
                }

                append("Параметры: player_client=tv_embedded,mweb; IPv4 принудительно; качество=$quality")
                if (useArchive) {
                    request.addOption(
                        "--download-archive",
                        File(outputDirectory, "downloaded.txt").absolutePath
                    )
                }

                val task = YtDlp.executeAsync(request) { progress, eta, line ->
                    if (!line.isNullOrEmpty()) {
                        append(String.format(Locale.US, "%.1f%% | ETA %ds | %s", progress, eta, line))
                    }
                }
                val result = task.get()
                if (result.isSuccess) {
                    append("Готово. Загрузка завершена.")
                } else {
                    append("Загрузка завершилась с кодом ошибки: ${result.exitCode}")
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
            if (::logView.isInitialized) logView.append("$line\n")
        }
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }
}
