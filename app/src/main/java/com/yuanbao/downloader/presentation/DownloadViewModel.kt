package com.yuanbao.downloader.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yuanbao.downloader.browser.Sniffer
import com.yuanbao.downloader.domain.SniffedResource
import com.yuanbao.downloader.domain.TaskOptions
import com.yuanbao.downloader.domain.TaskProgress
import com.yuanbao.downloader.engine.DownloadEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DownloadViewModel @Inject constructor(
    private val engine: DownloadEngine,
    private val sniffer: Sniffer
) : ViewModel() {

    val tasks: StateFlow<List<TaskProgress>> = engine.tasks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun createTask(url: String, options: TaskOptions = TaskOptions()) {
        viewModelScope.launch { engine.enqueue(url, options) }
    }

    fun createFromSniff(resource: SniffedResource) {
        viewModelScope.launch {
            val (url, options) = sniffer.buildTaskRequest(resource)
            engine.enqueue(url, options)
        }
    }

    fun pause(id: String) = engine.pause(id)
    fun resume(id: String) = engine.resume(id)
    fun cancel(id: String) = engine.cancel(id)
    fun retry(id: String) = engine.retry(id)
    fun setThreadCount(id: String, count: Int) = engine.updateThreadCount(id, count)
    fun pauseAll() = engine.pauseAll()
}
