package dev.claudechat

import com.google.gson.JsonObject

/** Recherche floue de fichiers pour les mentions `@` (FR-13). */
object Fuzzy {
    /** Score d'un chemin pour une requête ; null si la requête n'est pas une sous-séquence. Plus haut = meilleur. */
    fun score(query: String, path: String): Int? {
        if (query.isEmpty()) return 0
        val q = query.lowercase()
        val p = path.lowercase()
        val name = p.substringAfterLast('/')
        var score = 0
        if (name == q) score += 1000
        else if (name.startsWith(q)) score += 500
        else if (name.contains(q)) score += 300
        else if (p.contains(q)) score += 150
        var qi = 0
        var last = -2
        for ((i, c) in p.withIndex()) {
            if (qi < q.length && c == q[qi]) {
                score += if (i == last + 1) 5 else 1
                if (i == 0 || p[i - 1] in "/_-. ") score += 3
                last = i; qi++
            }
        }
        if (qi < q.length) return null
        return score - path.length / 10
    }

    fun search(query: String, paths: Collection<String>, limit: Int = 30): List<String> =
        paths.mapNotNull { p -> score(query, p)?.let { p to it } }
            .sortedByDescending { it.second }.take(limit).map { it.first }
}

/** Mentions `@chemin` dans un message. */
object Mentions {
    private val token = Regex("""(?<![\w@])@([\w./\\-]+)""")

    /** Chemins mentionnés, dans l'ordre et sans doublon. */
    fun find(text: String): List<String> = token.findAll(text).map { it.groupValues[1] }.distinct().toList()

    /**
     * Chemin absolu normalisé d'une mention. Avec [projectOnly] (message venu du téléphone), une mention qui sort du
     * projet (chemin absolu ailleurs, `..`) est refusée : null. Sinon tout chemin est accepté (saisie dans l'IDE).
     */
    fun resolve(rel: String, cwd: String, projectOnly: Boolean): String? {
        val base = java.nio.file.Path.of(cwd).toAbsolutePath().normalize()
        val p = runCatching { base.resolve(rel).normalize() }.getOrNull() ?: return null
        return p.toString().takeIf { !projectOnly || p.startsWith(base) }
    }

    /** Jeton `@xxx` en cours de frappe juste avant le curseur : (début, requête) ou null. */
    fun activeToken(text: String, caret: Int): Pair<Int, String>? {
        val before = text.substring(0, caret.coerceIn(0, text.length))
        val m = Regex("""(?<![\w@])@([\w./\\-]*)$""").find(before) ?: return null
        return m.range.first to m.groupValues[1]
    }
}

/** Commande `/xxx` en cours de frappe, uniquement en tout début de saisie (seul endroit où elle est interprétée). */
object SlashToken {
    /** Requête (texte après le `/`) ou null si le curseur n'est pas dans une commande initiale. */
    fun activeQuery(text: String, caret: Int): String? {
        val before = text.substring(0, caret.coerceIn(0, text.length))
        return Regex("""^/([\w:.-]*)$""").find(before)?.groupValues?.get(1)
    }

    fun filter(query: String, commands: Collection<String>): List<String> =
        commands.filter { it.startsWith(query, ignoreCase = true) }.sorted()
}

/** Surlignage d'une recherche dans du HTML, sans toucher aux balises (FR-07). */
object Highlight {
    fun apply(html: String, query: String, color: String = "#ffe08a"): String {
        if (query.isBlank()) return html
        val re = Regex(Regex.escape(query), RegexOption.IGNORE_CASE)
        return Regex("""(?<=^|>)[^<]+""").replace(html) { seg ->
            re.replace(seg.value) { "<span style=\"background-color:$color;color:#000000\">${it.value}</span>" }
        }
    }

    fun count(entries: List<String>, query: String): Int =
        if (query.isBlank()) 0 else entries.sumOf { Regex(Regex.escape(query), RegexOption.IGNORE_CASE).findAll(it).count() }
}

/** Résumé lisible d'un appel d'outil pour les cartes et l'approbation (FR-04). */
fun toolSummary(name: String, i: JsonObject): String {
    fun s(k: String) = i[k]?.takeIf { it.isJsonPrimitive }?.asString
    return when (name) {
        "Bash" -> s("command") ?: ""
        "Read", "Write", "Edit", "MultiEdit", "NotebookEdit" -> s("file_path") ?: s("notebook_path") ?: ""
        "Grep", "Glob" -> listOfNotNull(s("pattern"), s("path")).joinToString(" in ")
        "WebFetch" -> s("url") ?: ""
        "WebSearch" -> s("query") ?: ""
        "Task", "Agent" -> s("description") ?: ""
        else -> i.toString()
    }
}

/** Description détaillée d'une action à approuver (IDE et téléphone). */
fun describeTool(name: String, i: JsonObject): String {
    fun s(k: String) = i[k]?.takeIf { it.isJsonPrimitive }?.asString
    return when (name) {
        "Bash" -> s("command") ?: ""
        "Write" -> "Fichier : ${s("file_path")}\n" + (s("content") ?: "").take(1500)
        "Edit" -> "Fichier : ${s("file_path")}\n- ${(s("old_string") ?: "").take(600)}\n+ ${(s("new_string") ?: "").take(600)}"
        "MultiEdit" -> "Fichier : ${s("file_path")}\n${i["edits"]?.takeIf { it.isJsonArray }?.asJsonArray?.size() ?: 0} modification(s)"
        else -> toolSummary(name, i)
    }
}

/**
 * Catalogue des modèles (FR-62). Les modèles viennent de la CLI (réponse `initialize`, voir [update]) : nouveaux modèles,
 * noms et niveaux d'effort pris en charge sont ainsi toujours à jour. La liste figée ne sert que de secours
 * tant que la CLI n'a pas répondu (ou si elle est injoignable).
 */
object ModelCatalog {
    private val fallback = Constants.fallbackModels.map { it.first to it.third }
    private val fallbackAliases = Constants.fallbackModels.associate { it.second to it.first }

    @Volatile private var fromCli: List<CliModel> = emptyList()

    /** Retient les modèles annoncés par la CLI ; une liste vide (CLI muette) ne remplace pas la précédente. */
    fun update(models: List<CliModel>) { if (models.isNotEmpty()) fromCli = models }

    internal fun reset() { fromCli = emptyList() }

    /** Modèles annoncés par la CLI ; vide tant qu'elle n'a pas répondu. */
    fun all(): List<CliModel> = fromCli

    /** (identifiant, nom) : ceux de la CLI, sinon la liste de secours. */
    val known: List<Pair<String, String>> get() = fromCli.map { it.id to it.name }.ifEmpty { fallback }

    fun description(id: String): String? = fromCli.firstOrNull { it.id == id }?.description?.takeIf { it.isNotBlank() }

    /**
     * Niveaux d'effort proposés pour [id] : « auto » puis ceux que le modèle accepte ; seulement « auto » s'il n'en
     * accepte aucun. Modèle inconnu de la CLI (personnalisé, CLI muette) : [fallbackLevels], sans restriction.
     */
    fun effortChoices(id: String, fallbackLevels: List<String>): List<String> {
        val m = fromCli.firstOrNull { it.id == id } ?: return fallbackLevels
        return listOf("auto") + m.efforts
    }

    /** Alias (« sonnet ») -> identifiant complet ; inchangé sinon. */
    fun resolve(m: String): String {
        val key = m.trim()
        return fromCli.firstOrNull { it.alias.equals(key, ignoreCase = true) }?.id ?: fallbackAliases[key.lowercase()] ?: key
    }

    /** « Sonnet 5.5 » pour un identifiant connu, sinon déduit de claude-<famille>-<x>-<y>[-date], sinon l'identifiant brut. */
    fun label(id: String): String {
        known.firstOrNull { it.first == id }?.let { return it.second }
        val m = Regex("""^claude-([a-z]+)-(\d+)(?:-(\d{1,2}))?(?:-\d{8})?$""").find(id) ?: return id
        val v = m.groupValues[2] + m.groupValues[3].takeIf { it.isNotEmpty() }?.let { ".$it" }.orEmpty()
        return m.groupValues[1].replaceFirstChar { it.uppercase() } + " " + v
    }

    fun display(id: String): String = label(id).let { if (it == id) id else "$it  ($id)" }
}

/** Texte et état d'une jauge de quota (FR-63). */
object Quota {
    fun percent(w: QuotaWindow?, nowSec: Long): Int? =
        w?.let { if (it.resetsAt in 1 until nowSec) 0 else (it.utilization * 100).toInt().coerceIn(0, 100) }

    /** Part restante (100 − utilisée) ; null si la fenêtre n'est pas connue. */
    fun remainingPercent(w: QuotaWindow?, nowSec: Long): Int? = percent(w, nowSec)?.let { 100 - it }

    /** Durée jusqu'à la réinitialisation, ex. « 2 h 10 » ou « 3 j 4 h ». */
    fun remaining(resetsAt: Long, nowSec: Long): String {
        val s = (resetsAt - nowSec).coerceAtLeast(0)
        val d = s / 86400; val h = s % 86400 / 3600; val m = s % 3600 / 60
        return when { d > 0 -> "$d j $h h"; h > 0 -> "$h h %02d".format(m); else -> "$m min" }
    }
}

/** Détecte un quota rempli et dit quand reprendre (reprise automatique). */
object QuotaGuard {
    private val looksLikeQuota = Regex("(?i)(usage limit|limit reached|rate.?limit|quota|limit will reset|resets? at)")
    /** Délai par défaut après le début de la session suivante. */
    const val DEFAULT_DELAY_SEC = 120L
    private const val FALLBACK_SEC = 1800L

    /** Instant (secondes epoch) où relancer ; null si l'échec n'est pas lié à un quota. */
    fun blockedUntil(l: RateLimits?, message: String?, nowSec: Long, delaySec: Long = DEFAULT_DELAY_SEC): Long? {
        val rejected = l?.status == "rejected"
        if (!rejected && !(message != null && looksLikeQuota.containsMatchIn(message))) return null
        val candidates = buildList {
            if (rejected) {
                l?.resetsAt?.let { add(it) }
                when (l?.limitType) {
                    "five_hour" -> l.fiveHour?.resetsAt?.let { add(it) }
                    "seven_day" -> l.sevenDay?.resetsAt?.let { add(it) }
                }
            }
            if (isEmpty()) listOfNotNull(l?.fiveHour, l?.sevenDay).filter { it.utilization >= 0.99 }.forEach { add(it.resetsAt) }
            if (isEmpty()) l?.fiveHour?.resetsAt?.let { add(it) }
        }.filter { it > nowSec }
        val target = candidates.maxOrNull() ?: (nowSec + FALLBACK_SEC - delaySec)
        return target + delaySec
    }
}

/** Découpe un long texte en morceaux d'au plus `max` caractères, de préférence aux sauts de ligne. */
fun chunkText(text: String, max: Int = 3800): List<String> {
    val out = mutableListOf<String>()
    var rest = text.trim()
    while (rest.length > max) {
        var cut = rest.lastIndexOf('\n', max).takeIf { it > max / 2 } ?: rest.lastIndexOf(' ', max).takeIf { it > max / 2 } ?: max
        out += rest.substring(0, cut).trimEnd()
        rest = rest.substring(cut).trimStart()
    }
    if (rest.isNotEmpty()) out += rest
    return out
}

/** Évite plusieurs mesures de quota simultanées (un onglet = un panneau). */
object QuotaProbeGate {
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)
    fun tryStart() = running.compareAndSet(false, true)
    fun finish() = running.set(false)
}

/** Aperçu de la file d'attente : dernier message empilé (une ligne) et nombre des autres. */
fun queuePreview(queue: List<String>, max: Int = 80): String {
    val last = queue.lastOrNull()?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim() ?: return ""
    val short = if (last.length > max) last.take(max - 1) + "…" else last
    return if (queue.size > 1) "$short  (+${queue.size - 1} autre${if (queue.size > 2) "s" else ""})" else short
}

/** Déplace l'élément `from` à la position `to` (bornée) ; renvoie la nouvelle position. */
fun <T> MutableList<T>.moveItem(from: Int, to: Int): Int {
    if (from !in indices) return from
    val dest = to.coerceIn(0, lastIndex)
    add(dest, removeAt(from))
    return dest
}

private fun htmlEsc(t: String) = t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

/** Nom d'orateur lisible : en gras et en couleur, avec une précision discrète à côté. */
fun speakerLabel(name: String, colorHex: String, subtitle: String? = null): String =
    "<font color='$colorHex'><b>${htmlEsc(name)}</b></font>" +
        (subtitle?.takeIf { it.isNotBlank() }?.let { " <font color='gray' size='-1'>${htmlEsc(it)}</font>" } ?: "")

/** En-tête des réponses de Claude : barre verticale colorée puis nom (et modèle). */
fun assistantHeader(colorHex: String, subtitle: String?, gap: Boolean): String =
    (if (gap) "<table cellpadding='0' cellspacing='0'><tr><td height='10'></td></tr></table>" else "") +
        "<table cellpadding='0' cellspacing='0'><tr><td width='4' bgcolor='$colorHex'>&nbsp;</td><td>&nbsp;&nbsp;" +
        speakerLabel("Claude", colorHex, subtitle) + "</td></tr></table>"

/** Durée lisible : « 42 s », « 1 min 23 s », « 2 min », « 1 h 05 min », « 2 h ». */
fun formatDuration(ms: Long): String {
    val total = (ms.coerceAtLeast(0) + 500) / 1000     // arrondi à la seconde la plus proche
    if (total < 1) return "moins d'une seconde"
    val h = total / 3600
    val m = total % 3600 / 60
    val sec = total % 60
    return when {
        h > 0 -> if (m == 0L) "$h h" else "$h h %02d min".format(m)
        m > 0 -> if (sec == 0L) "$m min" else "$m min $sec s"
        else -> "$sec s"
    }
}

/**
 * Avertissement avant une action qui interrompt la réponse en cours (fermer l'onglet, nouvelle conversation…) ;
 * null si rien n'est en cours, donc aucune confirmation à demander.
 */
fun busyWarning(streaming: Boolean, queued: Int, consequence: String): String? {
    if (!streaming) return null
    val lost = when (queued) { 0 -> ""; 1 -> "\n\n1 message en attente sera perdu."; else -> "\n\n$queued messages en attente seront perdus." }
    return "Claude est en train de répondre. $consequence$lost"
}
