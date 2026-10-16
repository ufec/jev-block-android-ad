package me.ethanxu.jevnoisegate.feature.sms

import java.io.IOException
import java.lang.reflect.Proxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import me.ethanxu.jevnoisegate.core.data.MessageEventEntity
import me.ethanxu.jevnoisegate.core.data.ObservedEventDao
import me.ethanxu.jevnoisegate.core.data.toEntity
import me.ethanxu.jevnoisegate.core.model.Action
import me.ethanxu.jevnoisegate.core.model.DecidedBy
import me.ethanxu.jevnoisegate.core.model.FinalDecision
import org.junit.Assert.*
import org.junit.Test

class SmsIngestionTest {
    private val event = SmsEventFactory.eventsFrom(listOf(RawSms("1069", "完整短信正文", 123L))).single()
    private class Store {
        val rows = mutableMapOf<String, MessageEventEntity>()
        val dao = Proxy.newProxyInstance(ObservedEventDao::class.java.classLoader, arrayOf(ObservedEventDao::class.java)) { _, method, args ->
            when (method.name) {
                "insert" -> {
                    val row = args!![0] as MessageEventEntity
                    if (rows.containsKey(row.id)) -1L else { rows[row.id] = row; 1L }
                }
                "find" -> rows[args!![0] as String]
                else -> error("Unexpected DAO call: ${method.name}")
            }
        } as ObservedEventDao
    }

    @Test fun `replay resumes an inserted message after scheduling fails or is cancelled`() = runTest {
        listOf(IOException("scheduling failed"), CancellationException("broadcast timed out")).forEach { failure ->
            val store = Store()
            var attempts = 0
            try {
                ingestSms(event, store.dao, { null }, { attempts++; throw failure })
                fail("Expected scheduling failure")
            } catch (caught: Exception) { assertSame(failure, caught) }
            assertEquals(1, store.rows.size)
            val scheduled = mutableListOf<String>()
            ingestSms(event, store.dao, { null }, { attempts++; scheduled += it })
            assertEquals(2, attempts)
            assertEquals(listOf(event.id), scheduled)
            assertEquals(1, store.rows.size)
        }
    }

    @Test fun `replayed completed messages cannot be sent for a second decision`() = runTest {
        val store = Store()
        store.rows[event.id] = event.toEntity().copy(action = Action.ALLOW.name, decidedBy = DecidedBy.USER.name)
        ingestSms(event, store.dao, { error("Already completed") }, { error("Already completed") })
        assertEquals(DecidedBy.USER.name, store.rows[event.id]!!.decidedBy)
    }

    @Test fun `local allow and local suppress never schedule a model worker`() = runTest {
        var suppressed = 0
        listOf(Action.ALLOW, Action.SILENT_SUPPRESS).forEach { action ->
            ingestSms(event, Store().dao, { FinalDecision(it, action,
                if (action == Action.ALLOW) DecidedBy.OTP_GATE else DecidedBy.BLACKLIST) },
                { error("Local decisions must not schedule model work") }, { sender, body ->
                    assertEquals(event.senderKey, sender)
                    assertEquals(event.body, body)
                    suppressed++
                })
        }
        assertEquals(1, suppressed)
    }
}
