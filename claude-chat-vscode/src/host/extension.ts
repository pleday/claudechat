import * as path from 'node:path';
import * as vscode from 'vscode';
import type { ChangedFile } from '../core/changes';
import { RemoteBridge } from '../core/remote';
import { loadSession } from '../core/sessions';
import { SLASH_PROMPTS } from '../core/textTools';
import { ChangesView } from './changesView';
import { ChatHost, cliPath, currentEditor, projectDir, trackEditors, type Services } from './chatHost';
import { generateCommitMessage } from './commitMessage';
import { manageMcp, pickSession } from './pickers';
import { BeforeProvider, BEFORE_SCHEME, PROPOSAL_SCHEME, ProposalFs } from './virtualDocs';

const SECRET_TOKEN = 'claudeChat.telegramToken';

/** Point d'observation pour les tests d'intégration (extension host réel) ; sans effet sur l'utilisation normale. */
export interface TestApi { hosts(): ChatHost[] }

export function activate(context: vscode.ExtensionContext): TestApi {
  const remote = new RemoteBridge();
  const proposals = new ProposalFs();
  const before = new BeforeProvider();
  const changes = new ChangesView(before);
  const hosts = new Set<ChatHost>();
  let sidebar: ChatHost | undefined;
  let active: ChatHost | undefined;

  const svc: Services = {
    context, remote, proposals, before, changes,
    cliPath,
    activate: (h) => { active = h; changes.set(h.id, h.session.changedFiles); },
    changesChanged: (h, files) => { if (active === h || !active) changes.set(h.id, files); updateBadge(); },
    showSessions: (h) => void openSessions(h),
  };

  const changesTree = vscode.window.createTreeView('claudeChat.changes', { treeDataProvider: changes });
  const updateBadge = () => { changesTree.badge = changes.count ? { value: changes.count, tooltip: `${changes.count} fichier(s) modifié(s) par Claude` } : undefined; };

  context.subscriptions.push(
    vscode.workspace.registerFileSystemProvider(PROPOSAL_SCHEME, proposals, { isCaseSensitive: true }),
    vscode.workspace.registerTextDocumentContentProvider(BEFORE_SCHEME, before),
    changesTree,
  );
  trackEditors(context, () => hosts.forEach((h) => h.editorChanged()));

  // ----- vue latérale -----
  const getSidebar = (): ChatHost => (sidebar ??= new ChatHost(svc, 'view'));
  context.subscriptions.push(vscode.window.registerWebviewViewProvider('claudeChat.chat', {
    resolveWebviewView(view) {
      const host = getSidebar();
      hosts.add(host);
      host.bind(view.webview, (t) => { view.description = t === 'Nouvelle conversation' ? undefined : t; });
      if (view.visible) svc.activate(host);
      view.onDidChangeVisibility(() => { if (view.visible) svc.activate(host); });
    },
  }, { webviewOptions: { retainContextWhenHidden: true } }));

  // ----- onglets -----
  const openTab = (): ChatHost => {
    const host = new ChatHost(svc, 'panel');
    const panel = vscode.window.createWebviewPanel('claudeChat.tab', 'Claude Chat', { viewColumn: vscode.ViewColumn.Beside, preserveFocus: false }, {
      enableScripts: true, retainContextWhenHidden: true, localResourceRoots: [context.extensionUri],
    });
    panel.iconPath = vscode.Uri.joinPath(context.extensionUri, 'media', 'claude.svg');
    hosts.add(host);
    host.bind(panel.webview, (t) => { panel.title = t; });
    svc.activate(host);
    panel.onDidChangeViewState((e) => { if (e.webviewPanel.active) svc.activate(host); });
    panel.onDidDispose(() => { hosts.delete(host); if (active === host) active = sidebar; host.dispose(); });
    return host;
  };

  /** La conversation visée par une commande : la dernière active, sinon la vue latérale (ouverte au besoin). */
  const target = async (): Promise<ChatHost> => {
    if (active && hosts.has(active)) return active;
    await vscode.commands.executeCommand('claudeChat.chat.focus');
    for (let i = 0; i < 40 && !sidebar; i++) await new Promise((r) => setTimeout(r, 50));
    return getSidebar();
  };

  async function openSessions(h: ChatHost): Promise<void> {
    const pick = await pickSession(projectDir());
    if (!pick) return;
    const s = await loadSession(pick.info);
    if (pick.newTab) { const t = openTab(); await t.session.loadSession(s); return; }
    await h.session.loadSession(s);
  }

  // ----- accès à distance (Telegram) -----
  const reconfigureRemote = async () => {
    const c = vscode.workspace.getConfiguration('claudeChat');
    remote.reconfigure({
      enabled: c.get<boolean>('remote.enabled', false),
      token: await context.secrets.get(SECRET_TOKEN),
      chatId: c.get<string>('remote.chatId', ''),
      onPaired: (id) => { void c.update('remote.chatId', String(id), vscode.ConfigurationTarget.Global); },
    });
  };
  void reconfigureRemote();
  context.subscriptions.push(
    vscode.workspace.onDidChangeConfiguration((e) => {
      // le changement de chatId vient de l'appairage lui-même : inutile de redémarrer le bot
      if (e.affectsConfiguration('claudeChat.remote.enabled')) void reconfigureRemote();
    }),
    { dispose: () => remote.reconfigure({ enabled: false, onPaired: () => {} }) },
  );

  // ----- commandes -----
  const reg = (id: string, fn: (...a: any[]) => unknown) => context.subscriptions.push(vscode.commands.registerCommand(id, fn));

  reg('claudeChat.focus', () => vscode.commands.executeCommand('claudeChat.chat.focus'));
  reg('claudeChat.newTab', () => { openTab(); });
  reg('claudeChat.newConversation', async () => (await target()).session.newSession());
  reg('claudeChat.sessions', async () => openSessions(await target()));
  reg('claudeChat.mcp', () => manageMcp(cliPath(), projectDir()));
  reg('claudeChat.settings', () => vscode.commands.executeCommand('workbench.action.openSettings', 'claudeChat'));
  reg('claudeChat.openChange', (f: ChangedFile) => changes.openOne(f));
  reg('claudeChat.forgetChanges', () => active?.session.clearChangedFiles());
  reg('claudeChat.commitMessage', (scm?: { rootUri?: vscode.Uri }) =>
    generateCommitMessage(cliPath(), vscode.workspace.getConfiguration('claudeChat').get<string>('model', 'claude-sonnet-5-5'), scm));

  const selection = () => {
    const ed = vscode.window.activeTextEditor ?? currentEditor();
    if (!ed || ed.selection.isEmpty) { void vscode.window.showInformationMessage('Sélectionne d\'abord du code dans l\'éditeur.'); return undefined; }
    const rel = vscode.workspace.asRelativePath(ed.document.uri, false);
    return { rel, name: path.basename(rel), lang: ed.document.languageId, text: ed.document.getText(ed.selection), start: ed.selection.start.line + 1, end: ed.selection.end.line + 1 };
  };

  reg('claudeChat.sendSelection', async () => {
    const s = selection();
    if (!s) return;
    const h = await target();
    h.session.insertText(`\`\`\`${s.lang}\n// ${s.rel}:${s.start}-${s.end}\n${s.text}\n\`\`\``);
  });
  for (const command of Object.keys(SLASH_PROMPTS)) {
    reg(`claudeChat.${command}`, async () => {
      const s = selection();
      if (!s) return;
      (await target()).session.ask(command, s.name, s.lang, s.text);
    });
  }

  reg('claudeChat.setTelegramToken', async () => {
    const t = await vscode.window.showInputBox({ title: 'Jeton du bot Telegram', prompt: 'Créé avec @BotFather ; stocké dans le coffre sécurisé de VS Code', password: true, ignoreFocusOut: true });
    if (t === undefined) return;
    if (t.trim()) await context.secrets.store(SECRET_TOKEN, t.trim()); else await context.secrets.delete(SECRET_TOKEN);
    await reconfigureRemote();
  });
  reg('claudeChat.pairPhone', async () => {
    const c = vscode.workspace.getConfiguration('claudeChat');
    if (!c.get<boolean>('remote.enabled', false)) await c.update('remote.enabled', true, vscode.ConfigurationTarget.Global);
    if (!(await context.secrets.get(SECRET_TOKEN))) await vscode.commands.executeCommand('claudeChat.setTelegramToken');
    await reconfigureRemote();
    const code = remote.newPairingCode();
    if (!code) { void vscode.window.showWarningMessage('Accès à distance inactif : renseigne le jeton du bot Telegram.'); return; }
    void vscode.window.showInformationMessage(`Envoie « /pair ${code} » à ton bot Telegram (valable 5 minutes).`);
  });
  return { hosts: () => [...hosts] };
}

export function deactivate(): void {}
