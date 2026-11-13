# shared — ce que les deux éditeurs mettent en commun

Kotlin (JetBrains) et TypeScript (VS Code) ne partagent pas de code : on partage les **données** et la **définition du comportement**.

| Dossier | Contenu | Lu par |
|---------|---------|--------|
| `assets/` | icône, icônes du plugin, avatar | Gradle `processResources`, `esbuild.mjs` |
| `data/constants.json` | modes de permission, efforts, modèles de secours, prompts `/explain`…, prompt du message de commit, dossiers ignorés, extensions vidéo, seuils | `Constants` (Kotlin), `core/constants.ts` |
| `data/tips.json`, `data/slash-commands.json` | astuces d'accueil, catalogue des commandes `/` | `SharedData`, `core/slashCatalog.ts` |
| `test-vectors/*.json` | cas `entrée → sortie attendue` du comportement métier | `VectorsTest.kt`, `test/vectors.test.ts` |

## Vecteurs de test

Chaque fichier regroupe des sections `{ "in": …, "out": … }` : message épinglé, recherche floue, mentions `@`, commandes `/`, quotas et
reprise automatique, durées, modèles, analyse des réponses de la CLI (`initialize`, `rate_limit_event`, contexte), `claude mcp list`,
normalisation MCP, historique de saisie, catalogue des commandes, reconstitution d'un transcript. Les propriétés `null` et absentes
sont équivalentes. Les deux suites exécutent les mêmes cas : un écart de comportement entre les deux éditeurs fait échouer un test.

**Ajouter ou changer un comportement** :
1. ajouter le cas (la section `in`) dans le fichier JSON ;
2. implémenter dans les deux projets ;
3. générer les sorties depuis TypeScript : `UPDATE_VECTORS=1 npm test -- vectors` (dans `claude-chat-vscode`), **relire le diff** ;
4. vérifier le Kotlin : `./gradlew test --tests '*VectorsTest*'` (dans `claude-chat-jetbrains`).

Pour ajouter un type de cas : un handler dans `test/vectors.test.ts` et son équivalent dans `VectorsTest.kt`.
