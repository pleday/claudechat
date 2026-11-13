# Commandes courantes de l'extension VS Code (Node.js 20+ requis ; les dépendances sont installées au besoin).
NPM := npm

.PHONY: help deps build test e2e package run clean

help: ## Liste des commandes
	@grep -E '^[a-z]+:.*##' $(MAKEFILE_LIST) | sed 's/:.*##/ —/'

node_modules: package-lock.json
	$(NPM) ci
	@touch node_modules

deps: node_modules ## Installe les dépendances

build: node_modules ## Construit dist/extension.js et dist/webview.js
	$(NPM) run build

test: node_modules ## Vérifie les types et lance les tests unitaires
	$(NPM) run typecheck
	$(NPM) test

e2e: node_modules ## Test d'intégration dans un vrai VS Code (téléchargé dans .vscode-test)
	$(NPM) run test:e2e

package: node_modules ## Tests puis .vsix installable
	$(NPM) run package
	@ls -1 *.vsix

run: build ## VS Code de développement avec l'extension (équivalent de F5)
	code --extensionDevelopmentPath=$(CURDIR) --disable-extensions

clean: ## Supprime les fichiers générés
	rm -rf dist dist-e2e dist-vsix *.vsix .vscode-test
