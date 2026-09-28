package me.ethanxu.jevnoisegate.app

import me.ethanxu.jevnoisegate.core.common.ServiceState
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class ServiceStateTest {
    @After fun reset() {
        ServiceState.enable(false)
        ServiceState.listenerConnected(false)
        ServiceState.modelReady = false
    }

    @Test fun modelAndConnectionAreBothRequired() {
        ServiceState.enable(true)
        ServiceState.listenerConnected(true)
        ServiceState.modelReady = false
        assertFalse(ServiceState.running)
        ServiceState.modelReady = true
        assertTrue(ServiceState.running)
        ServiceState.listenerConnected(false)
        assertFalse(ServiceState.running)
    }

    @Test fun readyModelAndConnectedListenerDoNotEnableWithoutUser() {
        ServiceState.modelReady = true
        ServiceState.listenerConnected(true)
        ServiceState.enable(false)
        assertFalse(ServiceState.running)
    }

    @Test fun stoppingDisablesProcessingImmediately() {
        ServiceState.modelReady = true
        ServiceState.listenerConnected(true)
        ServiceState.enable(true)
        assertTrue(ServiceState.running)
        ServiceState.enable(false)
        assertFalse(ServiceState.running)
    }
}
