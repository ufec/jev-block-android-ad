package me.ethanxu.jevnoisegate.core.decision

import me.ethanxu.jevnoisegate.core.model.Action
import me.ethanxu.jevnoisegate.core.model.CategoryConfig

/**
 * 出厂默认分类配置。
 *
 * 这是**种子配置**而非硬编码策略：用户可以在「设置 → 用户偏好规则 → 判定门槛」里
 * 改掉每个类别的动作与门槛，代码不做任何假设。它的作用有两个 ——
 * 让首次启动时答案空间非空（[QuestionFactory.validate] 要求至少一个分类），
 * 以及给没调过的用户一个合理起点。
 *
 * 用户的覆盖是**叠加式**的（见 [applyCategoryOverrides]）：没改过的项一直用这里的值，
 * 所以以后调整下面的数字，没动过的用户会跟着变，而不是被一份陈旧快照钉住。
 *
 * 三处刻意的阈值选择，理由都是"误杀代价远高于漏放"：
 *
 * - **验证码 / 0f**：阈值 0 意味着无论模型多不确定都放行。这是整个系统的安全底线 ——
 *   漏放一条广告是无感，错拦一条验证码会让用户被锁在银行外面。
 *   注意它与管道里的本地 OTP 闸门是**两道独立保险**：闸门负责隐私（根本不上送），
 *   这一条负责兜住模型自己的判断。
 * - **广告 / 0.9**：误拦广告的代价其实很低，但"用户发现通知被吃掉"的摩擦很真实，
 *   因此门槛设高，宁可放过。
 * - **诈骗 / 0.7**：门槛低于广告，因为漏掉诈骗的代价更高。
 * - **正常 / ALLOW**：作为 catch-all，是 [QuestionFactory.validate] 强制要求的那个放行类别。
 */
object DefaultCategories {

    fun all(): List<CategoryConfig> = listOf(
        CategoryConfig(
            name = "验证码",
            description = "包含账号验证码、校验码、动态口令，用户正在等待并必须立刻看到",
            keywords = listOf("验证码", "校验码", "动态码", "动态口令", "OTP", "verification code"),
            action = Action.ALLOW,
            minConfidence = 0f,
        ),
        CategoryConfig(
            name = "诈骗",
            description = "冒充机构索要账号密码验证码、诱导转账、或诱导点击可疑链接",
            keywords = listOf("转账", "账户异常", "冻结", "点击链接", "安全验证", "退款"),
            action = Action.QUARANTINE,
            minConfidence = 0.7f,
        ),
        CategoryConfig(
            name = "广告",
            description = "推销商品、服务、活动的营销信息，用户预期之外的打扰",
            keywords = listOf("优惠", "促销", "限时", "仅需", "领取", "红包", "折扣"),
            action = Action.QUARANTINE,
            minConfidence = 0.9f,
        ),
        CategoryConfig(
            name = "正常",
            description = "亲友沟通、业务通知、账单物流、系统提醒等用户预期内的信息",
            keywords = emptyList(),
            action = Action.ALLOW,
            minConfidence = 0f,
        ),
    )
}
