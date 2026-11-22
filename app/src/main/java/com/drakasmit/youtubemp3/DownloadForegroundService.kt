package com.drakasmit.youtubemp3

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.provider.MediaStore
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.io.FileInputStream
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

class DownloadForegroundService : Service() {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    @Volatile private var task: Future<*>? = null
    private val stopRequested = AtomicBoolean(false)
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var engineReady = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundCompat(buildNotification("Подготовка загрузки…", 0))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP_DOWNLOAD) {
            stopDownload()
            return START_NOT_STICKY
        }
        if (action == ACTION_START_DOWNLOAD) {
            if (task?.isDone == false) return START_NOT_STICKY
            val url = intent.getStringExtra(EXTRA_URL).orEmpty()
            val quality = intent.getStringExtra(EXTRA_QUALITY) ?: "0"
            val archive = intent.getBooleanExtra(EXTRA_ARCHIVE, true)
            if (url.isBlank()) {
                DownloadStateManager.log("Ошибка: пустая ссылка.")
                finishState("Не указана ссылка", true)
                return START_NOT_STICKY
            }
            stopRequested.set(false)
            DownloadStateManager.update { it.copy(isDownloading = true, progress = 0, status = "Запуск загрузки…", track = "", eta = -1L) }
            task = executor.submit { runDownload(url, quality, archive) }
        }
        return START_NOT_STICKY
    }

    private fun runDownload(rawUrl: String, quality: String, useArchive: Boolean) {
        var failed = false
        val outputDirectory = File(cacheDir, "YTDWNLD")
        val savedPaths = mutableSetOf<String>()
        var savedCount = 0
        var pendingFile: File? = null
        try {
            acquireWakeLock()
            YoutubeDL.getInstance().init(applicationContext)
            FFmpeg.getInstance().init(applicationContext)
            engineReady = true
            try {
                when (YoutubeDL.getInstance().updateYoutubeDL(applicationContext, YoutubeDL.UpdateChannel._NIGHTLY)) {
                    YoutubeDL.UpdateStatus.DONE -> log("Обновление yt-dlp: DONE")
                    YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> log("yt-dlp уже актуален.")
                    null -> log("Сервер не вернул статус обновления yt-dlp.")
                }
            } catch (e: Exception) {
                log("Обновление yt-dlp пропущено: ${e.message ?: e.javaClass.simpleName}")
            }

            val normalized = normalizeYouTubeUrl(rawUrl)
            log("Запуск: $rawUrl")
            log("Тип ссылки: ${if (normalized.isPlaylist) "плейлист" else "одно видео"}")
            if (outputDirectory.exists()) outputDirectory.deleteRecursively()
            check(outputDirectory.mkdirs() || outputDirectory.isDirectory) { "Не удалось создать временную папку загрузки" }
            val template = File(outputDirectory, if (normalized.isPlaylist) "%(playlist_index)s - %(title)s.%(ext)s" else "%(title)s.%(ext)s").absolutePath
            val request = YoutubeDLRequest(normalized.value)
                .addOption("-o", template)
                .addOption("--ignore-errors")
                .addOption("--no-overwrites")
                .addOption(if (normalized.isPlaylist) "--yes-playlist" else "--no-playlist")
                .addOption("-x").addOption("--audio-format", "mp3")
                .addOption("--audio-quality", quality).addOption("--newline")
                .addOption("--postprocessor-args", "ffmpeg:-threads 4 -loglevel error")
            if (normalized.isMix) {
                request.addOption("--playlist-end", "25")
                log("Персональный автомикс YouTube: ограничение 25 треков.")
            }
            if (useArchive) request.addOption("--download-archive", File(filesDir, "downloaded.txt").absolutePath)
            log("Временная папка: cache/YTDWNLD")
            log("Архив истории: внутреннее хранилище приложения")
            request.buildCommand().forEachIndexed { index, arg -> log("  ${index + 1}. $arg") }

            val published = mutableListOf<Uri>()
            fun saveFile(file: File?) {
                if (file == null || !file.exists() || !file.isFile || !file.extension.equals("mp3", true) ||
                    file.absolutePath in savedPaths || stopRequested.get()) return
                try {
                    val uri = publishMp3(file)
                    published.add(uri)
                    savedPaths.add(file.absolutePath)
                    savedCount++
                    file.delete()
                    DownloadStateManager.update { it.copy(lastDownloadedUri = uri.toString(), status = "Сохранён треков: $savedCount") }
                    log("Сохранён сразу после конвертации ($savedCount): ${file.name}")
                } catch (e: Exception) {
                    log("Не удалось сохранить ${file.name}: ${e.message}")
                }
            }

            val response = YoutubeDL.getInstance().execute(request, PROCESS_ID, false) { progress, eta, line ->
                if (line.isNotBlank()) {
                    val percent = progress.toInt().coerceIn(0, 100)
                    val item = Regex("""Downloading item\\s+(\\d+)\\s+of\\s+(\\d+)""", RegexOption.IGNORE_CASE).find(line)
                    val trackText = item?.let { "Трек ${it.groupValues[1]} из ${it.groupValues[2]}" }.orEmpty()
                    val stage = when {
                        line.contains("ffmpeg", true) || line.contains("converting", true) || line.contains("ExtractAudio", true) -> "Конвертация в MP3…"
                        line.contains("Destination:", true) -> "Подготовка файла…"
                        else -> "Загрузка: ${String.format(Locale.US, "%.1f", progress)}%"
                    }
                    DownloadStateManager.update { it.copy(progress = percent, eta = eta.toLong(), track = trackText, status = if (trackText.isNotEmpty()) "$trackText • $stage" else stage) }
                    updateNotification(DownloadStateManager.snapshot().status, percent)
                    log(String.format(Locale.US, "%.1f%% | ETA %ds | %s", progress, eta, line))
                    if (line.contains("[ExtractAudio] Destination:", true)) {
                        pendingFile = File(line.substringAfter("Destination:", "").trim().trim('"'))
                    } else if (line.contains("Deleting original file", true) ||
                        Regex("""Downloading item\\s+\\d+\\s+of\\s+\\d+""", RegexOption.IGNORE_CASE).containsMatchIn(line)) {
                        saveFile(pendingFile)
                        pendingFile = null
                    }
                }
            }
            saveFile(pendingFile)
            if (stopRequested.get() || Thread.currentThread().isInterrupted) throw InterruptedException("Остановлено пользователем")
            if (response.exitCode != 0) {
                if (response.err.isNotBlank()) log(response.err.takeLast(12000))
                error("yt-dlp завершился с кодом ${response.exitCode}")
            }
            outputDirectory.walkTopDown().filter { it.isFile && it.extension.equals("mp3", true) && it.absolutePath !in savedPaths }.forEach { saveFile(it) }
            log(if (savedCount == 0) "Готовые MP3 не найдены." else "Всего сохранено MP3: $savedCount")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            if (!stopRequested.get()) {
                failed = true
                log("Задача прервана: ${e.message}")
            }
        } catch (e: Exception) {
            failed = true
            log("Ошибка загрузки: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
            wakeLock = null
            outputDirectory.deleteRecursively()
            if (!stopRequested.get()) finishState(if (failed) "Ошибка загрузки" else "Загрузка завершена", failed)
            else finishState("Загрузка остановлена", false)
            stopForegroundCompat()
            stopSelf()
        }
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:YtMp3Download")
            wakeLock?.acquire(6L * 60L * 60L * 1000L)
            log("CPU WakeLock активен.")
        } catch (e: Exception) {
            wakeLock = null
            log("WakeLock недоступен, продолжаю без него: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun normalizeYouTubeUrl(raw: String): NormalizedUrl {
        val uri = Uri.parse(raw.trim())
        val host = uri.host?.lowercase(Locale.ROOT) ?: error("Некорректный адрес ссылки")
        require(host in setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be", "www.youtu.be")) { "Поддерживаются только ссылки YouTube" }
        val videoId = if (host.endsWith("youtu.be")) uri.lastPathSegment else uri.getQueryParameter("v")
        val playlistId = uri.getQueryParameter("list")
        if (!videoId.isNullOrBlank()) require(videoId.matches(Regex("[A-Za-z0-9_-]{11}"))) { "Некорректный ID видео" }
        if (!playlistId.isNullOrBlank()) {
            val mix = playlistId.startsWith("RD") && !playlistId.startsWith("RDCLAK") && !playlistId.startsWith("RDMM")
            if (mix) {
                val id = videoId ?: run {
                    require(playlistId.length >= 13) { "В ID персонального микса не найден ID исходного видео" }
                    playlistId.substring(2, 13)
                }
                require(id.matches(Regex("[A-Za-z0-9_-]{11}"))) { "Некорректный ID видео в автомиксе" }
                return NormalizedUrl("https://www.youtube.com/watch?v=$id&list=$playlistId", true, true)
            }
            return NormalizedUrl("https://www.youtube.com/playlist?list=$playlistId", true)
        }
        require(!videoId.isNullOrBlank()) { "В ссылке не найден ID видео или плейлиста" }
        return NormalizedUrl("https://www.youtube.com/watch?v=$videoId", false)
    }

    private data class NormalizedUrl(val value: String, val isPlaylist: Boolean, val isMix: Boolean = false)

    private fun publishMp3(source: File): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, source.name)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/YTDWNLD")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
        }
        val uri = contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore не создал запись для ${source.name}")
        try {
            contentResolver.openOutputStream(uri, "w")?.use { output ->
                FileInputStream(source).use { input -> input.copyTo(output) }
            } ?: error("Не удалось открыть файл MediaStore")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val done = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
                contentResolver.update(uri, done, null, null)
            }
            return uri
        } catch (e: Exception) {
            contentResolver.delete(uri, null, null)
            throw e
        }
    }

    private fun stopDownload() {
        stopRequested.set(true)
        runCatching { YoutubeDL.getInstance().destroyProcessById(PROCESS_ID) }
        task?.cancel(true)
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
        DownloadStateManager.log("Запрошена остановка загрузки.")
    }

    private fun log(line: String) = DownloadStateManager.log(line)

    private fun finishState(status: String, failed: Boolean) {
        DownloadStateManager.update { it.copy(isDownloading = false, status = status, progress = if (failed) it.progress else 100, eta = -1L) }
        updateNotification(status, if (failed) DownloadStateManager.snapshot().progress else 100)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Загрузка MP3", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(status: String, progress: Int): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder.setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("YouTube MP3")
            .setContentText(status)
            .setProgress(100, progress.coerceIn(0, 100), false)
            .setStyle(Notification.BigTextStyle().bigText(status))
            .setOngoing(DownloadStateManager.snapshot().isDownloading)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NOTIFICATION_ID, notification)
    }

    private fun updateNotification(status: String, progress: Int) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(status, progress))
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE) else {
            @Suppress("DEPRECATION") stopForeground(true)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep the foreground download running when the activity task is swiped away.
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        executor.shutdown()
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START_DOWNLOAD = "com.drakasmit.youtubemp3.START_DOWNLOAD"
        const val ACTION_STOP_DOWNLOAD = "com.drakasmit.youtubemp3.STOP_DOWNLOAD"
        const val EXTRA_URL = "download_url"
        const val EXTRA_QUALITY = "download_quality"
        const val EXTRA_ARCHIVE = "download_archive"
        private const val CHANNEL_ID = "ytdwnld_download"
        private const val NOTIFICATION_ID = 7314
        private const val PROCESS_ID = "yt_mp3_download"

        fun startDownload(context: Context, url: String, quality: String, archive: Boolean) {
            val intent = Intent(context, DownloadForegroundService::class.java).apply {
                action = ACTION_START_DOWNLOAD
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_QUALITY, quality)
                putExtra(EXTRA_ARCHIVE, archive)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
        }

        fun stopDownload(context: Context) {
            val intent = Intent(context, DownloadForegroundService::class.java).apply { action = ACTION_STOP_DOWNLOAD }
            context.startService(intent)
        }
    }
}
