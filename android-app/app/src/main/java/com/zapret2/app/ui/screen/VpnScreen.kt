package com.zapret2.app.ui.screen

import android.content.Intent
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zapret2.app.R
import com.zapret2.app.network.NetworkGuardService
import com.zapret2.app.ui.components.AdaptiveActionGroup
import com.zapret2.app.ui.components.ContentCard
import com.zapret2.app.ui.components.SectionHeader
import com.zapret2.app.ui.components.SettingRow
import com.zapret2.app.ui.components.SettingToggleRow
import com.zapret2.app.ui.components.StatusIndicator
import com.zapret2.app.ui.theme.SizeTokens
import com.zapret2.app.ui.theme.SpacingTokens
import com.zapret2.app.vpn.NetworkMode
import com.zapret2.app.vpn.NetworkTransport
import com.zapret2.app.vpn.VpnNode
import com.zapret2.app.vpn.VpnStatus
import com.zapret2.app.vpn.ZapretVpnService
import com.zapret2.app.viewmodel.VpnUiState
import com.zapret2.app.viewmodel.VpnViewModel

/**
 * The tunnel screen: the subscription, the servers it contains, the tunnel itself and the
 * routing mode that decides whether the traffic leaves over Wi-Fi or over mobile data.
 */
@Composable
fun VpnScreen(
    viewModel: VpnViewModel? = null,
    previewState: VpnUiState? = null,
) {
    val activeViewModel = viewModel ?: if (previewState == null) hiltViewModel() else null
    val runtimeState = activeViewModel?.uiState?.collectAsStateWithLifecycle()
    val state = previewState ?: runtimeState?.value ?: VpnUiState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    var pendingStart by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (pendingStart) {
            pendingStart = false
            context.startService(Intent(context, ZapretVpnService::class.java))
        }
    }

    LaunchedEffect(activeViewModel) {
        activeViewModel?.ensureLoaded()
    }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        activeViewModel?.consumeMessage()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { scaffoldPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPadding),
        ) {
            val horizontalPadding = if (maxWidth >= SizeTokens.MediumBreakpoint) {
                SpacingTokens.ExtraLarge
            } else {
                SpacingTokens.Large
            }
            val compactActions = maxWidth < SizeTokens.CompactActionsBreakpoint
            LazyColumn(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .widthIn(max = SizeTokens.ContentMax)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(SpacingTokens.ItemVertical),
                contentPadding = PaddingValues(
                    start = horizontalPadding,
                    top = SpacingTokens.Large,
                    end = horizontalPadding,
                    bottom = SpacingTokens.Section,
                ),
            ) {
                item {
                    TunnelCard(
                        state = state,
                        onToggle = {
                            if (state.isRunning) {
                                context.startService(
                                    Intent(context, ZapretVpnService::class.java)
                                        .setAction(ZapretVpnService.ACTION_STOP),
                                )
                            } else {
                                val prepare = VpnService.prepare(context)
                                if (prepare != null) {
                                    pendingStart = true
                                    permissionLauncher.launch(prepare)
                                } else {
                                    context.startService(
                                        Intent(context, ZapretVpnService::class.java),
                                    )
                                }
                            }
                        },
                    )
                }

                item { SectionHeader(stringResource(R.string.vpn_subscription)) }

                item {
                    ContentCard {
                        OutlinedTextField(
                            value = state.subscriptionUrl,
                            onValueChange = { activeViewModel?.onSubscriptionUrlChanged(it) },
                            label = { Text(stringResource(R.string.vpn_subscription_hint)) },
                            singleLine = true,
                            enabled = !state.isBusy,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.width(SpacingTokens.Medium))
                        AdaptiveActionGroup(stacked = compactActions) { buttonModifier ->
                            FilledTonalButton(
                                onClick = { activeViewModel?.refreshSubscription() },
                                enabled = !state.isBusy,
                                modifier = buttonModifier,
                            ) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(SizeTokens.IconCompact),
                                )
                                Spacer(Modifier.width(SpacingTokens.Small))
                                Text(stringResource(R.string.vpn_update_subscription))
                            }
                            Button(
                                onClick = { activeViewModel?.testServers() },
                                enabled = !state.isBusy && state.nodes.isNotEmpty(),
                                colors = ButtonDefaults.buttonColors(),
                                modifier = buttonModifier,
                            ) {
                                Text(stringResource(R.string.vpn_test_servers))
                            }
                        }
                    }
                }

                item { SectionHeader(stringResource(R.string.vpn_servers)) }

                item {
                    ContentCard {
                        if (state.nodes.isEmpty()) {
                            Text(
                                text = stringResource(R.string.vpn_servers_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            state.nodes.forEach { node ->
                                ServerRow(
                                    node = node,
                                    selected = node.id == state.selectedNodeId,
                                    latency = state.latencies[node.id],
                                    onSelect = { activeViewModel?.selectNode(node) },
                                )
                            }
                        }
                    }
                }

                item { SectionHeader(stringResource(R.string.vpn_routing)) }

                item {
                    ContentCard {
                        SettingToggleRow(
                            title = stringResource(R.string.vpn_auto_switch),
                            subtitle = stringResource(R.string.vpn_auto_switch_subtitle),
                            checked = state.autoSwitchEnabled,
                            onCheckedChange = { enabled ->
                                activeViewModel?.setAutoSwitchEnabled(enabled)
                                val intent = Intent(context, NetworkGuardService::class.java).apply {
                                    action = if (enabled) {
                                        NetworkGuardService.ACTION_START
                                    } else {
                                        NetworkGuardService.ACTION_STOP
                                    }
                                }
                                if (enabled) {
                                    context.startForegroundService(intent)
                                } else {
                                    context.stopService(intent)
                                }
                            },
                            icon = Icons.Default.WifiTethering,
                        )
                        Spacer(Modifier.width(SpacingTokens.Medium))
                        Column(modifier = Modifier.selectableGroup()) {
                            ModeOption(
                                label = stringResource(R.string.vpn_mode_auto),
                                selected = state.mode == NetworkMode.AUTO,
                                onSelect = { activeViewModel?.setMode(NetworkMode.AUTO) },
                            )
                            ModeOption(
                                label = stringResource(R.string.vpn_mode_wifi),
                                selected = state.mode == NetworkMode.WIFI,
                                onSelect = { activeViewModel?.setMode(NetworkMode.WIFI) },
                            )
                            ModeOption(
                                label = stringResource(R.string.vpn_mode_lte),
                                selected = state.mode == NetworkMode.LTE,
                                onSelect = { activeViewModel?.setMode(NetworkMode.LTE) },
                            )
                        }
                        Spacer(Modifier.width(SpacingTokens.Medium))
                        SettingRow(
                            title = stringResource(R.string.vpn_current_network),
                            value = stringResource(networkLabel(state.network.transport)),
                            subtitle = state.networkReason.takeIf { it.isNotBlank() },
                            icon = Icons.Default.Tune,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TunnelCard(
    state: VpnUiState,
    onToggle: () -> Unit,
) {
    ContentCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = MaterialTheme.shapes.extraLarge,
            ) {
                Icon(
                    Icons.Default.PowerSettingsNew,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(SpacingTokens.RowVertical)
                        .size(SizeTokens.IconEmphasized),
                )
            }
            Spacer(Modifier.width(SpacingTokens.Large))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.vpn_tunnel),
                    style = MaterialTheme.typography.titleLargeEmphasized,
                )
                Text(
                    text = stringResource(statusLabel(state.status)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(SpacingTokens.Medium))
            StatusIndicator(isActive = state.isRunning)
        }
        Spacer(Modifier.width(SpacingTokens.Medium))
        AdaptiveActionGroup(stacked = false) { buttonModifier ->
            Button(
                onClick = onToggle,
                modifier = buttonModifier,
            ) {
                Text(
                    stringResource(
                        if (state.isRunning) R.string.vpn_disconnect else R.string.vpn_connect,
                    ),
                )
            }
        }
    }
}

@Composable
private fun ServerRow(
    node: VpnNode,
    selected: Boolean,
    latency: Int?,
    onSelect: () -> Unit,
) {
    Surface(
        onClick = onSelect,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = SpacingTokens.Large,
                vertical = SpacingTokens.RowVertical,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = null)
            Spacer(Modifier.width(SpacingTokens.Medium))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = node.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${node.protocol.displayName} · ${node.server}:${node.port}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(SpacingTokens.Medium))
            if (selected) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(SizeTokens.IconCompact),
                )
                Spacer(Modifier.width(SpacingTokens.Small))
            }
            Text(
                text = latency?.let { stringResource(R.string.vpn_latency_value, it) }
                    ?: stringResource(R.string.vpn_latency_unknown),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ModeOption(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onSelect,
            )
            .padding(vertical = SpacingTokens.Compact),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(SpacingTokens.Medium))
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun statusLabel(status: VpnStatus): Int = when (status) {
    VpnStatus.STOPPED -> R.string.vpn_status_stopped
    VpnStatus.STARTING -> R.string.vpn_status_starting
    VpnStatus.RUNNING -> R.string.vpn_status_running
    VpnStatus.ERROR -> R.string.vpn_status_error
}

private fun networkLabel(transport: NetworkTransport): Int = when (transport) {
    NetworkTransport.WIFI -> R.string.vpn_network_wifi
    NetworkTransport.CELLULAR -> R.string.vpn_network_cellular
    NetworkTransport.ETHERNET -> R.string.vpn_network_ethernet
    NetworkTransport.OTHER -> R.string.vpn_network_other
    NetworkTransport.NONE -> R.string.vpn_network_none
}