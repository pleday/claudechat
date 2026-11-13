import * as path from 'node:path';
import * as vscode from 'vscode';

export const PROPOSAL_SCHEME = 'claudechat-proposal';
export const BEFORE_SCHEME = 'claudechat-before';

/**
 * Partie « proposée par Claude » d'un diff d'approbation : un fichier en mémoire, modifiable (et enregistrable) dans
 * l'éditeur de comparaison de VS Code. Ce que l'utilisateur y laisse est appliqué à la place de la proposition.
 */
export class ProposalFs implements vscode.FileSystemProvider {
  private files = new Map<string, { data: Uint8Array; mtime: number }>();
  private emitter = new vscode.EventEmitter<vscode.FileChangeEvent[]>();
  onDidChangeFile = this.emitter.event;

  static uri(reqId: string, filePath: string): vscode.Uri {
    return vscode.Uri.from({ scheme: PROPOSAL_SCHEME, path: `/${reqId}/${path.basename(filePath)}` });
  }

  put(uri: vscode.Uri, text: string): void {
    const existed = this.files.has(uri.path);
    this.files.set(uri.path, { data: new TextEncoder().encode(text), mtime: Date.now() });
    this.emitter.fire([{ type: existed ? vscode.FileChangeType.Changed : vscode.FileChangeType.Created, uri }]);
  }

  text(uri: vscode.Uri): string | undefined {
    const f = this.files.get(uri.path);
    return f && new TextDecoder().decode(f.data);
  }

  drop(uri: vscode.Uri): void {
    if (this.files.delete(uri.path)) this.emitter.fire([{ type: vscode.FileChangeType.Deleted, uri }]);
  }

  watch(): vscode.Disposable { return new vscode.Disposable(() => {}); }
  stat(uri: vscode.Uri): vscode.FileStat {
    const f = this.files.get(uri.path);
    if (!f) throw vscode.FileSystemError.FileNotFound(uri);
    return { type: vscode.FileType.File, ctime: f.mtime, mtime: f.mtime, size: f.data.length };
  }
  readDirectory(): [string, vscode.FileType][] { return []; }
  createDirectory(): void {}
  readFile(uri: vscode.Uri): Uint8Array {
    const f = this.files.get(uri.path);
    if (!f) throw vscode.FileSystemError.FileNotFound(uri);
    return f.data;
  }
  writeFile(uri: vscode.Uri, content: Uint8Array): void {
    this.files.set(uri.path, { data: content, mtime: Date.now() });
    this.emitter.fire([{ type: vscode.FileChangeType.Changed, uri }]);
  }
  delete(uri: vscode.Uri): void { this.drop(uri); }
  rename(): void { throw vscode.FileSystemError.NoPermissions(); }
}

/** Contenu d'un fichier « avant Claude » (comparaison des fichiers modifiés) : lecture seule, servi depuis la mémoire. */
export class BeforeProvider implements vscode.TextDocumentContentProvider {
  private contents = new Map<string, string>();
  private emitter = new vscode.EventEmitter<vscode.Uri>();
  onDidChange = this.emitter.event;

  /** URI d'un contenu : `owner` distingue les conversations, `rel` est le chemin relatif (donne la coloration syntaxique). */
  set(owner: string, rel: string, text: string): vscode.Uri {
    const uri = vscode.Uri.from({ scheme: BEFORE_SCHEME, path: `/${owner}/${rel}` });
    this.contents.set(uri.path, text);
    this.emitter.fire(uri);
    return uri;
  }

  clear(owner: string): void {
    for (const k of [...this.contents.keys()]) if (k.startsWith(`/${owner}/`)) this.contents.delete(k);
  }

  provideTextDocumentContent(uri: vscode.Uri): string { return this.contents.get(uri.path) ?? ''; }
}
