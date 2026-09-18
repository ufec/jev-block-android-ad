package me.ethanxu.jevnoisegate.core.decision

import me.ethanxu.jevnoisegate.core.model.CategoryConfig

/**
 * 分类配置的来源。
 *
 * 抽成接口而不是直接传 `List<CategoryConfig>`，是因为分类配置**将来会来自用户设置**
 * （存 DataStore、可在界面上编辑）。把它固定成一个纯函数会让流水线无法感知配置变化，
 * 而配置恰恰是这个系统里唯一承载"什么算广告"这类知识的地方。
 */
fun interface CategoriesProvider {
    fun current(): List<CategoryConfig>
}
