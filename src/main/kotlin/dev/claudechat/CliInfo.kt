package dev.claudechat

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/** Modèle proposé par la CLI (réponse `initialize`). [efforts] vide : le modèle n'accepte aucun niveau d'effort. */
data class CliModel(val id: String, val alias: String, val name: String, val description: String, val efforts: List<String>) {
    /** Alias court (« sonnet ») par opposition à une version précise (« claude-opus-4-6 »). */
    val isAlias: Boolean get() = !alias.startsWith("claude-")
}

/** Ce que la CLI annonce au démarrage : commandes slash et modèles. */
data class CliInit(val commands: List<CliCommand>, val models: List<CliModel>)

private fun JsonElement?.str(): String? = this?.takeIf { it.isJsonPrimitive }?.asString

/** Charge utile d'une réponse de contrôle (`response.response`), ou null si le format est inattendu. */
fun controlPayload(j: JsonObject): JsonObject? =
    j["response"]?.takeIf { it.isJsonObject }?.asJsonObject?.get("response")?.takeIf { it.isJsonObject }?.asJsonObject

/**
 * Modèles d'une réponse `initialize`. L'entrée « default » est ignorée (elle double un autre modèle) et les doublons
 * sont retirés sur l'identifiant résolu, l'alias court passant avant la version précise.
 */
fun parseModels(j: JsonObject): List<CliModel> {
    val arr = controlPayload(j)?.get("models")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
    return arr.mapNotNull { e ->
        val o = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
        val alias = o["value"].str()?.takeIf { it.isNotBlank() && it != "default" } ?: return@mapNotNull null
        val id = o["resolvedModel"].str()?.takeIf { it.isNotBlank() } ?: alias
        val supports = o["supportsEffort"]?.takeIf { it.isJsonPrimitive }?.asBoolean == true
        val efforts = if (!supports) emptyList() else
            (o["supportedEffortLevels"] as? JsonArray)?.mapNotNull { it.str() }.orEmpty()
        CliModel(id, alias, o["displayName"].str() ?: id, o["description"].str().orEmpty(), efforts)
    }.distinctBy { it.id }
}
