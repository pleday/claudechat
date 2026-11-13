import * as fs from 'node:fs';
import * as path from 'node:path';
import {
  CliTurn, contextUsageOnce, fetchInit, probeQuota, type ContentBlock, type Json, type PermissionRequest, type QuotaWindow, type RateLimits,
  type TurnListener, type TurnOptions,
} from '../core/cli';
import { ChangeTracker, FsBaseline, GENERATED_DIRS, type ChangedFile } from '../core/changes';
import { DEFAULT_MODEL, LIMITS } from '../core/constants';
import { fmtTokens, isAlias, type ContextUsage } from '../core/cliInfo';
import { resolveMention } from '../core/mentionPath';
import { ModelCatalog } from '../core/models';
import type { RemoteBridge, RemoteHandle, RemoteSink } from '../core/remote';
import { listSessions, liveTitle, renameSession as storeRename, DEFAULT_TITLE, type Entry, type Kind, type Session } from '../core/sessions';
import { SlashCatalog } from '../core/slashCatalog';
import {
  busyWarning, describeTool, EDIT_TOOLS, EFFORTS, expandSlash, filePathOf, formatDuration, Fuzzy, isCommandTool, Mentions, moveItem, Quota,
  QuotaGuard, toolSummary,
} from '../core/textTools';
import * as video from '../core/video';
import type { Attachment, HostMsg, InteractionView, UiState, UsageReport, WebMsg } from '../protocol';

/** Tout ce dont une conversation a besoin du monde extérieur (VS Code, disque…) : substituable dans les tests. */
export interface SessionEnv {
  cwd(): string;
  /** Réglage `claudeChat.<key>`. */
  config<T>(key: string, fallback: T): T;
  /** Mémoire persistante propre à l'utilisateur (quotas, derniers choix). */
  getState<T>(key: string, fallback: T): T;
  setState(key: string, value: unknown): void;
  post(m: HostMsg): void;
  notify(kind: 'info' | 'warn' | 'error', msg: string): void;
  /** Confirmation modale ; true si l'utilisateur accepte. */
  confirm(message: string, yes: string): Promise<boolean>;
  /** Fichier de l'éditeur (ou sa sélection) à joindre au message. */
  editorContext(): { rel: string; label: string; block: string } | undefined;
  /** Nom du fichier courant, pour la pastille « contexte » ; vide si aucun. */
  editorLabel(): string;
  pickFiles(): Promise<string[]>;
  /** Saisie d'une ligne de texte (renommer) ; undefined si annulé. */
  inputBox(prompt: string, value: string): Promise<string | undefined>;
  /** Ouvre le diff « actuel / proposé » ; la partie proposée est modifiable. */
  showDiff(reqId: string, filePath: string, original: string, proposed: string): Promise<void>;
  /** Contenu actuel de la partie proposée (éventuellement modifiée par l'utilisateur) ; undefined si le diff n'a pas été ouvert. */
  readEdited(reqId: string): Promise<string | undefined>;
  closeDiff(reqId: string): void;
  /** Écrit un fichier via l'éditeur (le document ouvert reste synchronisé). */
  applyFile(filePath: string, text: string): Promise<void>;
  /** Les fichiers ont changé hors de l'éditeur (Bash…) : à recharger. */
  refreshFiles(paths: string[] | 'all'): void;
  openFile(abs: string, line?: number): void;
  openChanges(files: ChangedFile[], forget: () => void): void;
  setTitle(title: string): void;
  onChanges(files: ChangedFile[]): void;
  remote: RemoteBridge;
  openSettings(): void;
  openMcp(): void;
  projectFiles(): Promise<string[]>;
  showSessions(): void;
  cliPath(): string;
}

interface Interaction {
  req: PermissionRequest;
  title: string;
  body: string;
  options: string[];
  freeText: boolean;
  resolve: (choice: string) => void;
  handle?: RemoteHandle;
  done: boolean;
  id: string;
}

const MAX_RESUMES = LIMITS.maxAutoResumes;
const MAX_ATTACH_CHARS = LIMITS.maxAttachChars;
const IMAGE_MEDIA: Record<string, string> = { png: 'image/png', jpg: 'image/jpeg', jpeg: 'image/jpeg', gif: 'image/gif', webp: 'image/webp' };

interface ImageAttachment { name: string; media: string; data: string }
interface FileAttachment { rel: string; block: string }

/**
 * Une conversation : état, processus de la CLI, approbations, file d'attente, fichiers modifiés, quotas.
 * L'interface (webview) n'est qu'un afficheur : tout passe par {@link onMessage} et `env.post`.
 */
export class ChatSession implements RemoteSink {
  session: Session = { title: DEFAULT_TITLE, entries: [] };
  readonly catalog = new ModelCatalog();

  private streaming = false;
  private interrupted = false;
  private openKind?: Kind;
  private generation = 0;
  private turn?: CliTurn;
  private idleTurn?: CliTurn;
  private turnStart = 0;
  private lastDurationMs = 0;
  private liveCtx = 0;
  private liveOut = 0;
  private usageIn = 0;
  private usageOut = 0;
  private costUsd = 0;
  private activeModel?: string;
  private contextWindows = new Map<string, number>();
  private lastLimits?: RateLimits;
  private quotaError: string | null = null;
  private noQuotaLastTurn = false;

  private selectedModel: string;
  private selectedEffort: string;
  private selectedMode: string;
  private busyMode: 'refine' | 'queue';
  private contextOn = true;
  private skipAttachOnce = false;
  private forkNext = false;
  private allowAll = false;
  private customTitle?: string;
  private suggestion: string | null = null;
  private queue: string[] = [];
  private files: FileAttachment[] = [];
  private images: ImageAttachment[] = [];
  private refinedOriginals = new Map<string, string>();

  private current?: Interaction;
  private pending: Interaction[] = [];
  private seq = 0;
  private proposed = new Map<string, string>();

  private remoteOn = false;
  private autoResumeOn: boolean;
  private resumeAt = 0;
  private resumeAttempts = 0;
  private resumeTimer?: NodeJS.Timeout;
  private titleTimer?: NodeJS.Timeout;
  private titleChecks = 0;
  private tickTimer?: NodeJS.Timeout;
  private flushTimer?: NodeJS.Timeout;
  private dirty = new Set<number>();
  private changeTimer?: NodeJS.Timeout;
  private refreshTimer?: NodeJS.Timeout;
  private dirtyPaths = new Set<string>();
  private dirtyAll = false;
  private disposed = false;

  private tracker: ChangeTracker;
  private baseline: FsBaseline;
  private changeChain: Promise<unknown> = Promise.resolve();
  private commandStarts = new Map<string, number>();
  private pendingScan?: [number, number];
  changedFiles: ChangedFile[] = [];

  constructor(private env: SessionEnv) {
    this.tracker = new ChangeTracker(() => this.env.cwd());
    this.baseline = new FsBaseline(() => this.env.cwd(), (n) => GENERATED_DIRS.has(n));
    this.selectedModel = this.catalog.resolve(env.getState('model', env.config('model', DEFAULT_MODEL)));
    this.selectedEffort = env.getState('effort', env.config('effort', 'auto'));
    this.selectedMode = env.getState('permissionMode', env.config('permissionMode', 'manual'));
    this.busyMode = env.getState<string>('busyMode', env.config<string>('busyMode', 'refine')) === 'queue' ? 'queue' : 'refine';
    this.autoResumeOn = env.config('autoResume', false);
    this.remoteOn = false;
    this.applyRemote(env.config('remote.default', false));
    void this.loadCliInfo();
  }

  // ---------- Cycle de vie ----------

  dispose(): void {
    this.disposed = true;
    this.idleTurn?.kill(); this.idleTurn = undefined;
    this.abandonTurn();
    this.env.remote.unregister(this);
    for (const t of [this.resumeTimer, this.titleTimer, this.tickTimer, this.flushTimer, this.changeTimer, this.refreshTimer]) clearTimeout(t);
  }

  /** La webview vient de s'ouvrir (ou de se recharger) : lui renvoie tout. */
  sendAll(): void {
    this.post({ t: 'state', patch: this.fullState() });
    this.post({ t: 'entries', entries: this.session.entries });
  }

  private post(m: HostMsg): void { if (!this.disposed) this.env.post(m); }
  private patch(p: Partial<UiState>): void { this.post({ t: 'state', patch: p }); }

  private cwd(): string { return this.env.cwd(); }

  /** Commandes slash et modèles de la CLI (une requête `initialize`, sans appel au modèle). */
  private async loadCliInfo(): Promise<void> {
    const init = await fetchInit(this.env.cliPath(), this.cwd()).catch(() => ({ commands: [], models: [] }));
    if (this.disposed) return;
    this.catalog.update(init.models);
    this.slash = SlashCatalog.build(init.commands);
    this.patch({ slash: this.slash, ...this.controlsState() });
  }

  private slash = SlashCatalog.build([]);

  // ---------- État envoyé à l'interface ----------

  private controlsState(): Partial<UiState> {
    const efforts = this.effortChoices(this.selectedModel);
    return {
      mode: this.selectedMode,
      model: this.selectedModel,
      effort: efforts.includes(this.selectedEffort) ? this.selectedEffort : 'auto',
      efforts,
      models: this.modelChoices(),
      busyMode: this.busyMode,
      contextOn: this.contextOn,
      remoteOn: this.remoteOn,
      autoResumeOn: this.autoResumeOn,
    };
  }

  private modelChoices(): UiState['models'] {
    const known = this.catalog.all();
    const extra = this.env.config('customModels', '').split(/[,\n]/).map((s) => s.trim()).filter(Boolean);
    const out = known.length
      ? known.map((m) => ({ id: m.id, name: m.name, description: m.description, isAlias: isAlias(m) }))
      : this.catalog.known().map(([id, name]) => ({ id, name, description: '', isAlias: true }));
    for (const id of extra) if (!out.some((m) => m.id === id)) out.push({ id, name: this.catalog.label(id), description: '', isAlias: false });
    if (!out.some((m) => m.id === this.selectedModel)) out.push({ id: this.selectedModel, name: this.catalog.label(this.selectedModel), description: '', isAlias: false });
    return out;
  }

  private effortChoices(model: string): string[] { return this.catalog.effortChoices(model, EFFORTS); }

  private fullState(): UiState {
    return {
      ...this.controlsState(),
      ...this.statusState(),
      ...this.quotaState(),
      queue: [...this.queue],
      changes: this.changesState(),
      suggestion: this.suggestion,
      attachments: this.attachmentViews(),
      interaction: this.interactionView(),
      title: this.session.title,
      slash: this.slash,
      canFork: this.session.id !== undefined && !this.streaming,
      forkPending: this.forkNext,
      contextFile: this.env.editorLabel(),
      projectName: path.basename(this.cwd()),
      cwd: this.cwd(),
    } as UiState;
  }

  private fmt(n: number): string { return n >= 1000 ? `${(n / 1000).toFixed(1)}k` : String(n); }

  private statusState(): Pick<UiState, 'streaming' | 'statusText' | 'statusTip' | 'resume' | 'ringPercent' | 'ringTip' | 'usageLine'> {
    const window = this.contextWindows.get(this.activeModel ?? this.selectedModel) ?? 200_000;
    const percent = Math.round((this.liveCtx * 100) / window);
    const model = this.activeModel ? this.catalog.label(this.activeModel) : undefined;
    let statusText: string;
    let resume: UiState['resume'] = null;
    if (this.resumeAt > 0) {
      const at = new Date(this.resumeAt);
      const hh = `${String(at.getHours()).padStart(2, '0')}:${String(at.getMinutes()).padStart(2, '0')}`;
      const left = Quota.remaining(this.resumeAt / 1000, Date.now() / 1000);
      resume = { at: hh, left };
      statusText = `⏸ Quota atteint — reprise automatique à ${hh} (dans ${left})`;
    } else if (this.streaming) {
      const sec = Math.floor((Date.now() - this.turnStart) / 1000);
      statusText = `Réponse en cours · ${Math.floor(sec / 60)}:${String(sec % 60).padStart(2, '0')} · contexte ${this.fmt(this.liveCtx)} · sortie ${this.fmt(this.liveOut)} tokens${model ? ` · ${model}` : ''}`;
    } else {
      statusText = [
        model && `${model} · contexte ${this.fmt(this.liveCtx)} tokens`,
        this.lastDurationMs > 0 && `réponse en ${formatDuration(this.lastDurationMs)}`,
      ].filter(Boolean).join(' · ');
    }
    return {
      streaming: this.streaming,
      statusText,
      statusTip: this.activeModel ? `Modèle utilisé : ${this.activeModel}` : '',
      resume,
      ringPercent: percent,
      ringTip: `Contexte : ${this.liveCtx.toLocaleString('fr-FR')} / ${window.toLocaleString('fr-FR')} tokens (${percent} %) — cliquer pour le détail`,
      usageLine: this.usageIn + this.usageOut > 0
        ? `↑ ${this.fmt(this.usageIn)} ↓ ${this.fmt(this.usageOut)}${this.costUsd > 0 ? ` · ${this.costUsd.toFixed(2)} $` : ''}` : '',
    };
  }

  private sendStatus(): void { this.patch({ ...this.statusState(), canFork: this.session.id !== undefined && !this.streaming }); }

  // ---------- Quotas ----------

  private fiveHourWindow(): QuotaWindow | undefined {
    const u = this.env.getState('rlFiveUtil', -1);
    return u >= 0 ? { utilization: u, resetsAt: this.env.getState('rlFiveReset', 0) } : undefined;
  }
  private weekWindow(): QuotaWindow | undefined {
    const u = this.env.getState('rlWeekUtil', -1);
    return u >= 0 ? { utilization: u, resetsAt: this.env.getState('rlWeekReset', 0) } : undefined;
  }

  /** Batterie = part restante de la fenêtre de 5 h. */
  private quotaState(): Pick<UiState, 'battery'> {
    const remaining = Quota.remainingPercent(this.fiveHourWindow(), Date.now() / 1000);
    return { battery: { remaining: remaining ?? null, tip: (remaining !== undefined ? `Session de 5 h : ${remaining} % restant` : 'Session de 5 h : pas encore mesurée') + ' — cliquer pour le détail' } };
  }

  private applyLimits(l: RateLimits): void {
    if (l.fiveHour) { this.env.setState('rlFiveUtil', l.fiveHour.utilization); this.env.setState('rlFiveReset', l.fiveHour.resetsAt); }
    if (l.sevenDay) { this.env.setState('rlWeekUtil', l.sevenDay.utilization); this.env.setState('rlWeekReset', l.sevenDay.resetsAt); }
    this.env.setState('rlAt', Date.now());
    this.quotaError = null;
    this.patch(this.quotaState());
  }

  private static probing = false;

  /** Mesure les quotas hors conversation (au démarrage, ou via « Actualiser »). */
  async probeQuota(): Promise<void> {
    if (ChatSession.probing) return;
    ChatSession.probing = true;
    this.quotaError = null;
    try {
      const r = await probeQuota(this.env.cliPath(), this.cwd());
      if (r.limits) this.applyLimits(r.limits); else this.quotaError = r.error ?? null;
    } finally { ChatSession.probing = false; }
    this.patch(this.quotaState());
  }

  private usageReport(): UsageReport {
    const now = Date.now() / 1000;
    const line = (title: string, w?: QuotaWindow) => {
      const used = Quota.percent(w, now) ?? null;
      let reset = 'pas encore mesuré';
      if (w && used !== null) {
        reset = w.resetsAt > now
          ? `réinitialisation dans ${Quota.remaining(w.resetsAt, now)} (${new Date(w.resetsAt * 1000).toLocaleString('fr-FR', { weekday: 'short', hour: '2-digit', minute: '2-digit' })})`
          : 'fenêtre réinitialisée';
      }
      return { title, used, reset };
    };
    const at = this.env.getState('rlAt', 0);
    return {
      five: line('Session de 5 h', this.fiveHourWindow()),
      week: line('Semaine (7 jours)', this.weekWindow()),
      model: this.catalog.display(this.activeModel ?? this.selectedModel),
      context: `${fmtTokens(this.liveCtx)} tokens`,
      tokens: `↑ ${fmtTokens(this.usageIn)} entrée · ↓ ${fmtTokens(this.usageOut)} sortie`,
      cost: this.costUsd > 0 ? `${this.costUsd.toFixed(2)} $` : '',
      error: this.quotaError,
      noQuota: this.noQuotaLastTurn,
      measured: at > 0 ? `Dernière mesure il y a ${Quota.remaining(now, at / 1000)}` : "Aucune mesure pour l'instant",
    };
  }

  // ---------- Messages de l'interface ----------

  async onMessage(m: WebMsg): Promise<void> {
    switch (m.t) {
      case 'ready': this.sendAll(); void this.probeQuotaIfStale(); break;
      case 'send': await this.send(m.text, m.mode ?? this.busyMode); break;
      case 'stop': this.stop(); break;
      case 'newSession': await this.newSession(); break;
      case 'sessions': this.env.showSessions(); break;
      case 'fork':
        if (this.session.id && !this.streaming) {
          this.forkNext = true;
          this.addEntry({ kind: 'notice', text: 'Fork : le prochain message démarre une nouvelle branche de cette conversation.' });
          this.patch({ forkPending: true });
        }
        break;
      case 'rename': await this.rename(await this.env.inputBox('Nom de la conversation', this.session.title)); break;
      case 'setMode': this.setMode(m.id); break;
      case 'setModel': this.selectModel(m.id); break;
      case 'setEffort': this.selectedEffort = m.id; this.saveChoices(); this.patch(this.controlsState()); break;
      case 'setBusyMode': this.busyMode = m.mode; this.env.setState('busyMode', m.mode); this.patch({ busyMode: m.mode }); break;
      case 'toggleContext': this.contextOn = !this.contextOn; this.patch({ contextOn: this.contextOn, contextFile: this.env.editorLabel() }); break;
      case 'toggleRemote': this.setRemote(!this.remoteOn); break;
      case 'toggleAutoResume': this.autoResumeOn = !this.autoResumeOn; if (!this.autoResumeOn) this.cancelResume(); this.patch({ autoResumeOn: this.autoResumeOn }); break;
      case 'interact': { const i = this.findInteraction(m.id); if (i) this.resolveLocal(i, m.choice); break; }
      case 'viewDiff': { const i = this.findInteraction(m.id); if (i) void this.showDiff(i.req); break; }
      case 'attachFiles': await this.addFiles(); break;
      case 'removeAttachment': this.removeAttachment(m.key); break;
      case 'pasteImage': this.images.push({ name: m.name, media: m.media, data: m.data }); this.sendAttachments(); break;
      case 'mention': this.post({ t: 'mention', seq: m.seq, results: await this.mentionResults(m.q) }); break;
      case 'checkFiles': this.post({ t: 'files', seq: m.seq, existing: this.existingFiles(m.paths) }); break;
      case 'openFile': this.openFile(m.spec); break;
      case 'openChanges': this.env.openChanges(this.changedFiles, () => this.clearChangedFiles()); break;
      case 'forgetChanges': this.clearChangedFiles(); break;
      case 'queueClear': this.queue = []; this.sendQueue(); break;
      case 'queueRemove': this.queue.splice(m.i, 1); this.sendQueue(); break;
      case 'queueMove': moveItem(this.queue, m.i, m.to); this.sendQueue(); break;
      case 'acceptSuggestion': if (this.suggestion) { this.post({ t: 'setInput', text: this.suggestion }); this.suggestion = null; this.patch({ suggestion: null }); } break;
      case 'dismissSuggestion': this.suggestion = null; this.patch({ suggestion: null }); break;
      case 'showContext': await this.showContextDetails(); break;
      case 'showUsage': this.post({ t: 'usage', report: this.usageReport() }); break;
      case 'refreshQuota': await this.probeQuota(); this.post({ t: 'usage', report: this.usageReport() }); break;
      case 'mcp': this.env.openMcp(); break;
      case 'settings': this.env.openSettings(); break;
      case 'cancelResume': this.cancelResume(); break;
    }
  }

  private async probeQuotaIfStale(): Promise<void> {
    const at = this.env.getState('rlAt', 0);
    if (Date.now() - at > LIMITS.quotaStaleMs) await this.probeQuota();
  }

  // ---------- Entrées du transcript ----------

  private addEntry(e: Omit<Entry, 'text'> & { text?: string }): Entry {
    const entry: Entry = { text: '', ...e };
    this.session.entries.push(entry);
    this.post({ t: 'add', entry });
    return entry;
  }

  private patchEntry(i: number, patch: Partial<Entry>): void { this.post({ t: 'patch', i, patch }); }

  /** Regroupe les mises à jour du texte en cours de réponse (une toutes les 40 ms au plus). */
  private scheduleFlush(): void {
    if (this.flushTimer) return;
    this.flushTimer = setTimeout(() => {
      this.flushTimer = undefined;
      for (const i of this.dirty) { const e = this.session.entries[i]; if (e) this.patchEntry(i, { text: e.text }); }
      this.dirty.clear();
    }, 40);
  }

  private flushNow(): void {
    clearTimeout(this.flushTimer); this.flushTimer = undefined;
    for (const i of this.dirty) { const e = this.session.entries[i]; if (e) this.patchEntry(i, { text: e.text }); }
    this.dirty.clear();
  }

  private append(kind: Kind, delta: string): void {
    const last = this.session.entries.at(-1);
    if (this.openKind === kind && last && last.kind === kind) {
      last.text += delta;
      this.dirty.add(this.session.entries.length - 1);
      this.scheduleFlush();
    } else {
      this.addEntry({ kind, text: delta, model: this.activeModel });
      this.openKind = kind;
    }
  }

  // ---------- Envoi ----------

  private async send(raw: string, mode: 'refine' | 'queue'): Promise<void> {
    const original = raw.trim();
    if (!original && !this.files.length && !this.images.length) return;
    if (this.streaming) {
      if (mode === 'refine') this.refine(original); else { this.queue.push(original); this.sendQueue(); }
      return;
    }
    await this.dispatch(original);
  }

  /** Précise la consigne en cours : injecté au prochain point d'arrêt de Claude, sans interruption. */
  private refine(original: string): void {
    const t = this.turn;
    if (!t || !this.streaming) { if (this.streaming) { this.queue.push(original); this.sendQueue(); } else void this.dispatch(original); return; }
    const text = expandSlash(original);
    const body = [...this.mentionAttachments(original).map((a) => a.block), text].join('\n\n');
    const entry = this.addEntry({ kind: 'user', text, note: '⚡ précision envoyée pendant la réponse' });
    this.refinedOriginals.set(body.trim(), original);
    if (!t.refine(body)) { // le tour vient de se terminer : on retombe sur la file
      const i = this.session.entries.indexOf(entry);
      if (i >= 0) { this.session.entries.splice(i, 1); this.post({ t: 'entries', entries: this.session.entries }); }
      this.refinedOriginals.delete(body.trim());
      if (this.streaming) { this.queue.push(original); this.sendQueue(); } else void this.dispatch(original);
    }
  }

  private async dispatch(original: string, fromPhone = false): Promise<void> {
    const text = expandSlash(original);
    const noAttach = this.skipAttachOnce;
    this.skipAttachOnce = false;
    const blocks: FileAttachment[] = [];
    if (this.contextOn && !noAttach) { const c = this.env.editorContext(); if (c) blocks.push({ rel: c.rel, block: c.block }); }
    blocks.push(...this.files);
    blocks.push(...this.mentionAttachments(original, fromPhone));
    const seen = new Set<string>();
    const filesUsed = blocks.filter((b) => (seen.has(b.rel) ? false : (seen.add(b.rel), true)));
    const images = [...this.images];
    this.files = []; this.images = []; this.sendAttachments();

    const content: ContentBlock[] = images.map((i) => ({ type: 'image', source: { type: 'base64', media_type: i.media, data: i.data } }));
    const body = [...filesUsed.map((f) => f.block), text].filter((s) => s.trim()).join('\n\n');
    content.push({ type: 'text', text: body.trim() ? body : '(pièce jointe)' });
    const attachedNames = [...filesUsed.map((f) => f.rel), ...images.map((i) => i.name)];
    const attached = attachedNames.length ? attachedNames.join(', ') : undefined;
    this.addEntry({ kind: 'user', text, attached });
    if (this.session.entries.filter((e) => e.kind === 'user').length === 1) {
      this.session.title = (original.split('\n').find((l) => l.trim()) ?? attached ?? 'Pièce jointe').slice(0, 60);
      this.env.setTitle(this.session.title); this.patch({ title: this.session.title });
    }
    this.startTurn(content);
  }

  private startTurn(content: ContentBlock[]): void {
    this.streaming = true;
    this.interrupted = false;
    this.openKind = undefined;
    const gen = ++this.generation;
    this.idleTurn?.kill(); this.idleTurn = undefined; // le nouveau message ouvre son propre processus
    this.turnStart = Date.now(); this.lastDurationMs = 0; this.liveOut = 0; this.lastLimits = undefined;
    this.suggestion = null; this.patch({ suggestion: null });
    // photo du projet avant que Claude agisse ; les fichiers que l'utilisateur a modifiés depuis la réponse précédente repartent de zéro
    this.chain(async () => { this.tracker.startTurn(); await this.baseline.capture(); });
    this.tick();
    const model = this.selectedModel.trim() || this.env.config('model', DEFAULT_MODEL);
    this.saveChoices();
    this.sendStatus();
    this.patch({ suggestion: null });

    const fork = this.forkNext;
    this.forkNext = false;
    this.patch({ forkPending: false });
    const efforts = this.effortChoices(model);
    const opts: TurnOptions = {
      cli: this.env.cliPath(), cwd: this.cwd(), model, effort: efforts.includes(this.selectedEffort) ? this.selectedEffort : 'auto',
      permissionMode: this.selectedMode, sessionId: this.session.id, appendSystem: this.env.config('systemPrompt', ''), mcp: [], fork,
      waitForMcp: true, suggestions: this.env.config('promptSuggestions', true),
    };
    const t = new CliTurn(opts, content, this.listener(gen));
    this.turn = t;
    t.start();
  }

  private chain(fn: () => Promise<void>): void {
    this.changeChain = this.changeChain.then(fn).catch((e) => console.warn('Suivi des fichiers :', e));
  }

  private tick(): void {
    clearTimeout(this.tickTimer);
    this.sendStatus();
    // dernier passage après la fin : sans ce rafraîchissement, « Réponse en cours » resterait affiché
    if (this.streaming) this.tickTimer = setTimeout(() => this.tick(), 1000);
  }

  private saveChoices(): void {
    this.env.setState('model', this.selectedModel);
    this.env.setState('effort', this.selectedEffort);
    this.env.setState('permissionMode', this.selectedMode);
  }

  // ---------- Événements de la CLI ----------

  /** Chaque rappel est ignoré si la conversation a changé de tour entre-temps (nouvelle session, interruption). */
  private listener(gen: number): TurnListener {
    const ui = (run: () => void) => { if (gen === this.generation && !this.disposed) run(); };
    return {
      onInit: (sid, model) => ui(() => { this.session.id = sid; this.activeModel = model ?? this.activeModel; this.sendStatus(); }),
      onLiveUsage: (ctx, out) => ui(() => { this.liveCtx = ctx; this.liveOut = out; this.sendStatus(); }),
      onRateLimits: (l) => ui(() => { this.lastLimits = l; this.applyLimits(l); }),
      onSuggestion: (t) => ui(() => { this.suggestion = t; this.patch({ suggestion: t }); }),
      onTitle: (t) => ui(() => this.applyCliTitle(t)),
      onContextWindow: (m, n) => ui(() => { this.contextWindows.set(m, n); this.sendStatus(); }),
      onRefineLost: (texts) => ui(() => {
        for (const body of texts) {
          const original = this.refinedOriginals.get(body.trim()) ?? body;
          this.refinedOriginals.delete(body.trim());
          this.queue.push(original);
        }
        this.sendQueue();
        this.addEntry({ kind: 'notice', text: 'Une précision arrivée trop tard est remise en file d\'attente.' });
      }),
      onText: (d) => ui(() => this.append('text', d)),
      onThinking: (d) => ui(() => this.append('thinking', d)),
      onToolUse: (id, name, input) => {
        if (gen !== this.generation) return;
        // état du fichier avant que l'outil s'exécute : lu tout de suite
        this.tracker.beforeEdit(name, input);
        if (isCommandTool(name)) this.commandStarts.set(id, Date.now());
        ui(() => {
          this.openKind = undefined;
          if (!this.session.entries.some((e) => e.toolId === id)) this.addEntry({ kind: 'tool', toolId: id, toolName: name, input, model: this.activeModel });
        });
      },
      onToolResult: (id, text, isError) => {
        if (gen !== this.generation) return;
        const started = this.commandStarts.get(id);
        if (started !== undefined) { this.commandStarts.delete(id); this.requestCommandScan(started, Date.now()); }
        ui(() => {
          const i = this.session.entries.findIndex((e) => e.toolId === id);
          if (i < 0) return;
          const e = this.session.entries[i];
          e.result = text; e.isError = isError;
          this.patchEntry(i, { result: text, isError });
          if (e.toolName && ['Edit', 'Write', 'MultiEdit', 'NotebookEdit', 'Bash'].includes(e.toolName)) { this.scheduleRefresh(e); this.scheduleChangedFilesRefresh(); }
        });
      },
      onPermission: (req) => ui(() => {
        if (this.allowAll && req.tool !== 'AskUserQuestion' && req.tool !== 'ExitPlanMode') this.turn?.allow(req.requestId, req.input);
        else this.enqueue(req);
      }),
      onUsage: (i, o, c) => ui(() => { this.usageIn += i; this.usageOut += o; this.costUsd += c; this.sendStatus(); }),
      onDone: (isError, message) => ui(() => this.finish(isError, message)),
      onMcpProblem: (servers) => ui(() => this.addEntry({ kind: 'notice', text: `Serveur MCP non connecté : ${servers.join(', ')}. Ses outils sont indisponibles pour cette réponse (voir « Serveurs MCP »).` })),
      onResumed: () => ui(() => {
        if (this.streaming) return;
        this.streaming = true; this.interrupted = false; this.openKind = undefined;
        this.turn = this.idleTurn;
        this.turnStart = Date.now(); this.lastDurationMs = 0; this.liveOut = 0;
        this.tick();
      }),
    };
  }

  private finish(isError: boolean, message?: string): void {
    this.flushNow();
    this.streaming = false;
    this.lastDurationMs = !isError && !this.interrupted ? Date.now() - this.turnStart : 0;
    this.idleTurn = this.turn; // processus gardé ouvert un moment : la CLI peut reprendre (tâche de fond)
    this.turn = undefined;
    this.tick();
    this.clearInteractions('Réponse terminée', false);
    this.refinedOriginals.clear();
    const delayMin = this.env.config('autoResumeDelayMin', 2);
    const until = isError && !this.interrupted && this.autoResumeOn && this.resumeAttempts < MAX_RESUMES
      ? QuotaGuard.blockedUntil(this.lastLimits, message, Date.now() / 1000, delayMin * 60) : undefined;
    if (!isError) this.resumeAttempts = 0;
    this.noQuotaLastTurn = !isError && !this.interrupted && this.lastLimits === undefined;
    if (isError) {
      if (this.interrupted) this.addEntry({ kind: 'notice', text: 'Réponse interrompue.' });
      else if (until !== undefined) this.addEntry({ kind: 'notice', text: `Quota atteint : la conversation reprendra automatiquement ${delayMin} min après le début de la prochaine session.` });
      else this.addEntry({ kind: 'error', text: message ?? 'Erreur' });
    }
    if (until !== undefined) {
      this.scheduleResume(until * 1000);
      if (this.remoteOn) this.env.remote.notifyPhone(`⏸ [${path.basename(this.cwd())}] Quota atteint, reprise automatique prévue.`);
    }
    this.chain(async () => this.tracker.endTurn());
    this.scheduleChangedFilesRefresh();
    if (!isError && !this.interrupted) this.scheduleTitleRefresh();
    if (this.env.config('notify', true) && !this.interrupted) this.env.notify('info', `Claude a terminé : ${this.session.title}`);
    if (this.remoteOn && !this.interrupted && !isError) {
      const answer = this.finalAnswer() || '(aucune réponse textuelle)';
      this.env.remote.say(this, `✔ [${path.basename(this.cwd())} · ${this.session.title.slice(0, 40)}] Claude a terminé :\n\n${answer}\n\n↩ Réponds à ce message pour continuer la conversation.`);
    } else if (this.remoteOn && !this.interrupted && until === undefined) {
      this.env.remote.say(this, `⚠ [${path.basename(this.cwd())}] Erreur : ${message ?? 'inconnue'}`);
    }
    if (this.queue.length && this.resumeAt === 0) {
      const merged = this.queue.join('\n\n'); this.queue = []; this.sendQueue();
      void this.dispatch(merged);
    }
  }

  stop(): void {
    this.interrupted = true;
    this.clearInteractions('Interrompu depuis VS Code', true);
    this.turn?.interrupt();
  }

  private abandonTurn(): void {
    if (this.streaming) {
      this.generation++; this.turn?.kill(); this.turn = undefined; this.streaming = false;
      this.clearInteractions('Conversation fermée', false); this.sendStatus();
    }
  }

  get isStreaming(): boolean { return this.streaming; }

  /** Avertit (et demande confirmation) avant une action qui interromprait la réponse en cours. */
  async confirmDiscard(consequence: string, yes: string): Promise<boolean> {
    const w = busyWarning(this.streaming, this.queue.length, consequence);
    return w === undefined ? true : this.env.confirm(w, yes);
  }

  // ---------- Reprise automatique ----------

  private scheduleResume(atMs: number): void {
    this.resumeAt = atMs; this.resumeAttempts++;
    this.pollResume();
  }

  /** Vérifie toutes les 30 s (robuste à la mise en veille) si l'heure de reprise est passée. */
  private pollResume(): void {
    clearTimeout(this.resumeTimer);
    if (this.resumeAt === 0) return;
    if (!this.streaming && Date.now() >= this.resumeAt) {
      this.resumeAt = 0;
      const extra = this.queue.join('\n\n'); this.queue = []; this.sendQueue();
      this.addEntry({ kind: 'notice', text: 'Reprise automatique après réinitialisation du quota…' });
      if (this.remoteOn) this.env.remote.notifyPhone(`▶ [${path.basename(this.cwd())}] Reprise automatique de la conversation.`);
      this.skipAttachOnce = true;
      void this.dispatch("Le quota est réinitialisé : reprends ta tâche là où tu t'étais arrêté." + (extra ? `\n\n${extra}` : ''));
      return;
    }
    this.sendStatus();
    this.resumeTimer = setTimeout(() => this.pollResume(), 30_000);
  }

  private cancelResume(): void { this.resumeAt = 0; this.resumeAttempts = 0; clearTimeout(this.resumeTimer); this.sendStatus(); }

  // ---------- Fichiers modifiés par Claude ----------

  /**
   * Demande l'analyse de ce que la commande a changé. Les demandes qui arrivent pendant qu'une analyse est en cours sont
   * regroupées en une seule, sur la réunion de leurs fenêtres (un outil MCP rapide ne déclenche pas un parcours du disque chacun).
   */
  private requestCommandScan(start: number, end: number): void {
    const prev = this.pendingScan;
    this.pendingScan = prev ? [Math.min(prev[0], start), Math.max(prev[1], end)] : [start, end];
    if (prev) return; // une analyse est déjà prévue : elle prendra cette fenêtre aussi
    this.chain(async () => {
      const w = this.pendingScan; this.pendingScan = undefined;
      if (!w) return;
      await this.tracker.afterCommand(this.baseline, w[0], w[1]);
      this.scheduleChangedFilesRefresh();
    });
  }

  private scheduleChangedFilesRefresh(): void {
    clearTimeout(this.changeTimer);
    this.changeTimer = setTimeout(() => this.refreshChangedFiles(), 300);
  }

  /** Relit l'état des fichiers suivis puis met la ligne à jour. */
  refreshChangedFiles(): void {
    this.changedFiles = this.tracker.isEmpty ? [] : this.tracker.changes();
    this.sendChanges();
  }

  private changesState(): UiState['changes'] {
    const f = this.changedFiles;
    return f.length ? { count: f.length, added: f.reduce((a, c) => a + c.added, 0), removed: f.reduce((a, c) => a + c.removed, 0) } : null;
  }

  private sendChanges(): void { this.patch({ changes: this.changesState() }); this.env.onChanges(this.changedFiles); }

  clearChangedFiles(): void { this.tracker.clear(); this.changedFiles = []; this.sendChanges(); }

  /** Les fichiers modifiés par un outil sont rechargés dans l'éditeur (regroupés : une rafale d'outils = un passage). */
  private scheduleRefresh(e: Entry): void {
    const p = e.toolName && EDIT_TOOLS.has(e.toolName) && e.input ? filePathOf(e.input) : undefined;
    if (p) this.dirtyPaths.add(p); else this.dirtyAll = true;
    clearTimeout(this.refreshTimer);
    this.refreshTimer = setTimeout(() => {
      const all = this.dirtyAll; const paths = [...this.dirtyPaths];
      this.dirtyAll = false; this.dirtyPaths.clear();
      if (all) this.env.refreshFiles('all'); else if (paths.length) this.env.refreshFiles(paths);
    }, 300);
  }

  // ---------- Titre ----------

  private applyCliTitle(title: string): void {
    if (this.customTitle !== undefined || !title.trim() || title === this.session.title) return;
    this.session.title = title;
    this.env.setTitle(title); this.patch({ title });
  }

  /**
   * La CLI génère le titre de la session après la réponse, en l'écrivant dans le fichier de session : on le relit
   * quelques fois (2,5 s, 7,5 s, 12,5 s après la fin) pour que l'onglet ne garde pas le début du premier message.
   */
  private scheduleTitleRefresh(): void {
    if (this.customTitle !== undefined || !this.session.id) return;
    this.titleChecks = 0;
    clearTimeout(this.titleTimer);
    this.titleTimer = setTimeout(() => void this.refreshCliTitle(), 2500);
  }

  private async refreshCliTitle(): Promise<void> {
    const sid = this.session.id;
    if (!sid || this.customTitle !== undefined) return;
    const t = await liveTitle(this.cwd(), sid);
    if (this.disposed || this.session.id !== sid) return;
    if (t) this.applyCliTitle(t);
    if (++this.titleChecks < 3) this.titleTimer = setTimeout(() => void this.refreshCliTitle(), 5000);
  }

  /** Renomme l'onglet, et la session côté CLI (visible dans `claude --resume`). */
  async rename(newTitle?: string): Promise<void> {
    if (newTitle === undefined) return;
    const t = newTitle.trim();
    if (!t) return;
    this.customTitle = t; this.session.title = t;
    this.env.setTitle(t); this.patch({ title: t });
    if (this.session.id) {
      await storeRename(this.session.id, t);
      void (this.turn ?? this.idleTurn)?.renameSession(t);
    }
  }

  // ---------- Nouvelle conversation, reprise ----------

  async newSession(): Promise<void> {
    if (!(await this.confirmDiscard('Démarrer une nouvelle conversation interrompt la réponse en cours (la conversation actuelle reste dans l\'historique des sessions).', 'Interrompre et recommencer'))) return;
    this.abandonTurn();
    this.reset({ title: DEFAULT_TITLE, entries: [] });
    this.post({ t: 'newConversation' });
  }

  async loadSession(s: Session): Promise<boolean> {
    if (!(await this.confirmDiscard('Charger une autre session interrompt la réponse en cours.', 'Interrompre et charger'))) return false;
    this.abandonTurn();
    this.reset(s);
    return true;
  }

  private reset(s: Session): void {
    this.idleTurn?.kill(); this.idleTurn = undefined;
    this.suggestion = null; this.clearChangedFiles(); this.cancelResume();
    this.session = s; this.customTitle = undefined; this.allowAll = false;
    this.queue = []; this.files = []; this.images = []; this.forkNext = false;
    this.usageIn = 0; this.usageOut = 0; this.costUsd = 0; this.liveCtx = 0; this.liveOut = 0; this.activeModel = undefined;
    this.applyRemote(this.env.config('remote.default', false));
    this.env.setTitle(s.title);
    this.sendAll();
  }

  // ---------- Modes, modèles ----------

  private setMode(id: string): void {
    this.selectedMode = id; this.saveChoices(); this.patch(this.controlsState());
    // tout de suite sur le processus vivant, sans attendre le message suivant
    const t = this.turn ?? this.idleTurn;
    if (!t) return;
    void t.setPermissionMode(id).then((r) => {
      if (r && !r.ok) this.addEntry({ kind: 'notice', text: `Le mode « ${id} » prendra effet au prochain message : la CLI le refuse en cours de session${r.error ? ` (${r.error})` : ''}.` });
    });
  }

  private selectModel(id: string): void {
    this.selectedModel = id;
    const eff = this.effortChoices(id);
    if (!eff.includes(this.selectedEffort)) this.selectedEffort = 'auto';
    this.saveChoices();
    this.patch(this.controlsState());
  }

  // ---------- Permissions, plans, questions ----------

  private findInteraction(id: string): Interaction | undefined {
    return this.current?.id === id ? this.current : this.pending.find((i) => i.id === id);
  }

  private enqueue(req: PermissionRequest): void {
    if (req.tool === 'AskUserQuestion') { this.enqueueQuestions(req); return; }
    let i: Interaction;
    if (req.tool === 'ExitPlanMode') {
      i = this.interaction(req, 'Plan proposé par Claude', typeof req.input.plan === 'string' ? req.input.plan : '', ['Accepter le plan', 'Refuser'], false, (c) => {
        if (c === 'Accepter le plan') this.turn?.allow(req.requestId, req.input); else this.turn?.deny(req.requestId, "L'utilisateur a refusé le plan.");
      });
    } else {
      i = this.interaction(req, `${req.tool} — ${toolSummary(req.tool, req.input).slice(0, 120)}`, describeTool(req.tool, req.input),
        ['Autoriser', 'Refuser', 'Tout autoriser (session)'], false, (c) => void this.decide(req, c));
    }
    this.offer(i);
  }

  private interaction(req: PermissionRequest, title: string, body: string, options: string[], freeText: boolean, resolve: (c: string) => void): Interaction {
    return { req, title, body, options, freeText, resolve, done: false, id: `i${++this.seq}` };
  }

  private offer(i: Interaction): void {
    this.pending.push(i);
    if (this.env.config('notify', true)) this.env.notify('info', `Claude attend une réponse : ${i.title}`);
    if (this.remoteOn) this.attachRemote(i);
    this.nextInteraction();
  }

  private attachRemote(i: Interaction): void {
    if (i.done || i.handle) return;
    i.handle = this.env.remote.offer(`[${path.basename(this.cwd())}] ${i.title}`, i.body, i.options, i.freeText, (choice) => this.finishInteraction(i, choice));
  }

  /** Une interaction par question, dans le panneau (et sur le téléphone). */
  private enqueueQuestions(req: PermissionRequest): void {
    const qs: Json[] = Array.isArray(req.input.questions) ? req.input.questions : [];
    const answers: Json = {};
    const step = (idx: number): void => {
      if (idx >= qs.length) { this.turn?.allow(req.requestId, { ...req.input, answers }); return; }
      const qo = qs[idx];
      const question: string | undefined = qo.question;
      if (!question) return step(idx + 1);
      const opts: string[] = Array.isArray(qo.options) ? qo.options.map((o: Json) => String(o.label ?? '')) : [];
      this.offer(this.interaction(req, qo.header ?? 'Question de Claude', question, opts, true, (c) => { answers[question] = c; step(idx + 1); }));
    };
    step(0);
  }

  private nextInteraction(): void {
    if (this.current) return;
    const i = this.pending.shift();
    if (!i) { this.patch({ interaction: null }); return; }
    this.current = i;
    this.patch({ interaction: this.interactionView() });
    if (this.canDiff(i) && this.env.config('autoDiff', true)) void this.showDiff(i.req);
  }

  private canDiff(i: Interaction): boolean { return EDIT_TOOLS.has(i.req.tool) && filePathOf(i.req.input) !== undefined; }

  private interactionView(): InteractionView | null {
    const i = this.current;
    return i ? { id: i.id, title: i.title, body: i.body, options: i.options, freeText: i.freeText, canDiff: this.canDiff(i) } : null;
  }

  private resolveLocal(i: Interaction, choice: string): void {
    if (i.done) return;
    if (i.handle) { if (!i.handle.tryClaim()) return; i.handle.done("l'IDE", choice); }
    this.finishInteraction(i, choice);
  }

  private finishInteraction(i: Interaction, choice: string): void {
    if (i.done) return;
    i.done = true;
    if (this.current === i) { this.current = undefined; this.patch({ interaction: null }); }
    this.pending = this.pending.filter((p) => p !== i);
    i.resolve(choice);
    this.nextInteraction();
  }

  private clearInteractions(reason: string, deny: boolean): void {
    const all = [...(this.current ? [this.current] : []), ...this.pending];
    this.pending = []; this.current = undefined; this.patch({ interaction: null });
    for (const i of all) {
      i.done = true;
      i.handle?.cancel(reason);
      this.env.closeDiff(i.req.requestId);
      if (deny) this.turn?.deny(i.req.requestId, 'Interrompu par l\'utilisateur');
    }
  }

  /** Applique le choix sur une demande de permission (diff corrigé par l'utilisateur). */
  private async decide(req: PermissionRequest, choice: string): Promise<void> {
    const edited = await this.env.readEdited(req.requestId);
    const prop = this.proposed.get(req.requestId);
    this.proposed.delete(req.requestId);
    this.env.closeDiff(req.requestId);
    const allow = choice !== 'Refuser';
    if (choice.startsWith('Tout autoriser')) this.allowAll = true;
    if (allow && edited !== undefined && prop !== undefined && edited !== prop) {
      const p = filePathOf(req.input)!;
      await this.env.applyFile(p, edited);
      this.turn?.deny(req.requestId, `L'utilisateur a modifié ta proposition et appliqué lui-même cette version finale du fichier ${p}. Ne refais pas la modification ; relis le fichier si nécessaire.`);
    } else if (allow) this.turn?.allow(req.requestId, req.input);
    else this.turn?.deny(req.requestId, "L'utilisateur a refusé cette action.");
  }

  // ---------- Diff ----------

  private async showDiff(req: PermissionRequest): Promise<void> {
    const p = filePathOf(req.input);
    if (!p) return;
    let orig = '';
    try { orig = fs.readFileSync(p, 'utf8'); } catch { /* nouveau fichier */ }
    const prop = proposedText(req, orig);
    if (prop === undefined) return;
    this.proposed.set(req.requestId, prop);
    await this.env.showDiff(req.requestId, p, orig, prop);
  }

  // ---------- Pièces jointes, mentions ----------

  private attachmentViews(): Attachment[] {
    const out: Attachment[] = [];
    // une vidéo apparaît une seule fois (miniature de sa 1re image, « clip.mp4 ×6 »)
    const groups = new Map<string, ImageAttachment[]>();
    for (const i of this.images) (groups.get(i.name) ?? groups.set(i.name, []).get(i.name)!).push(i);
    const seen = new Set<string>();
    for (const f of this.files) {
      if (seen.has(f.rel)) continue;
      seen.add(f.rel);
      const base = f.rel.split('/').pop()!;
      if (groups.has(base)) continue;
      out.push({ key: f.rel, label: base, tip: f.rel, kind: 'file' });
    }
    for (const [name, list] of groups) {
      out.push({ key: name, label: list.length > 1 ? `${name} ×${list.length}` : name, tip: name, kind: 'image', thumb: `data:${list[0].media};base64,${list[0].data}` });
    }
    return out;
  }

  private sendAttachments(): void { this.patch({ attachments: this.attachmentViews() }); }

  private removeAttachment(key: string): void {
    this.images = this.images.filter((i) => i.name !== key);
    this.files = this.files.filter((f) => f.rel !== key && f.rel.split('/').pop() !== key);
    this.sendAttachments();
  }

  private relPath(abs: string): string {
    const base = this.cwd();
    return abs.startsWith(base + path.sep) ? abs.slice(base.length + 1).split(path.sep).join('/') : path.basename(abs);
  }

  private async addFiles(): Promise<void> {
    for (const f of await this.env.pickFiles()) await this.attachFile(f);
    this.sendAttachments();
  }

  /** Joint un fichier : image, vidéo (images extraites par ffmpeg) ou texte. */
  async attachFile(abs: string): Promise<void> {
    const name = path.basename(abs);
    try { if (!fs.statSync(abs).isFile()) return; } catch { return; }
    if (video.isVideo(name)) { await this.attachVideo(abs, this.relPath(abs), name); return; }
    const ext = name.split('.').pop()?.toLowerCase() ?? '';
    const media = IMAGE_MEDIA[ext];
    const buf = fs.readFileSync(abs);
    if (media) { this.images.push({ name, media, data: buf.toString('base64') }); return; }
    if (buf.includes(0)) { this.env.notify('warn', `${name} : fichier binaire non joint (demande à Claude de le lire par son chemin).`); return; }
    const text = buf.toString('utf8');
    if (text.length > MAX_ATTACH_CHARS) { this.env.notify('warn', `${name} est trop volumineux : non joint.`); return; }
    const rel = this.relPath(abs);
    this.files.push({ rel, block: `<file path="${rel}">\n${text}\n</file>` });
  }

  /** Vidéo : quelques images extraites par ffmpeg (l'API n'accepte pas la vidéo). */
  private async attachVideo(abs: string, rel: string, name: string): Promise<void> {
    this.env.notify('info', `Extraction des images de « ${name} »…`);
    const { text, frames } = await video.attachVideo(abs, rel);
    this.files.push({ rel, block: text });
    for (const f of frames) this.images.push({ name, media: 'image/jpeg', data: f.toString('base64') });
    if (!frames.length) this.env.notify('warn', `${name} : images non extraites (ffmpeg requis). Le chemin est transmis à Claude.`);
    else this.env.notify('info', `${name} : ${frames.length} image(s) extraite(s) et jointes.`);
    this.sendAttachments();
  }

  /** Fichiers mentionnés avec `@` dans le message, joints en texte. */
  private mentionAttachments(text: string, projectOnly = false): FileAttachment[] {
    const out: FileAttachment[] = [];
    for (const rel of Mentions.find(text)) {
      const abs = resolveMention(rel, this.cwd(), projectOnly);
      if (!abs) continue;
      try {
        if (!fs.statSync(abs).isFile()) continue;
        const buf = fs.readFileSync(abs);
        if (buf.includes(0) || buf.length > MAX_ATTACH_CHARS) continue;
        out.push({ rel, block: `<file path="${rel}">\n${buf.toString('utf8')}\n</file>` });
      } catch { /* mention sans fichier */ }
    }
    return out;
  }

  private projectFilesCache?: { at: number; files: string[] };

  private async mentionResults(q: string): Promise<string[]> {
    if (!this.projectFilesCache || Date.now() - this.projectFilesCache.at > 30_000) {
      this.projectFilesCache = { at: Date.now(), files: await this.env.projectFiles() };
    }
    return Fuzzy.search(q, this.projectFilesCache.files);
  }

  private existingFiles(paths: string[]): string[] {
    return paths.filter((p) => {
      try { return fs.statSync(path.isAbsolute(p) ? p : path.join(this.cwd(), p)).isFile(); } catch { return false; }
    });
  }

  private openFile(spec: string): void {
    const m = /^(.*?)(?::(\d+))?$/.exec(spec)!;
    const p = m[1];
    const abs = path.isAbsolute(p) ? p : path.join(this.cwd(), p);
    this.env.openFile(abs, m[2] ? Number(m[2]) : undefined);
  }

  /** Le fichier courant ou la sélection de l'éditeur ont changé. */
  onEditorChanged(): void { this.patch({ contextFile: this.env.editorLabel() }); }

  /** Ajoute du texte au champ de saisie (sélection de l'éditeur, snippet). */
  insertText(text: string): void { this.post({ t: 'setInput', text, append: true }); }

  /** Pose une question toute prête sur du code sélectionné (« Expliquer », « Corriger »…). */
  ask(command: string, fileName: string, lang: string, code: string): void {
    this.post({ t: 'setInput', text: `/${command}\n\n\`\`\`${lang}\n// ${fileName}\n${code}\n\`\`\``, append: false });
    this.post({ t: 'focus' });
  }

  // ---------- Contexte ----------

  /**
   * Détail du contexte au clic sur l'anneau. Mesuré par le processus vivant de la conversation s'il y en a un
   * (exact) ; sinon la session est rechargée à part, sans serveurs MCP (approximatif).
   */
  private async showContextDetails(): Promise<void> {
    const live = this.turn ?? this.idleTurn;
    let usage: ContextUsage | undefined = await live?.contextUsage();
    usage ??= await contextUsageOnce(this.env.cliPath(), this.cwd(), this.session.id);
    if (!usage) { this.env.notify('warn', 'Contexte non mesuré : la CLI n\'a pas répondu.'); return; }
    this.post({ t: 'context', usage, modelLabel: usage.model ? this.catalog.label(usage.model) : '' });
  }

  // ---------- Accès à distance (téléphone) ----------

  private applyRemote(on: boolean): void {
    this.remoteOn = on;
    if (on) this.env.remote.register(this); else this.env.remote.unregister(this);
  }

  /** Active ou coupe l'envoi des demandes de cette conversation vers le téléphone. */
  private setRemote(on: boolean): void {
    if (on && !this.env.remote.isPaired()) {
      this.env.notify('warn', 'Accès à distance non prêt : active-le et appaire ton téléphone (commande « Claude Chat : Appairer mon téléphone »).');
      this.patch({ remoteOn: false });
      return;
    }
    this.applyRemote(on);
    const all = [...(this.current ? [this.current] : []), ...this.pending];
    if (on) all.forEach((i) => this.attachRemote(i));
    else all.forEach((i) => { i.handle?.cancel('Accès à distance désactivé pour cette conversation'); i.handle = undefined; });
    this.patch({ remoteOn: this.remoteOn });
  }

  /** Message libre reçu du téléphone : devient un message de l'utilisateur (ou attend la fin de la réponse). */
  onPhoneMessage(text: string): void {
    if (!this.remoteOn) return;
    if (this.streaming) {
      this.queue.push(text); this.sendQueue();
      this.env.remote.say(this, '📨 Claude est encore au travail : ton message est en file d\'attente.');
    } else {
      this.addEntry({ kind: 'notice', text: '📱 Message reçu du téléphone.' });
      this.env.remote.say(this, '▶ Envoyé à Claude.');
      this.skipAttachOnce = true;
      void this.dispatch(text, true);
    }
  }

  /** Réponse finale à renvoyer au téléphone : le texte après le dernier outil, sinon le dernier texte. */
  private finalAnswer(): string {
    const e = this.session.entries;
    let start = e.length;
    while (start > 0 && e[start - 1].kind !== 'user') start--;
    const tail = e.slice(start);
    let k = tail.length;
    while (k > 0 && tail[k - 1].kind !== 'tool') k--;
    const afterTool = tail.slice(k).filter((x) => x.kind === 'text');
    const texts = afterTool.length ? afterTool : tail.filter((x) => x.kind === 'text').slice(-1);
    return texts.map((x) => x.text).join('\n\n').trim();
  }

  private sendQueue(): void { this.patch({ queue: [...this.queue] }); }

  /** Sessions récentes du projet (pour le sélecteur). */
  listSessions = (offset: number, limit: number, archived: boolean) => listSessions(this.cwd(), offset, limit, archived);
}

/** Texte du fichier après l'outil d'édition ; undefined pour un autre outil. */
export function proposedText(req: PermissionRequest, orig: string): string | undefined {
  const i = req.input;
  const applyEdit = (text: string, e: Json): string => {
    const old: string | undefined = e.old_string;
    if (old === undefined) return text;
    const neu: string = e.new_string ?? '';
    return e.replace_all === true ? text.split(old).join(neu) : text.replace(old, () => neu);
  };
  switch (req.tool) {
    case 'Write': return typeof i.content === 'string' ? i.content : undefined;
    case 'Edit': return applyEdit(orig, i);
    case 'MultiEdit': return Array.isArray(i.edits) ? i.edits.reduce((acc: string, e: Json) => applyEdit(acc, e), orig) : undefined;
    default: return undefined;
  }
}
