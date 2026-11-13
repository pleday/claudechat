# Écarts avec la spécification

Même périmètre fonctionnel que le plugin JetBrains (voir `../../claude-chat-jetbrains/docs/ECARTS.md` pour la liste des exigences FR-xx couvertes
et non couvertes) ; seules les différences propres à VS Code sont listées ici.

Adaptations à la plateforme :
- **Diff modifiable** : comparaison native de VS Code dont la partie droite est un document en mémoire, éditable ; la version laissée par l'utilisateur est appliquée (FR-32).
- **Fichiers modifiés par Claude** : vue dédiée dans la barre d'activité et éditeur de comparaison multiple (`vscode.changes`) au lieu de l'arbre des changements de l'IDE.
- **Onglets** : vue latérale + onglets d'éditeur (`WebviewPanel`) au lieu des onglets de la fenêtre d'outils.
- **Interface** : webview HTML (rendu Markdown complet : tableaux, listes, code) au lieu de Swing ; couleurs reprises du thème VS Code.
- **Jeton Telegram** : `SecretStorage` de VS Code au lieu du coffre de l'IDE.
- **Message de commit** : bouton du contrôle de code source (API de l'extension Git) au lieu de la zone de commit de l'IDE.

Limites propres à VS Code :
- pas de glisser-déposer de fichiers dans la zone de saisie (bouton **+** et mentions `@`) ;
- fermer l'onglet d'une conversation en cours d'exécution l'interrompt sans confirmation ;
- un seul dossier racine (le premier de l'espace de travail) ;
- pas de recherche dans les réglages dédiée : tout est dans *Paramètres > Extensions > Claude Chat*.
