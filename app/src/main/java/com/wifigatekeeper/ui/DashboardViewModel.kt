package com.wifigatekeeper.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wifigatekeeper.WifiGatekeeperApp
import com.wifigatekeeper.background.MonitoringForegroundService
import com.wifigatekeeper.data.db.ActivityLogEntity
import com.wifigatekeeper.data.db.KnownDeviceEntity
import com.wifigatekeeper.data.repository.PollStatus
import com.wifigatekeeper.data.settings.SettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** State holder behind the dashboard tabs. */
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as WifiGatekeeperApp).container
    private val repository = container.repository

    val pollStatus: StateFlow<PollStatus> = repository.pollStatus

    val pendingDevices: StateFlow<List<KnownDeviceEntity>> = repository.pendingDevices
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val whitelistedDevices: StateFlow<List<KnownDeviceEntity>> = repository.whitelistedDevices
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val whitelistedCount: StateFlow<Int> = repository.whitelistedCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val monitoringEnabled: StateFlow<Boolean> = container.settingsRepository.settings
        .flatMapLatest { it -> MutableStateFlow(it.monitoringEnabled) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val pollIntervalSeconds: StateFlow<Int> = container.settingsRepository.settings
        .flatMapLatest { MutableStateFlow(it.pollIntervalSeconds) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 60)

    val activityLog: StateFlow<List<ActivityLogEntity>> = repository.recentLog()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _macFiltering = MutableStateFlow<Boolean?>(null)
    val macFiltering: StateFlow<Boolean?> = _macFiltering

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast

    init {
        refreshMacFiltering()
        // Kick off one immediate poll so the status card fills in quickly.
        viewModelScope.launch { repository.pollNow(notify = true) }
    }

    fun consumeToast() {
        _toast.value = null
    }

    fun refreshMacFiltering() {
        viewModelScope.launch {
            _macFiltering.value = repository.isMacFilteringEnabled().getOrNull()
        }
    }

    fun accept(mac: String) {
        viewModelScope.launch {
            _busy.value = true
            repository.acceptDevice(mac).fold(
                onSuccess = { _toast.value = "Device accepted and whitelisted." },
                onFailure = { _toast.value = it.message ?: "Accept failed." },
            )
            _busy.value = false
            refreshMacFiltering()
        }
    }

    fun reject(mac: String) {
        viewModelScope.launch {
            repository.rejectDevice(mac)
            _toast.value = "Device rejected locally."
        }
    }

    fun revoke(mac: String) {
        viewModelScope.launch {
            _busy.value = true
            repository.revokeDevice(mac).fold(
                onSuccess = { _toast.value = "Device removed from the whitelist." },
                onFailure = { _toast.value = it.message ?: "Revoke failed." },
            )
            _busy.value = false
            refreshMacFiltering()
        }
    }

    fun setMonitoring(enabled: Boolean) {
        viewModelScope.launch {
            container.settingsRepository.setMonitoringEnabled(enabled)
            val context = getApplication<Application>()
            if (enabled) {
                MonitoringForegroundService.start(context)
            } else {
                MonitoringForegroundService.stop(context)
            }
        }
    }

    fun setPollInterval(seconds: Int) {
        viewModelScope.launch { container.settingsRepository.setPollIntervalSeconds(seconds) }
    }

    fun setMacFiltering(enabled: Boolean) {
        viewModelScope.launch {
            _busy.value = true
            repository.setMacFilteringEnabled(enabled).fold(
                onSuccess = { _macFiltering.value = enabled },
                onFailure = { _toast.value = it.message ?: "Router refused the change." },
            )
            _busy.value = false
            refreshMacFiltering()
        }
    }
}
