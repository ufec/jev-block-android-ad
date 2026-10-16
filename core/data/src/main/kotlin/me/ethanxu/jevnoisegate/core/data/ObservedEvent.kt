package me.ethanxu.jevnoisegate.core.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/**
 * 采集到的一条消息事件，连同它的判断结果。
 *
 * 采集与判断保存在同一行。列表只查询 EventSummary，用户展开时再按 ID 读取正文；
 * bucket_history 由触发器维护计数，模型准备无需重新扫描该分桶的历史。
 *
 * 同时冗余存了 [bucketKey]、[fingerprint] 与 [skeleton]：前两者是流水线主键，
 * 第三个让诊断页能直接展示"为什么这两条被判为同一模板"。
 */
@Entity(
    tableName = "observed_event",
    indices = [
        Index("bucketKey"),
        Index("fingerprint"),
        Index("timestampMs"),
        Index(value = ["source", "decidedAtMs"]),
        Index(value = ["source", "capturedAtMs"]),
        Index(value = ["action", "timestampMs", "id", "uploaded"]),
        Index(value = ["packageName", "channelId"]),
        Index(value = ["decidedBy", "timestampMs", "latencyMs"]),
    ],
)
data class MessageEventEntity(
    @PrimaryKey val id: String,
    /** [me.ethanxu.jevnoisegate.core.model.EventSource] 的名字。 */
    val source: String,
    val timestampMs: Long,
    val packageName: String,
    val channelId: String?,
    val senderKey: String?,
    /** App 显示名，由采集端解析；拿不到时为 null。 */
    val appLabel: String?,
    /** 通知渠道显示名，由采集端解析。 */
    val channelLabel: String?,
    val title: String?,
    val body: String,
    /** 分桶键：通知为 `包名|渠道`，短信为发件号码。 */
    val bucketKey: String,
    /** 归一化骨架的 hash，缓存主键。 */
    val fingerprint: String,
    /** 归一化骨架原文，仅供诊断展示。 */
    val skeleton: String,

    // -----------------------------------------------------------------------
    // 判断结果。采集后立即由流水线回填，因此以下字段在判断完成前为 null。
    // -----------------------------------------------------------------------

    /** [me.ethanxu.jevnoisegate.core.model.Action] 的名字。 */
    val action: String? = null,
    /** [me.ethanxu.jevnoisegate.core.model.DecidedBy] 的名字。 */
    val decidedBy: String? = null,
    val category: String? = null,
    val confidence: Float? = null,
    /** 仅降级放行时非空，说明本地模型故障。 */
    val failureReason: String? = null,
    /** 模型阶段耗时（毫秒），包含并发排队与重试；不包含采集、数据库或撤销。 */
    val latencyMs: Long? = null,
    /**
     * 是否运行过模型。保留旧 uploaded 列名以兼容已有数据库。
     * 这不能证明模型给出了结论，降级失败同样为 true。
     */
    @ColumnInfo(name = "uploaded") val backendAttempted: Boolean? = null,
    @ColumnInfo(defaultValue = "0") val decidedAtMs: Long = 0L,
    @ColumnInfo(defaultValue = "0") val capturedAtMs: Long = timestampMs,
    val ingressElapsedMs: Long? = null,
    val bootCount: Int? = null,
)

/** User corrections survive event cleanup and never send the message to a model. */
@Entity(tableName = "user_allow")
data class UserAllowEntity(@PrimaryKey val fingerprint: String, val createdAtMs: Long)

/** Updated atomically by SQLite triggers for every event mutation. */
@Entity(tableName = "bucket_history")
data class BucketHistoryEntity(
    @PrimaryKey val bucketKey: String,
    val totalSeen: Int,
    val suppressedCount: Int,
)

/** The event list never loads title/body until the user reveals that row. */
data class EventSummary(
    val id: String, val source: String, val timestampMs: Long, val packageName: String,
    val channelLabel: String?, val senderKey: String?, val appLabel: String?,
    val fingerprint: String, val skeleton: String, val action: String?, val decidedBy: String?,
    val category: String?, val confidence: Float?, val failureReason: String?, val latencyMs: Long?,
    val hasContent: Boolean,
)
data class EventContent(val title: String?, val body: String)

private const val SUMMARY_COLUMNS = "id, source, timestampMs, packageName, channelLabel, senderKey, appLabel, " +
    "fingerprint, skeleton, action, decidedBy, category, confidence, failureReason, latencyMs, " +
    "(IFNULL(title, '') != '' OR body != '') AS hasContent"

/** 按渠道聚合的计数投影，用于回答"到底哪些 App 的哪些渠道在发广告"。 */
data class ChannelCount(
    @ColumnInfo(name = "packageName") val packageName: String,
    @ColumnInfo(name = "channelId") val channelId: String?,
    @ColumnInfo(name = "eventCount") val eventCount: Int,
)

/** 某渠道的历史统计，作为 state 的一部分喂给模型。 */
data class ChannelStats(
    @ColumnInfo(name = "totalSeen") val totalSeen: Int,
    @ColumnInfo(name = "suppressedCount") val suppressedCount: Int,
)

/** 动作分布投影。 */
data class ActionCount(
    @ColumnInfo(name = "action") val action: String,
    @ColumnInfo(name = "eventCount") val eventCount: Int,
)

data class EventOverview(
    val total: Int = 0,
    val allowed: Int = 0,
    val suppressed: Int = 0,
    val quarantined: Int = 0,
    val attempted: Int = 0,
)

@Dao
interface ObservedEventDao {
    @Query("SELECT $SUMMARY_COLUMNS FROM observed_event ORDER BY timestampMs DESC LIMIT :limit")
    fun observeRecentSummaries(limit: Int): Flow<List<EventSummary>>

    @Query("SELECT $SUMMARY_COLUMNS FROM observed_event WHERE action = 'QUARANTINE' ORDER BY timestampMs DESC, id DESC LIMIT :limit")
    fun observeQuarantineSummaries(limit: Int): Flow<List<EventSummary>>

    @Query("SELECT $SUMMARY_COLUMNS FROM observed_event WHERE action = 'QUARANTINE' AND (timestampMs, id) < (:beforeTimestamp, :beforeId) ORDER BY timestampMs DESC, id DESC LIMIT :limit")
    fun observeQuarantineSummariesBefore(beforeTimestamp: Long, beforeId: String, limit: Int): Flow<List<EventSummary>>

    @Query("SELECT title, body FROM observed_event WHERE id = :id")
    fun observeContent(id: String): Flow<EventContent?>
    @Query("SELECT EXISTS(SELECT 1 FROM user_allow WHERE fingerprint = :fingerprint)")
    suspend fun isUserAllowed(fingerprint: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putUserAllow(value: UserAllowEntity)

    @Query("SELECT * FROM observed_event WHERE id = :id")
    suspend fun find(id: String): MessageEventEntity?

    @Query("UPDATE observed_event SET action = 'ALLOW', decidedBy = 'USER', failureReason = NULL WHERE id = :id AND action = 'QUARANTINE'")
    suspend fun markRestored(id: String): Int

    @Transaction
    suspend fun restore(id: String, nowMs: Long): MessageEventEntity? {
        val event = find(id)?.takeIf { it.action == "QUARANTINE" } ?: return null
        putUserAllow(UserAllowEntity(correctionKey(event.bucketKey, event.title, event.body), nowMs))
        markRestored(id)
        return event
    }

    @Query("SELECT * FROM observed_event WHERE action = 'QUARANTINE' ORDER BY timestampMs DESC, id DESC LIMIT :limit")
    fun observeQuarantined(limit: Int): Flow<List<MessageEventEntity>>
    @Query("SELECT * FROM observed_event WHERE action = 'QUARANTINE' AND (timestampMs, id) < (:beforeTimestamp, :beforeId) ORDER BY timestampMs DESC, id DESC LIMIT :limit")
    fun observeQuarantineBefore(beforeTimestamp: Long, beforeId: String, limit: Int): Flow<List<MessageEventEntity>>
    @Query("SELECT COUNT(*) FROM observed_event WHERE action = 'QUARANTINE'")
    fun observeQuarantineCount(): Flow<Int>

    @Query("SELECT * FROM observed_event WHERE source = 'SMS' AND action IN ('SILENT_SUPPRESS', 'QUARANTINE') AND decidedAtMs >= :sinceMs ORDER BY decidedAtMs DESC LIMIT 128")
    suspend fun recentSuppressedSms(sinceMs: Long): List<MessageEventEntity>

    @Query("SELECT * FROM observed_event WHERE source = 'SMS' AND body = :body AND capturedAtMs >= :sinceMs ORDER BY capturedAtMs DESC LIMIT 128")
    fun observeSmsOwners(body: String, sinceMs: Long): Flow<List<MessageEventEntity>>

    @Query("DELETE FROM observed_event WHERE timestampMs < :cutoffMs OR id NOT IN (SELECT id FROM observed_event ORDER BY timestampMs DESC LIMIT 10000)")
    suspend fun prune(cutoffMs: Long)

    @Query("DELETE FROM observed_event WHERE timestampMs < :cutoffMs")
    suspend fun pruneExpired(cutoffMs: Long)

    @Query("SELECT COUNT(*) FROM observed_event")
    suspend fun eventCount(): Int

    @Query("DELETE FROM observed_event WHERE id IN (SELECT id FROM observed_event ORDER BY timestampMs ASC LIMIT :excess)")
    suspend fun trimOldest(excess: Int)

    /** 冲突时忽略：同一 id 重复上报（通知更新、广播重放）不应产生重复行。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRaw(entity: MessageEventEntity): Long

    /** A fast count and indexed incremental eviction keep the hard cap without a full prune scan. */
    @Transaction
    suspend fun insert(entity: MessageEventEntity): Long {
        val inserted = insertRaw(entity)
        if (inserted != -1L) {
            val excess = eventCount() - 10_000
            if (excess > 0) trimOldest(excess)
        }
        return inserted
    }

    @Query("SELECT * FROM observed_event ORDER BY timestampMs DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<MessageEventEntity>>

    @Query("SELECT COUNT(*) FROM observed_event")
    fun observeCount(): Flow<Int>

    @Query("""
        SELECT COUNT(*) AS total,
            IFNULL(SUM(CASE WHEN action = 'ALLOW' THEN 1 ELSE 0 END), 0) AS allowed,
            IFNULL(SUM(CASE WHEN action = 'SILENT_SUPPRESS' THEN 1 ELSE 0 END), 0) AS suppressed,
            IFNULL(SUM(CASE WHEN action = 'QUARANTINE' THEN 1 ELSE 0 END), 0) AS quarantined,
            IFNULL(SUM(CASE WHEN uploaded = 1 THEN 1 ELSE 0 END), 0) AS attempted
        FROM observed_event
    """)
    fun observeOverview(): Flow<EventOverview>

    @Query(
        """
        SELECT packageName, channelId, COUNT(*) AS eventCount
        FROM observed_event
        GROUP BY packageName, channelId
        ORDER BY eventCount DESC
        LIMIT :limit
        """,
    )
    fun observeTopChannels(limit: Int): Flow<List<ChannelCount>>

    // -----------------------------------------------------------------------
    // 判断结果
    // -----------------------------------------------------------------------

    /** 判断完成后回填结果。 */
    @Query(
        """
        UPDATE observed_event
        SET action = :action,
            decidedBy = :decidedBy,
            category = :category,
            confidence = :confidence,
            failureReason = :failureReason,
            latencyMs = :latencyMs,
            uploaded = :backendAttempted,
            decidedAtMs = :decidedAtMs
        WHERE id = :id AND (decidedBy IS NULL OR decidedBy != 'USER')
        """,
    )
    suspend fun recordDecision(
        id: String,
        action: String,
        decidedBy: String,
        category: String?,
        confidence: Float?,
        failureReason: String?,
        latencyMs: Long,
        backendAttempted: Boolean,
        decidedAtMs: Long = System.currentTimeMillis(),
    ): Int

    /**
     * 该渠道的历史统计：见过多少条、其中多少条被判为噪音。
     *
     * [excludeId] 排除当前这条 —— 否则第一条事件就会看到"见过 1 条"，
     * 而正确的语义是"在它之前见过多少条"。这个数字会进入喂给模型的 state，
     * 起点错了会系统性偏置判断。
     */
    @Query(
        """
        SELECT IFNULL((SELECT totalSeen FROM bucket_history WHERE bucketKey = :bucketKey), 0)
            - EXISTS(SELECT 1 FROM observed_event WHERE id = :excludeId AND bucketKey = :bucketKey) AS totalSeen,
            IFNULL((SELECT suppressedCount FROM bucket_history WHERE bucketKey = :bucketKey), 0)
            - EXISTS(SELECT 1 FROM observed_event WHERE id = :excludeId AND bucketKey = :bucketKey
                AND action IN ('SILENT_SUPPRESS', 'QUARANTINE')) AS suppressedCount
        """,
    )
    suspend fun channelStats(bucketKey: String, excludeId: String): ChannelStats

    /**
     * 最近 1000 条 MODEL/FALLBACK 的模型阶段耗时，包含并发排队和重试。
     * 只取真正运行过模型的记录：验证码、号码黑名单与用户纠错不调模型，延迟恒为 0，
     * 混进来会让 P50/P95 显著偏低 —— 看起来比实际快，比没有指标更误导。
     * FALLBACK 保留：初始化或推理耗时真实，那正是用户会感知到的卡顿。
     */
    @Query(
        """
        SELECT latencyMs FROM (
            SELECT latencyMs, timestampMs FROM observed_event
            WHERE latencyMs IS NOT NULL AND decidedBy = 'MODEL'
            UNION ALL
            SELECT latencyMs, timestampMs FROM observed_event
            WHERE latencyMs IS NOT NULL AND decidedBy = 'FALLBACK'
            ORDER BY timestampMs DESC LIMIT 1000
        ) ORDER BY latencyMs
        """,
    )
    fun observeLatencies(): Flow<List<Long>>

    @Query(
        """
        SELECT action, COUNT(*) AS eventCount
        FROM observed_event
        WHERE action IS NOT NULL
        GROUP BY action
        """,
    )
    fun observeActionCounts(): Flow<List<ActionCount>>


    @Query("DELETE FROM observed_event")
    suspend fun clear()

    /** 删除某个应用的全部事件。事件页左/右滑某个分组时调用。 */
    @Query("DELETE FROM observed_event WHERE packageName = :packageName")
    suspend fun deleteByPackage(packageName: String)
}

@Database(
    entities = [MessageEventEntity::class, UserAllowEntity::class, BucketHistoryEntity::class],
    version = 5,
    exportSchema = true,
)
abstract class JevNoiseGateDatabase : RoomDatabase() {
    abstract fun observedEventDao(): ObservedEventDao

    companion object {
        const val NAME: String = "jevnoisegate.db"
    }
}
