package com.wifigatekeeper.ui

import android.os.Build
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.HorizontalDivider
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
import com.wifigatekeeper.core.network.SelfMacResolver
import com.wifigatekeeper.core.router.MacAddressValidator
import com.wifigatekeeper.core.router.ProtectionApplier
import com.wifigatekeeper.core.router.RouterClientFactory
import com.wifigatekeeper.core.router.RouterKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val Accent = Color(0xFF529286)
private val ErrorRed = Color(0xFFB3474A)

/** Live status of one auto-protection step on the wizard screen. */
private data class ProtectStep(
    val label: String,
    val state: State,
    val detail: String = "",
) {
    enum class State { Waiting, Running, Done, Failed }
}

/**
 * First-run setup: a browser quick link that logs the user into their own
 * router and auto-applies protection (whitelist this phone first, then enable
 * MAC filtering), with the classic manual form kept below for edge cases.
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
    var quickLink by remember { mutableStateOf(false) }
    var protectionSteps by remember { mutableStateOf(listOf<ProtectStep>()) }

    // Prefill with the real gateway of the current Wi-Fi when discoverable.
    LaunchedEffect(Unit) {
        container.networkChecker.currentGateway()?.let { gateway ->
            if (gateway.isNotBlank()) routerIp = gateway
        }
    }

    // The quick-link browser flow replaces this screen entirely.
    if (quickLink) {
        QuickLinkScreen(
            gatewayUrl = "http://" + routerIp.trim(),
            onLinked = { ip, linkedUser, linkedPass, linkedKind ->
                routerIp = ip
                username = linkedUser
                password = linkedPass
                kind = linkedKind
                container.credentialsStore.save(ip, linkedUser, linkedPass, linkedKind.name)
                runProtection(
                    container = container,
                    scope = scope,
                    kind = linkedKind,
                    ip = ip,
                    user = linkedUser,
                    pass = linkedPass,
                    onSteps = { protectionSteps = it },
                    onFinished = { MonitoringForegroundService.start(context); onDone() },
                )
            },
            onClose = { quickLink = false },
        )
        return
    }

    // The protection wizard also replaces this screen once it starts.
    if (protectionSteps.isNotEmpty()) {
        ProtectionWizardScreen(steps = protectionSteps, onClose = onDone)
        return
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
            text = "🛡",
            fontSize = 34.sp,
        )
        Text(
            text = "Wi-Fi Gatekeeper",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = "First-run setup  •  v1.3.0",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(24.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(2.dp, Accent),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    "⚡ Quick link (easiest)",
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Open your router's own page inside the app and log in as usual - the app " +
                        "reads the credentials, adds THIS phone to the whitelist first, then " +
                        "turns MAC filtering on. No typing here.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = { quickLink = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Open browser quick link")
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            HorizontalDivider(modifier = Modifier.weight(1f))
            Text(
                "  manual setup (advanced)  ",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary,
            )
            HorizontalDivider(modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(16.dp))

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
                color = if (testOk) Accent else MaterialTheme.colorScheme.error,
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
                runProtection(
                    container = container,
                    scope = scope,
                    kind = kind,
                    ip = routerIp,
                    user = username,
                    pass = password,
                    onSteps = { protectionSteps = it },
                    onFinished = { MonitoringForegroundService.start(context); onDone() },
                )
            },
            enabled = testOk,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Save and protect")
        }
        TextButton(
            onClick = {
                container.credentialsStore.save(routerIp, username, password, kind.name)
                MonitoringForegroundService.start(context)
                onDone()
            },
            modifier = Modifier.padding(top = 4.dp),
        ) {
            Text("Skip protection (advanced)")
        }
        Text(
            text = "No WPS option? Android 9+ removed WPS from apps entirely, so the " +
                "browser quick link above is the safe replacement.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 8.dp),
        )
        Spacer(Modifier.height(24.dp))
    }
}

/** Starts the auto-protection wizard against [ip] and reports progress via [onSteps]. */
private fun runProtection(
    container: com.wifigatekeeper.ServiceLocator,
    scope: kotlinx.coroutines.CoroutineScope,
    kind: RouterKind,
    ip: String,
    user: String,
    pass: String,
    onSteps: (List<ProtectStep>) -> Unit,
    onFinished: () -> Unit,
) {
    val initial = listOf(
        ProtectStep("Finding this phone on the network", ProtectStep.State.Waiting),
        ProtectStep("Adding \"${Build.MODEL.ifBlank { "this phone" }}\" to the whitelist", ProtectStep.State.Waiting),
        ProtectStep("Enabling MAC filtering (allow-list mode)", ProtectStep.State.Waiting),
    )
    onSteps(initial)

    fun update(index: Int, state: ProtectStep.State, detail: String = "") {
        onSteps(initial.mapIndexed { i, step ->
            if (i == index) step.copy(state = state, detail = detail) else step
        })
    }

    scope.launch {
        val client = RouterClientFactory.create(kind, ip, user, pass)

        update(0, ProtectStep.State.Running)
        val localIp = container.networkChecker.currentLocalIp()
        val entries = client.fetchConnectedDevices().fold(
            onSuccess = { it },
            onFailure = {
                update(0, ProtectStep.State.Failed, it.message ?: "scan failed")
                update(1, ProtectStep.State.Failed)
                update(2, ProtectStep.State.Failed)
                return@launch
            },
        )
        val mac = SelfMacResolver.resolve(localIp, entries)?.let { MacAddressValidator.normalise(it) }
        if (mac == null) {
            update(0, ProtectStep.State.Failed, "MAC not found - add the phone manually")
            update(1, ProtectStep.State.Failed)
            update(2, ProtectStep.State.Failed)
            return@launch
        }
        update(0, ProtectStep.State.Done, mac)

        val outcome = ProtectionApplier.apply(
            client,
            mac,
            Build.MODEL.ifBlank { "My phone" },
        ) { step ->
            when (step) {
                ProtectionApplier.Step.WHITELIST -> update(1, ProtectStep.State.Running)
                ProtectionApplier.Step.ENABLE -> { update(1, ProtectStep.State.Done); update(2, ProtectStep.State.Running) }
            }
        }
        when (outcome) {
            is ProtectionApplier.Outcome.FullyApplied -> update(2, ProtectStep.State.Done)
            is ProtectionApplier.Outcome.WhitelistOnly -> {
                update(1, ProtectStep.State.Done, mac)
                update(2, ProtectStep.State.Failed, outcome.reason)
            }
            is ProtectionApplier.Outcome.Failed -> {
                update(1, ProtectStep.State.Failed, outcome.message)
                update(2, ProtectStep.State.Failed)
            }
        }
        // Successful runs continue to the dashboard automatically; failures stay
        // visible until the user closes the wizard on purpose.
        if (outcome is ProtectionApplier.Outcome.FullyApplied) {
            delay(1_200)
            onFinished()
        }
    }
}

/** Progress surface shown while the protection sequence runs. */
@Composable
private fun ProtectionWizardScreen(steps: List<ProtectStep>, onClose: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
    ) {
        Spacer(Modifier.height(40.dp))
        Text("🛡 Setting up protection", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(
            "Your phone is whitelisted before the filter goes on, so you can never lock yourself out.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 6.dp),
        )
        Spacer(Modifier.height(24.dp))
        for (step in steps) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 10.dp)) {
                when (step.state) {
                    ProtectStep.State.Running -> CircularProgressIndicator(
                        modifier = Modifier.height(20.dp).width(20.dp),
                        strokeWidth = 2.dp,
                    )
                    ProtectStep.State.Done -> Text("✓", color = Accent, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    ProtectStep.State.Failed -> Text("✕", color = ErrorRed, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    ProtectStep.State.Waiting -> Text("○", color = MaterialTheme.colorScheme.primary, fontSize = 20.sp)
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(step.label, fontSize = 15.sp)
                    if (step.detail.isNotBlank()) {
                        Text(
                            step.detail,
                            fontSize = 12.sp,
                            color = if (step.state == ProtectStep.State.Failed) ErrorRed else MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
        val allSettled = steps.isNotEmpty() && steps.none {
            it.state == ProtectStep.State.Waiting || it.state == ProtectStep.State.Running
        }
        val anyFailed = steps.any { it.state == ProtectStep.State.Failed }
        if (allSettled && anyFailed) {
            Spacer(Modifier.height(16.dp))
            Text(
                "Setup is still usable - live monitoring works. Apply the failed steps " +
                    "from Settings → One-tap protection, or from the router page.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                Text("Open dashboard")
            }
        }
    }
}
