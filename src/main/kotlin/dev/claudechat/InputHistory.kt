package dev.claudechat

/**
 * Historique des messages envoyés, rappelés avec ↑ / ↓ dans le champ de saisie (comme un terminal).
 * Le texte en cours de frappe est conservé comme brouillon et restitué en redescendant.
 */
class InputHistory(private val max: Int = 50) {
    private val entries = ArrayList<String>()
    private var pos = 0          // == entries.size : on n'est pas dans l'historique
    private var draft = ""

    fun add(text: String) {
        val t = text.trim()
        if (t.isNotEmpty() && entries.lastOrNull() != t) {
            entries.add(t)
            if (entries.size > max) entries.removeAt(0)
        }
        pos = entries.size; draft = ""
    }

    /** Message précédent, ou null s'il n'y en a plus. [current] est le texte actuel du champ. */
    fun previous(current: String): String? {
        // texte modifié pendant la navigation : il devient le nouveau brouillon
        if (pos == entries.size || current != entries[pos]) { draft = current; pos = entries.size }
        if (pos == 0) return null
        return entries[--pos]
    }

    /** Message suivant (ou le brouillon en bout de course), ou null si on n'est pas dans l'historique. */
    fun next(current: String): String? {
        if (pos >= entries.size) return null
        if (current != entries[pos]) return null
        pos++
        return if (pos == entries.size) draft else entries[pos]
    }
}
