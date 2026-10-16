package me.ethanxu.jevnoisegate.core.common.log

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class BatchedLogWriterTest {
    @Test fun `idle writer has no timer publications and first later line receives a full flush window`() = runTest {
        var publications = 0
        val disk = mutableListOf<String>()
        val writer = BatchedLogWriter(backgroundScope, write = { disk += it }, clearFiles = {},
            publish = { publications++ }, nowMs = { testScheduler.currentTime })
        runCurrent()
        assertEquals(1, publications)
        advanceTimeBy(3_600_000)
        assertEquals(1, publications)
        writer.append("accepted-before-logging-off")
        runCurrent()
        advanceTimeBy(1_999)
        assertTrue(disk.isEmpty())
        advanceTimeBy(2)
        assertEquals(listOf("accepted-before-logging-off"), disk)
        val afterFlush = publications
        advanceTimeBy(3_600_000)
        assertEquals(afterFlush, publications)
    }
    @Test fun `burst producers never write and one consumer batches at threshold`() = runTest {
        val batches = mutableListOf<List<String>>()
        val writer = BatchedLogWriter(backgroundScope, write = { batches += it }, clearFiles = {},
            nowMs = { testScheduler.currentTime })
        repeat(128) { writer.append("line-$it") }
        assertTrue(batches.isEmpty())
        runCurrent()
        advanceTimeBy(101)
        assertEquals(listOf((0..127).map { "line-$it" }), batches)
    }

    @Test fun `small batch is flushed by timer and clearing keeps only later lines`() = runTest {
        val disk = mutableListOf<String>()
        val writer = BatchedLogWriter(backgroundScope, write = { disk += it }, clearFiles = { disk.clear() },
            nowMs = { testScheduler.currentTime })
        writer.append("old")
        runCurrent()
        advanceTimeBy(2_001)
        assertEquals(listOf("old"), disk)
        writer.append("discarded")
        writer.clear()
        writer.append("new")
        runCurrent()
        advanceTimeBy(101)
        assertEquals(listOf("new"), disk)
    }

    @Test fun `blocked consumer queue retains a bounded tail`() = runTest {
        val batches = mutableListOf<List<String>>()
        val writer = BatchedLogWriter(backgroundScope, write = { batches += it }, clearFiles = {},
            nowMs = { testScheduler.currentTime }, maxPending = 64)
        repeat(200) { writer.append("$it") }
        runCurrent()
        advanceTimeBy(101)
        assertEquals((136..199).map(Int::toString), batches.single())
    }
}
