package com.wifigatekeeper.data.repository

import com.wifigatekeeper.core.network.LocalNetworkChecker
import com.wifigatekeeper.core.network.SelfMacResolver
import com.wifigatekeeper.core.router.DeviceScanEntry
import com.wifigatekeeper.core.router.MacAddressValidator
import com.wifigatekeeper.core.router.ProtectionApplier
import com.wifigatekeeper.core.router.RouterClient
import com.wifigatekeeper.core.router.RouterClientFactory
import com.wifigatekeeper.core.router.RouterException
import com.wifigatekeeper.core.router.RouterKind
import com.wifigatekeeper.data.db.ActivityLogEntity
import com.wifigatekeeper.data.db.AppDatabase
import com.wifigatekeeper.data.db.DeviceStatus
import com.wifigatekeeper.data.db.KnownDeviceEntity
import com.wifigatekeeper.data.security.CredentialsStore
import com.wifigatekeeper.data.settings.SettingsRepository
import com.wifigatekeeper.notifications.Notifier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Aggregated outcome of one poll cycle, surfaced in the status card. */
data class PollStatus(
    val connected: Boolean = false,
    val message: String = "",
    val lastPollAt: Long = 0,
    val deviceCount: Int = 0,
)

/** The brain: scans, baselines, decisions, and activity logging. */
class RouterRepository(
    private val credentials: CredentialsStore,
    private val settings: SettingsRepository,
    private val db: AppDatabase,
    private val networkChecker: LocalNetworkChecker,
    private val notifier: Notifier,
    private val lanBinding: com.wifigatekeeper.core.network.LanNetworkBinding? = null,
) {
    private val deviceDao get() = db.deviceDao()
    private val logDao get() = db.activityLogDao()

    private val _pollStatus = MutableStateFlow(PollStatus())
    val pollStatus: StateFlow<PollStatus> = _pollStatus

    val pendingDevices: Flow<List<KnownDeviceEntity>> = deviceDao.pendingDevices()
    val whitelistedDevices: Flow<List<KnownDeviceEntity>> = deviceDao.whitelistedDevices()
    val whitelistedCount: Flow<Int> = deviceDao.whitelistedCount()

    private fun client(): RouterClient? {
        if (!credentials.hasCredentials()) return null
        val kind = runCatching { RouterKind.valueOf(credentials.kind()) }
            .getOrDefault(RouterKind.TP_LINK_CLASSIC)
        return RouterClientFactory.create(
            kind,
            credentials.ip(),
            credentials.username(),
            credentials.password(),
            // Pin requests to the Wi-Fi transport when mobile data is also up,
            // otherwise Android may route LAN-bound calls over cellular.
            lanBinding?.wifiSocketFactory(),
        )
    }

    private suspend fun log(type: String, message: String) {
        logDao.insert(
            ActivityLogEntity(timestamp = System.currentTimeMillis(), type = type, message = message),
        )
    }

    /** One scan cycle. [notify] controls new-device notifications. */
    suspend fun pollNow(notify: Boolean) {
        val client = client() ?: return
        val currentSettings = settings.snapshot()
        if (!currentSettings.monitoringEnabled) return

        if (!networkChecker.isOnSameNetworkAs(credentials.ip())) {
            _pollStatus.value = PollStatus(
                connected = false,
                message = "Phone is not on the router network",
                lastPollAt = System.currentTimeMillis(),
                deviceCount = _pollStatus.value.deviceCount,
            )
            log("network", "Poll skipped: phone is not on the router network.")
            return
        }

        client.fetchConnectedDevices().fold(
            onSuccess = { entries ->
                val now = System.currentTimeMillis()
                var newCount = 0
                var notified = 0
                for (entry in entries) {
                    val existing = deviceDao.byMac(entry.mac)
                    when {
                        existing == null -> {
                            if (!currentSettings.baselineDone) {
                                // First successful scan: devices already present are trusted.
                                deviceDao.upsert(
                                    KnownDeviceEntity(
                                        entry.mac, entry.name, entry.ip, now, now,
                                        DeviceStatus.WHITELISTED.name,
                                    ),
                                )
                            } else {
                                deviceDao.upsert(
                                    KnownDeviceEntity(
                                        entry.mac, entry.name, entry.ip, now, now,
                                        DeviceStatus.PENDING.name,
                                    ),
                                )
                                newCount++
                                if (notify && notified < MAX_NOTIFICATIONS_PER_POLL) {
                                    notifier.notifyNewDevice(entry.mac, entry.name, entry.ip)
                                    notified++
                                }
                            }
                        }
                        else -> deviceDao.touch(entry.mac, now, entry.ip)
                    }
                }
                if (!currentSettings.baselineDone && entries.isNotEmpty()) {
                    settings.setBaselineDone(true)
                    log("baseline", "Baseline recorded: ${entries.size} existing devices trusted.")
                }
                _pollStatus.value = PollStatus(true, "Router connected", now, entries.size)
                if (newCount > 0) log("discovery", "Found $newCount new device(s) on the network.")
            },
            onFailure = { error ->
                _pollStatus.value = PollStatus(
                    false,
                    error.message ?: "Poll failed",
                    System.currentTimeMillis(),
                    _pollStatus.value.deviceCount,
                )
                // Diagnostics: say WHICH router kind failed and why, so an
                // unsupported model is visible in the activity log instead of
                // a bare "poll failed".
                log(
                    "error",
                    "Poll failed on ${client.kind.label}: ${error.message} " +
                        "(gateway ${credentials.ip()})",
                )
            },
        )
    }

    suspend fun acceptDevice(mac: String): Result<Unit> {
        val client = client() ?: return Result.failure(RouterException("No router configured yet."))
        val label = deviceDao.byMac(mac)?.name.orEmpty()
        return client.addToWhitelist(mac, label).fold(
            onSuccess = {
                deviceDao.updateStatus(mac, DeviceStatus.WHITELISTED.name)
                log("accept", "Device $mac accepted and added to the router whitelist.")
                notifier.cancelDeviceNotification(mac)
                Result.success(Unit)
            },
            onFailure = { error ->
                log("error", "Accept failed for $mac: ${error.message}")
                Result.failure(error)
            },
        )
    }

    /** Local-only rejection: stops notifications, does not touch the router. */
    suspend fun rejectDevice(mac: String): Result<Unit> {
        deviceDao.updateStatus(mac, DeviceStatus.BLACKLISTED.name)
        notifier.cancelDeviceNotification(mac)
        log("reject", "Device $mac rejected locally (notifications stopped).")
        return Result.success(Unit)
    }

    suspend fun revokeDevice(mac: String): Result<Unit> {
        val client = client() ?: return Result.failure(RouterException("No router configured yet."))
        return client.removeFromWhitelist(mac).fold(
            onSuccess = {
                deviceDao.updateStatus(mac, DeviceStatus.PENDING.name)
                log("revoke", "Device $mac removed from the router whitelist.")
                Result.success(Unit)
            },
            onFailure = { error ->
                log("error", "Revoke failed for $mac: ${error.message}")
                Result.failure(error)
            },
        )
    }

    suspend fun isMacFilteringEnabled(): Result<Boolean?> {
        val client = client() ?: return Result.failure(RouterException("No router configured yet."))
        return client.isMacFilteringEnabled()
    }

    suspend fun setMacFilteringEnabled(enabled: Boolean): Result<Unit> {
        val client = client() ?: return Result.failure(RouterException("No router configured yet."))
        return client.setMacFilteringEnabled(enabled)
    }

    suspend fun probeConnection(): Result<Int> {
        val client = client() ?: return Result.failure(RouterException("No router configured yet."))
        return client.probeConnection()
    }

    /**
     * Auto-protection: identifies this phone, whitelists it FIRST, and only
     * then switches MAC filtering on (the reverse order would lock the phone
     * out of its own network). The switch is gated on the phone's MAC being
     * confirmed by the router's own device list - never on a bare hardware-MAC
     * read, which Android 10+ randomization makes wrong.
     */
    suspend fun applyProtection(): ProtectionApplier.Outcome {
        val client = client() ?: return ProtectionApplier.Outcome.Failed("No router configured yet.")
        if (!networkChecker.isOnSameNetworkAs(credentials.ip())) {
            return ProtectionApplier.Outcome.Failed("The phone is not on the router network.")
        }
        val localIp = networkChecker.currentLocalIp()
        val entries = client.fetchConnectedDevices().getOrElse {
            return ProtectionApplier.Outcome.Failed("Could not read the device list: ${it.message}")
        }
        val resolved = SelfMacResolver.resolveWithConfirmation(localIp, entries)
            ?: return ProtectionApplier.Outcome.Failed(
                "Could not identify this phone's MAC - pick it from the device list " +
                    "or add it manually from the router page.",
            )
        val label = android.os.Build.MODEL.ifBlank { "My phone" }
        val outcome = ProtectionApplier.apply(client, resolved.mac, label, resolved.confirmed)
        log(
            "protection",
            when (outcome) {
                is ProtectionApplier.Outcome.FullyApplied ->
                    "Protection applied: $label (${resolved.mac}) whitelisted and MAC filtering enabled."
                is ProtectionApplier.Outcome.WhitelistOnly ->
                    "${resolved.mac} whitelisted; enabling filtering failed: ${outcome.reason}"
                is ProtectionApplier.Outcome.Failed -> "Protection failed: ${outcome.message}"
            },
        )
        return outcome
    }

    fun recentLog(): Flow<List<ActivityLogEntity>> = logDao.recent()

    suspend fun clearLog() = logDao.clear()

    private companion object {
        const val MAX_NOTIFICATIONS_PER_POLL = 3
    }
}
