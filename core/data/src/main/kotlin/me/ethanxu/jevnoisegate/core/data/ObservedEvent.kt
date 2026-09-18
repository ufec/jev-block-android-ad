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
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/**
 * 采集到的一条消息事件，连同它的判断结果。
 *
 * 采集与判断放在同一行而不是拆两张表：一条事件的判断结果必须与其内容一起读取才有意义
 * （诊断页要展示"这条是什么内容、被怎么判的、花了多久"）。事件量级不高，
 * 单表的读放大可忽略，换来的是查询与迁移的简单。
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
    /** 仅降级放行时非空，说明是超时、限流还是断网。 */
    val failureReason: String? = null,
    /** 模型往返耗时（毫秒）。这是"能否跑赢通知震动"的直接证据。 */
    val latencyMs: Long? = null,
    /**
     * 是否真的把内容发送到了外部 API。
     *
     * 独立成列是为了让"验证码永不上送"这个隐私承诺**可被统计验证**，
     * 而不是只写在注释里。
     */
    val uploaded: Boolean? = null,
)

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

@Dao
interface ObservedEventDao {

    /** 冲突时忽略：同一 id 重复上报（通知更新、广播重放）不应产生重复行。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: MessageEventEntity): Long

    @Query("SELECT * FROM observed_event ORDER BY timestampMs DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<MessageEventEntity>>

    @Query("SELECT COUNT(*) FROM observed_event")
    fun observeCount(): Flow<Int>

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
            uploaded = :uploaded
        WHERE id = :id
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
        uploaded: Boolean,
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
        SELECT COUNT(*) AS totalSeen,
               IFNULL(SUM(CASE WHEN action IN ('SILENT_SUPPRESS', 'QUARANTINE') THEN 1 ELSE 0 END), 0) AS suppressedCount
        FROM observed_event
        WHERE bucketKey = :bucketKey AND id != :excludeId
        """,
    )
    suspend fun channelStats(bucketKey: String, excludeId: String): ChannelStats

    /**
     * 模型往返耗时，供诊断页算 P50/P95。
     *
     * **只取真正发起过网络请求的记录**（MODEL 与 FALLBACK）：
     * OTP 闸门、号码黑名单、指纹缓存这些路径根本不调 API，延迟恒为 0。
     * 把它们算进来会让 P50/P95 显著偏低 —— 指标名字写着"模型往返延迟"，
     * 却混进了一堆 0，结果是"看起来比实际快"，比没有指标更误导。
     *
     * FALLBACK 要保留：它虽然是失败，但确实发起了请求且耗时真实（含重试），
     * 而那恰恰是用户会感知到的卡顿。
     */
    @Query(
        """
        SELECT latencyMs FROM observed_event
        WHERE latencyMs IS NOT NULL AND decidedBy IN ('MODEL', 'FALLBACK')
        ORDER BY latencyMs
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

    /** 实际外发到 API 的事件数。用于核验隐私承诺是否兑现。 */
    @Query("SELECT COUNT(*) FROM observed_event WHERE uploaded = 1")
    fun observeUploadedCount(): Flow<Int>

    @Query("DELETE FROM observed_event")
    suspend fun clear()

    /** 删除某个应用的全部事件。事件页左/右滑某个分组时调用。 */
    @Query("DELETE FROM observed_event WHERE packageName = :packageName")
    suspend fun deleteByPackage(packageName: String)
}

@Database(
    entities = [MessageEventEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class JevNoiseGateDatabase : RoomDatabase() {
    abstract fun observedEventDao(): ObservedEventDao

    companion object {
        const val NAME: String = "jevnoisegate.db"
    }
}
