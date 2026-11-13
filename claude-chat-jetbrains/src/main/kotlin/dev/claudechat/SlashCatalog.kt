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

    /** Ordre d'affichage des groupes. */
    val ORDER = listOf(PLUGIN, SESSION, CONFIG, CODE, AGENTS, DIAG, SKILLS, PLUGINS)

    /** Commandes internes de la CLI, sans intérêt pour l'utilisateur. */
    private val hidden = setOf("workflow-launch-exec", "extra-usage", "design-consent", "design-revoke")

    private val local = mapOf(
        "explain" to "Expliquer le code sélectionné",
        "fix" to "Trouver et corriger les bugs",
        "tests" to "Écrire des tests unitaires",
        "doc" to "Ajouter de la documentation",
        "review" to "Revue de code par gravité",
    )

    /** Catégorie et description française des commandes connues de la CLI. */
    private val known: Map<String, Pair<String, String>> = mapOf(
        "clear" to (SESSION to "Nouvelle session avec un contexte vide"),
        "compact" to (SESSION to "Résumer la conversation pour libérer du contexte"),
        "context" to (SESSION to "Afficher l'utilisation du contexte"),
        "rename" to (SESSION to "Renommer la conversation"),
        "usage" to (SESSION to "Coût de la session et usage du forfait"),
        "usage-credits" to (SESSION to "Configurer ou demander des crédits d'usage"),
        "recap" to (SESSION to "Générer un récapitulatif d'une ligne"),
        "focus" to (SESSION to "Vue épurée : demande, résumé et réponse"),
        "color" to (SESSION to "Couleur de la barre de saisie"),
        "goal" to (SESSION to "Définir un objectif : continuer jusqu'à l'atteindre"),
        "insights" to (SESSION to "Rapport d'analyse de vos sessions"),

        "model" to (CONFIG to "Choisir le modèle"),
        "effort" to (CONFIG to "Régler le niveau d'effort"),
        "fast" to (CONFIG to "Activer ou désactiver le mode rapide"),
        "config" to (CONFIG to "Modifier un réglage par sa clé"),
        "output-style" to (CONFIG to "Lister ou changer le style de sortie"),
        "mcp" to (CONFIG to "Gérer les serveurs MCP"),
        "autocompact" to (CONFIG to "Taille de la fenêtre d'auto-compactage"),
        "advisor" to (CONFIG to "Consulter un modèle plus puissant aux moments clés"),
        "auto-mode-setup" to (CONFIG to "Adapter le mode auto à votre environnement"),
        "import" to (CONFIG to "Importer la config d'un autre agent de code"),
        "update-config" to (CONFIG to "Configurer Claude Code (settings.json, hooks, permissions)"),
        "fewer-permission-prompts" to (CONFIG to "Réduire les demandes d'autorisation (liste d'autorisations)"),
        "reload-plugins" to (CONFIG to "Activer les changements de plugins"),
        "reload-skills" to (CONFIG to "Recharger les skills modifiés sur disque"),

        "init" to (CODE to "Créer un CLAUDE.md documentant le code"),
        "code-review" to (CODE to "Revue du diff, d'une PR ou d'une branche"),
        "security-review" to (CODE to "Revue de sécurité des changements en cours"),
        "simplify" to (CODE to "Simplifier et nettoyer le code modifié"),
        "verify" to (CODE to "Vérifier qu'un changement fait ce qu'il doit"),
        "debug" to (CODE to "Activer les logs de debug et diagnostiquer"),
        "run" to (CODE to "Lancer l'application pour voir un changement"),
        "run-skill-generator" to (CODE to "Créer le skill de lancement du projet"),
        "batch" to (CODE to "Planifier puis exécuter un grand changement en parallèle"),
        "claude-api" to (CODE to "Référence de l'API Claude / SDK Anthropic"),

        "agents" to (AGENTS to "Créer ou gérer des sous-agents"),
        "list-agents" to (AGENTS to "Lister sous-agents et sessions joignables"),
        "loop" to (AGENTS to "Répéter un prompt à intervalle régulier"),
        "schedule" to (AGENTS to "Planifier des agents cloud (routines)"),
        "deep-research" to (AGENTS to "Recherche approfondie avec rapport sourcé"),
        "workflow-authoring" to (AGENTS to "Référence pour écrire un script de workflow"),
        "team-onboarding" to (AGENTS to "Guide d'intégration pour l'équipe"),

        "doctor" to (DIAG to "Vérifier l'installation et corriger les problèmes"),
        "heapdump" to (DIAG to "Exporter le tas JS sur le Bureau"),
        "skill-doctor" to (DIAG to "Skills inutilisés qui coûtent du contexte"),

        "design" to (SKILLS to "Accès de Claude à vos projets Design"),
        "design-sync" to (SKILLS to "Envoyer un design system vers claude.ai/design"),
        "dataviz" to (SKILLS to "Graphiques et visualisations de données"),
        "plugin-authoring" to (SKILLS to "Créer ou déboguer un mod / plugin"),
        "docs" to (SKILLS to "Documents éditables partagés"),
        "docx" to (SKILLS to "Documents Word (.docx)"),
        "pdf" to (SKILLS to "Lire, créer ou modifier des PDF"),
        "pptx" to (SKILLS to "Présentations PowerPoint (.pptx)"),
        "xlsx" to (SKILLS to "Tableurs (.xlsx, .csv)"),
        "google-workspace" to (SKILLS to "Fichiers Google Docs, Sheets et Slides"),
        "import-memory" to (SKILLS to "Importer une mémoire depuis un autre assistant"),
        "morning" to (SKILLS to "Brief du matin"),
        "skill-creator" to (SKILLS to "Créer ou améliorer un skill"),
    )

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
