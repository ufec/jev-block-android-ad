package me.ethanxu.jevnoisegate.app

import androidx.lifecycle.ViewModelStore
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.ExperimentalCoroutinesApi
import me.ethanxu.jevnoisegate.core.data.EventOverview
import me.ethanxu.jevnoisegate.core.data.EventSummary
import me.ethanxu.jevnoisegate.core.data.ObservedEventDao
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class EventSubscriptionsTest {
    @Test fun `selected list subscribes alone and two ordinary collectors share one DAO flow`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var recent = 0
        var quarantine = 0
        var content = 0
        val recentFlow = flow<List<EventSummary>> {
            recent++
            try { emit(emptyList()); awaitCancellation() } finally { recent-- }
        }
        val quarantineFlow = flow<List<EventSummary>> {
            quarantine++
            try { emit(emptyList()); awaitCancellation() } finally { quarantine-- }
        }
        val dao = Proxy.newProxyInstance(ObservedEventDao::class.java.classLoader,
            arrayOf(ObservedEventDao::class.java)) { _, method, _ -> when (method.name) {
                "observeRecentSummaries" -> recentFlow
                "observeQuarantineSummaries" -> quarantineFlow
                "observeOverview" -> flowOf(EventOverview())
                "observeTopChannels", "observeLatencies" -> flowOf(emptyList<Any>())
                "observeContent" -> { content++; flowOf(null) }
                else -> error("Unexpected query: ${method.name}")
            } } as ObservedEventDao
        val store = ViewModelStore()
        try {
            val application = RuntimeEnvironment.getApplication()
            val model = MainViewModel(dao, LocalLayaBackend(application), application,
                RecoveryNotifier(application))
            store.put("model", model)
            runCurrent()
            assertEquals(0, recent)
            val hidden = backgroundScope.launch { model.quarantinePage.collect {} }
            runCurrent()
            assertEquals(0, recent)
            assertEquals(1, quarantine)
            assertEquals(0, content)
            hidden.cancel(); runCurrent()
            assertEquals(0, quarantine)
            val first = backgroundScope.launch { model.eventGroups.collect {} }
            val second = backgroundScope.launch { model.eventGroups.collect {} }
            runCurrent()
            assertEquals(1, recent)
            first.cancel(); runCurrent()
            assertEquals(1, recent)
            second.cancel(); runCurrent()
            assertEquals(0, recent)
            assertEquals(0, content)
            model.observeContent("selected")
            assertEquals(1, content)
        } finally { store.clear(); Dispatchers.resetMain() }
    }
}
