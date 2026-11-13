# Écarts avec la spécification

Couvert : FR-01 à 05, 07 (recherche), 08, 09, 12, 13 (mentions `@`, slash), 20–22, 24, 30–32, 34, 40–44, 50, 51 (titre généré par la CLI, repris dans l'onglet), 53 (partiel), 62, 64 (détail du contexte au clic sur l'anneau), 65, 70 (partiel), 85, RG-1 à RG-6.

Non couvert :
- FR-06 réduction des réponses / index des envois, FR-10 `/btw`, FR-11 tâches d'arrière-plan, FR-14 voix
- FR-23 application d'ouverture, FR-33 mise à jour du diff si le disque change (la comparaison de la liste des fichiers modifiés lit le fichier actuel, mais le diff d'approbation reste figé), FR-35 rewind (le fork est disponible)
- FR-52 non-lus, FR-54 cartes/arbre, FR-55 worktrees, FR-56 MCP de gestion
- FR-60/61 comptes multiples et pool (connexion via `claude auth login`), FR-63 rapport d'usage détaillé
- FR-72 bibliothèque de prompts, FR-73 artefacts, FR-74 i18n 12 langues + RTL, FR-75–76 réglages par projet
- FR-80 redémarrage du moteur (non applicable : pas de moteur séparé), FR-81 sons, FR-82 tunnel, FR-83 veille, FR-84 guide JCEF
- Mode navigateur « standalone » ; des trois couches de tests, seule la couche Kotlin existe (plus des tests d'intégration contre la vraie CLI, `RealCliIT`, à lancer à la main)

Au-delà de la spécification :
- liste des fichiers modifiés par Claude (outils d'édition et commandes), ouverte dans l'arbre des changements et la comparaison de l'IDE ;
- message de l'utilisateur épinglé en haut, avec repli sur la tâche précédente pour les demandes de reprise ;
- suggestion du message suivant, historique de saisie, mode de permission appliqué en cours de réponse, confirmation avant d'interrompre une réponse ;
- modèles et niveaux d'effort lus depuis la CLI.
