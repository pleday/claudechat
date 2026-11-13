// Vecteurs de test communs avec le plugin JetBrains : ../shared/test-vectors/*.json (entrée -> sortie attendue).
// Régénérer les sorties après un changement voulu : UPDATE_VECTORS=1 npm test -- vectors (puis relire le diff et lancer aussi les tests Kotlin).
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { parseRateLimits, cleanSuggestion } from '../src/core/cli';
import { fmtTokens, parseCommands, parseContextUsage, parseModels, contextPercent } from '../src/core/cliInfo';
import { lineCount } from '../src/core/changes';
import { InputHistory } from '../src/core/inputHistory';
import * as mcp from '../src/core/mcp';
import { ModelCatalog } from '../src/core/models';
import { listSessions, loadSession, paths, projectDir } from '../src/core/sessions';
import { SlashCatalog } from '../src/core/slashCatalog';
import { isResume, pickSticky } from '../src/core/stickyPrompt';
import {
  busyWarning, chunkText, describeTool, formatDuration, Fuzzy, Mentions, queuePreview, Quota, QuotaGuard, SlashToken, toolSummary,
} from '../src/core/textTools';
import { tmp } from './helpers';

const DIR = join(__dirname, '..', '..', 'shared', 'test-vectors');
const UPDATE = process.env.UPDATE_VECTORS === '1';

/** Forme canonique : null et propriétés absentes sont équivalents. */
export function canon(x: unknown): unknown {
  if (Array.isArray(x)) return x.map(canon);
  if (x && typeof x === 'object') {
    return Object.fromEntries(Object.entries(x).filter(([, v]) => v !== null && v !== undefined).map(([k, v]) => [k, canon(v)]));
  }
  return x ?? null;
}

type Handler = (i: any) => unknown;
const win = (w: any) => (w ? { utilization: w.utilization, resetsAt: w.resetsAt } : undefined);
const limits = (l: any) => (l ? { ...l, fiveHour: win(l.fiveHour), sevenDay: win(l.sevenDay) } : undefined);
const catalog = () => new ModelCatalog();

const handlers: Record<string, Record<string, Handler>> = {
  sticky: { isResume: (i) => isResume(i), pick: (i) => pickSticky(i) ?? null },
  'text-tools': {
    fuzzySearch: (i) => Fuzzy.search(i.query, i.paths),
    mentionsFind: (i) => Mentions.find(i),
    mentionsActive: (i) => Mentions.activeToken(i.text, i.caret) ?? null,
    slashActive: (i) => SlashToken.activeQuery(i.text, i.caret) ?? null,
    slashFilter: (i) => SlashToken.filter(i.query, i.commands),
    toolSummary: (i) => toolSummary(i.name, i.input),
    describeTool: (i) => describeTool(i.name, i.input),
    formatDuration: (i) => formatDuration(i),
    quotaRemaining: (i) => Quota.remaining(i.resetsAt, i.now),
    quotaPercent: (i) => Quota.percent(win(i.window), i.now) ?? null,
    quotaGuard: (i) => QuotaGuard.blockedUntil(limits(i.limits), i.message ?? undefined, i.now, i.delay) ?? null,
    queuePreview: (i) => queuePreview(i.queue, i.max),
    busyWarning: (i) => busyWarning(i.streaming, i.queued, i.consequence) ?? null,
    chunkText: (i) => chunkText(i.text, i.max),
    fmtTokens: (i) => fmtTokens(i),
    modelLabel: (i) => catalog().label(i),
    modelResolve: (i) => catalog().resolve(i),
    lineCount: (i) => lineCount(i),
  },
  'cli-parsing': {
    rateLimits: (i) => parseRateLimits(i) ?? null,
    models: (i) => parseModels(i),
    commands: (i) => parseCommands(i),
    contextUsage: (i) => {
      const u = parseContextUsage(i);
      return u ? { ...u, percent: contextPercent(u) } : null;
    },
    cleanSuggestion: (i) => cleanSuggestion(i) ?? null,
  },
  mcp: {
    parseList: (i) => mcp.parseList(i),
    statusOf: (i) => mcp.statusOf(i),
    mask: (i) => mcp.mask(i),
    normalize: (i) => {
      const n = mcp.normalize(i.text, i.name);
      return { servers: n.servers.map(([k, v]) => [k, JSON.parse(v)]), error: n.error ?? null };
    },
  },
  'input-history': {},
  'slash-catalog': {},
  'session-transcript': {},
};

function runHistory(s: any): void {
  const h = new InputHistory(s.max);
  for (const op of s.ops) {
    if (op.op === 'add') h.add(op.text);
    else {
      const got = (op.op === 'previous' ? h.previous(op.current) : h.next(op.current)) ?? null;
      if (UPDATE) op.out = got; else expect(got, JSON.stringify(op)).toEqual(op.out);
    }
  }
}

function runCatalog(c: any): void {
  const all = SlashCatalog.build(c.cli);
  const outBuild = all.map((e) => ({ name: e.name, category: e.category }));
  const outGroup = c.queries.map((q: string) => SlashCatalog.group(q, all).map(([cat, l]) => [cat, l.map((e) => e.name)]));
  if (UPDATE) { c.build = outBuild; c.group = outGroup; return; }
  expect(outBuild).toEqual(c.build);
  expect(outGroup).toEqual(c.group);
}

async function runTranscript(c: any): Promise<void> {
  const home = tmp();
  paths.claudeDir = () => join(home, '.claude');
  paths.dataHome = () => join(home, 'data');
  const cwd = '/work/vecteur';
  mkdirSync(projectDir(cwd), { recursive: true });
  writeFileSync(join(projectDir(cwd), 's.jsonl'), c.lines.map((l: unknown) => JSON.stringify(l)).join('\n') + '\n');
  const [info] = await listSessions(cwd);
  const s = await loadSession(info);
  const entries = s.entries.map((e) => ({ kind: e.kind, text: e.text, toolName: e.toolName, result: e.result, isError: e.isError, model: e.model }));
  if (UPDATE) { c.title = info.title; c.entries = entries; return; }
  expect(info.title).toBe(c.title);
  expect(canon(entries)).toEqual(canon(c.entries));
}

describe('vecteurs communs (shared/test-vectors)', () => {
  for (const file of Object.keys(handlers)) {
    const path = join(DIR, `${file}.json`);
    const data = JSON.parse(readFileSync(path, 'utf8'));
    it(file, async () => {
      for (const [section, h] of Object.entries(handlers[file])) {
        for (const c of data[section]) {
          const got = canon(h(c.in));
          if (UPDATE) c.out = got; else expect(got, `${section}(${JSON.stringify(c.in)})`).toEqual(canon(c.out));
        }
      }
      if (file === 'input-history') data.scenarios.forEach(runHistory);
      if (file === 'slash-catalog') data.cases.forEach(runCatalog);
      if (file === 'session-transcript') for (const c of data.cases) await runTranscript(c);
      if (UPDATE) writeFileSync(path, JSON.stringify(data, null, 1) + '\n');
    });
  }

  it('le manifeste de l\'extension suit les constantes communes', async () => {
    const { PERMISSION_MODES, EFFORTS, DEFAULT_MODEL } = await import('../src/core/constants');
    const props = JSON.parse(readFileSync(join(__dirname, '..', 'package.json'), 'utf8')).contributes.configuration.properties;
    expect(props['claudeChat.permissionMode'].enum).toEqual(PERMISSION_MODES.map((m) => m[0]));
    expect(props['claudeChat.effort'].enum).toEqual(EFFORTS);
    expect(props['claudeChat.model'].default).toBe(DEFAULT_MODEL);
  });
});
