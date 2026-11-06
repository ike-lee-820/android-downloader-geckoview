package com.yuanbao.downloader.presentation

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yuanbao.downloader.MainActivity
import com.yuanbao.downloader.data.settings.AppSettings
import com.yuanbao.downloader.data.settings.SettingsDataStore
import com.yuanbao.downloader.data.settings.SettingsKeys
import com.yuanbao.downloader.domain.EngineEvent
import com.yuanbao.downloader.engine.DownloadEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val settingsStore: SettingsDataStore,
    private val engine: DownloadEngine
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val _pendingUrl = MutableStateFlow<String?>(null)
    val pendingUrl: StateFlow<String?> = _pendingUrl.asStateFlow()

    private val _mobileConfirm = MutableStateFlow<String?>(null)
    val mobileConfirm: StateFlow<String?> = _mobileConfirm.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        viewModelScope.launch {
            engine.events.collect { event ->
                when (event) {
                    is EngineEvent.MobileNetworkConfirm ->
                        _mobileConfirm.value = "当前为移动网络，是否继续下载？"
                    is EngineEvent.Message -> _message.value = event.text
                    else -> Unit
                }
            }
        }
    }

    fun handleIntent(intent: Intent) {
        val raw = intent.getStringExtra(MainActivity.EXTRA_NEW_TASK_URL)
            ?: intent.data?.toString()
            ?: intent.getStringExtra(Intent.EXTRA_TEXT)
            ?: return
        val url = Regex("(?:https?|ftp)://[^\\s<>\"']+").find(raw)?.value
            ?: raw.trim().takeIf { it.startsWith("http") }
        if (!url.isNullOrBlank()) _pendingUrl.value = url
    }

    fun consumePendingUrl() { _pendingUrl.value = null }
    fun clearMessage() { _message.value = null }
    fun dismissMobileConfirm() { _mobileConfirm.value = null }

    fun confirmMobile() {
        engine.resumeAllForNetwork()
        _mobileConfirm.value = null
    }

    fun setOnboardingDone() {
        viewModelScope.launch { settingsStore.set(SettingsKeys.ONBOARDING_DONE, true) }
    }
}
