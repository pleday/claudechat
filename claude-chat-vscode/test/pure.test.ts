import { describe, expect, it } from 'vitest';
import { contextPercent, fmtTokens, isAlias, parseCommands, parseContextUsage, parseModels } from '../src/core/cliInfo';
import { InputHistory } from '../src/core/inputHistory';
import { resolveMention } from '../src/core/mentionPath';
import { ModelCatalog } from '../src/core/models';
import { SlashCatalog, Tips } from '../src/core/slashCatalog';
import { isResume, pickSticky } from '../src/core/stickyPrompt';
import {
  busyWarning, chunkText, describeTool, expandSlash, formatDuration, Fuzzy, Mentions, moveItem, queuePreview, Quota, QuotaGuard, SlashToken, toolSummary,
} from '../src/core/textTools';

const init = (payload: object) => ({ response: { subtype: 'success', response: payload } });

describe('cliInfo', () => {
  it('modèles : ignore « default », dédoublonne, lit les efforts', () => {
    const m = parseModels(init({ models: [
      { value: 'default', displayName: 'Default' },
      { value: 'sonnet', resolvedModel: 'claude-sonnet-5-5', displayName: 'Sonnet 5.5', supportsEffort: true, supportedEffortLevels: ['low', 'high'] },
      { value: 'claude-sonnet-5-5', resolvedModel: 'claude-sonnet-5-5' },
      { value: 'haiku', displayName: 'Haiku', supportsEffort: false, supportedEffortLevels: ['low'] },
    ] }));
    expect(m.map((x) => x.id)).toEqual(['claude-sonnet-5-5', 'haiku']);
    expect(m[0].efforts).toEqual(['low', 'high']);
    expect(m[1].efforts).toEqual([]);
    expect(isAlias(m[0])).toBe(true);
    expect(parseModels({})).toEqual([]);
  });

  it('commandes : normalise les espaces, ignore les entrées invalides', () => {
    expect(parseCommands(init({ commands: [{ name: 'compact', description: 'a  b\nc' }, { name: '' }, 3] }))).toEqual([{ name: 'compact', description: 'a b c' }]);
  });

  it('contexte : totaux, catégories, seuil de compaction', () => {
    const u = parseContextUsage({
      maxTokens: 200000, totalTokens: 50000, model: 'claude-sonnet-5-5', autoCompactThreshold: 160000, isAutoCompactEnabled: true,
      categories: [{ id: 'messages', tokens: 30000, kind: 'used' }, { id: 'free_space', tokens: 150000, kind: 'free' }],
      messageBreakdown: { toolCallTokens: 1, toolResultTokens: 2, assistantMessageTokens: 3, userMessageTokens: 4 },
    })!;
    expect(contextPercent(u)).toBe(25);
    expect(u.categories[0].name).toBe('Messages');
    expect(u.autoCompactAt).toBe(160000);
    expect(parseContextUsage({ maxTokens: 0 })).toBeUndefined();
    expect(parseContextUsage({ maxTokens: 10, totalTokens: 1, isAutoCompactEnabled: false, autoCompactThreshold: 5 })!.autoCompactAt).toBeUndefined();
  });

  it('formate les tokens à la française', () => {
    expect(fmtTokens(842)).toBe('842');
    expect(fmtTokens(134_600)).toBe('134,6k');
    expect(fmtTokens(1_000_000)).toBe('1,0M');
  });
});

describe('catalogue de modèles', () => {
  it('secours sans CLI, puis modèles de la CLI', () => {
    const c = new ModelCatalog();
    expect(c.resolve('Sonnet')).toBe('claude-sonnet-5-5');
    expect(c.label('claude-opus-5-5')).toBe('Opus 5.5');
    expect(c.label('claude-foo-4-6-20250101')).toBe('Foo 4.6');
    expect(c.label('inconnu')).toBe('inconnu');
    expect(c.effortChoices('x', ['auto', 'low'])).toEqual(['auto', 'low']);
    c.update([{ id: 'claude-x', alias: 'x', name: 'X', description: 'd', efforts: ['low'] }]);
    c.update([]); // une CLI muette ne remplace rien
    expect(c.all()).toHaveLength(1);
    expect(c.effortChoices('claude-x', [])).toEqual(['auto', 'low']);
    expect(c.resolve('x')).toBe('claude-x');
    expect(c.display('claude-x')).toBe('X  (claude-x)');
  });
});

describe('saisie : mentions, slash, recherche floue', () => {
  it('trouve les mentions sans doublon', () => {
    expect(Mentions.find('voir @src/a.ts et @src/a.ts puis @b.md, mail a@b.c')).toEqual(['src/a.ts', 'b.md']);
  });
  it('jeton actif avant le curseur', () => {
    expect(Mentions.activeToken('regarde @sr', 11)).toEqual({ start: 8, query: 'sr' });
    expect(Mentions.activeToken('regarde @sr ok', 14)).toBeUndefined();
    expect(SlashToken.activeQuery('/co', 3)).toBe('co');
    expect(SlashToken.activeQuery('texte /co', 9)).toBeUndefined();
    expect(SlashToken.filter('c', ['compact', 'clear', 'model'])).toEqual(['clear', 'compact']);
  });
  it('refuse les mentions hors du projet pour le téléphone', () => {
    expect(resolveMention('a/b.ts', '/p', true)).toBe('/p/a/b.ts');
    expect(resolveMention('../x', '/p', true)).toBeUndefined();
    expect(resolveMention('/etc/passwd', '/p', true)).toBeUndefined();
    expect(resolveMention('/etc/passwd', '/p', false)).toBe('/etc/passwd');
  });
  it('classe les fichiers', () => {
    expect(Fuzzy.search('chat', ['src/ChatPanel.kt', 'docs/archive/c/h/a/t.md', 'README.md'])[0]).toBe('src/ChatPanel.kt');
    expect(Fuzzy.score('zz', 'abc')).toBeUndefined();
    expect(Fuzzy.search('', ['a', 'b'])).toEqual(['a', 'b']);
  });
  it('développe /explain', () => {
    expect(expandSlash('/explain')).toMatch(/^Explique ce code/);
    expect(expandSlash('/fix  le parseur')).toMatch(/\n\nle parseur$/);
    expect(expandSlash('/compact')).toBe('/compact');
    expect(expandSlash('bonjour')).toBe('bonjour');
  });
});

describe('catalogue des commandes et astuces (données communes avec JetBrains)', () => {
  it('regroupe et classe', () => {
    const all = SlashCatalog.build([{ name: 'compact', description: 'x' }, { name: 'acme:deploy', description: 'd' }, { name: 'mon-skill', description: 's' }, { name: 'review', description: 'doublon' }, { name: '__hidden', description: '' }]);
    expect(all.find((e) => e.name === 'review')?.category).toBe(SlashCatalog.PLUGIN);
    expect(all.find((e) => e.name === 'acme:deploy')?.category).toBe(SlashCatalog.PLUGINS);
    expect(all.find((e) => e.name === 'mon-skill')?.category).toBe(SlashCatalog.SKILLS);
    expect(all.some((e) => e.name === '__hidden')).toBe(false);
    const g = SlashCatalog.group('', all).map((x) => x[0]);
    expect(g).toEqual(['Raccourcis du plugin', 'Session', 'Skills et extensions', 'Plugins']);
    expect(SlashCatalog.group('co', all).find((x) => x[0] === 'Session')![1].map((e) => e.name)).toEqual(['compact']);
  });
  it('astuces uniques, groupes bouclés', () => {
    expect(new Set(Tips.all).size).toBe(Tips.all.length);
    expect(Tips.group(0)).toHaveLength(Tips.GROUP);
    expect(Tips.group(0)).toEqual(Tips.group(Tips.groups));
    expect(Tips.group(-1)).toEqual(Tips.group(Tips.groups - 1));
    expect(new Set(Array.from({ length: Tips.groups }, (_, i) => Tips.group(i)).flat())).toEqual(new Set(Tips.all));
  });
});

describe('message épinglé', () => {
  it('reconnaît les demandes de reprise', () => {
    for (const t of ['continue', 'Reprends là où tu t\'étais arrêté', 'oui vas-y', 'Le quota est réinitialisé : reprends ta tâche']) expect(isResume(t), t).toBe(true);
    for (const t of ['continue le refactoring de la classe Foo', 'corrige le bug', '']) expect(isResume(t), t).toBe(false);
  });
  it('épingle la tâche, pas la reprise', () => {
    expect(pickSticky(['fais X', 'continue'])).toEqual({ prompt: 'fais X', resumedWith: 'continue' });
    expect(pickSticky(['fais X', 'puis Y'])).toEqual({ prompt: 'puis Y' });
    expect(pickSticky(['continue'])).toEqual({ prompt: 'continue' });
    expect(pickSticky([])).toBeUndefined();
  });
});

describe('historique de saisie', () => {
  it('rappelle et restitue le brouillon', () => {
    const h = new InputHistory();
    h.add('un'); h.add('deux'); h.add('deux');
    expect(h.previous('brouillon')).toBe('deux');
    expect(h.previous('deux')).toBe('un');
    expect(h.previous('un')).toBeUndefined();
    expect(h.next('un')).toBe('deux');
    expect(h.next('deux')).toBe('brouillon');
    expect(h.next('brouillon')).toBeUndefined();
  });
  it('borne la taille', () => {
    const h = new InputHistory(2);
    ['a', 'b', 'c'].forEach((x) => h.add(x));
    expect(h.previous('')).toBe('c');
    expect(h.previous('c')).toBe('b');
    expect(h.previous('b')).toBeUndefined();
  });
});

describe('outils, quotas, durées', () => {
  it('résume les outils', () => {
    expect(toolSummary('Bash', { command: 'ls' })).toBe('ls');
    expect(toolSummary('Grep', { pattern: 'x', path: 'src' })).toBe('x in src');
    expect(toolSummary('Inconnu', { a: 1 })).toBe('{"a":1}');
    expect(describeTool('Edit', { file_path: '/a', old_string: 'x', new_string: 'y' })).toBe('Fichier : /a\n- x\n+ y');
    expect(describeTool('MultiEdit', { file_path: '/a', edits: [1, 2] })).toContain('2 modification(s)');
  });
  it('jauges de quota', () => {
    expect(Quota.percent({ utilization: 0.42, resetsAt: 0 }, 10)).toBe(42);
    expect(Quota.percent({ utilization: 0.9, resetsAt: 5 }, 10)).toBe(0); // fenêtre réinitialisée
    expect(Quota.remainingPercent({ utilization: 0.42, resetsAt: 0 }, 10)).toBe(58);
    expect(Quota.remainingPercent(undefined, 10)).toBeUndefined();
    expect(Quota.remaining(10 + 3 * 3600 + 5 * 60, 10)).toBe('3 h 05');
    expect(Quota.remaining(10 + 90_000, 10)).toBe('1 j 1 h');
    expect(Quota.remaining(10 + 600, 10)).toBe('10 min');
  });
  it('reprise automatique après quota', () => {
    expect(QuotaGuard.blockedUntil(undefined, 'erreur banale', 100)).toBeUndefined();
    expect(QuotaGuard.blockedUntil({ status: 'rejected', resetsAt: 1000 }, undefined, 100, 60)).toBe(1060);
    expect(QuotaGuard.blockedUntil(undefined, 'Usage limit reached', 100, 60)).toBe(100 + 1800);
    expect(QuotaGuard.blockedUntil({ fiveHour: { utilization: 1, resetsAt: 500 } }, 'rate limit', 100, 0)).toBe(500);
  });
  it('durées', () => {
    expect(formatDuration(200)).toBe("moins d'une seconde");
    expect(formatDuration(42_000)).toBe('42 s');
    expect(formatDuration(83_000)).toBe('1 min 23 s');
    expect(formatDuration(120_000)).toBe('2 min');
    expect(formatDuration(3_900_000)).toBe('1 h 05 min');
    expect(formatDuration(7_200_000)).toBe('2 h');
  });
  it('file d\'attente, découpage, avertissement', () => {
    expect(queuePreview(['a', 'b\nsuite'])).toBe('b  (+1 autre)');
    expect(queuePreview([])).toBe('');
    const l = [1, 2, 3, 4]; expect(moveItem(l, 0, 2)).toBe(2); expect(l).toEqual([2, 3, 1, 4]); expect(moveItem(l, 9, 0)).toBe(9);
    const parts = chunkText(('mot '.repeat(50) + '\n').repeat(5), 120);
    expect(parts.every((p) => p.length <= 120)).toBe(true);
    expect(parts.join(' ').replace(/\s+/g, ' ')).toBe(('mot '.repeat(50)).repeat(5).replace(/\s+/g, ' ').trim());
    expect(busyWarning(false, 3, 'x')).toBeUndefined();
    expect(busyWarning(true, 0, 'Stop.')).toBe('Claude est en train de répondre. Stop.');
    expect(busyWarning(true, 2, 'Stop.')).toContain('2 messages en attente seront perdus.');
  });
});
