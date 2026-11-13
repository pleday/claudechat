import { spawn, type ChildProcess } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { createInterface } from 'node:readline';
import { parseContextUsage, parseCommands, parseModels, controlPayload, type CliCommand, type CliModel, type ContextUsage } from './cliInfo';
import { resultText } from './sessions';

export type Json = Record<string, any>;

/** Fenêtre de quota : part utilisée (0..1) et instant de réinitialisation (secondes epoch). */
export interface QuotaWindow { utilization: number; resetsAt: number }
export interface RateLimits {
  fiveHour?: QuotaWindow;
  sevenDay?: QuotaWindow;
  /** « allowed », « rejected »… tel que fourni par la CLI. */
  status?: string;
  /** Fenêtre concernée par `status` (five_hour, seven_day) et sa réinitialisation. */
  limitType?: string;
  resetsAt?: number;
}

/** Extrait les quotas d'un événement `rate_limit_event` de la CLI ; undefined s'il n'en contient pas. */
export function parseRateLimits(j: Json): RateLimits | undefined {
  const info = j.rate_limit_info;
  if (!info || typeof info !== 'object') return undefined;
  const w = info.unifiedWindows && typeof info.unifiedWindows === 'object' ? info.unifiedWindows : undefined;
  const win = (k: string): QuotaWindow | undefined => {
    const o = w?.[k];
    if (!o || typeof o !== 'object' || typeof o.utilization !== 'number') return undefined;
    return { utilization: o.utilization, resetsAt: typeof o.resetsAt === 'number' ? o.resetsAt : 0 };
  };
  const str = (v: unknown) => (typeof v === 'string' ? v : undefined);
  const r: RateLimits = {
    fiveHour: win('five_hour'),
    sevenDay: win('seven_day'),
    status: str(info.status),
    limitType: str(info.rateLimitType),
    resetsAt: typeof info.resetsAt === 'number' ? info.resetsAt : undefined,
  };
  return r.fiveHour || r.sevenDay || r.status === 'rejected' ? r : undefined;
}

export interface ProbeResult { limits?: RateLimits; error?: string }

/** Réponse de la CLI à une requête de contrôle. */
export interface ControlResult { ok: boolean; payload?: Json; error?: string }

export interface McpEntry { name: string; url: string; token?: string }

export interface PermissionRequest {
  requestId: string;
  tool: string;
  input: Json;
  description: string;
}

export interface TurnOptions {
  cli: string;
  cwd: string;
  model: string;
  effort: string;
  permissionMode: string;
  sessionId?: string;
  appendSystem: string;
  mcp: McpEntry[];
  fork?: boolean;
  /** Attendre que les serveurs MCP aient fini de se connecter avant d'envoyer le message (sinon leurs outils manquent). */
  waitForMcp?: boolean;
  /** La CLI prédit le prochain message de l'utilisateur après chaque réponse (`--prompt-suggestions`). */
  suggestions?: boolean;
}

/** Contenu d'un message utilisateur : blocs `text` et `image` de l'API. */
export type ContentBlock = Json;

export interface TurnListener {
  onInit?(sessionId: string, model?: string): void;
  onText?(delta: string): void;
  onThinking?(delta: string): void;
  onToolUse?(id: string, name: string, input: Json): void;
  onToolResult?(id: string, text: string, isError: boolean): void;
  onPermission?(req: PermissionRequest): void;
  onUsage?(input: number, output: number, costUsd: number): void;
  /** Tokens en direct : taille du contexte et tokens de sortie cumulés du tour. */
  onLiveUsage?(contextTokens: number, outputTokens: number): void;
  onRateLimits?(limits: RateLimits): void;
  /** Taille de la fenêtre de contexte du modèle (fournie avec le résultat). */
  onContextWindow?(model: string, tokens: number): void;
  /** Précisions envoyées mais non consommées avant la fin du tour : à renvoyer comme nouveau message. */
  onRefineLost?(texts: string[]): void;
  onDone?(isError: boolean, message?: string): void;
  /** La CLI a repris du travail après un `result` (tâche de fond, message empilé…) : le tour est de nouveau en cours. */
  onResumed?(): void;
  /** La CLI a changé le titre de la session (renommage, titre généré). */
  onTitle?(title: string): void;
  /** Message que l'utilisateur écrira probablement ensuite, proposé par la CLI après la réponse. */
  onSuggestion?(text: string): void;
  /** Serveurs MCP non connectés (« nom (statut) ») à l'envoi du message : leurs outils sont indisponibles pour ce tour. */
  onMcpProblem?(servers: string[]): void;
}

const RESUME_EVENTS = new Set(['stream_event', 'assistant', 'control_request']);
const MCP_WAIT_MS = 15_000;
/** Processus utilitaires (commandes, quota) : inutile de démarrer les serveurs MCP de l'utilisateur à chaque fois. */
const NO_MCP = ['--strict-mcp-config', '--mcp-config', '{"mcpServers":{}}'];

export const timing = {
  /** Silence après un `result` avant de fermer l'entrée de la CLI (modifiable par les tests). */
  quietBeforeCloseMs: 3 * 60_000,
};

const sleep = (ms: number) => new Promise<void>((r) => setTimeout(r, ms));

function mcpJson(list: McpEntry[]): string {
  const servers: Json = {};
  for (const m of list) {
    servers[m.name] = { type: 'http', url: m.url, ...(m.token ? { headers: { Authorization: `Bearer ${m.token}` } } : {}) };
  }
  return JSON.stringify({ mcpServers: servers });
}

/** Suggestion exploitable : une ligne, sans espaces superflus, bornée ; undefined si vide. */
export function cleanSuggestion(raw?: string | null): string | undefined {
  const line = raw?.split('\n').find((l) => l.trim());
  return line?.trim().slice(0, 300) || undefined;
}

/**
 * Un tour de conversation = un processus `claude -p` en entrée/sortie stream-json.
 * Un seul processus pilote une session à la fois ; mode, effort et modèle prennent effet au tour suivant.
 */
export class CliTurn {
  private proc?: ChildProcess;
  private killed = false;
  private ended = false;
  private refinements: string[] = [];
  private stderr = '';
  private lastActivity = Date.now();
  private mcpStatus?: Array<[string, string]>;
  private pending = new Map<string, (r: ControlResult) => void>();
  private seq = 0;
  private ctx = 0;
  private doneOut = 0;
  private curOut = 0;
  private finished = false;
  private closer?: NodeJS.Timeout;

  constructor(private o: TurnOptions, private content: ContentBlock[], private l: TurnListener) {}

  start(): void {
    const o = this.o;
    const cmd = [
      '-p', '--input-format', 'stream-json', '--output-format', 'stream-json',
      '--verbose', '--include-partial-messages', '--replay-user-messages', '--permission-prompt-tool', 'stdio',
      '--permission-mode', o.permissionMode,
    ];
    if (o.model.trim()) cmd.push('--model', o.model);
    if (o.effort.trim() && o.effort !== 'auto') cmd.push('--effort', o.effort);
    if (o.sessionId) cmd.push('--resume', o.sessionId);
    if (o.sessionId && o.fork) cmd.push('--fork-session');
    if (o.suggestions) cmd.push('--prompt-suggestions');
    if (o.appendSystem.trim()) cmd.push('--append-system-prompt', o.appendSystem);
    if (o.mcp.length) cmd.push('--mcp-config', mcpJson(o.mcp));

    let p: ChildProcess;
    try {
      p = spawn(o.cli, cmd, { cwd: o.cwd, env: { CLAUDE_CODE_ENTRYPOINT: 'vscode-gui', ...process.env }, stdio: ['pipe', 'pipe', 'pipe'] });
    } catch (e) {
      this.l.onDone?.(true, `Impossible de lancer « ${o.cli} » : ${(e as Error).message}`);
      return;
    }
    this.proc = p;
    let spawnFailed = false;
    p.on('error', (e) => {
      spawnFailed = true;
      this.l.onDone?.(true, `Impossible de lancer « ${o.cli} » : ${e.message}`);
    });
    p.stdin!.on('error', () => { /* processus terminé : l'écriture échoue, sans importance */ });
    p.stderr!.setEncoding('utf8');
    p.stderr!.on('data', (d: string) => { this.stderr += d; });

    const rl = createInterface({ input: p.stdout! });
    rl.on('line', (line) => this.onLine(line));
    const rlClosed = new Promise<void>((r) => rl.on('close', () => r()));
    const exited = new Promise<number | null>((r) => p.on('close', (code) => r(code)));
    void Promise.all([rlClosed, exited]).then(([, code]) => {
      if (spawnFailed) return;
      clearInterval(this.closer);
      if (!this.finished && !this.killed) {
        this.l.onDone?.(true, this.stderr.trim() || `Le processus claude s'est arrêté (code ${code ?? -1}).`);
      } else if (!this.finished) this.l.onDone?.(false);
    });

    const first: Json = { type: 'user', message: { role: 'user', content: this.content } };
    if (o.waitForMcp) void this.awaitMcp().then(() => { if (!this.killed) this.send(first); });
    else this.send(first);
  }

  /**
   * La CLI démarre ses serveurs MCP en tâche de fond : au premier message, ceux qui sont encore « pending » n'ont
   * pas d'outils. On attend donc leur connexion (15 s max) avant d'envoyer.
   */
  private async awaitMcp(): Promise<void> {
    const deadline = Date.now() + MCP_WAIT_MS;
    let i = 0;
    while (!this.killed && this.alive() && Date.now() < deadline) {
      this.mcpStatus = undefined;
      this.send({ type: 'control_request', request_id: `mcp-wait-${i++}`, request: { subtype: 'mcp_status' } });
      let w = 0;
      while (!this.mcpStatus && w++ < 40 && !this.killed) await sleep(50);
      const st = this.mcpStatus as Array<[string, string]> | undefined;
      if (!st) break; // la CLI ne répond pas à mcp_status : on n'attend pas
      if (!st.some((s) => s[1] === 'pending')) break;
      await sleep(300);
    }
    const bad = ((this.mcpStatus as Array<[string, string]> | undefined) ?? []).filter((s) => s[1] === 'failed' || s[1] === 'pending');
    if (bad.length) this.l.onMcpProblem?.(bad.map((s) => `${s[0]} (${s[1]})`));
  }

  private alive(): boolean {
    return !!this.proc && this.proc.exitCode === null && !this.proc.killed;
  }

  private onLine(line: string): void {
    this.lastActivity = Date.now();
    if (!line.startsWith('{')) return;
    let j: Json;
    try { j = JSON.parse(line); } catch { return; }
    // un événement illisible est ignoré : il ne doit pas interrompre toute la réponse
    try { this.handle(j); } catch (e) { console.warn(`Événement de la CLI ignoré (${(e as Error).message}) : ${line.slice(0, 200)}`); }
  }

  private handle(j: Json): void {
    const l = this.l;
    if (this.ended && RESUME_EVENTS.has(j.type)) {
      // la CLI continue après un `result` : on garde l'entrée ouverte et on repasse en « en cours »
      this.ended = false; this.finished = false;
      l.onResumed?.();
    }
    switch (j.type) {
      case 'system':
        if (j.subtype === 'init') l.onInit?.(j.session_id, j.model);
        else if (j.subtype === 'session_title_changed' && typeof j.title === 'string' && j.title.trim()) l.onTitle?.(j.title);
        break;
      case 'stream_event': this.stream(j.event); break;
      case 'assistant':
        if (Array.isArray(j.message?.content)) for (const b of j.message.content) {
          if (b.type === 'tool_use') l.onToolUse?.(b.id, b.name, b.input ?? {});
        }
        break;
      case 'user': {
        const c = j.message?.content;
        if (typeof c === 'string') this.consume(c);
        else if (Array.isArray(c)) for (const b of c) {
          if (b.type === 'tool_result') l.onToolResult?.(b.tool_use_id, resultText(b.content), b.is_error === true);
          // la CLI rejoue le message quand elle le prend en compte : la précision est consommée
          else if (b.type === 'text') this.consume(b.text);
        }
        break;
      }
      case 'control_response': {
        const resp = j.response;
        if (resp && typeof resp === 'object') {
          const rid: string | undefined = resp.request_id;
          if (rid) {
            const done = this.pending.get(rid);
            if (done) {
              this.pending.delete(rid);
              done({ ok: resp.subtype === 'success', payload: controlPayload(j), error: typeof resp.error === 'string' ? resp.error : undefined });
            }
            if (rid.startsWith('mcp-wait')) {
              const servers = resp.response?.mcpServers;
              this.mcpStatus = Array.isArray(servers)
                ? servers.filter((e: Json) => typeof e?.name === 'string').map((e: Json): [string, string] => [e.name, e.status ?? ''])
                : [];
            }
          }
        }
        break;
      }
      case 'rate_limit_event': { const r = parseRateLimits(j); if (r) l.onRateLimits?.(r); break; }
      case 'prompt_suggestion': { const s = cleanSuggestion(typeof j.suggestion === 'string' ? j.suggestion : undefined); if (s) l.onSuggestion?.(s); break; }
      case 'control_request': {
        const r = j.request;
        if (r?.subtype === 'can_use_tool') {
          l.onPermission?.({ requestId: j.request_id, tool: r.tool_name, input: r.input ?? {}, description: r.description ?? '' });
        }
        break;
      }
      case 'result': this.onResult(j); break;
    }
  }

  private consume(text: unknown): void {
    if (typeof text !== 'string') return;
    const i = this.refinements.indexOf(text.trim());
    if (i >= 0) this.refinements.splice(i, 1);
  }

  private onResult(j: Json): void {
    const l = this.l;
    this.finished = true; this.ended = true;
    if (this.refinements.length) { const lost = this.refinements; this.refinements = []; l.onRefineLost?.(lost); }
    if (j.modelUsage && typeof j.modelUsage === 'object') {
      for (const [m, v] of Object.entries<Json>(j.modelUsage)) if (typeof v?.contextWindow === 'number') l.onContextWindow?.(m, v.contextWindow);
    }
    const u = j.usage ?? {};
    l.onUsage?.(u.input_tokens ?? 0, u.output_tokens ?? 0, j.total_cost_usd ?? 0);
    const err = j.is_error === true;
    l.onDone?.(err, err ? (j.result ?? 'Erreur') : undefined);
    this.closeInputWhenQuiet();
  }

  /**
   * Ferme l'entrée (le processus se termine) seulement quand la CLI est restée silencieuse un long moment après
   * le `result`. La fermer tout de suite faisait échouer les permissions d'un travail qui reprend (« Stream closed »).
   */
  private closeInputWhenQuiet(): void {
    clearInterval(this.closer);
    this.closer = setInterval(() => {
      if (!this.ended || this.killed || !this.alive()) { clearInterval(this.closer); return; }
      if (Date.now() - this.lastActivity > timing.quietBeforeCloseMs) {
        clearInterval(this.closer);
        try { this.proc?.stdin?.end(); } catch { /* déjà fermé */ }
      }
    }, Math.min(2000, Math.max(10, timing.quietBeforeCloseMs / 2)));
    this.closer.unref?.();
  }

  private stream(ev: Json): void {
    const l = this.l;
    switch (ev.type) {
      case 'message_start': {
        const u = ev.message?.usage;
        if (u) {
          this.ctx = ['input_tokens', 'cache_creation_input_tokens', 'cache_read_input_tokens'].reduce((a, k) => a + (typeof u[k] === 'number' ? u[k] : 0), 0);
          this.curOut = 0;
          l.onLiveUsage?.(this.ctx, this.doneOut);
        }
        break;
      }
      case 'message_delta':
        if (typeof ev.usage?.output_tokens === 'number') { this.curOut = ev.usage.output_tokens; l.onLiveUsage?.(this.ctx, this.doneOut + this.curOut); }
        break;
      case 'message_stop': this.doneOut += this.curOut; this.curOut = 0; break;
      case 'content_block_delta': {
        const d = ev.delta;
        if (d?.type === 'text_delta') l.onText?.(d.text);
        else if (d?.type === 'thinking_delta') l.onThinking?.(d.thinking);
        break;
      }
    }
  }

  private send(o: Json): void { this.trySend(o); }

  /** Écrit un message sur l'entrée de la CLI ; false si elle est fermée (le processus se termine). */
  private trySend(o: Json): boolean {
    const stdin = this.proc?.stdin;
    if (!stdin || stdin.destroyed || !stdin.writable) return false;
    try { stdin.write(JSON.stringify(o) + '\n'); return true; } catch { return false; }
  }

  /**
   * Requête de contrôle sur le processus de cette conversation (mode, renommage, contexte…). null si le processus est
   * terminé ou son entrée fermée, ou s'il ne répond pas dans [timeoutMs].
   */
  request(subtype: string, fields: Record<string, string> = {}, timeoutMs = 8000): Promise<ControlResult | null> {
    if (this.killed || !this.alive()) return Promise.resolve(null);
    const id = `req-${++this.seq}`;
    return new Promise((resolve) => {
      const timer = setTimeout(() => { this.pending.delete(id); resolve(null); }, timeoutMs);
      this.pending.set(id, (r) => { clearTimeout(timer); resolve(r); });
      if (!this.trySend(controlRequest(id, subtype, fields))) { clearTimeout(timer); this.pending.delete(id); resolve(null); }
    });
  }

  /** Contexte de cette conversation, mesuré par le processus qui la porte (exact, serveurs MCP compris). */
  async contextUsage(timeoutMs = 8000): Promise<ContextUsage | undefined> {
    const r = await this.request('get_context_usage', {}, timeoutMs);
    return r?.ok && r.payload ? parseContextUsage(r.payload) : undefined;
  }

  /** Change le mode de permission tout de suite. null : aucun processus à qui le dire. */
  setPermissionMode(mode: string): Promise<ControlResult | null> { return this.request('set_permission_mode', { mode }); }

  /** Renomme la session côté CLI (visible dans `claude --resume`). */
  renameSession(title: string): Promise<ControlResult | null> { return this.request('rename_session', { title }); }

  /** Précise la consigne pendant que Claude travaille (injecté au prochain point d'arrêt). false si le tour est terminé. */
  refine(text: string): boolean {
    if (this.ended || this.killed || !this.alive()) return false;
    this.refinements.push(text.trim());
    this.send({ type: 'user', message: { role: 'user', content: [{ type: 'text', text }] }, priority: 'next' });
    return true;
  }

  allow(requestId: string, updatedInput: Json): void { this.respond(requestId, { behavior: 'allow', updatedInput }); }
  deny(requestId: string, message: string): void { this.respond(requestId, { behavior: 'deny', message }); }

  private respond(requestId: string, body: Json): void {
    this.send({ type: 'control_response', response: { subtype: 'success', request_id: requestId, response: body } });
  }

  /** Interrompt la réponse en cours. */
  interrupt(): void {
    this.send(controlRequest(randomUUID(), 'interrupt'));
    const p = this.proc;
    const t = setTimeout(() => { if (p && p.exitCode === null) this.kill(); }, 4000);
    t.unref?.();
  }

  kill(): void {
    this.killed = true;
    clearInterval(this.closer);
    this.proc?.kill();
  }
}

function controlRequest(id: string, subtype: string, fields: Record<string, string> = {}): Json {
  return { type: 'control_request', request_id: id, request: { subtype, ...fields } };
}

/** Exécute un processus et renvoie (code, sortie fusionnée) ; délai dépassé : code -1. */
export function exec(cli: string, args: string[], opts: { cwd?: string; timeoutMs?: number; stdin?: string } = {}): Promise<{ code: number; out: string }> {
  return new Promise((resolve) => {
    let out = '';
    let settled = false;
    const finish = (code: number, text?: string) => { if (!settled) { settled = true; clearTimeout(timer); resolve({ code, out: (text ?? out).trim() }); } };
    let p: ChildProcess;
    try { p = spawn(cli, args, { cwd: opts.cwd, stdio: ['pipe', 'pipe', 'pipe'] }); }
    catch (e) { resolve({ code: -1, out: `impossible de lancer la CLI : ${(e as Error).message}` }); return; }
    const timer = setTimeout(() => { p.kill(); finish(-1, 'délai dépassé'); }, opts.timeoutMs ?? 90_000);
    p.stdout!.setEncoding('utf8'); p.stderr!.setEncoding('utf8');
    p.stdout!.on('data', (d: string) => { out += d; });
    p.stderr!.on('data', (d: string) => { out += d; });
    p.stdin!.on('error', () => {});
    p.on('error', (e) => finish(-1, `impossible de lancer la CLI : ${e.message}`));
    p.on('close', (code) => finish(code ?? -1));
    p.stdin!.end(opts.stdin ?? '');
  });
}

/**
 * Lance un processus `claude` à part, lui envoie une seule requête de contrôle (aucun message : aucun appel au
 * modèle) et renvoie sa réponse complète ; undefined en cas d'échec ou de délai dépassé.
 */
function controlOnce(cli: string, args: string[], cwd: string, id: string, subtype: string, timeoutSec: number): Promise<Json | undefined> {
  return new Promise((resolve) => {
    let p: ChildProcess;
    try { p = spawn(cli, args, { cwd, stdio: ['pipe', 'pipe', 'ignore'] }); } catch { resolve(undefined); return; }
    let done = false;
    const finish = (r?: Json) => {
      if (done) return;
      done = true; clearTimeout(timer);
      try { p.stdin?.end(); } catch { /* fermé */ }
      p.kill();
      resolve(r);
    };
    const timer = setTimeout(() => finish(undefined), timeoutSec * 1000);
    p.on('error', () => finish(undefined));
    p.on('close', () => finish(undefined));
    p.stdin!.on('error', () => {});
    createInterface({ input: p.stdout! }).on('line', (line) => {
      if (!line.startsWith('{') || !line.includes(`"${id}"`)) return;
      try { finish(JSON.parse(line)); } catch { /* ligne illisible */ }
    });
    p.stdin!.write(JSON.stringify(controlRequest(id, subtype)) + '\n');
  });
}

const baseArgs = ['-p', '--input-format', 'stream-json', '--output-format', 'stream-json', '--verbose', '--no-session-persistence'];

export interface CliInit { commands: CliCommand[]; models: CliModel[] }

/** Commandes slash et modèles annoncés par la CLI (requête `initialize`, sans appel au modèle). Listes vides en cas d'échec. */
export async function fetchInit(cli: string, cwd: string, timeoutSec = 20): Promise<CliInit> {
  const j = await controlOnce(cli, [...baseArgs, ...NO_MCP], cwd, 'init-cmds', 'initialize', timeoutSec);
  return j ? { commands: parseCommands(j), models: parseModels(j) } : { commands: [], models: [] };
}

/**
 * Contexte d'une conversation mesuré hors de son processus : la session est rechargée (`--resume`) sans rien
 * envoyer ni écrire. Les serveurs MCP ne sont pas démarrés : leurs outils ne sont pas comptés (approximate).
 */
export async function contextUsageOnce(cli: string, cwd: string, sessionId?: string, timeoutSec = 30): Promise<ContextUsage | undefined> {
  const args = [...baseArgs, ...(sessionId ? ['--resume', sessionId] : []), ...NO_MCP];
  const j = await controlOnce(cli, args, cwd, 'ctx-once', 'get_context_usage', timeoutSec);
  if (!j || j.response?.subtype !== 'success') return undefined;
  const payload = controlPayload(j);
  const u = payload && parseContextUsage(payload);
  return u ? { ...u, approximate: true } : undefined;
}

/**
 * Mesure les quotas sans conversation : une requête minimale (Haiku, sans session ni outil) dont on lit
 * l'événement `rate_limit_event`. Coût : quelques millièmes de dollar.
 */
export function probeQuota(cli: string, cwd: string, timeoutSec = 60): Promise<ProbeResult> {
  return new Promise((resolve) => {
    const args = ['-p', '--output-format', 'stream-json', '--verbose', '--no-session-persistence', '--permission-mode', 'dontAsk',
      '--permission-prompts', 'none', '--model', 'claude-haiku-5-5', ...NO_MCP];
    let p: ChildProcess;
    try { p = spawn(cli, args, { cwd, stdio: ['pipe', 'pipe', 'pipe'] }); }
    catch (e) { resolve({ error: `impossible de lancer la CLI : ${(e as Error).message}` }); return; }
    let limits: RateLimits | undefined;
    let err = '';
    let settled = false;
    const finish = (r: ProbeResult) => { if (!settled) { settled = true; clearTimeout(timer); resolve(r); } };
    const timer = setTimeout(() => { p.kill(); finish({ limits, error: 'délai dépassé' }); }, timeoutSec * 1000);
    p.on('error', (e) => finish({ error: `impossible de lancer la CLI : ${e.message}` }));
    p.stderr!.setEncoding('utf8');
    p.stderr!.on('data', (d: string) => { err += d; });
    p.stdin!.on('error', () => {});
    p.stdin!.end('ok');
    createInterface({ input: p.stdout! }).on('line', (line) => {
      if (!line.startsWith('{') || !line.includes('rate_limit_event')) return;
      try { const r = parseRateLimits(JSON.parse(line)); if (r) limits = r; } catch { /* ligne illisible */ }
    });
    p.on('close', () => finish(limits ? { limits } : { error: err.trim() || "la CLI n'a fourni aucune information de quota (compte API, proxy ou version sans cette donnée ?)" }));
  });
}

/** Question ponctuelle sans session ni outil (message de commit). Rejette si la CLI échoue sans rien produire. */
export async function oneShot(cli: string, cwd: string, model: string, prompt: string, signal?: AbortSignal): Promise<string> {
  const args = ['-p', '--no-session-persistence', '--permission-mode', 'dontAsk', '--permission-prompts', 'none'];
  if (model.trim()) args.push('--model', model);
  return new Promise((resolve, reject) => {
    const p = spawn(cli, args, { cwd, stdio: ['pipe', 'pipe', 'pipe'] });
    let out = ''; let err = '';
    p.stdout!.setEncoding('utf8'); p.stderr!.setEncoding('utf8');
    p.stdout!.on('data', (d: string) => { out += d; });
    p.stderr!.on('data', (d: string) => { err += d; });
    p.stdin!.on('error', () => {});
    p.on('error', (e) => reject(e));
    signal?.addEventListener('abort', () => { p.kill(); resolve(out); });
    p.on('close', (code) => (code !== 0 && !out.trim() ? reject(new Error(err.trim() || `code ${code}`)) : resolve(out)));
    p.stdin!.end(prompt);
  });
}
