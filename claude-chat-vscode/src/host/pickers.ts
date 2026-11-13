import * as vscode from 'vscode';
import { add, list, merge, MCP_SCOPES, mask, normalize, readConfigs, remove, type McpScope, type McpServer, type McpStatus } from '../core/mcp';
import { deleteSession, listSessions, loadSession, renameSession, setArchived, type SessionInfo } from '../core/sessions';

const PAGE = 30;

const ago = (ms: number): string => {
  const min = Math.max(0, Math.round((Date.now() - ms) / 60_000));
  if (min < 1) return "à l'instant";
  if (min < 60) return `il y a ${min} min`;
  const h = Math.round(min / 60);
  if (h < 48) return `il y a ${h} h`;
  return `il y a ${Math.round(h / 24)} j`;
};

type Pick = vscode.QuickPickItem & { info?: SessionInfo; more?: boolean };

/**
 * Sessions de la CLI du projet : reprendre, renommer, archiver, supprimer, ouvrir dans un onglet.
 * Renvoie la session à ouvrir (et si c'est dans un nouvel onglet), ou undefined.
 */
export async function pickSession(cwd: string): Promise<{ info: SessionInfo; newTab: boolean } | undefined> {
  return new Promise((resolve) => {
    const qp = vscode.window.createQuickPick<Pick>();
    qp.title = 'Sessions Claude (reprendre, renommer, archiver, supprimer)';
    qp.placeholder = 'Choisir une session à reprendre';
    qp.matchOnDescription = true;
    qp.ignoreFocusOut = false;
    let showArchived = false;
    let loaded = 0;
    let all: SessionInfo[] = [];
    let hasMore = false;
    const toggle: vscode.QuickInputButton = { iconPath: new vscode.ThemeIcon('archive'), tooltip: 'Afficher / masquer les sessions archivées' };
    qp.buttons = [toggle];

    const draw = () => {
      const items: Pick[] = all.map((info) => ({
        label: `${info.archived ? '$(archive) ' : ''}${info.title}`,
        description: ago(info.updated),
        detail: info.id,
        info,
        buttons: [
          { iconPath: new vscode.ThemeIcon('link-external'), tooltip: 'Ouvrir dans un nouvel onglet' },
          { iconPath: new vscode.ThemeIcon('edit'), tooltip: 'Renommer' },
          { iconPath: new vscode.ThemeIcon(info.archived ? 'inbox' : 'archive'), tooltip: info.archived ? 'Restaurer' : 'Archiver' },
          { iconPath: new vscode.ThemeIcon('trash'), tooltip: 'Supprimer' },
        ],
      }));
      if (hasMore) items.push({ label: '$(ellipsis) Plus…', more: true, alwaysShow: true });
      if (!items.length) items.push({ label: 'Aucune session pour ce projet', alwaysShow: true });
      qp.items = items;
    };

    const reload = async (reset: boolean) => {
      qp.busy = true;
      if (reset) { loaded = 0; all = []; }
      const page = await listSessions(cwd, loaded, PAGE + 1, showArchived);
      hasMore = page.length > PAGE;
      all = all.concat(page.slice(0, PAGE));
      loaded += Math.min(page.length, PAGE);
      qp.busy = false;
      draw();
    };

    qp.onDidTriggerButton(async (b) => { if (b === toggle) { showArchived = !showArchived; await reload(true); } });
    qp.onDidTriggerItemButton(async (e) => {
      const info = e.item.info;
      if (!info) return;
      const tip = e.button.tooltip;
      if (tip === 'Ouvrir dans un nouvel onglet') { resolve({ info, newTab: true }); qp.dispose(); return; }
      if (tip === 'Renommer') {
        const t = await vscode.window.showInputBox({ prompt: 'Nouveau nom', value: info.title });
        if (t?.trim()) await renameSession(info.id, t.trim());
      } else if (tip === 'Supprimer') {
        const ok = await vscode.window.showWarningMessage(`Supprimer définitivement « ${info.title} » ?`, { modal: true }, 'Supprimer');
        if (ok) await deleteSession(info);
      } else await setArchived(info.id, !info.archived);
      await reload(true);
      qp.show();
    });
    qp.onDidAccept(() => {
      const it = qp.selectedItems[0];
      if (it?.more) { void reload(false); return; }
      if (it?.info) { resolve({ info: it.info, newTab: false }); qp.dispose(); }
    });
    qp.onDidHide(() => { resolve(undefined); qp.dispose(); });
    qp.show();
    void reload(true);
  });
}

export const loadFullSession = loadSession;

const STATUS: Record<McpStatus, { icon: string; text: string }> = {
  checking: { icon: 'sync~spin', text: 'vérification…' },
  connected: { icon: 'pass', text: 'connecté' },
  needs_auth: { icon: 'key', text: 'authentification requise' },
  failed: { icon: 'error', text: 'échec' },
  pending: { icon: 'loading~spin', text: 'en attente' },
};

type McpPick = vscode.QuickPickItem & { server?: McpServer; add?: boolean };

/** Serveurs MCP : liste avec statut, ajout (JSON) et suppression, comme la fenêtre du plugin JetBrains. */
export async function manageMcp(cli: string, cwd: string): Promise<void> {
  const qp = vscode.window.createQuickPick<McpPick>();
  qp.title = 'Serveurs MCP';
  qp.placeholder = 'Ajouter un serveur, ou voir les détails d\'un serveur';
  const refresh: vscode.QuickInputButton = { iconPath: new vscode.ThemeIcon('refresh'), tooltip: 'Actualiser les statuts' };
  qp.buttons = [refresh];
  let servers: McpServer[] = [];

  const draw = () => {
    const items: McpPick[] = [{ label: '$(add) Ajouter un serveur…', add: true, alwaysShow: true }];
    let lastScope = '';
    for (const s of servers) {
      if (s.scope !== lastScope) { lastScope = s.scope; items.push({ label: MCP_SCOPES[s.scope].title, kind: vscode.QuickPickItemKind.Separator }); }
      const st = STATUS[s.status];
      items.push({
        label: `$(${st.icon}) ${s.name}`,
        description: `${s.type ?? ''} · ${st.text}`,
        detail: s.target,
        server: s,
        buttons: MCP_SCOPES[s.scope].removable ? [{ iconPath: new vscode.ThemeIcon('trash'), tooltip: 'Supprimer' }] : [],
      });
    }
    qp.items = items;
  };

  const load = async () => {
    qp.busy = true;
    const configs = readConfigs(cwd);
    servers = configs; draw();
    servers = merge(configs, await list(cli, cwd));
    qp.busy = false;
    draw();
  };

  const addServer = async () => {
    const scopePick = await vscode.window.showQuickPick(
      (['user', 'project', 'local'] as McpScope[]).map((s) => ({ label: MCP_SCOPES[s].title, description: MCP_SCOPES[s].source, scope: s })),
      { title: 'Portée du serveur', ignoreFocusOut: true });
    if (!scopePick) return;
    const json = await vscode.window.showInputBox({
      title: 'Configuration JSON du serveur', ignoreFocusOut: true,
      prompt: 'Un serveur ({"command": …} ou {"url": …}), ou {"mcpServers": {…}}',
      placeHolder: '{"mcpServers": {"nom": {"command": "npx", "args": ["…"]}}}',
    });
    if (!json) return;
    let n = normalize(json, '');
    if (n.error?.startsWith('Donne un nom')) {
      const name = await vscode.window.showInputBox({ title: 'Nom du serveur', ignoreFocusOut: true, prompt: 'Lettres, chiffres, « - », « _ » et « . »' });
      if (!name) return;
      n = normalize(json, name);
    }
    if (n.error) { void vscode.window.showErrorMessage(n.error); return; }
    for (const [name, cfg] of n.servers) {
      const [ok, out] = await add(cli, cwd, name, cfg, scopePick.scope);
      void (ok ? vscode.window.showInformationMessage(`${name} : ${out}`) : vscode.window.showErrorMessage(`${name} : ${out}`));
    }
  };

  qp.onDidTriggerButton(() => void load());
  qp.onDidTriggerItemButton(async (e) => {
    const s = e.item.server;
    if (!s) return;
    const ok = await vscode.window.showWarningMessage(`Supprimer le serveur « ${s.name} » (portée ${MCP_SCOPES[s.scope].title}) ?`, { modal: true }, 'Supprimer');
    if (!ok) return;
    const [done, out] = await remove(cli, cwd, s.name, s.scope);
    if (!done) void vscode.window.showErrorMessage(out);
    await load();
  });
  qp.onDidAccept(async () => {
    const it = qp.selectedItems[0];
    if (it?.add) { qp.hide(); await addServer(); void manageMcp(cli, cwd); return; }
    const s = it?.server;
    if (!s) return;
    const lines = [`Portée : ${MCP_SCOPES[s.scope].title}`, s.type && `Type : ${s.type}`, s.target && `Cible : ${s.target}`,
      Object.keys(s.headers).length && `En-têtes : ${Object.entries(s.headers).map(([k, v]) => `${k} : ${mask(v)}`).join(', ')}`,
      Object.keys(s.env).length && `Variables : ${Object.keys(s.env).map((k) => `${k}=${mask('x')}`).join(', ')}`].filter(Boolean);
    void vscode.window.showInformationMessage(`${s.name}\n${lines.join('\n')}`, { modal: true });
  });
  qp.onDidHide(() => qp.dispose());
  qp.show();
  await load();
}
