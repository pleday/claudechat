#!/usr/bin/env bash
# Génère updatePlugins.xml : dépôt de plugins personnalisé pointant vers la release GitHub d'une version.
# Usage : scripts/update-repo.sh [version]   (par défaut : dernier tag git « vX.Y.Z », sans le « v »)
# À lancer après la création de la release, puis à commiter et pousser.
# updatePlugins.xml reste à la racine du dépôt : son URL brute est celle que les IDE des utilisateurs interrogent.
set -euo pipefail
cd "$(dirname "$0")/.."
OUT="../updatePlugins.xml"

REPO="pleday/claudechat"
VERSION="${1:-$(git describe --tags --abbrev=0 --match 'v[0-9]*' | sed 's/^v//')}"

# notes de version : section « ## <version> » du changelog, en liste HTML
NOTES="$(awk -v v="$VERSION" '$0 ~ "^## " v {f=1; next} /^## /{f=0} f && /^- /{print}' CHANGELOG.md \
  | sed -E 's/&/\&amp;/g; s/</\&lt;/g; s/>/\&gt;/g; s/\*\*([^*]+)\*\*/<b>\1<\/b>/g; s/`([^`]+)`/<code>\1<\/code>/g; s/^- (.*)$/<li>\1<\/li>/')"

cat > "$OUT" <<XML
<?xml version="1.0" encoding="UTF-8"?>
<plugins>
  <plugin id="dev.claudechat" url="https://github.com/${REPO}/releases/download/v${VERSION}/claude-chat-${VERSION}.zip" version="${VERSION}">
    <idea-version since-build="243"/>
    <name>Claude Chat</name>
    <description>Interface graphique pour Claude Code (CLI claude) dans les IDE JetBrains.</description>
    <change-notes><![CDATA[<ul>
${NOTES}
</ul>]]></change-notes>
  </plugin>
</plugins>
XML
echo "updatePlugins.xml généré pour la version ${VERSION}"
