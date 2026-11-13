import * as path from 'node:path';
import * as vscode from 'vscode';
import type { ChangedFile } from '../core/changes';
import type { BeforeProvider } from './virtualDocs';

/** Vue « Fichiers modifiés par Claude » : un clic ouvre la comparaison (état avant Claude contre état actuel). */
export class ChangesView implements vscode.TreeDataProvider<ChangedFile> {
  private files: ChangedFile[] = [];
  private owner = 'none';
  private emitter = new vscode.EventEmitter<void>();
  onDidChangeTreeData = this.emitter.event;

  constructor(private before: BeforeProvider) {}

  set(owner: string, files: ChangedFile[]): void {
    this.owner = owner; this.files = files;
    this.emitter.fire();
  }

  get count(): number { return this.files.length; }

  getTreeItem(f: ChangedFile): vscode.TreeItem {
    const item = new vscode.TreeItem(path.basename(f.rel), vscode.TreeItemCollapsibleState.None);
    const dir = path.dirname(f.rel);
    const stat = f.status === 'new' ? `nouveau  +${f.added}` : f.status === 'deleted' ? `supprimé  −${f.removed}` : `+${f.added} −${f.removed}`;
    item.description = `${dir === '.' ? '' : `${dir}  `}${stat}`;
    item.resourceUri = vscode.Uri.file(f.path);
    item.iconPath = new vscode.ThemeIcon(f.status === 'new' ? 'diff-added' : f.status === 'deleted' ? 'diff-removed' : 'diff-modified');
    item.tooltip = `${f.rel} — ${stat}`;
    item.command = { command: 'claudeChat.openChange', title: 'Voir les modifications', arguments: [f] };
    return item;
  }

  getChildren(): ChangedFile[] { return this.files; }

  /** Ressources (libellé, avant, après) d'un fichier pour les éditeurs de comparaison. */
  resources(f: ChangedFile): [vscode.Uri, vscode.Uri, vscode.Uri] {
    const file = vscode.Uri.file(f.path);
    const left = this.before.set(this.owner, f.rel, f.before ?? '');
    const right = f.status === 'deleted' ? this.before.set(this.owner, `${f.rel}.supprimé`, '') : file;
    return [file, left, right];
  }

  async openOne(f: ChangedFile): Promise<void> {
    const [, left, right] = this.resources(f);
    await vscode.commands.executeCommand('vscode.diff', left, right, `${f.rel} (avant Claude ↔ actuel)`);
  }

  /** Tous les fichiers dans un éditeur de comparaison multiple. */
  async openAll(files: ChangedFile[]): Promise<void> {
    if (!files.length) return;
    if (files.length === 1) { await this.openOne(files[0]); return; }
    const res = files.map((f) => this.resources(f));
    await vscode.commands.executeCommand('vscode.changes', 'Fichiers modifiés par Claude', res);
  }
}
