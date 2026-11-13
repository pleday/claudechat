# Commandes du dépôt parent ; chaque projet a aussi son propre Makefile.
.PHONY: help test build clean jetbrains-test jetbrains-plugin jetbrains-run vscode-test vscode-package vscode-run e2e

help: ## Liste des commandes
	@grep -E '^[a-z-]+:.*##' $(MAKEFILE_LIST) | sed 's/:.*##/ —/'

test: jetbrains-test vscode-test ## Tests des deux projets

build: jetbrains-plugin vscode-package ## Plugin JetBrains (zip) et extension VS Code (.vsix)

jetbrains-test: ## Tests du plugin JetBrains
	$(MAKE) -C claude-chat-jetbrains test

jetbrains-plugin: ## Zip du plugin JetBrains
	$(MAKE) -C claude-chat-jetbrains plugin

jetbrains-run: ## IDE de test avec le plugin
	$(MAKE) -C claude-chat-jetbrains run

vscode-test: ## Types et tests de l'extension VS Code
	$(MAKE) -C claude-chat-vscode test

vscode-package: ## .vsix de l'extension VS Code
	$(MAKE) -C claude-chat-vscode package

vscode-run: ## VS Code de développement avec l'extension
	$(MAKE) -C claude-chat-vscode run

e2e: ## Test d'intégration de l'extension dans un vrai VS Code
	$(MAKE) -C claude-chat-vscode e2e

clean: ## Supprime les fichiers générés
	$(MAKE) -C claude-chat-jetbrains clean
	$(MAKE) -C claude-chat-vscode clean
