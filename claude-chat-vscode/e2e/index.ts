// Exécuté DANS l'extension host de VS Code (voir run.mjs) : vérifie l'activation, les commandes et le chargement du webview.
import * as assert from 'node:assert';
import * as fs from 'node:fs';
import * as path from 'node:path';
import * as vscode from 'vscode';
import type { TestApi } from '../src/host/extension';

const pkg = JSON.parse(fs.readFileSync(path.join(__dirname, '..', 'package.json'), 'utf8'));
const until = async (cond: () => boolean, what: string, ms = 20_000) => {
  const t0 = Date.now();
  while (!cond()) {
    if (Date.now() - t0 > ms) throw new Error(`délai dépassé : ${what}`);
    await new Promise((r) => setTimeout(r, 100));
  }
};

const tests: Array<[string, () => Promise<void>]> = [];
const test = (name: string, fn: () => Promise<void>) => tests.push([name, fn]);

let api: TestApi;

test('l\'extension s\'active', async () => {
  const ext = vscode.extensions.getExtension('pleday.claude-chat-vscode');
  assert.ok(ext, 'extension introuvable');
  api = (await ext.activate()) as TestApi;
  assert.ok(api.hosts);
});

test('toutes les commandes du manifeste sont enregistrées', async () => {
  const all = new Set(await vscode.commands.getCommands(true));
  const missing = pkg.contributes.commands.map((c: { command: string }) => c.command).filter((c: string) => !all.has(c));
  assert.deepStrictEqual(missing, []);
});

test('la vue latérale charge son webview (le script de l\'interface s\'exécute)', async () => {
  await vscode.commands.executeCommand('claudeChat.chat.focus');
  await until(() => api.hosts().length > 0 && api.hosts()[0].readyCount > 0, 'webview prête (message « ready »)');
});

test('un onglet de conversation : message, réponse streamée par le faux claude', async () => {
  await vscode.commands.executeCommand('claudeChat.newTab');
  await until(() => api.hosts().length === 2 && api.hosts()[1].readyCount > 0, 'onglet prêt');
  const s = api.hosts()[1].session;
  await s.onMessage({ t: 'send', text: 'Dis bonjour' });
  await until(() => !s.isStreaming && s.session.entries.length === 2, 'réponse', 30_000);
  assert.deepStrictEqual(s.session.entries.map((e) => [e.kind, e.text]), [['user', 'Dis bonjour'], ['text', 'Bonjour de la CLI']]);
  assert.strictEqual(s.session.id, 'e2e-session');
  const panelTab = vscode.window.tabGroups.all.flatMap((g) => g.tabs).find((t) => t.label === 'Dis bonjour');
  assert.ok(panelTab, `le titre de l'onglet doit suivre la conversation (onglets : ${vscode.window.tabGroups.all.flatMap((g) => g.tabs).map((t) => t.label)})`);
});

test('commandes de l\'éditeur : la sélection est envoyée à la conversation', async () => {
  const project = process.env.CCG_E2E_PROJECT!;
  const doc = await vscode.workspace.openTextDocument(path.join(project, 'hello.txt'));
  const ed = await vscode.window.showTextDocument(doc);
  ed.selection = new vscode.Selection(0, 0, 0, 7);
  const before = api.hosts().length;
  await vscode.commands.executeCommand('claudeChat.sendSelection');
  assert.strictEqual(api.hosts().length, before); // aucun nouvel onglet : la conversation active est réutilisée
});

test('la liste des sessions s\'ouvre sans erreur', async () => {
  const p = vscode.commands.executeCommand('claudeChat.sessions');
  await new Promise((r) => setTimeout(r, 800));
  await vscode.commands.executeCommand('workbench.action.closeQuickOpen');
  await Promise.race([p, new Promise((r) => setTimeout(r, 2000))]);
});

test('les réglages sont déclarés avec leurs valeurs par défaut', async () => {
  const c = vscode.workspace.getConfiguration('claudeChat');
  assert.strictEqual(c.get('permissionMode'), 'manual');
  assert.strictEqual(c.get('busyMode'), 'refine');
  assert.strictEqual(c.get('remote.enabled'), false);
});

export async function run(): Promise<void> {
  let failed = 0;
  for (const [name, fn] of tests) {
    try { await fn(); console.log(`  ✓ ${name}`); } catch (e) { failed++; console.error(`  ✗ ${name}\n    ${(e as Error).message}`); }
  }
  if (failed) throw new Error(`${failed} test(s) en échec`);
}
