package me.ethanxu.jevnoisegate.core.common

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process state shared by both collectors and the application. */
object ServiceState {
    private val connection = MutableStateFlow(false)
    val connected = connection.asStateFlow()
    private val activation = MutableStateFlow(false)
    val enabled = activation.asStateFlow()
    @Volatile var modelReady: Boolean = false
    val running: Boolean get() = enabled.value && connected.value && modelReady
    fun listenerConnected(value: Boolean) { connection.value = value }
    fun enable(value: Boolean) { activation.value = value }
}
