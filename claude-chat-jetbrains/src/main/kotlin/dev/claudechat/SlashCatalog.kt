package dev.claudechat

/** Une commande slash proposée à la saisie, avec sa catégorie et sa description (en français quand elle est connue). */
data class SlashEntry(val name: String, val category: String, val description: String)

/** Regroupe et traduit les commandes slash : raccourcis du plugin + commandes renvoyées par la CLI. */
object SlashCatalog {
    const val PLUGIN = "Raccourcis du plugin"
    const val SESSION = "Session"
    const val CONFIG = "Configuration"
    const val CODE = "Code et revue"
    const val AGENTS = "Agents et automatisation"
    const val DIAG = "Diagnostic"
    const val SKILLS = "Skills et extensions"
    const val PLUGINS = "Plugins"

    private val data = SharedData.read("slash-commands.json").asJsonObject

    private fun strings(o: com.google.gson.JsonObject) = o.entrySet().associate { it.key to it.value.asString }

    /** Ordre d'affichage des groupes. */
    val ORDER: List<String> = data["categories"].asJsonArray.map { it.asString }

    /** Commandes internes de la CLI, sans intérêt pour l'utilisateur. */
    private val hidden: Set<String> = data["hidden"].asJsonArray.map { it.asString }.toSet()

    private val local: Map<String, String> = strings(data["local"].asJsonObject)

    /** Catégorie et description française des commandes connues de la CLI. */
    private val known: Map<String, Pair<String, String>> = data["known"].asJsonObject.entrySet().associate { (n, v) ->
        n to (v.asJsonObject["category"].asString to v.asJsonObject["description"].asString)
    }

    /** Liste complète : raccourcis du plugin d'abord, puis la CLI ; le plugin l'emporte en cas de doublon. */
    fun build(cli: List<CliCommand>): List<SlashEntry> {
        val out = LinkedHashMap<String, SlashEntry>()
        local.forEach { (n, d) -> out[n] = SlashEntry(n, PLUGIN, d) }
        for (c in cli) {
            if (c.name in out || c.name in hidden || c.name.startsWith("__")) continue
            val k = known[c.name]
            val cat = k?.first ?: if (':' in c.name) PLUGINS else SKILLS
            out[c.name] = SlashEntry(c.name, cat, k?.second ?: c.description)
        }
        return out.values.toList()
    }

    /** Entrées dont le nom commence par [query], regroupées par catégorie (ordre [ORDER]) puis triées par nom. */
    fun group(query: String, entries: List<SlashEntry>): List<Pair<String, List<SlashEntry>>> =
        entries.filter { it.name.startsWith(query, ignoreCase = true) }
            .groupBy { it.category }
            .map { (c, l) -> c to l.sortedBy { it.name } }
            .sortedBy { ORDER.indexOf(it.first) }
}
