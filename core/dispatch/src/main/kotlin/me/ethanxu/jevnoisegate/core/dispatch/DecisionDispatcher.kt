package me.ethanxu.jevnoisegate.core.dispatch

import javax.inject.Inject
import javax.inject.Singleton
import me.ethanxu.jevnoisegate.core.data.ObservedEventDao
import me.ethanxu.jevnoisegate.core.data.correctionKey
import me.ethanxu.jevnoisegate.core.data.settings.SettingsRepository
import me.ethanxu.jevnoisegate.core.decision.CategoriesProvider
import me.ethanxu.jevnoisegate.core.decision.DecisionInput
import me.ethanxu.jevnoisegate.core.decision.EventLabels
import me.ethanxu.jevnoisegate.core.decision.UserRules
import me.ethanxu.jevnoisegate.core.model.ChannelContext
import me.ethanxu.jevnoisegate.core.model.MessageEvent
import me.ethanxu.jevnoisegate.core.model.FinalDecision
import me.ethanxu.jevnoisegate.core.model.bucketKey
import me.ethanxu.jevnoisegate.core.pipeline.MessagePipeline
import me.ethanxu.jevnoisegate.core.pipeline.PipelineResult
import me.ethanxu.jevnoisegate.core.common.DecisionTrace

@Singleton
class DecisionDispatcher @Inject constructor(
    private val pipeline: MessagePipeline,
    private val dao: ObservedEventDao,
    private val categoriesProvider: CategoriesProvider,
    private val settings: SettingsRepository,
) {
    private suspend fun input(event: MessageEvent, labels: EventLabels, trace: DecisionTrace): DecisionInput {
        settings.awaitReady()
        val prefs = settings.preferences.value
        return DecisionInput(
            event = event,
            context = ChannelContext(event.packageName, event.channelId),
            categories = categoriesProvider.current(),
            labels = labels,
            userRules = UserRules(prefs.preferAllowRules, prefs.preferBlockRules),
            smsSenderBlacklist = prefs.smsSenderBlacklist,
            userAllowed = trace.database { dao.isUserAllowed(correctionKey(event.bucketKey, event.title, event.body)) },
        )
    }

    /** Safe inside a broadcast: only local rules and database operations. */
    suspend fun dispatchLocal(event: MessageEvent, labels: EventLabels = EventLabels(), trace: DecisionTrace = DecisionTrace()): FinalDecision? {
        val result = pipeline.localDecision(input(event, labels, trace)) ?: return null
        trace.prepared()
        record(result, trace)
        return result.decision
    }

    suspend fun dispatch(event: MessageEvent, labels: EventLabels = EventLabels(), trace: DecisionTrace = DecisionTrace()): FinalDecision {
        val base = input(event, labels, trace)
        pipeline.localDecision(base)?.let { trace.prepared(); record(it, trace); return it.decision }
        val stats = trace.database { dao.channelStats(event.bucketKey, excludeId = event.id) }
        trace.prepared()
        val result = pipeline.process(base.copy(context = base.context.copy(
            totalSeen = stats.totalSeen, suppressedCount = stats.suppressedCount,
        )))
        // A user may restore the message while a model request is in flight.
        trace.queueMs = result.queueMs
        trace.backendMs = result.backendMs
        val corrected = if (trace.database { dao.isUserAllowed(correctionKey(event.bucketKey, event.title, event.body)) }) {
            pipeline.localDecision(base.copy(userAllowed = true))!!
                .copy(backendAttempted = result.backendAttempted)
        } else result
        record(corrected, trace)
        return corrected.decision
    }

    private suspend fun record(result: PipelineResult, trace: DecisionTrace) {
        val decision = result.decision
        trace.database { dao.recordDecision(
            id = decision.event.id, action = decision.action.name, decidedBy = decision.decidedBy.name,
            category = decision.category, confidence = decision.confidence,
            failureReason = decision.failureReason?.name, latencyMs = decision.latencyMillis,
            backendAttempted = result.backendAttempted,
        ) }
    }
}
