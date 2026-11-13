// @vitest-environment jsdom
import { beforeAll, describe, expect, it, vi } from 'vitest';
import { webviewHtml } from '../src/host/webviewHtml';
import type { HostMsg, UiState, WebMsg } from '../src/protocol';

const sent: WebMsg[] = [];
const host = (m: HostMsg) => window.dispatchEvent(new MessageEvent('message', { data: m }));
const $ = (id: string) => document.getElementById(id)!;
const tick = () => new Promise((r) => setTimeout(r, 30));

beforeAll(async () => {
  // la coquille HTML est celle que génère l'extension (mêmes identifiants que ceux utilisés par le script)
  const html = webviewHtml({ cspSource: 'vscode-resource:' } as never, {} as never, (...p) => ({ toString: () => `/${p.join('/')}` }) as never);
  document.documentElement.innerHTML = html.replace(/<script[\s\S]*?<\/script>/g, '').replace(/<!doctype html>/i, '');
  (window as any).__CCG = { avatar: '/media/avatar.png' };
  (globalThis as any).acquireVsCodeApi = () => ({ postMessage: (m: WebMsg) => sent.push(m) });
  (HTMLElement.prototype as any).scrollIntoView = () => {};
  vi.stubGlobal('requestAnimationFrame', (f: () => void) => setTimeout(f, 0));
  await import('../src/webview/main');
});

const base: Partial<UiState> = {
  mode: 'manual', model: 'claude-sonnet-5-5', effort: 'auto', efforts: ['auto', 'low', 'high'], busyMode: 'refine', cwd: '/p',
  models: [{ id: 'claude-sonnet-5-5', name: 'Sonnet 5.5', description: '', isAlias: true }, { id: 'claude-opus-4-1', name: 'Opus 4.1', description: '', isAlias: false }],
  slash: [{ name: 'explain', category: 'Raccourcis du plugin', description: 'Expliquer' }, { name: 'compact', category: 'Session', description: 'Résumer' }],
};

describe('interface du webview', () => {
  it('annonce qu\'elle est prête et affiche l\'accueil sans conversation', () => {
    expect(sent[0]).toEqual({ t: 'ready' });
    expect(document.querySelector('.welcome .greeting')?.textContent).toContain('Sur quoi on travaille');
    expect(document.querySelectorAll('.tips div').length).toBe(3);
  });

  it('affiche l\'état : mode, modèle, effort, batterie, anneau', () => {
    host({ t: 'state', patch: { ...base, battery: { remaining: 58, tip: 'Session de 5 h' }, ringPercent: 42, ringTip: 'Contexte' } });
    expect($('modeBtn').textContent).toContain('Demander');
    expect($('modelBtn').textContent).toContain('Sonnet 5.5');
    expect($('effortBtn').hidden).toBe(false);
    expect($('battery').textContent).toContain('58 %');
    expect($('ring').style.getPropertyValue('--p')).toBe('42');
  });

  it('rend la conversation : message, Markdown assaini, outils, notice, erreur', () => {
    host({ t: 'entries', entries: [
      { kind: 'user', text: 'Salut <b>moi</b>' },
      { kind: 'text', text: '# Titre\n\n**gras** et `code` <img src=x onerror="window.pwn=1"><script>window.pwn=2</script>\n\n```ts\nconst a = 1;\n```', model: 'claude-sonnet-5-5' },
      { kind: 'tool', text: '', toolName: 'Edit', toolId: 't', input: { file_path: '/p/src/a.ts' }, result: 'ok' },
      { kind: 'tool', text: '', toolName: 'Bash', toolId: 'u', input: { command: 'ls' } },
      { kind: 'notice', text: 'Réponse interrompue.' },
      { kind: 'error', text: 'boum' },
    ] });
    expect(document.querySelector('.welcome')).toBeNull();
    expect(document.querySelector('.usertext')?.textContent).toBe('Salut <b>moi</b>'); // échappé, jamais interprété
    expect(document.querySelector('.who.claude')?.textContent).toContain('Sonnet 5.5');
    const md = document.querySelector('.md')!;
    expect(md.querySelector('h1')?.textContent).toBe('Titre');
    expect(md.querySelector('strong')?.textContent).toBe('gras');
    expect(md.querySelector('pre code')?.textContent).toContain('const a = 1;');
    expect(md.querySelector('script')).toBeNull();
    expect(md.innerHTML).not.toMatch(/onerror/);
    expect((window as any).pwn).toBeUndefined();
    const cards = [...document.querySelectorAll('.toolcard')];
    expect(cards[0].textContent).toContain('✓ Edit');
    expect(cards[0].querySelector('a.filelink')?.textContent).toBe('src/a.ts'); // chemin raccourci
    expect(cards[1].textContent).toContain('⏳ Bash');
    expect(document.querySelector('.notice')?.textContent).toBe('Réponse interrompue.');
    expect(document.querySelector('.errtext')?.textContent).toBe('Erreur : boum');
  });

  it('épingle le message de l\'utilisateur et le déplie au clic', () => {
    expect($('sticky').hidden).toBe(false);
    expect($('sticky').textContent).toContain('Salut <b>moi</b>');
    $('sticky').click();
    expect($('sticky').className).toBe('open');
  });

  it('streaming : ajout puis mise à jour du texte et du résultat d\'un outil', async () => {
    host({ t: 'add', entry: { kind: 'text', text: 'Bon' } });
    host({ t: 'patch', i: 6, patch: { text: 'Bonjour **toi**' } });
    await tick();
    const last = [...document.querySelectorAll('.entry.text')].at(-1)!;
    expect(last.querySelector('strong')?.textContent).toBe('toi');
    host({ t: 'patch', i: 3, patch: { result: 'fini' } });
    await tick();
    expect(document.querySelectorAll('.toolcard')[1].textContent).toContain('✓ Bash');
  });

  it('demande l\'existence des fichiers cités et en fait des liens', async () => {
    sent.length = 0;
    host({ t: 'add', entry: { kind: 'text', text: 'Voir `src/a.ts:12` et `n/existe/pas.ts`' } });
    await tick();
    const ask = sent.find((m) => m.t === 'checkFiles') as Extract<WebMsg, { t: 'checkFiles' }>;
    expect(ask.paths.sort()).toEqual(['n/existe/pas.ts', 'src/a.ts']);
    host({ t: 'files', seq: 0, existing: ['src/a.ts'] });
    const links = [...document.querySelectorAll('.entry.text:last-child a.filelink')];
    expect(links.map((a) => (a as HTMLElement).dataset.open)).toEqual(['src/a.ts:12']);
    sent.length = 0;
    (links[0] as HTMLElement).click();
    expect(sent).toEqual([{ t: 'openFile', spec: 'src/a.ts:12' }]);
  });

  it('envoie le message avec Entrée, pas avec Maj+Entrée ; vide le champ', () => {
    sent.length = 0;
    const input = $('input') as HTMLTextAreaElement;
    input.value = 'bonjour';
    input.dispatchEvent(new Event('input'));
    expect(($('send') as HTMLButtonElement).disabled).toBe(false);
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', shiftKey: true, bubbles: true }));
    expect(sent).toEqual([]);
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }));
    expect(sent).toEqual([{ t: 'send', text: 'bonjour', mode: undefined }]);
    expect(input.value).toBe('');
    expect(($('send') as HTMLButtonElement).disabled).toBe(true);
  });

  it('historique : ↑ rappelle le message envoyé', () => {
    const input = $('input') as HTMLTextAreaElement;
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowUp', bubbles: true, cancelable: true }));
    expect(input.value).toBe('bonjour');
    input.value = '';
  });

  it('complétion « / » : groupes, filtre, validation', () => {
    const input = $('input') as HTMLTextAreaElement;
    input.value = '/'; input.setSelectionRange(1, 1);
    input.dispatchEvent(new Event('input'));
    const box = $('completion');
    expect(box.hidden).toBe(false);
    expect([...box.querySelectorAll('.mhead')].map((h) => h.textContent)).toEqual(['Raccourcis du plugin', 'Session']);
    input.value = '/co'; input.setSelectionRange(3, 3);
    input.dispatchEvent(new Event('input'));
    expect([...box.querySelectorAll('.crow')].map((r) => r.firstChild?.textContent)).toEqual(['/compact']);
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }));
    expect(input.value).toBe('/compact ');
    expect(box.hidden).toBe(true);
    input.value = '';
  });

  it('complétion « @ » : interroge l\'extension et insère le fichier choisi', () => {
    sent.length = 0;
    const input = $('input') as HTMLTextAreaElement;
    input.value = 'voir @re'; input.setSelectionRange(8, 8);
    input.dispatchEvent(new Event('input'));
    const q = sent.find((m) => m.t === 'mention') as Extract<WebMsg, { t: 'mention' }>;
    expect(q.q).toBe('re');
    host({ t: 'mention', seq: 999, results: ['périmé'] }); // réponse d'une requête dépassée : ignorée
    expect($('completion').hidden).toBe(true);
    host({ t: 'mention', seq: q.seq, results: ['README.md', 'src/a.ts'] });
    expect($('completion').querySelectorAll('.crow').length).toBe(2);
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Tab', bubbles: true, cancelable: true }));
    expect(input.value).toBe('voir @README.md ');
    input.value = '';
  });

  it('panneau d\'approbation : boutons, diff, réponse libre', () => {
    sent.length = 0;
    host({ t: 'state', patch: { interaction: { id: 'i1', title: 'Edit — a.ts', body: 'Fichier : a.ts', options: ['Autoriser', 'Refuser'], freeText: true, canDiff: true } } });
    const p = $('interaction');
    expect(p.hidden).toBe(false);
    expect(p.querySelector('.ititle')?.textContent).toBe('Edit — a.ts');
    const buttons = [...p.querySelectorAll('button')];
    expect(buttons.map((b) => b.textContent)).toEqual(['Envoyer', 'Voir le diff', 'Autoriser', 'Refuser']);
    buttons[1].click();
    buttons[3].click();
    const free = p.querySelector('.ifree input') as HTMLInputElement;
    free.value = 'plutôt autrement';
    free.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
    expect(sent).toEqual([{ t: 'viewDiff', id: 'i1' }, { t: 'interact', id: 'i1', choice: 'Refuser' }, { t: 'interact', id: 'i1', choice: 'plutôt autrement' }]);
    host({ t: 'state', patch: { interaction: null } });
    expect(p.hidden).toBe(true);
  });

  it('pendant une réponse : bouton Interrompre, envoi en « préciser », file d\'attente', () => {
    sent.length = 0;
    host({ t: 'state', patch: { streaming: true, statusText: 'Réponse en cours', queue: ['un', 'deux'] } });
    expect($('stop').hidden).toBe(false);
    expect($('busyMenu').hidden).toBe(false);
    expect($('send').textContent).toBe('⚡');
    expect($('queue').textContent).toContain('+1 autre');
    $('stop').click();
    $('queue').querySelector('a')!.click();
    expect(sent).toEqual([{ t: 'stop' }, { t: 'queueClear' }]);
    host({ t: 'state', patch: { streaming: false, queue: [] } });
    expect($('stop').hidden).toBe(true);
    expect($('queue').hidden).toBe(true);
  });

  it('pastilles : fichiers modifiés, suggestion, pièces jointes', () => {
    sent.length = 0;
    host({ t: 'state', patch: {
      changes: { count: 2, added: 5, removed: 1 }, suggestion: 'Lance les tests',
      attachments: [{ key: 'a.ts', label: 'a.ts', tip: 'a.ts', kind: 'file' }, { key: 'cap.png', label: 'cap.png', tip: 'cap.png', kind: 'image', thumb: 'data:image/png;base64,AAAA' }],
    } });
    const chips = [...document.querySelectorAll('#hints .chip')];
    expect(chips.map((c) => c.querySelector('.ctext')?.textContent)).toEqual(['📝 2 fichiers modifiés par Claude  +5 −1', '💡 Lance les tests', '📄 ', 'cap.png'].slice(0, 2).concat(['a.ts', 'cap.png']));
    expect(($('send') as HTMLButtonElement).disabled).toBe(false); // une pièce jointe suffit pour envoyer
    (chips[0].querySelector('.ctext') as HTMLElement).click();
    (chips[0].querySelector('.x') as HTMLElement).click();
    (chips[3].querySelector('.x') as HTMLElement).click();
    expect(sent).toEqual([{ t: 'openChanges' }, { t: 'forgetChanges' }, { t: 'removeAttachment', key: 'cap.png' }]);
  });

  it('menus : modèles (alias puis autres versions) et modes', async () => {
    sent.length = 0;
    $('modelBtn').click();
    await tick(); // les écouteurs de fermeture sont posés après le clic
    const items = () => [...document.querySelectorAll('.popup .mitem, .popup .mhead')].map((e) => e.textContent!.trim());
    expect(items()).toEqual(['✓Sonnet 5.5', 'Autres versions', 'Opus 4.1']);
    (document.querySelectorAll('.popup .mitem')[1] as HTMLElement).click();
    expect(sent).toEqual([{ t: 'setModel', id: 'claude-opus-4-1' }]);
    expect(document.querySelector('.popup')).toBeNull();
    $('modeBtn').click();
    expect(items().length).toBe(6);
    await tick();
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    expect(document.querySelector('.popup')).toBeNull();
  });

  it('détail du contexte et de l\'usage', () => {
    host({ t: 'context', modelLabel: 'Sonnet 5.5', usage: {
      total: 50000, max: 200000, categories: [{ id: 'messages', name: 'Messages', tokens: 30000, kind: 'used' }, { id: 'free', name: 'Libre', tokens: 150000, kind: 'free' }],
      autoCompactAt: 160000, toolCalls: 1000, toolResults: 2000, attachments: 0, assistant: 3000, user: 4000, approximate: true,
    } });
    const r = document.querySelector('.popup.report')!;
    expect(r.textContent).toContain('50,0k / 200,0k tokens (25 %)');
    expect(r.textContent).toContain('Messages');
    expect(r.textContent).toContain('compaction automatique à 160,0k');
    expect(r.textContent).toContain('serveurs MCP ne sont pas comptés');
    host({ t: 'usage', report: { five: { title: 'Session de 5 h', used: 40, reset: 'dans 2 h' }, week: { title: 'Semaine (7 jours)', used: null, reset: 'pas encore mesuré' }, model: 'Sonnet', context: '1k', tokens: '↑ 1 ↓ 2', cost: '0.10 $', error: null, noQuota: false, measured: 'Aucune mesure' } });
    const u = document.querySelector('.popup.report')!;
    expect(u.textContent).toContain('40 % utilisé · 60 % restant');
    expect(u.textContent).toContain('Coût estimé : 0.10 $');
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
  });

  it('recherche dans la conversation', () => {
    $('searchBtn').click();
    expect($('search').hidden).toBe(false);
    ($('searchInput') as HTMLInputElement).value = 'gras';
    $('searchInput').dispatchEvent(new Event('input'));
    expect($('searchCount').textContent).toBe('1/1');
    $('searchClose').click();
    expect($('search').hidden).toBe(true);
  });

  it('colle une image : elle est envoyée à l\'extension', async () => {
    sent.length = 0;
    const file = new File([new Uint8Array([137, 80, 78, 71])], 'image.png', { type: 'image/png' });
    const ev = new Event('paste', { bubbles: true, cancelable: true }) as any;
    ev.clipboardData = { files: [file] };
    $('input').dispatchEvent(ev);
    await new Promise((r) => setTimeout(r, 50));
    const m = sent.find((x) => x.t === 'pasteImage') as Extract<WebMsg, { t: 'pasteImage' }>;
    expect(m.media).toBe('image/png');
    expect(m.name).toMatch(/^capture-\d+-1\.png$/);
    expect(m.data.length).toBeGreaterThan(0);
    expect(ev.defaultPrevented).toBe(true);
  });

  it('afficher/masquer la réflexion et les outils', () => {
    host({ t: 'entries', entries: [{ kind: 'thinking', text: 'hmm' }, { kind: 'tool', text: '', toolName: 'Read', toolId: 'x', input: {} }, { kind: 'text', text: 'ok' }] });
    expect(document.querySelector('.entry.thinking')!.hasAttribute('hidden')).toBe(false);
    $('thinkBtn').click(); $('toolsBtn').click();
    expect(document.querySelector('.entry.thinking')!.hasAttribute('hidden')).toBe(true);
    expect(document.querySelector('.entry.tool')!.hasAttribute('hidden')).toBe(true);
    $('thinkBtn').click(); $('toolsBtn').click();
  });

  it('nouvelle conversation : retour à l\'accueil', () => {
    host({ t: 'entries', entries: [] });
    expect(document.querySelector('.welcome')).not.toBeNull();
    expect($('sticky').hidden).toBe(true);
  });
});
