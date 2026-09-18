package me.ethanxu.jevnoisegate.core.dispatch

import javax.inject.Inject
import javax.inject.Singleton
import me.ethanxu.jevnoisegate.core.data.ObservedEventDao
import me.ethanxu.jevnoisegate.core.decision.CategoriesProvider
import me.ethanxu.jevnoisegate.core.decision.DecisionInput
import me.ethanxu.jevnoisegate.core.decision.EventLabels
import me.ethanxu.jevnoisegate.core.decision.SmsBlacklist
import me.ethanxu.jevnoisegate.core.decision.UserRules
import me.ethanxu.jevnoisegate.core.data.settings.SettingsRepository
import me.ethanxu.jevnoisegate.core.model.Action
import me.ethanxu.jevnoisegate.core.model.ChannelContext
import me.ethanxu.jevnoisegate.core.model.DecidedBy
import me.ethanxu.jevnoisegate.core.model.EventSource
import me.ethanxu.jevnoisegate.core.model.FinalDecision
import me.ethanxu.jevnoisegate.core.model.MessageEvent
import me.ethanxu.jevnoisegate.core.model.bucketKey
import me.ethanxu.jevnoisegate.core.pipeline.MessagePipeline

/**
 * 采集端与流水线之间的粘合层。
 *
 * 职责只有三件：读取该渠道的历史上下文、调用流水线、把结果回填到存储。
 *
 * 采集端（通知监听、短信接收）都只调用 [dispatch]，因此两边语义强制一致 ——
 * 不可能出现"通知走了判断但短信没有"这类分叉。
 */
@Singleton
class DecisionDispatcher @Inject constructor(
    private val pipeline: MessagePipeline,
    private val dao: ObservedEventDao,
    private val categoriesProvider: CategoriesProvider,
    private val settings: SettingsRepository,
) {

    /**
     * 判断一条已落库的事件，并把结果回填。
     *
     * 调用前事件必须已经插入数据库（本方法依赖它读取渠道历史）。
     *
     * @return 最终决策。调用方据此执行动作（如取消通知）。
     */
    suspend fun dispatch(event: MessageEvent, labels: EventLabels = EventLabels()): FinalDecision {
        // 号码黑名单是硬规则，放在最前面：命中即拦截，不查历史、不调模型。
        // 用户填黑名单的意图是"这个号码一定不要"，没有需要权衡的余地 ——
        // 让它依赖网络或缓存状态反而是错的。
        if (event.source == EventSource.SMS &&
            SmsBlacklist.matches(event.senderKey, settings.preferences.value.smsSenderBlacklist)
        ) {
            val blocked = FinalDecision(
                event = event,
                action = Action.SILENT_SUPPRESS,
                decidedBy = DecidedBy.BLACKLIST,
            )
            dao.recordDecision(
                id = event.id,
                action = blocked.action.name,
                decidedBy = blocked.decidedBy.name,
                category = null,
                confidence = null,
                failureReason = null,
                latencyMs = 0L,
                // 未产生网络请求，自然不算上送。
                uploaded = false,
            )
            return blocked
        }

        val stats = dao.channelStats(event.bucketKey, excludeId = event.id)

        val input = DecisionInput(
            event = event,
            context = ChannelContext(
                packageName = event.packageName,
                channelId = event.channelId,
                totalSeen = stats.totalSeen,
                suppressedCount = stats.suppressedCount,
            ),
            categories = categoriesProvider.current(),
            labels = labels,
            // 每次判断都从内存 StateFlow 读当前值，而不是缓存一份 ——
            // 用户改完规则应当立刻生效，不该要求重启。
            userRules = settings.preferences.value.let {
                UserRules(
                    preferAllow = it.preferAllowRules,
                    preferBlock = it.preferBlockRules,
                )
            },
        )

        val result = pipeline.process(input)

        dao.recordDecision(
            id = event.id,
            action = result.decision.action.name,
            decidedBy = result.decision.decidedBy.name,
            category = result.decision.category,
            confidence = result.decision.confidence,
            failureReason = result.decision.failureReason?.name,
            latencyMs = result.decision.latencyMillis,
            uploaded = result.uploaded,
        )

        return result.decision
    }
}
