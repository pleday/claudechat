package dev.claudechat

import java.util.Collections
import java.util.IdentityHashMap

/**
 * Cache du HTML produit pour chaque élément de la conversation. Pendant une réponse le rendu est refait
 * toutes les 80 ms : seule la dernière entrée change, les autres sont réutilisées au lieu d'être re-parsées.
 *
 * Les blocs copiables (`id='code<n>'`) sont numérotés dans toute la conversation : le HTML est calculé avec
 * une numérotation locale puis renuméroté à l'insertion.
 */
class RenderCache {
    private class Slot(val key: Any, val html: String, val codes: List<String>)

    private val slots = IdentityHashMap<Any, Slot>()
    private val codeId = Regex("""(<(?:table|blockquote)\s[^>]*?id=')code(\d+)'""")

    /**
     * HTML de [owner] ; [compute] n'est rappelé que si [key] a changé. [codes] (si non null) reçoit les textes
     * bruts des blocs copiables de cet élément, à la suite de ceux déjà présents.
     */
    fun render(owner: Any, key: Any, codes: MutableList<String>? = null, compute: (MutableList<String>?) -> String): String {
        var slot = slots[owner]
        if (slot == null || slot.key != key) {
            val local = if (codes != null) mutableListOf<String>() else null
            val html = compute(local)
            slot = Slot(key, html, local.orEmpty())
            slots[owner] = slot
        }
        if (codes == null || slot.codes.isEmpty()) return slot.html
        val offset = codes.size
        codes.addAll(slot.codes)
        return if (offset == 0) slot.html else codeId.replace(slot.html) { "${it.groupValues[1]}code${it.groupValues[2].toInt() + offset}'" }
    }

    /** Oublie les éléments qui ne sont plus dans la conversation (nouvelle conversation, session chargée). */
    fun retainOnly(live: Collection<Any>) {
        val keep = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>()).apply { addAll(live) }
        slots.keys.retainAll(keep)
    }

    val size: Int get() = slots.size
}
