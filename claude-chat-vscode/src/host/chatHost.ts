import * as fs from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';
import * as vscode from 'vscode';
import type { ChangedFile } from '../core/changes';
import { LIMITS } from '../core/constants';
import type { RemoteBridge } from '../core/remote';
import type { HostMsg, WebMsg } from '../protocol';
import { ChatSession, type SessionEnv } from './chatSession';
import type { ChangesView } from './changesView';
import { ProposalFs, type BeforeProvider } from './virtualDocs';
import { webviewHtml } from './webviewHtml';

/** Services partagés par toutes les conversations (vue latérale et onglets). */
export interface Services {
  context: vscode.ExtensionContext;
  remote: RemoteBridge;
  proposals: ProposalFs;
  before: BeforeProvider;
  changes: ChangesView;
  /** Une conversation vient de prendre le focus : les vues globales (fichiers modifiés) la suivent. */
  activate(host: ChatHost): void;
  changesChanged(host: ChatHost, files: ChangedFile[]): void;
  showSessions(host: ChatHost): void;
  cliPath(): string;
}

const cfg = () => vscode.workspace.getConfiguration('claudeChat');

/** Dossier du projet : le premier dossier ouvert, sinon le dossier personnel. */
export function projectDir(): string {
  return vscode.workspace.workspaceFolders?.[0]?.uri.fsPath ?? os.homedir();
}

export function cliPath(): string {
  const set = cfg().get<string>('cliPath', '').trim();
  if (set) return set;
  const local = path.join(os.homedir(), '.local', 'bin', 'claude');
  try { fs.accessSync(local, fs.constants.X_OK); return local; } catch { return 'claude'; }
}

let lastEditor: vscode.TextEditor | undefined;
export function trackEditors(ctx: vscode.ExtensionContext, onChange: () => void): void {
  lastEditor = vscode.window.activeTextEditor;
  ctx.subscriptions.push(
    vscode.window.onDidChangeActiveTextEditor((e) => { if (e) lastEditor = e; onChange(); }),
    vscode.window.onDidChangeTextEditorSelection(() => onChange()),
  );
}

/** Éditeur à prendre comme contexte : l'éditeur actif, ou le dernier utilisé s'il est encore visible. */
export function currentEditor(): vscode.TextEditor | undefined {
  const a = vscode.window.activeTextEditor;
  if (a && a.document.uri.scheme === 'file') return a;
  return lastEditor && vscode.window.visibleTextEditors.includes(lastEditor) && lastEditor.document.uri.scheme === 'file' ? lastEditor : undefined;
}

const MAX_ATTACH_CHARS = LIMITS.maxAttachChars;

/** Une conversation reliée à une webview (vue latérale ou onglet). */
export class ChatHost {
  readonly session: ChatSession;
  readonly id = Math.random().toString(36).slice(2, 8);
  private webview?: vscode.Webview;
  private setTitleFn: (t: string) => void = () => {};
  private diffUris = new Map<string, vscode.Uri>();
  private disposables: vscode.Disposable[] = [];
  /** Nombre de fois où la webview a annoncé qu'elle était prête (prouve que son script s'est exécuté). */
  readyCount = 0;

  constructor(private svc: Services, readonly kind: 'view' | 'panel') {
    this.session = new ChatSession(this.makeEnv());
  }

  /** Relie (ou re-relie après un rechargement) une webview. */
  bind(webview: vscode.Webview, setTitle: (t: string) => void): void {
    this.disposables.forEach((d) => d.dispose());
    this.webview = webview;
    this.setTitleFn = setTitle;
    webview.options = { enableScripts: true, localResourceRoots: [this.svc.context.extensionUri] };
    webview.html = webviewHtml(webview, this.svc.context.extensionUri, (...p) => webview.asWebviewUri(vscode.Uri.joinPath(this.svc.context.extensionUri, ...p)));
    this.disposables = [webview.onDidReceiveMessage((m: WebMsg) => {
      if (m.t === 'ready') this.readyCount++;
      this.session.onMessage(m).catch((e) => console.error('Claude Chat :', e));
    })];
    setTitle(this.session.session.title);
  }

  /** Remet une conversation vide ou une session reprise dans cet hôte. */
  dispose(): void {
    this.disposables.forEach((d) => d.dispose());
    this.session.dispose();
  }

  private post(m: HostMsg): void { void this.webview?.postMessage(m); }

  private makeEnv(): SessionEnv {
    const svc = this.svc;
    const self = this;
    return {
      cwd: projectDir,
      config: <T>(key: string, fallback: T) => cfg().get<T>(key, fallback),
      getState: <T>(key: string, fallback: T) => svc.context.globalState.get<T>(`ccg.${key}`, fallback),
      setState: (key, value) => { void svc.context.globalState.update(`ccg.${key}`, value); },
      post: (m) => this.post(m),
      notify: (kind, msg) => {
        void (kind === 'info' ? vscode.window.showInformationMessage(msg) : kind === 'warn' ? vscode.window.showWarningMessage(msg) : vscode.window.showErrorMessage(msg));
      },
      confirm: async (message, yes) => (await vscode.window.showWarningMessage(message, { modal: true }, yes)) === yes,
      inputBox: (prompt, value) => Promise.resolve(vscode.window.showInputBox({ prompt, value })),
      editorContext() {
        const ed = currentEditor();
        if (!ed) return undefined;
        const rel = vscode.workspace.asRelativePath(ed.document.uri, false);
        const sel = ed.selection;
        if (!sel.isEmpty) {
          return { rel, label: path.basename(rel), block: `<selection path="${rel}" lines="${sel.start.line + 1}-${sel.end.line + 1}">\n${ed.document.getText(sel)}\n</selection>` };
        }
        const text = ed.document.getText();
        if (text.length > MAX_ATTACH_CHARS) { void vscode.window.showWarningMessage(`${path.basename(rel)} est trop volumineux : non joint.`); return undefined; }
        return { rel, label: path.basename(rel), block: `<file path="${rel}">\n${text}\n</file>` };
      },
      editorLabel() {
        const ed = currentEditor();
        if (!ed) return '';
        return path.basename(ed.document.uri.fsPath) + (ed.selection.isEmpty ? '' : ' (sélection)');
      },
      pickFiles: async () => (await vscode.window.showOpenDialog({ canSelectMany: true, openLabel: 'Joindre', defaultUri: vscode.Uri.file(projectDir()) }))?.map((u) => u.fsPath) ?? [],
      async showDiff(reqId, filePath, original, proposed) {
        const right = ProposalFs.uri(reqId, filePath);
        svc.proposals.put(right, proposed);
        self.diffUris.set(reqId, right);
        const left = svc.before.set(`proposal-${reqId}`, path.basename(filePath), original);
        await vscode.commands.executeCommand('vscode.diff', left, right, `Claude : ${path.basename(filePath)} (la partie droite est modifiable)`, { preview: false, preserveFocus: false });
      },
      async readEdited(reqId) {
        const uri = self.diffUris.get(reqId);
        if (!uri) return undefined;
        const doc = vscode.workspace.textDocuments.find((d) => d.uri.toString() === uri.toString());
        if (doc?.isDirty) await doc.save();
        return svc.proposals.text(uri);
      },
      closeDiff(reqId) {
        const uri = self.diffUris.get(reqId);
        if (!uri) return;
        self.diffUris.delete(reqId);
        void (async () => {
          const doc = vscode.workspace.textDocuments.find((d) => d.uri.toString() === uri.toString());
          if (doc?.isDirty) await doc.save(); // sans cela, fermer l'onglet demanderait d'enregistrer
          for (const g of vscode.window.tabGroups.all) {
            for (const t of g.tabs) {
              if (t.input instanceof vscode.TabInputTextDiff && t.input.modified.toString() === uri.toString()) await vscode.window.tabGroups.close(t);
            }
          }
          svc.proposals.drop(uri);
          svc.before.clear(`proposal-${reqId}`);
        })();
      },
      async applyFile(filePath, text) {
        const uri = vscode.Uri.file(filePath);
        try {
          const doc = await vscode.workspace.openTextDocument(uri);
          const edit = new vscode.WorkspaceEdit();
          edit.replace(uri, new vscode.Range(0, 0, doc.lineCount, 0), text);
          await vscode.workspace.applyEdit(edit);
          await doc.save();
        } catch {
          await vscode.workspace.fs.createDirectory(vscode.Uri.file(path.dirname(filePath)));
          await vscode.workspace.fs.writeFile(uri, new TextEncoder().encode(text));
        }
      },
      refreshFiles() { /* VS Code surveille le disque : les documents ouverts se rechargent seuls */ },
      openFile(abs, line) {
        const sel = line ? new vscode.Range(Math.max(0, line - 1), 0, Math.max(0, line - 1), 0) : undefined;
        void vscode.window.showTextDocument(vscode.Uri.file(abs), { selection: sel, preview: false }).then(undefined, () => {
          void vscode.window.showWarningMessage(`Fichier introuvable : ${abs}`);
        });
      },
      openChanges(files, forget) {
        void forget;
        void vscode.commands.executeCommand('claudeChat.changes.focus');
        void svc.changes.openAll(files);
      },
      setTitle: (t) => this.setTitleFn(t),
      onChanges: (files) => svc.changesChanged(this, files),
      remote: svc.remote,
      openSettings: () => { void vscode.commands.executeCommand('workbench.action.openSettings', 'claudeChat'); },
      openMcp: () => { void vscode.commands.executeCommand('claudeChat.mcp'); },
      projectFiles: async () => {
        const uris = await vscode.workspace.findFiles('**/*', '**/{node_modules,.git,build,dist,out,target,.gradle,.idea,__pycache__}/**', 8000);
        return uris.map((u) => vscode.workspace.asRelativePath(u, false)).sort();
      },
      showSessions: () => svc.showSessions(this),
      cliPath: () => svc.cliPath(),
    };
  }

  /** Le fichier courant ou la sélection ont changé : met à jour la pastille « contexte ». */
  editorChanged(): void { this.session.onEditorChanged(); }
}
