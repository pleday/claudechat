# Claude Chat

**Une interface graphique pour [Claude Code](https://claude.com/claude-code), directement dans votre éditeur.**

Claude Chat enveloppe la CLI `claude` dans un panneau de conversation natif : réponses en streaming, cartes d'outils,
approbation des actions, diff modifiable avant d'accepter, suivi des quotas… sans quitter l'IDE. Il est disponible
en deux versions aux fonctionnalités identiques :

| Éditeur | Projet | Technologie |
|---------|--------|-------------|
| IntelliJ IDEA et autres IDE JetBrains ≥ 2024.3 | [`claude-chat-jetbrains`](claude-chat-jetbrains) | Kotlin, Gradle, Swing |
| VS Code ≥ 1.90 | [`claude-chat-vscode`](claude-chat-vscode) | TypeScript, esbuild, webview |

## Fonctionnalités

- **Conversation en streaming** : Markdown, réflexion, cartes d'outils, historique de saisie, pièces jointes (images, fichiers, vidéos), mentions `@fichier` et commandes `/` de la CLI.
- **Contrôle total** : approbation des actions, plan et questions de Claude, **diff modifiable** avant d'accepter, changement du mode de permission en cours de réponse, messages empilés ou précisions injectées sans interrompre.
- **Fichiers modifiés par Claude** : la liste de ce que Claude a créé, modifié ou supprimé (y compris via Bash, sous-agents ou outils MCP), avec comparaison « avant Claude ↔ actuel ». Indépendant de git.
- **Suivi** : quotas de la session de 5 h, détail du contexte, durée, coût, titre de session, reprise automatique après un quota atteint.
- **Sessions** : reprise des sessions de la CLI (`~/.claude/projects`) ; une conversation commencée dans un éditeur se poursuit dans l'autre.
- **MCP** : gestion des serveurs MCP.
- **Accès à distance** : débloquez une demande d'autorisation depuis votre smartphone grâce à un bot Telegram.

Le détail de chaque version est dans son README ([JetBrains](claude-chat-jetbrains/README.md), [VS Code](claude-chat-vscode/README.md)).

## Installation

### Prérequis

- La CLI [`claude`](https://claude.com/claude-code) installée et connectée :
  ```bash
  claude auth login
  ```
- Pour construire depuis les sources : `make`, **JDK 21** (JetBrains) et **Node.js 20+** (VS Code).

Il n'y a pas de publication sur les marketplaces : on installe un fichier de release, ou on le construit soi-même.

### Plugin JetBrains

1. Téléchargez le zip `claude-chat-jetbrains-X.Y.Z.zip` depuis les [releases GitHub](https://github.com/pleday/claudechat/releases) (tags `vX.Y.Z`).
2. Dans l'IDE : *Réglages > Plugins > ⚙ > Install Plugin from Disk…*, puis sélectionnez le zip et redémarrez.
3. Pour recevoir les mises à jour dans l'IDE : *Réglages > Plugins > ⚙ > Manage Plugin Repositories… > +*, puis ajoutez
   `https://raw.githubusercontent.com/pleday/claudechat/main/updatePlugins.xml`.

### Extension VS Code

1. Téléchargez `claude-chat-vscode-X.Y.Z.vsix` depuis les [releases GitHub](https://github.com/pleday/claudechat/releases) (tags `vscode-vX.Y.Z`).
2. Installez-le : *Extensions > … > Install from VSIX…*, ou
   ```bash
   code --install-extension claude-chat-vscode-X.Y.Z.vsix
   ```

### Depuis les sources

```bash
git clone git@github.com:pleday/claudechat.git
cd claudechat
make build
```

`make build` produit le zip du plugin JetBrains (`claude-chat-jetbrains/build/distributions/`) et le `.vsix` de l'extension
VS Code (`claude-chat-vscode/`), à installer comme ci-dessus.

## Utilisation

Ouvrez le panneau **Claude Chat** dans votre éditeur, écrivez votre demande et validez. Claude travaille dans le dossier du
projet ouvert ; chaque action sensible vous est soumise (vous pouvez modifier le diff avant de l'accepter).
Les réglages (modèle, effort, mode de permission, accès à distance…) se trouvent dans les préférences de l'éditeur,
sous *Claude Chat*.

## Développement

| Commande | Rôle |
|----------|------|
| `make test` | tests des deux projets |
| `make build` | plugin JetBrains (zip) et extension VS Code (`.vsix`) |
| `make jetbrains-run` / `make vscode-run` | éditeur de test avec le projet |
| `make e2e` | test d'intégration de l'extension dans un vrai VS Code |

Chaque projet garde ses propres commandes (`claude-chat-jetbrains/Makefile`, `claude-chat-vscode/Makefile`).

### Architecture

Kotlin (JVM) et TypeScript ne pouvant pas partager de code, on mutualise ce qui est **données** :

- `shared/assets` — icônes et avatar ;
- `shared/data` — constantes (modes de permission, efforts, prompts…), astuces, commandes slash ;
- `shared/test-vectors` — cas « entrée → sortie » exécutés par **les deux** suites de tests : un écart de comportement entre les éditeurs fait échouer un test (voir [`shared/README.md`](shared/README.md)) ;
- le dossier de données `~/.claude-code-gui` (titres et archives des sessions), commun aux deux éditeurs.

Chaque build copie ces fichiers dans ses propres ressources : on n'édite jamais les copies. Les deux projets implémentent le même
protocole avec la CLI (stream-json, requêtes de contrôle) ; quand l'un change, l'autre se vérifie avec la même liste
fonctionnelle (`*/docs/VALIDATION.md`).

### Publication

- **Plugin JetBrains** : tag `vX.Y.Z`, release GitHub avec le zip, puis `claude-chat-jetbrains/scripts/update-repo.sh` met à jour
  `updatePlugins.xml` (à laisser à la **racine** : c'est l'URL interrogée par les IDE des utilisateurs). Détails dans le README du plugin.
- **Extension VS Code** : tag `vscode-vX.Y.Z`, release GitHub avec le `.vsix` (`make -C claude-chat-vscode package`).

## Licence

[MIT](LICENSE) © 2026 Pierrick Le Day : vous pouvez utiliser, modifier et distribuer ce logiciel librement, y compris à des fins
commerciales, à condition de conserver la mention de copyright et le texte de la licence.
