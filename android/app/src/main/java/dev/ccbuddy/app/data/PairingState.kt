package dev.ccbuddy.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Shared pairing UI state: the currently-displayed PIN, and any pending accept/deny prompt. */
class PairingState {
    private val _activePin = MutableStateFlow<ActivePin?>(null)
    val activePin: StateFlow<ActivePin?> = _activePin

    private val _pendingRequest = MutableStateFlow<PendingPairRequest?>(null)
    val pendingRequest: StateFlow<PendingPairRequest?> = _pendingRequest

    fun setPin(pin: ActivePin?) {
        _activePin.value = pin
    }

    fun setPendingRequest(request: PendingPairRequest?) {
        _pendingRequest.value = request
    }
}
