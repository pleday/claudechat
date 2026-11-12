# Changelog

## Non publié

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
