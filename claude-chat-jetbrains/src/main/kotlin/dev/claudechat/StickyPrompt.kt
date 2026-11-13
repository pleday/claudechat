package dev.claudechat

import java.text.Normalizer

/**
 * Choix du message de l'utilisateur épinglé en haut de la conversation : le dernier, sauf si c'est une simple demande
 * de reprise (« continue », « reprends là où tu t'étais arrêté »…) qui n'a aucun sens seule : on épingle alors le
 * message précédent, celui qui contient la tâche.
 */
object StickyPrompt {
    /** [prompt] : le message à afficher ; [resumedWith] : la demande de reprise qui le suit, le cas échéant. */
    data class Choice(val prompt: String, val resumedWith: String? = null)

    private val TRIGGERS = setOf(
        "reprend", "reprends", "reprendre", "reprenez", "continue", "continues", "continuer", "continuez", "poursuis", "poursuivre",
        "poursuivez", "recommence", "recommences", "recommencer", "recommencez", "relance", "relances", "relancer", "reessaie",
        "reessaye", "reessayer", "retente", "retenter", "resume", "retry", "proceed",
    )

    private val PHRASES = listOf(
        "vas y", "allez y", "on continue", "go on", "go ahead", "keep going", "carry on", "try again", "essaie encore", "c est reparti",
    )

    /** Mots sans contenu propre : une demande de reprise n'est faite que de ceux-là et d'un verbe de reprise. */
    private val FILLER = setOf(
        "oui", "ok", "okay", "stp", "svp", "s", "il", "te", "vous", "plait", "merci", "maintenant", "donc", "alors", "bon", "allez",
        "la", "ou", "tu", "t", "etais", "etait", "arrete", "arretes", "arrete", "ta", "ton", "tache", "travail", "on", "y", "vas", "va",
        "de", "du", "des", "le", "les", "toi", "claude", "encore", "une", "fois", "juste", "please", "pls", "now", "again", "where",
        "you", "left", "off", "the", "task", "it", "ahead", "e", "avait", "stoppe", "interrompu", "ca", "cela", "tout", "au", "meme", "point",
    )

    private fun tokens(s: String): List<String> =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
            .split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }

    /** Vrai pour un message qui ne fait que demander de reprendre ce qui était interrompu. */
    fun isResume(text: String): Boolean {
        val t = tokens(text)
        if (t.isEmpty() || t.size > 14) return false
        val joined = t.joinToString(" ")
        // reprise automatique après quota (envoyée par le plugin)
        if (joined.startsWith("le quota est reinitialise")) return true
        var rest = t
        val phrase = PHRASES.firstOrNull { " $joined ".contains(" $it ") }
        val triggered = t.any { it in TRIGGERS } || phrase != null
        if (!triggered) return false
        if (phrase != null) rest = " $joined ".replace(" $phrase ", " ").trim().split(" ").filter { it.isNotEmpty() }
        return rest.all { it in TRIGGERS || it in FILLER }
    }

    /** [userMessages] : les messages de l'utilisateur, du plus ancien au plus récent ; null s'il n'y en a pas. */
    fun pick(userMessages: List<String>): Choice? {
        val last = userMessages.lastOrNull() ?: return null
        if (!isResume(last)) return Choice(last)
        val i = userMessages.indexOfLast { !isResume(it) }
        return if (i < 0) Choice(last) else Choice(userMessages[i], resumedWith = last)
    }
}
