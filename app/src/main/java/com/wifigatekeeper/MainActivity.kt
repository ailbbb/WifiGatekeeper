package com.wifigatekeeper

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
