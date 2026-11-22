package com.drakasmit.youtubemp3

import android.net.Uri
import java.util.concurrent.CopyOnWriteArraySet

data class DownloadUiState(
    val isDownloading: Boolean = false,
    val progress: Int = 0,
    val status: String = "Готово к загрузке",
    val log: String = "",
    val track: String = "",
    val eta: Long = -1L,
    val lastDownloadedUri: String? = null
)

object DownloadStateManager {
    @Volatile private var current = DownloadUiState()
    private val listeners = CopyOnWriteArraySet<(DownloadUiState) -> Unit>()

    fun snapshot(): DownloadUiState = current

    fun observe(listener: (DownloadUiState) -> Unit) {
        listeners.add(listener)
        listener(current)
    }

    fun remove(listener: (DownloadUiState) -> Unit) {
        listeners.remove(listener)
    }

    @Synchronized fun update(transform: (DownloadUiState) -> DownloadUiState) {
        current = transform(current)
        listeners.forEach { listener -> runCatching { listener(current) } }
    }

    fun log(line: String) = update { it.copy(log = (it.log + line + "\n").takeLast(30000)) }
}
