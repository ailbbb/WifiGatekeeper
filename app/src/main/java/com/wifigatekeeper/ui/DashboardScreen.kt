package com.wifigatekeeper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wifigatekeeper.data.db.KnownDeviceEntity
import com.wifigatekeeper.data.repository.PollStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Green = Color(0xFF529286)
private val Red = Color(0xFFB3474A)
private val Amber = Color(0xFFC9A227)

/** Main dashboard: status card, three tabs, settings and activity log. */
@Composable
fun DashboardScreen(onEditCredentials: () -> Unit) {
    val vm: DashboardViewModel = viewModel()

    val pollStatus by vm.pollStatus.collectAsState()
    val pending by vm.pendingDevices.collectAsState()
    val whitelisted by vm.whitelistedDevices.collectAsState()
    val monitoring by vm.monitoringEnabled.collectAsState()
    val interval by vm.pollIntervalSeconds.collectAsState()
    val macFiltering by vm.macFiltering.collectAsState()
    val logEntries by vm.activityLog.collectAsState()
    val toast by vm.toast.collectAsState()

    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Pending", "Whitelisted", "Settings")

    LaunchedEffect(toast) {
        toast?.let { message ->
            // Auto-dismiss after a short read window.
            kotlinx.coroutines.delay(4_000)
            vm.consumeToast()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        StatusCard(pollStatus, monitoring, vm::setMonitoring)

        Spacer(Modifier.height(12.dp))

        TabRow(selectedTabIndex = tab) {
            tabs.forEachIndexed { index, label ->
                Tab(
                    selected = tab == index,
                    onClick = { tab = index },
                    text = { Text(label) },
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> DeviceList(
                    devices = pending,
                    emptyText = "No pending devices. New devices will appear here.",
                    macLabel = { device -> device.mac },
                    trailing = { device ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { vm.accept(device.mac) }, enabled = !vm.busy.value) {
                                Text("Accept")
                            }
                            OutlinedButton(onClick = { vm.reject(device.mac) }) {
                                Text("Reject")
                            }
                        }
                    },
                )
                1 -> DeviceList(
                    devices = whitelisted,
                    emptyText = "Whitelist is empty.",
                    macLabel = { device -> device.mac },
                    trailing = { device ->
                        OutlinedButton(onClick = { vm.revoke(device.mac) }, enabled = !vm.busy.value) {
                            Text("Revoke")
                        }
                    },
                )
                else -> SettingsTab(
                    macFiltering = macFiltering,
                    interval = interval,
                    logEntries = logEntries,
                    onSetMacFiltering = vm::setMacFiltering,
                    onSetInterval = vm::setPollInterval,
                    onEditCredentials = onEditCredentials,
                    onClearLog = { vm.consumeToast() },
                )
            }
        }

        toast?.let { message ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = message,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                    .padding(10.dp),
            )
        }
    }
}

@Composable
private fun StatusCard(
    pollStatus: PollStatus,
    monitoring: Boolean,
    onSetMonitoring: (Boolean) -> Unit,
) {
    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .background(
                            if (pollStatus.connected) Green else Red,
                            CircleShape,
                        )
                        .padding(6.dp),
                )
                Spacer(Modifier.padding(6.dp))
                Column {
                    Text(
                        text = if (pollStatus.connected) "Router connected" else "Router disconnected",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                    )
                    Text(
                        text = pollStatus.message.ifBlank { "Waiting for the first poll" },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = buildString {
                    append("Last poll: ")
                    append(
                        if (pollStatus.lastPollAt > 0) {
                            timeFormatter.format(Date(pollStatus.lastPollAt))
                        } else {
                            "never"
                        },
                    )
                    append("  -  Devices: ")
                    append(pollStatus.deviceCount)
                },
                fontSize = 12.sp,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Real-time monitoring", fontWeight = FontWeight.SemiBold)
                    Text(
                        text = "Foreground poll for instant alerts",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Switch(checked = monitoring, onCheckedChange = onSetMonitoring)
            }
        }
    }
}

@Composable
private fun DeviceList(
    devices: List<KnownDeviceEntity>,
    emptyText: String,
    macLabel: (KnownDeviceEntity) -> String,
    trailing: @Composable (KnownDeviceEntity) -> Unit,
) {
    if (devices.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(emptyText, color = MaterialTheme.colorScheme.primary)
        }
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(devices, key = { it.mac }) { device ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(macLabel(device), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
                    device.name?.takeIf { it.isNotBlank() }?.let {
                        Text("Name: $it", fontSize = 12.sp)
                    }
                    device.lastIp?.let {
                        Text("IP: $it", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        trailing(device)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsTab(
    macFiltering: Boolean?,
    interval: Int,
    logEntries: List<com.wifigatekeeper.data.db.ActivityLogEntity>,
    onSetMacFiltering: (Boolean) -> Unit,
    onSetInterval: (Int) -> Unit,
    onEditCredentials: () -> Unit,
    onClearLog: () -> Unit,
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("MAC filtering (whitelist mode)", fontWeight = FontWeight.SemiBold)
                    Text(
                        text = when (macFiltering) {
                            null -> "State unknown (router did not report it)"
                            else -> if (macFiltering) "Enabled: unknown MACs cannot join the network" else "Disabled: every device can join"
                        },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Enabled", modifier = Modifier.weight(1f))
                        Switch(
                            checked = macFiltering == true,
                            onCheckedChange = { onSetMacFiltering(it) },
                        )
                    }
                }
            }
        }
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("Poll interval (real-time mode)", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (option in listOf(30, 60, 120, 300)) {
                            Box(
                                modifier = Modifier
                                    .background(
                                        if (interval == option) Green else MaterialTheme.colorScheme.surface,
                                        RoundedCornerShape(8.dp),
                                    )
                                    .clickable { onSetInterval(option) }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                            ) {
                                Text(
                                    text = "$option s",
                                    color = if (interval == option) Color.White else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    TextButton(onClick = onEditCredentials) {
                        Text("Edit router credentials")
                    }
                }
            }
        }
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Recent activity", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = onClearLog) { Text("Clear") }
            }
        }
        items(logEntries, key = { it.id }) { entry ->
            val formatter = remember { SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()) }
            Column(Modifier.padding(vertical = 2.dp)) {
                Text(
                    text = formatter.format(Date(entry.timestamp)) + " - " + entry.type,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(entry.message, fontSize = 12.sp)
            }
        }
    }
}
