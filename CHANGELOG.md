# Changelog

## Non publié

- **Collage d'images** : Ctrl+V / Maj+Inser et le glisser-déposer passent maintenant par un `TransferHandler` et un raccourci dédiés (le collage ne passait pas par `paste()` dans l'IDE) ; les fichiers copiés depuis un gestionnaire de fichiers sont aussi joints. Du texte copié avec une image (tableur, page web) reste collé comme texte
- **Aperçu des pièces jointes** : miniatures des images (clic pour agrandir), pastilles pour les fichiers, retrait pièce par pièce (×) au lieu de la simple liste de noms

- Suppression de la case « Autoriser la télémétrie » des réglages : elle n'était lue par aucun code et ne déclenchait aucun envoi

## 0.8.2

- **Serveurs MCP prêts avant l'envoi** : la CLI démarre ses serveurs MCP en tâche de fond, et au premier message ceux encore « pending » n'avaient pas d'outils (Claude les croyait déconnectés, par exemple SonarQube). Le plugin attend maintenant leur connexion (15 s max, requête `mcp_status`) avant d'envoyer, et affiche une notice si un serveur est en échec ou toujours en attente
- Les processus utilitaires (liste des commandes `/`, mesure du quota) ne démarrent plus les serveurs MCP de l'utilisateur

## 0.8.1

- **Permissions « Stream closed » (suite)** : la CLI peut reprendre du travail après un `result` (tâche de fond, message empilé). Le plugin garde alors l'entrée ouverte (fermeture seulement après 3 min de silence), repasse en « en cours » et affiche normalement les demandes de permission, au lieu de les perdre. Le processus d'un tour terminé est arrêté dès qu'un nouveau message est envoyé

## 0.8.0

- Icône du plugin (clair et sombre) pour le Marketplace et la fenêtre des plugins

- **Permissions « Stream closed »** : l'entrée de la CLI n'est plus fermée dès la fin d'un tour mais quand elle est silencieuse (1,5 s, plafond 2 min), pour ne plus faire échouer les permissions d'un travail qui continue. Une permission demandée après la fin du tour est refusée avec un message clair (et une notice dans le panneau) ; les événements tardifs sont journalisés dans `idea.log`

- **Auto-complétion de `/`** : popup flottant au niveau du curseur, navigation ↑/↓, Entrée ou Tab pour valider, Échap pour fermer. Les commandes viennent de la CLI (requête `initialize`, sans appel au modèle), regroupées par catégorie, avec descriptions en français pour les commandes connues ; les raccourcis du plugin passent en premier
- Même popup pour les mentions `@fichier` : liste préchargée à l'ouverture, dossiers cachés et fichiers ignorés exclus, fichiers ouverts puis récents proposés pour un `@` seul

## 0.7.0

- **Ouverture du panneau plus rapide** : les actions de la barre d'outils et des menus se rafraîchissent hors du thread d'interface (`ActionUpdateThread.BGT`) (l'IDE signalait jusqu'à plusieurs secondes d'attente de ce thread au démarrage)
- Dépôt de mises à jour du plugin (`updatePlugins.xml`) pour que l'IDE identifie l'origine du plugin ; `make repo` le régénère
- Construction du plugin indépendante d'un second IDE (index de recherche des réglages désactivé), même quand `make run` est ouvert
- **Serveurs MCP** : nouvelle fenêtre (liste groupée par portée Utilisateur / Projet / Local / claude.ai, badges de statut « Connecté », « Authentification requise », « Échec », détails dépliables avec secrets masqués, recherche, actualisation) et fenêtre « Ajouter un serveur MCP » (nom facultatif, portée, configuration JSON `mcpServers` ou d'un seul serveur). S'appuie sur `claude mcp list / add-json / remove` ; remplace l'ancien tableau des réglages

## 0.6.0

- Écran d'accueil à chaque nouvel onglet : portrait (ressource `images/avatar.png`, remplaçable) et astuces qui tournent
- Transcript plus lisible : « Vous » et « Claude » en gras et en couleur, barre d'accent, modèle affiché à côté de Claude
- Pièces jointes **vidéo** (mp4, mov, webm, mkv, avi…) : images extraites avec ffmpeg et envoyées à Claude avec les instants (la piste audio n'est pas transmise)
- Zone de saisie refondue : carte à bordure d'accent, « + », permissions, fichier courant (cliquable), modèle/effort, anneau de contexte, bouton d'envoi rond avec menu « Mettre en file d'attente / Préciser l'exécution en cours »
- **Préciser** une réponse en cours : le message est injecté au prochain point d'arrêt de Claude, sans l'interrompre (Ctrl+Entrée ou menu du bouton)
- File d'attente visible : le dernier message empilé s'affiche, un clic ouvre le détail de tous (ordre, suppression, envoi immédiat en précision)

## 0.5.0 — première release

Réécriture complète : le plugin pilote désormais la CLI `claude` (plus de clé API).

- Conversation en streaming (`claude -p`, stream-json), réflexion et cartes d'outils, interruption (Échap)
- Approbation des actions (autoriser / refuser / tout autoriser), plans et questions de Claude dans le panneau
- Diff IntelliJ modifiable avant d'accepter une modification ; rechargement des fichiers modifiés
- Sessions de la CLI : liste paginée, reprise, renommage, archivage, suppression ; onglets multiples fermables ; fork
- Contexte de l'éditeur (fichier / sélection), mentions `@fichier`, images (fichier ou presse-papiers), recherche dans la conversation
- Modèles avec numéro de version, effort, mode de permission ; message de commit généré
- Quotas : batterie 5 h (vert ≥ 50 %, orange ≥ 20 %, rouge) avec détail 5 h / 7 j, mesure automatique, bouton Actualiser
- Reprise automatique quand le quota est rempli (délai réglable, 2 min par défaut)
- Déblocage à distance via Telegram (appairage, boutons, réponse libre, échange bidirectionnel), par conversation
- Serveurs MCP supplémentaires, mise à jour de la CLI depuis les réglages

Prérequis : IntelliJ IDEA ≥ 2024.3, JDK 21, CLI `claude` connectée.
