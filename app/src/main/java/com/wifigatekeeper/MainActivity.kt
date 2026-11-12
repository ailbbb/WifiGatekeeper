package com.wifigatekeeper

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.wifigatekeeper.ui.DashboardScreen
import com.wifigatekeeper.ui.SetupScreen
import com.wifigatekeeper.ui.theme.WifiGatekeeperTheme

class MainActivity : ComponentActivity() {

    // True once router credentials exist (or were just saved).
    private var hasCredentials by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hasCredentials = (application as WifiGatekeeperApp).container.credentialsStore.hasCredentials()
        setContent {
            WifiGatekeeperTheme {
                // New-device alerts are the core feature: on Android 13+ they are
                // silently dropped until POST_NOTIFICATIONS is granted, so ask as
                // soon as the dashboard is reachable.
                val notificationPermission = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { }
                LaunchedEffect(hasCredentials) {
                    if (hasCredentials && Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.POST_NOTIFICATIONS,
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                if (hasCredentials) {
                    DashboardScreen(
                        onEditCredentials = { hasCredentials = false },
                    )
                } else {
                    SetupScreen(
                        onDone = { hasCredentials = true },
                    )
                }
            }
        }
    }
}
