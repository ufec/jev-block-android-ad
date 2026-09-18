package me.ethanxu.jevnoisegate.sdk.typesafe

/** 账号可用模型列表。对应上游 `client.models`。 */
class ModelsResource internal constructor(
    private val fetch: suspend () -> List<ModelCard>,
) {
    /** 列出账号可用的模型。对应上游 `Models.list()`。 */
    suspend fun list(): List<ModelCard> = fetch()
}
