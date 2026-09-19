package me.ethanxu.jevnoisegate.core.decision

import me.ethanxu.jevnoisegate.core.model.Action
import me.ethanxu.jevnoisegate.core.model.CategoryConfig

/**
 * 把用户在设置里做的覆盖合并到出厂分类上。
 *
 * 抽成纯函数而不是留在 DI 装配里，是因为**有两处需要同一个答案**：
 * 判断链要按覆盖后的门槛执行，设置页要按覆盖后的值摆滑块位置。
 * 两边各算各的，迟早会出现"界面显示 50%、实际按 90% 执行"这种最难查的分歧。
 *
 * @param thresholds 类别名 → 判定门槛。越界值夹到 `0f..1f`。
 * @param actions 类别名 → [Action] 的枚举名。名字非法时**退回出厂动作**而不是抛异常 ——
 *   存储里的脏数据不该让整条判断链失败。
 */
fun applyCategoryOverrides(
    defaults: List<CategoryConfig>,
    thresholds: Map<String, Float> = emptyMap(),
    actions: Map<String, String> = emptyMap(),
): List<CategoryConfig> = defaults.map { config ->
    config.copy(
        action = actions[config.name]
            ?.let { name -> Action.entries.firstOrNull { it.name == name } }
            ?: config.action,
        minConfidence = thresholds[config.name]?.coerceIn(0f, 1f) ?: config.minConfidence,
    )
}
