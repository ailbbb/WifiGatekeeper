package com.wifigatekeeper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wifigatekeeper.WifiGatekeeperApp
import com.wifigatekeeper.background.MonitoringForegroundService
import com.wifigatekeeper.core.router.RouterClientFactory
import com.wifigatekeeper.core.router.RouterKind
import kotlinx.coroutines.launch

/**
 * First-run setup: router address, admin credentials, router kind, and a live
 * connection test before anything is persisted.
 */
@Composable
fun SetupScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as WifiGatekeeperApp).container
    val scope = rememberCoroutineScope()

    var routerIp by remember { mutableStateOf("192.168.0.1") }
    var username by remember { mutableStateOf("admin") }
    var password by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(RouterKind.TP_LINK_CLASSIC) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testOk by remember { mutableStateOf(false) }

    // Prefill with the real gateway of the current Wi-Fi when discoverable.
    LaunchedEffect(Unit) {
        container.networkChecker.currentGateway()?.let { gateway ->
            if (gateway.isNotBlank()) routerIp = gateway
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        Text(
            text = "Wi-Fi Gatekeeper",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = "First-run setup",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = routerIp,
            onValueChange = { routerIp = it },
            label = { Text("Router IP address") },
            supportingText = { Text("TP-Link WR940N default: 192.168.0.1") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Admin username") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Admin password") },
            supportingText = { Text("Router login password - not the Wi-Fi password") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    "Router interface type",
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                for (candidate in RouterKind.entries) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        RadioButton(
                            selected = kind == candidate,
                            onClick = { kind = candidate },
                        )
                        Column {
                            Text(candidate.label, fontWeight = FontWeight.Medium)
                            Text(
                                candidate.hint,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(20.dp))

        Button(
            onClick = {
                testing = true
                testResult = null
                scope.launch {
                    val client = RouterClientFactory.create(kind, routerIp, username, password)
                    val result = client.probeConnection()
                    testing = false
                    result.fold(
                        onSuccess = { count ->
                            testOk = true
                            testResult = "Connected. $count devices found on the network."
                        },
                        onFailure = { error ->
                            testOk = false
                            testResult = error.message ?: "Connection failed."
                        },
                    )
                }
            },
            enabled = !testing && routerIp.isNotBlank() && password.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (testing) {
                CircularProgressIndicator(
                    modifier = Modifier.width(18.dp).height(18.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(10.dp))
            }
            Text("Test connection")
        }
        testResult?.let { message ->
            Spacer(Modifier.height(10.dp))
            Text(
                text = message,
                fontSize = 13.sp,
                color = if (testOk) Color(0xFF529286) else MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surface,
                        RoundedCornerShape(8.dp),
                    )
                    .padding(10.dp),
            )
        }
        Spacer(Modifier.height(16.dp))

        Button(
            onClick = {
                container.credentialsStore.save(routerIp, username, password, kind.name)
                MonitoringForegroundService.start(context)
                onDone()
            },
            enabled = testOk,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Save and continue")
        }
        TextButton(
            onClick = onDone,
            modifier = Modifier.padding(top = 4.dp),
        ) {
            Text("Skip test (advanced)")
        }
        Spacer(Modifier.height(24.dp))
    }
}
