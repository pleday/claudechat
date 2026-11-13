import { execFileSync } from 'node:child_process';
import { mkdirSync, readFileSync, statSync, utimesSync, writeFileSync, rmSync, renameSync } from 'node:fs';
import { join } from 'node:path';
import { beforeEach, describe, expect, it } from 'vitest';
import { ChangeTracker, FsBaseline, GENERATED_DIRS, lineCount, lineStats, readTextOrNull } from '../src/core/changes';
import * as mcp from '../src/core/mcp';
import { deleteSession, listSessions, liveTitle, loadSession, paths, projectDir, renameSession, setArchived } from '../src/core/sessions';
import { tmp } from './helpers';

describe('sessions de la CLI', () => {
  let home: string; let cwd: string;
  beforeEach(() => {
    home = tmp();
    paths.claudeDir = () => join(home, '.claude');
    paths.dataHome = () => join(home, 'data');
    cwd = '/work/mon-projet';
    mkdirSync(projectDir(cwd), { recursive: true });
  });
  const write = (id: string, lines: object[], mtime = Date.now() / 1000) => {
    const f = join(projectDir(cwd), `${id}.jsonl`);
    writeFileSync(f, lines.map((l) => JSON.stringify(l)).join('\n') + '\n');
    utimesSync(f, mtime, mtime);
    return f;
  };
  const user = (t: string) => ({ type: 'user', message: { role: 'user', content: t } });

  it('liste les plus récentes d\'abord, avec le titre généré ou le premier message', async () => {
    write('a', [user('premier message de a')], 1000);
    write('b', [user('message de b'), { type: 'ai-title', aiTitle: 'Titre IA' }], 2000);
    write('c', [user('<command-name>interne</command-name>'), user('vrai texte')], 500);
    const l = await listSessions(cwd);
    expect(l.map((s) => [s.id, s.title])).toEqual([['b', 'Titre IA'], ['a', 'premier message de a'], ['c', 'vrai texte']]);
    expect((await listSessions(cwd, 1, 1)).map((s) => s.id)).toEqual(['a']);
  });

  it('un titre personnalisé de la CLI l\'emporte sur le titre généré', async () => {
    write('a', [user('x'), { type: 'ai-title', aiTitle: 'IA' }, { type: 'custom-title', customTitle: 'Mon titre' }]);
    expect((await listSessions(cwd))[0].title).toBe('Mon titre');
  });

  it('renommer, archiver, supprimer (métadonnées partagées avec le plugin JetBrains)', async () => {
    write('a', [user('un')], 1000); write('b', [user('deux')], 2000);
    await renameSession('a', 'Renommée');
    await setArchived('b', true);
    expect((await listSessions(cwd)).map((s) => s.title)).toEqual(['Renommée']);
    expect((await listSessions(cwd, 0, 30, true)).map((s) => [s.id, s.archived])).toEqual([['b', true], ['a', false]]);
    const meta = JSON.parse(readFileSync(join(paths.dataHome(), 'session-meta.json'), 'utf8'));
    expect(meta).toEqual({ titles: { a: 'Renommée' }, archived: ['b'] });
    const [a] = await listSessions(cwd);
    await deleteSession(a);
    expect((await listSessions(cwd, 0, 30, true)).map((s) => s.id)).toEqual(['b']);
  });

  it('lit un session-meta.json incomplet sans planter', async () => {
    mkdirSync(paths.dataHome(), { recursive: true });
    writeFileSync(join(paths.dataHome(), 'session-meta.json'), '{"titles":null}');
    write('a', [user('un')]);
    expect(await listSessions(cwd)).toHaveLength(1);
    writeFileSync(join(paths.dataHome(), 'session-meta.json'), '{tronqué');
    expect(await listSessions(cwd)).toHaveLength(1);
  });

  it('reconstitue le transcript : texte, réflexion, outils et leurs résultats', async () => {
    const f = write('s', [
      user('lis a.txt'),
      { type: 'assistant', message: { model: 'claude-sonnet-5-5', content: [{ type: 'thinking', thinking: 'hmm' }, { type: 'text', text: 'ok' }, { type: 'tool_use', id: 't1', name: 'Read', input: { file_path: '/a.txt' } }] } },
      { type: 'user', message: { content: [{ type: 'tool_result', tool_use_id: 't1', content: [{ type: 'text', text: 'contenu' }], is_error: false }] } },
      { type: 'assistant', message: { content: [{ type: 'text', text: '  ' }] } },
    ]);
    const s = await loadSession({ id: 's', title: 'T', updated: 0, archived: false, file: f });
    expect(s.entries.map((e) => e.kind)).toEqual(['user', 'thinking', 'text', 'tool']);
    expect(s.entries[3]).toMatchObject({ toolName: 'Read', result: 'contenu', isError: false, model: 'claude-sonnet-5-5' });
  });

  it('lit le titre vivant dans la fin du fichier', async () => {
    write('s', [user('x'), { type: 'ai-title', aiTitle: 'Premier' }, ...Array.from({ length: 50 }, () => user('y'.repeat(200))), { type: 'ai-title', aiTitle: 'Récent' }]);
    expect(await liveTitle(cwd, 's', 2000)).toBe('Récent');
    expect(await liveTitle(cwd, 'absent')).toBeUndefined();
  });
});

describe('fichiers modifiés par Claude', () => {
  it('compte les lignes', () => {
    expect(lineCount('')).toBe(0);
    expect(lineCount('a\nb\n')).toBe(2);
    expect(lineCount('a\nb')).toBe(2);
    expect(lineStats('a\nb\nc\n', 'a\nB\nc\nd\n')).toEqual([2, 1]);
    expect(lineStats('', 'a\nb\n')).toEqual([2, 0]);
    expect(lineStats('a\nb\n', '')).toEqual([0, 2]);
  });

  function setup() {
    const dir = tmp();
    const t = new ChangeTracker(() => dir);
    return { dir, t, f: (n: string) => join(dir, n) };
  }

  it('mémorise l\'état avant la première modification seulement', () => {
    const { t, f } = setup();
    writeFileSync(f('a.txt'), 'un\ndeux\n');
    t.beforeEdit('Edit', { file_path: f('a.txt') });
    writeFileSync(f('a.txt'), 'un\nDEUX\n');
    t.beforeEdit('Edit', { file_path: f('a.txt') }); // déjà suivi : l'état initial est conservé
    writeFileSync(f('a.txt'), 'un\nDEUX\ntrois\n');
    const [c] = t.changes();
    expect(c).toMatchObject({ rel: 'a.txt', status: 'modified', added: 2, removed: 1, before: 'un\ndeux\n' });
    t.beforeEdit('Read', { file_path: f('b.txt') });
    expect(t.changes()).toHaveLength(1);
  });

  it('nouveau fichier, fichier supprimé, modification annulée', () => {
    const { t, f } = setup();
    t.beforeEdit('Write', { file_path: f('new.txt') });
    writeFileSync(f('new.txt'), 'x\ny\n');
    writeFileSync(f('gone.txt'), 'bye\n');
    t.beforeEdit('Edit', { file_path: f('gone.txt') });
    rmSync(f('gone.txt'));
    writeFileSync(f('same.txt'), 'idem');
    t.beforeEdit('Edit', { file_path: f('same.txt') });
    writeFileSync(f('same.txt'), 'autre'); writeFileSync(f('same.txt'), 'idem');
    const by = Object.fromEntries(t.changes().map((c) => [c.rel, c]));
    expect(by['new.txt']).toMatchObject({ status: 'new', added: 2, before: null });
    expect(by['gone.txt']).toMatchObject({ status: 'deleted', removed: 1, before: 'bye\n' });
    expect(by['same.txt']).toBeUndefined();
  });

  it('un chemin relatif est résolu depuis le projet ; un fichier binaire n\'est pas suivi', () => {
    const { t, f, dir } = setup();
    writeFileSync(f('r.txt'), 'a\n');
    t.beforeEdit('Edit', { file_path: 'r.txt' });
    writeFileSync(f('r.txt'), 'b\n');
    expect(t.changes()[0].path).toBe(join(dir, 'r.txt'));
    const bin = new ChangeTracker(() => dir, () => { throw new Error('binaire'); });
    bin.beforeEdit('Edit', { file_path: f('r.txt') });
    expect(bin.isEmpty).toBe(true);
  });

  it('les modifications faites à la main entre deux réponses ne sont pas attribuées à Claude', () => {
    const { t, f } = setup();
    writeFileSync(f('a.txt'), 'v1\n');
    t.beforeEdit('Edit', { file_path: f('a.txt') });
    writeFileSync(f('a.txt'), 'v2\n');
    t.endTurn();
    writeFileSync(f('a.txt'), 'v2 + moi\n'); // l'utilisateur modifie
    t.startTurn();
    expect(t.isEmpty).toBe(true);
    t.clear();
    expect(t.changes()).toEqual([]);
  });

  it('refuse de lire un fichier trop gros', () => {
    const { f } = setup();
    writeFileSync(f('gros.txt'), 'x'.repeat(100));
    expect(() => readTextOrNull(f('gros.txt'), 10)).toThrow();
    expect(readTextOrNull(f('absent.txt'))).toBeNull();
  });

  describe('photo du projet (FsBaseline)', () => {
    const touch = (p: string, sec: number) => utimesSync(p, sec, sec);

    it('retrouve créations, modifications dans la fenêtre et suppressions', async () => {
      const dir = tmp();
      writeFileSync(join(dir, 'm.txt'), 'avant\n'); writeFileSync(join(dir, 'ancien.txt'), 'x\n'); writeFileSync(join(dir, 'del.txt'), 'bye\n');
      const b = new FsBaseline(() => dir);
      await b.capture();
      const now = Date.now();
      writeFileSync(join(dir, 'm.txt'), 'après la commande\n'); writeFileSync(join(dir, 'n.txt'), 'neuf\n'); rmSync(join(dir, 'del.txt'));
      // modifié en dehors de la fenêtre (à la main, bien plus tard) : non attribué
      writeFileSync(join(dir, 'ancien.txt'), 'main\n'); touch(join(dir, 'ancien.txt'), now / 1000 + 3600);
      const found = Object.fromEntries((await b.scan(now - 1000, now + 1000)).map((o) => [o.path.split('/').pop(), o]));
      expect(found['m.txt']).toMatchObject({ kind: 'modified', before: 'avant\n' });
      expect(found['n.txt']).toMatchObject({ kind: 'new', before: null });
      expect(found['del.txt']).toMatchObject({ kind: 'deleted', before: 'bye\n' });
      expect(found['ancien.txt']).toBeUndefined();
    });

    it('un renommage (date conservée) est une suppression plus une création', async () => {
      const dir = tmp();
      writeFileSync(join(dir, 'a.txt'), 'x\n');
      const b = new FsBaseline(() => dir);
      await b.capture();
      renameSync(join(dir, 'a.txt'), join(dir, 'b.txt'));
      const r = await b.scan(Date.now() - 1000, Date.now() + 1000);
      expect(r.map((o) => `${o.kind}:${o.path.split('/').pop()}`).sort()).toEqual(['deleted:a.txt', 'new:b.txt']);
    });

    it('ignore les dossiers générés, et les projets trop gros', async () => {
      const dir = tmp();
      mkdirSync(join(dir, 'node_modules')); writeFileSync(join(dir, 'node_modules', 'x.js'), 'x');
      writeFileSync(join(dir, 'a.txt'), 'a');
      const b = new FsBaseline(() => dir, (n) => GENERATED_DIRS.has(n));
      await b.capture();
      expect(b.fileCount).toBe(1);
      const big = new FsBaseline(() => dir, () => false, () => false, 256 * 1024, 1024, 1);
      writeFileSync(join(dir, 'b.txt'), 'b');
      await big.capture();
      writeFileSync(join(dir, 'c.txt'), 'c');
      expect(await big.scan(0, Date.now() + 1000)).toEqual([]); // photo incomplète : rien plutôt que tout
    });

    it('binaire et gros fichiers : suivis mais non comparables', async () => {
      const dir = tmp();
      writeFileSync(join(dir, 'b.bin'), Buffer.from([1, 0, 2])); writeFileSync(join(dir, 'g.txt'), 'x'.repeat(300_000));
      const b = new FsBaseline(() => dir);
      await b.capture();
      writeFileSync(join(dir, 'b.bin'), Buffer.from([9, 0, 9])); writeFileSync(join(dir, 'g.txt'), 'y'.repeat(300_000));
      expect(await b.scan(Date.now() - 1000, Date.now() + 1000)).toEqual([]);
      void statSync;
    });

    it('le suivi attribue à Claude ce que la commande a fait', async () => {
      const dir = tmp();
      writeFileSync(join(dir, 'a.txt'), 'avant\n');
      const t = new ChangeTracker(() => dir);
      const b = new FsBaseline(() => dir);
      await b.capture();
      const s = Date.now();
      writeFileSync(join(dir, 'a.txt'), 'après la commande\n'); writeFileSync(join(dir, 'z.txt'), 'neuf\n');
      await t.afterCommand(b, s, Date.now());
      expect(t.changes().map((c) => `${c.status}:${c.rel}`).sort()).toEqual(['modified:a.txt', 'new:z.txt']);
    });
  });

  it('git ne change rien au suivi (commit automatique de Claude)', () => {
    const { t, f, dir } = setup();
    const git = (...a: string[]) => execFileSync('git', ['-C', dir, '-c', 'user.name=t', '-c', 'user.email=t@t', '-c', 'core.hooksPath=/dev/null', '-c', 'commit.gpgsign=false', ...a]);
    git('init', '-q'); writeFileSync(f('a.txt'), 'v1\n'); git('add', '.'); git('commit', '-qm', 'init');
    t.beforeEdit('Edit', { file_path: f('a.txt') });
    writeFileSync(f('a.txt'), 'v2\n'); git('commit', '-qam', 'auto');
    expect(t.changes()[0]).toMatchObject({ status: 'modified', before: 'v1\n' });
  });
});

describe('serveurs MCP', () => {
  it('analyse la sortie de `claude mcp list`', () => {
    const l = mcp.parseList(`Checking MCP server health...\n\nidea: http://localhost:64342/stream (HTTP) - ✓ Connected\nsonar: npx sonar - ✗ Failed to connect\nclaude.ai Gmail: https://x - ! Needs authentication`);
    expect(l).toEqual([
      { name: 'idea', target: 'http://localhost:64342/stream', type: 'http', status: 'connected' },
      { name: 'sonar', target: 'npx sonar', type: undefined, status: 'failed' },
      { name: 'claude.ai Gmail', target: 'https://x', type: undefined, status: 'needs_auth' },
    ]);
  });

  it('lit les trois configurations et fusionne les statuts', () => {
    const home = tmp(); const cwd = join(home, 'p'); mkdirSync(cwd);
    const claudeJson = join(home, '.claude.json');
    writeFileSync(claudeJson, JSON.stringify({ mcpServers: { u: { command: 'npx', args: ['a', 'b'], env: { K: 'v' } } }, projects: { [cwd]: { mcpServers: { l: { type: 'http', url: 'http://x', headers: { Authorization: 'Bearer s' } } } } } }));
    writeFileSync(join(cwd, '.mcp.json'), JSON.stringify({ mcpServers: { p: { url: 'http://p' } } }));
    const cfg = mcp.readConfigs(cwd, claudeJson, join(cwd, '.mcp.json'));
    expect(cfg.map((s) => `${s.scope}:${s.name}:${s.target}`)).toEqual(['user:u:npx a b', 'project:p:http://p', 'local:l:http://x']);
    const merged = mcp.merge(cfg, [{ name: 'u', target: '', status: 'connected' }, { name: 'claude.ai X', target: 'https://c', status: 'needs_auth' }, { name: 'plug', target: 't', status: 'connected' }]);
    expect(merged.map((s) => `${s.name}:${s.status}`)).toEqual(['u:connected', 'p:failed', 'l:failed', 'claude.ai X:needs_auth', 'plug:connected']);
    expect(merged.find((s) => s.name === 'claude.ai X')?.scope).toBe('claude_ai');
    expect(merged.find((s) => s.name === 'plug')?.scope).toBe('other');
  });

  it('masque les secrets', () => {
    expect(mcp.mask('Bearer abc')).toBe('Bearer ••••••');
    expect(mcp.mask('secret')).toBe('••••••');
  });

  it('normalise les trois formes de configuration', () => {
    expect(mcp.normalize('{"mcpServers":{"a":{"command":"x"}}}', '').servers).toEqual([['a', '{"command":"x"}']]);
    expect(mcp.normalize('{"a":{"url":"u"},"b":{"command":"c"}}', '').servers.map((s) => s[0])).toEqual(['a', 'b']);
    expect(mcp.normalize('{"command":"x"}', 'srv').servers).toEqual([['srv', '{"command":"x"}']]);
    expect(mcp.normalize('{"command":"x"}', '').error).toMatch(/nom/i);
    expect(mcp.normalize('{"command":"x"}', 'bad name').error).toMatch(/invalide/);
    expect(mcp.normalize('{pas du json', 'x').error).toMatch(/JSON invalide/);
    expect(mcp.normalize('[]', 'x').error).toMatch(/objet/);
    expect(mcp.normalize('{"mcpServers":{}}', 'x').error).toMatch(/vide/);
    expect(mcp.normalize('{"mcpServers":{"a":{"foo":1}}}', 'x').error).toMatch(/ni « command » ni « url »/);
    expect(mcp.normalize('{"truc":1}', 'x').error).toMatch(/Aucun serveur/);
  });
});
