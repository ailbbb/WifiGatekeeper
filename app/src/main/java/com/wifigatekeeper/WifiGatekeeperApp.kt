package com.wifigatekeeper

import android.app.Application
import android.content.Context
import com.wifigatekeeper.background.PollWorker
import com.wifigatekeeper.core.network.LocalNetworkChecker
import com.wifigatekeeper.data.db.AppDatabase
import com.wifigatekeeper.data.repository.RouterRepository
import com.wifigatekeeper.data.security.CredentialsStore
import com.wifigatekeeper.data.settings.SettingsRepository
import com.wifigatekeeper.notifications.NotificationChannels
import com.wifigatekeeper.notifications.Notifier

/** Composition root: one instance per process, lazily wired. */
class ServiceLocator(context: Context) {
    val credentialsStore = CredentialsStore(context)
    val settingsRepository = SettingsRepository(context)
    val networkChecker = LocalNetworkChecker(context)
    val notifier = Notifier(context)
    private val db = AppDatabase.get(context)
    val repository = RouterRepository(
        credentialsStore,
        settingsRepository,
        db,
        networkChecker,
        notifier,
    )
}

class WifiGatekeeperApp : Application() {

    lateinit var container: ServiceLocator
        private set

    override fun onCreate() {
        super.onCreate()
        container = ServiceLocator(this)
        NotificationChannels.ensure(this)
        PollWorker.schedulePeriodic(this)
    }
}
