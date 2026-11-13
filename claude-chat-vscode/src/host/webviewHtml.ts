import { randomBytes } from 'node:crypto';
import type * as vscode from 'vscode';

/** Page du webview : coquille HTML ; tout le comportement est dans dist/webview.js. */
export function webviewHtml(webview: vscode.Webview, extensionUri: vscode.Uri, asUri: (...p: string[]) => vscode.Uri): string {
  const nonce = randomBytes(16).toString('base64');
  const script = asUri('dist', 'webview.js');
  const css = asUri('media', 'webview.css');
  const avatar = asUri('media', 'avatar.png');
  void extensionUri;
  const csp = [
    "default-src 'none'",
    `img-src ${webview.cspSource} data: https:`,
    `style-src ${webview.cspSource} 'unsafe-inline'`,
    `script-src 'nonce-${nonce}'`,
  ].join('; ');
  const btn = (id: string, text: string, title: string, cls = 'tb') => `<button id="${id}" class="${cls}" title="${title}">${text}</button>`;
  return `<!doctype html>
<html lang="fr">
<head>
<meta charset="utf-8">
<meta http-equiv="Content-Security-Policy" content="${csp}">
<meta name="viewport" content="width=device-width, initial-scale=1">
<link rel="stylesheet" href="${css}">
<title>Claude Chat</title>
</head>
<body>
<div id="app">
  <div id="toolbar">
    ${btn('renameBtn', '✎', 'Renommer la conversation')}
    ${btn('searchBtn', '🔍', 'Rechercher dans la conversation (Ctrl+F)')}
    ${btn('forkBtn', '⑂', 'Dupliquer la conversation (fork au prochain message)')}
    ${btn('thinkBtn', '💭', 'Afficher la réflexion')}
    ${btn('toolsBtn', '🛠', 'Afficher les outils')}
    ${btn('resumeBtn', '⏯', 'Reprendre automatiquement quand le quota est rempli (cette conversation)')}
    ${btn('remoteBtn', '📱', 'Recevoir les demandes sur le téléphone (Telegram) pour cette conversation')}
  </div>
  <div id="sticky" hidden></div>
  <div id="search" hidden>
    <input id="searchInput" placeholder="Rechercher dans la conversation" />
    <span id="searchCount"></span>
    ${btn('searchPrev', '↑', 'Précédent')}${btn('searchNext', '↓', 'Suivant')}${btn('searchClose', '✕', 'Fermer')}
  </div>
  <div id="transcript" tabindex="-1"></div>
  <div id="footer">
    <div id="interaction" hidden></div>
    <div id="queue" hidden></div>
    <div id="hints" hidden></div>
    <div id="statusrow"><span id="hourglass" hidden>⏳</span><span id="status"></span><span id="usage"></span><span id="ring" title="Contexte"></span>${btn('battery', '', 'Quota', 'fb')}</div>
    <div id="card">
      <div id="completion" hidden></div>
      <textarea id="input" rows="1" placeholder="Demande à Claude…   @ fichier · / commande" spellcheck="false"></textarea>
      <div id="controls">
        ${btn('plus', '+', 'Joindre, mentionner, commandes', 'fb')}
        ${btn('modeBtn', '', 'Mode de permission', 'fb')}
        ${btn('ctxBtn', '', 'Contexte de l\'éditeur', 'fb ctx')}
        <span class="grow"></span>
        ${btn('modelBtn', '', 'Modèle', 'fb')}
        ${btn('effortBtn', '', 'Niveau d\'effort', 'fb')}
        ${btn('stop', '■', 'Interrompre (Échap)', 'round stop')}
        ${btn('busyMenu', '⌄', 'Que faire du message pendant la réponse ?', 'round')}
        ${btn('send', '➤', 'Envoyer (Entrée)', 'round send')}
      </div>
    </div>
  </div>
</div>
<script nonce="${nonce}">window.__CCG = { avatar: ${JSON.stringify(avatar.toString())} };</script>
<script nonce="${nonce}" src="${script}"></script>
</body>
</html>`;
}
