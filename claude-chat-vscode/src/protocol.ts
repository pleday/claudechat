// Messages échangés entre l'extension (hôte) et l'interface (webview). Types seulement : importé par les deux côtés.
import type { Entry } from './core/sessions';
import type { SlashEntry } from './core/slashCatalog';
import type { ContextUsage } from './core/cliInfo';

export interface ModelChoice { id: string; name: string; description: string; isAlias: boolean }

export interface Attachment {
  /** Clé de suppression : nom de fichier (ou chemin relatif). */
  key: string;
  label: string;
  tip: string;
  kind: 'file' | 'image';
  /** Miniature (URL data:) pour les images. */
  thumb?: string;
}

export interface InteractionView {
  id: string;
  title: string;
  body: string;
  options: string[];
  freeText: boolean;
  canDiff: boolean;
}

export interface UiState {
  streaming: boolean;
  statusText: string;
  statusTip: string;
  resume: { at: string; left: string } | null;
  ringPercent: number;
  ringTip: string;
  mode: string;
  model: string;
  effort: string;
  efforts: string[];
  models: ModelChoice[];
  busyMode: 'refine' | 'queue';
  contextOn: boolean;
  /** Fichier courant de l'éditeur (pastille « contexte »), vide si aucun. */
  contextFile: string;
  remoteOn: boolean;
  autoResumeOn: boolean;
  canFork: boolean;
  forkPending: boolean;
  battery: { remaining: number | null; tip: string };
  usageLine: string;
  queue: string[];
  changes: { count: number; added: number; removed: number } | null;
  suggestion: string | null;
  attachments: Attachment[];
  interaction: InteractionView | null;
  title: string;
  slash: SlashEntry[];
  projectName: string;
  /** Dossier du projet (pour raccourcir les chemins affichés). */
  cwd: string;
}

export type HostMsg =
  | { t: 'state'; patch: Partial<UiState> }
  | { t: 'entries'; entries: Entry[] }
  | { t: 'add'; entry: Entry }
  | { t: 'patch'; i: number; patch: Partial<Entry> }
  | { t: 'mention'; seq: number; results: string[] }
  | { t: 'files'; seq: number; existing: string[] }
  | { t: 'context'; usage: ContextUsage | null; modelLabel: string }
  | { t: 'usage'; report: UsageReport }
  | { t: 'setInput'; text: string; append?: boolean }
  | { t: 'focus' }
  | { t: 'newConversation' };

export interface UsageLine { title: string; used: number | null; reset: string }

export interface UsageReport {
  five: UsageLine;
  week: UsageLine;
  model: string;
  context: string;
  tokens: string;
  cost: string;
  error: string | null;
  noQuota: boolean;
  measured: string;
}

export type WebMsg =
  | { t: 'ready' }
  | { t: 'send'; text: string; mode?: 'refine' | 'queue' }
  | { t: 'stop' }
  | { t: 'newSession' }
  | { t: 'sessions' }
  | { t: 'fork' }
  | { t: 'rename' }
  | { t: 'setMode'; id: string }
  | { t: 'setModel'; id: string }
  | { t: 'setEffort'; id: string }
  | { t: 'setBusyMode'; mode: 'refine' | 'queue' }
  | { t: 'toggleContext' }
  | { t: 'toggleRemote' }
  | { t: 'toggleAutoResume' }
  | { t: 'interact'; id: string; choice: string }
  | { t: 'viewDiff'; id: string }
  | { t: 'attachFiles' }
  | { t: 'removeAttachment'; key: string }
  | { t: 'pasteImage'; name: string; media: string; data: string }
  | { t: 'mention'; seq: number; q: string }
  | { t: 'checkFiles'; seq: number; paths: string[] }
  | { t: 'openFile'; spec: string }
  | { t: 'openChanges' }
  | { t: 'forgetChanges' }
  | { t: 'queueClear' }
  | { t: 'queueRemove'; i: number }
  | { t: 'queueMove'; i: number; to: number }
  | { t: 'acceptSuggestion' }
  | { t: 'dismissSuggestion' }
  | { t: 'showContext' }
  | { t: 'showUsage' }
  | { t: 'refreshQuota' }
  | { t: 'mcp' }
  | { t: 'settings' }
  | { t: 'cancelResume' };
