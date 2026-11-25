package com.zapret2.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zapret2.app.vpn.NetworkMode
import com.zapret2.app.vpn.NetworkSnapshot
import com.zapret2.app.vpn.VpnNode
import com.zapret2.app.vpn.VpnRepository
import com.zapret2.app.vpn.VpnStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class VpnUiState(
    val status: VpnStatus = VpnStatus.STOPPED,
    val subscriptionUrl: String = "",
    val nodes: List<VpnNode> = emptyList(),
    val selectedNodeId: String? = null,
    val latencies: Map<String, Int?> = emptyMap(),
    val mode: NetworkMode = NetworkMode.AUTO,
    val autoSwitchEnabled: Boolean = true,
    val network: NetworkSnapshot = NetworkSnapshot(),
    val networkReason: String = "",
    val isBusy: Boolean = false,
    val message: String? = null,
) {
    val selectedNode: VpnNode? get() = nodes.firstOrNull { it.id == selectedNodeId }

    val isRunning: Boolean get() = status == VpnStatus.RUNNING
}

/**
 * State holder for the VPN screen.
 *
 * The tunnel itself lives in a service, so this view model only prepares what the screen needs
 * to show and forwards the user's choices to the repository that both the screen and the
 * services read from.
 */
@HiltViewModel
class VpnViewModel @Inject constructor(
    private val repository: VpnRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(VpnUiState())
    val uiState: StateFlow<VpnUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.status.collect { status ->
                _uiState.update { it.copy(status = status) }
            }
        }
        viewModelScope.launch {
            repository.nodes.collect { nodes ->
                _uiState.update { it.copy(nodes = nodes) }
            }
        }
        viewModelScope.launch {
            repository.selectedNodeId.collect { id ->
                _uiState.update { it.copy(selectedNodeId = id) }
            }
        }
        viewModelScope.launch {
            repository.subscriptionUrl.collect { url ->
                _uiState.update { it.copy(subscriptionUrl = url) }
            }
        }
        viewModelScope.launch {
            repository.latencies.collect { latencies ->
                _uiState.update { it.copy(latencies = latencies) }
            }
        }
        viewModelScope.launch {
            repository.mode.collect { mode ->
                _uiState.update { it.copy(mode = mode) }
            }
        }
        viewModelScope.launch {
            repository.autoSwitchEnabled.collect { enabled ->
                _uiState.update { it.copy(autoSwitchEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            repository.networkSnapshot.collect { snapshot ->
                _uiState.update { it.copy(network = snapshot) }
            }
        }
        viewModelScope.launch {
            repository.networkReason.collect { reason ->
                _uiState.update { it.copy(networkReason = reason) }
            }
        }
        viewModelScope.launch {
            repository.message.collect { message ->
                _uiState.update { it.copy(message = message) }
            }
        }
    }

    /** Restores the saved state once, when the screen asks for it. */
    fun ensureLoaded() {
        repository.ensureLoaded()
    }

    fun onSubscriptionUrlChanged(url: String) {
        repository.setSubscriptionUrl(url)
    }

    fun refreshSubscription() {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            val result = repository.refreshSubscription()
            _uiState.update {
                it.copy(
                    isBusy = false,
                    message = result.exceptionOrNull()?.message,
                )
            }
        }
    }

    fun testServers() {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            repository.testServers()
            _uiState.update { it.copy(isBusy = false) }
        }
    }

    fun selectNode(node: VpnNode) {
        repository.selectNode(node)
    }

    fun setMode(mode: NetworkMode) {
        repository.setMode(mode)
    }

    fun setAutoSwitchEnabled(enabled: Boolean) {
        repository.setAutoSwitchEnabled(enabled)
    }

    fun consumeMessage() {
        repository.consumeMessage()
    }
}
