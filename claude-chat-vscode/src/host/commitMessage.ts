import * as vscode from 'vscode';
import { oneShot } from '../core/cli';
import { COMMIT_PROMPT, LIMITS } from '../core/constants';

interface GitRepo { rootUri: vscode.Uri; inputBox: { value: string }; diff(cached?: boolean): Promise<string> }
interface GitApi { repositories: GitRepo[]; getRepository(uri: vscode.Uri): GitRepo | null }

/** Nettoie la réponse : retire d'éventuelles clôtures de code. */
export function cleanCommitMessage(raw: string): string {
  return raw.trim().replace(/^```[a-z]*\n?/, '').replace(/```$/, '').trim();
}

/** Bouton « Générer le message de commit » dans la barre du contrôle de code source. */
export async function generateCommitMessage(cli: string, model: string, scm?: { rootUri?: vscode.Uri }): Promise<void> {
  const git = vscode.extensions.getExtension<{ getAPI(v: number): GitApi }>('vscode.git')?.exports.getAPI(1);
  if (!git) { void vscode.window.showWarningMessage('L\'extension Git de VS Code est indisponible.'); return; }
  const repo = (scm?.rootUri && git.getRepository(scm.rootUri)) || git.repositories[0];
  if (!repo) { void vscode.window.showWarningMessage('Aucun dépôt git ouvert.'); return; }
  await vscode.window.withProgress({ location: vscode.ProgressLocation.Notification, title: 'Claude : message de commit', cancellable: true }, async (_p, token) => {
    // fichiers indexés s'il y en a, sinon tout ce qui est modifié
    let diff = await repo.diff(true);
    if (!diff.trim()) diff = await repo.diff(false);
    if (!diff.trim()) { void vscode.window.showWarningMessage('Aucun changement à décrire.'); return; }
    if (diff.length > LIMITS.commitDiffMaxChars) diff = diff.slice(0, LIMITS.commitDiffMaxChars) + '\n[... diff tronqué ...]';
    const ctl = new AbortController();
    token.onCancellationRequested(() => ctl.abort());
    try {
      const out = await oneShot(cli, repo.rootUri.fsPath, model, COMMIT_PROMPT + diff, ctl.signal);
      const msg = cleanCommitMessage(out);
      if (msg && !token.isCancellationRequested) repo.inputBox.value = msg;
    } catch (e) {
      void vscode.window.showErrorMessage(`Génération impossible : ${(e as Error).message}`);
    }
  });
}
