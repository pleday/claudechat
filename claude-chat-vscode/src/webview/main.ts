import { contextPercent, fmtTokens, type ContextUsage } from '../core/cliInfo';
import { InputHistory } from '../core/inputHistory';
import type { Entry } from '../core/sessions';
import { SlashCatalog, Tips } from '../core/slashCatalog';
import { pickSticky } from '../core/stickyPrompt';
import { Mentions, PERMISSION_MODES, queuePreview, SlashToken } from '../core/textTools';
import type { HostMsg, UiState, UsageReport, WebMsg } from '../protocol';
import { applyKnownFiles, el, renderEntry, startsResponse, type RenderCtx } from './render';

declare function acquireVsCodeApi(): { postMessage(m: WebMsg): void };
declare global { interface Window { __CCG: { avatar: string } } }

const vscode = acquireVsCodeApi();
const post = (m: WebMsg) => vscode.postMessage(m);
const $ = <T extends HTMLElement>(id: string) => document.getElementById(id) as T;

const state: UiState = {
  streaming: false, statusText: '', statusTip: '', resume: null, ringPercent: 0, ringTip: '', mode: 'manual', model: '', effort: 'auto',
  efforts: ['auto'], models: [], busyMode: 'refine', contextOn: true, contextFile: '', remoteOn: false, autoResumeOn: false, canFork: false,
  forkPending: false, battery: { remaining: null, tip: '' }, usageLine: '', queue: [], changes: null, suggestion: null, attachments: [],
  interaction: null, title: '', slash: [], projectName: '', cwd: '',
};

let entries: Entry[] = [];
let nodes: HTMLElement[] = [];
let showThinking = true;
let showTools = true;
const fileExists = new Map<string, boolean>();
const history = new InputHistory();
let stickyOpen = false;
let queueOpen = false;
let tipGroup = Math.floor(Math.random() * Tips.groups);

const transcript = $('transcript');
const input = $<HTMLTextAreaElement>('input');

const modelLabel = (id: string) => state.models.find((m) => m.id === id)?.name ?? id;
const ctx = (): RenderCtx => ({
  cwd: state.cwd, showThinking, showTools, modelLabel, fileExists,
  askFiles: (paths) => { paths.forEach((p) => fileExists.set(p, false)); post({ t: 'checkFiles', seq: 0, paths }); },
});

// ---------- Transcript ----------

const nearBottom = () => transcript.scrollHeight - transcript.scrollTop - transcript.clientHeight < 80;
const scrollBottom = () => { transcript.scrollTop = transcript.scrollHeight; };

function nodeFor(i: number): HTMLElement {
  return renderEntry(entries[i], entries[i].kind === 'text' && startsResponse(entries, i), ctx());
}

function renderWelcome(): HTMLElement {
  const w = el('div', 'welcome');
  const img = el('img', 'avatar');
  img.src = window.__CCG.avatar; img.alt = '';
  w.append(img, el('div', 'greeting', 'Salut ! Sur quoi on travaille ?'));
  const card = el('div', 'tips');
  const draw = () => {
    card.replaceChildren(el('b', undefined, '💡 Astuces'), ...Tips.group(tipGroup).map((t) => el('div', undefined, `• ${t}`)));
  };
  draw();
  const next = el('a', 'link', 'Astuces suivantes ›');
  next.addEventListener('click', () => { tipGroup++; draw(); });
  w.append(card, next);
  return w;
}

function renderAll(): void {
  const stick = true;
  transcript.replaceChildren();
  nodes = [];
  if (!entries.length) { transcript.append(renderWelcome()); }
  else entries.forEach((_, i) => { const n = nodeFor(i); nodes.push(n); transcript.append(n); });
  updateSticky();
  if (stick) scrollBottom();
  runSearch();
}

function addEntry(e: Entry): void {
  const stick = nearBottom() || e.kind === 'user';
  if (!nodes.length) transcript.replaceChildren();
  entries.push(e);
  const n = nodeFor(entries.length - 1);
  nodes.push(n);
  transcript.append(n);
  if (e.kind === 'user') { stickyOpen = false; updateSticky(); }
  if (stick) scrollBottom();
}

function patchEntry(i: number, patch: Partial<Entry>): void {
  const e = entries[i];
  if (!e) return;
  Object.assign(e, patch);
  // un outil vient de finir : un fichier cité a pu être créé, on le redemandera
  if (patch.result !== undefined) for (const [k, v] of fileExists) if (!v) fileExists.delete(k);
  const stick = nearBottom();
  const n = nodeFor(i);
  nodes[i].replaceWith(n);
  nodes[i] = n;
  if (stick) scrollBottom();
}

// Pendant une réponse longue, le rendu Markdown complet est regroupé sur une image d'animation.
const queuedPatches = new Map<number, Partial<Entry>>();
let patchFrame = 0;
function schedulePatch(i: number, patch: Partial<Entry>): void {
  queuedPatches.set(i, { ...queuedPatches.get(i), ...patch });
  if (patchFrame) return;
  patchFrame = requestAnimationFrame(() => {
    patchFrame = 0;
    for (const [k, p] of queuedPatches) patchEntry(k, p);
    queuedPatches.clear();
  });
}

function updateSticky(): void {
  const bar = $('sticky');
  const users = entries.filter((e) => e.kind === 'user').map((e) => e.text);
  const c = pickSticky(users);
  bar.hidden = !c;
  if (!c) return;
  bar.className = stickyOpen ? 'open' : '';
  bar.replaceChildren(el('span', 'pin', c.resumedWith ? '↻ ' : '📌 '), el('span', 'stext', c.prompt));
  bar.title = c.resumedWith ? `Reprise demandée : « ${c.resumedWith} »` : 'Votre message';
}

// ---------- Recherche dans la conversation ----------

let matches: Range[] = [];
let matchIndex = 0;
function runSearch(): void {
  const q = $<HTMLInputElement>('searchInput').value;
  matches = [];
  const hl = typeof CSS !== 'undefined' ? (CSS as any).highlights : undefined;
  hl?.delete('ccg-search');
  if (q && !$('search').hidden) {
    const walker = document.createTreeWalker(transcript, NodeFilter.SHOW_TEXT);
    const low = q.toLowerCase();
    for (let n = walker.nextNode(); n; n = walker.nextNode()) {
      if ((n.parentElement?.closest('[hidden]'))) continue;
      const t = (n.textContent ?? '').toLowerCase();
      for (let at = t.indexOf(low); at >= 0; at = t.indexOf(low, at + low.length)) {
        const r = new Range(); r.setStart(n, at); r.setEnd(n, at + q.length); matches.push(r);
      }
    }
    const H = (window as any).Highlight;
    if (H && hl && matches.length) hl.set('ccg-search', new H(...matches));
  }
  matchIndex = Math.min(matchIndex, Math.max(0, matches.length - 1));
  $('searchCount').textContent = q ? `${matches.length ? matchIndex + 1 : 0}/${matches.length}` : '';
}
function gotoMatch(d: number): void {
  if (!matches.length) return;
  matchIndex = (matchIndex + d + matches.length) % matches.length;
  matches[matchIndex].startContainer.parentElement?.scrollIntoView({ block: 'center' });
  $('searchCount').textContent = `${matchIndex + 1}/${matches.length}`;
}
function toggleSearch(): void {
  const s = $('search');
  s.hidden = !s.hidden;
  if (!s.hidden) $<HTMLInputElement>('searchInput').focus(); else runSearch();
}

// ---------- Menus et fenêtres flottantes ----------

interface MenuItem { label: string; desc?: string; checked?: boolean; header?: boolean; run?: () => void }

let closePopup: (() => void) | undefined;
function popup(anchor: HTMLElement, build: (box: HTMLElement) => void, right = false): HTMLElement {
  closePopup?.();
  const box = el('div', 'popup');
  build(box);
  document.body.append(box);
  const r = anchor.getBoundingClientRect();
  box.style.bottom = `${window.innerHeight - r.top + 4}px`;
  if (right) box.style.right = `${Math.max(4, window.innerWidth - r.right)}px`; else box.style.left = `${Math.max(4, r.left)}px`;
  const off = (ev: Event) => { if (!box.contains(ev.target as Node)) close(); };
  const key = (ev: KeyboardEvent) => { if (ev.key === 'Escape') close(); };
  const close = () => { box.remove(); document.removeEventListener('mousedown', off, true); document.removeEventListener('keydown', key, true); if (closePopup === close) closePopup = undefined; };
  setTimeout(() => { document.addEventListener('mousedown', off, true); document.addEventListener('keydown', key, true); });
  closePopup = close;
  return box;
}

function menu(anchor: HTMLElement, items: MenuItem[], right = false): void {
  popup(anchor, (box) => {
    box.classList.add('menu');
    for (const it of items) {
      if (it.header) { box.append(el('div', 'mhead', it.label)); continue; }
      const row = el('div', 'mitem');
      row.append(el('span', 'check', it.checked ? '✓' : ''), el('span', undefined, it.label));
      if (it.desc) row.title = it.desc;
      row.addEventListener('click', () => { closePopup?.(); it.run?.(); });
      box.append(row);
    }
  }, right);
}

function showContext(u: ContextUsage | null, label: string): void {
  if (!u) return;
  popup($('ring'), (box) => {
    box.classList.add('report');
    box.append(el('b', undefined, `Contexte : ${fmtTokens(u.total)} / ${fmtTokens(u.max)} tokens (${contextPercent(u)} %)${label ? ` · ${label}` : ''}`));
    const used = u.categories.filter((c) => c.kind === 'used' && c.tokens > 0).sort((a, b) => b.tokens - a.tokens);
    const total = Math.max(1, used.reduce((a, c) => a + c.tokens, 0));
    const table = el('table');
    for (const c of used) {
      const filled = Math.min(14, Math.max(1, Math.round((c.tokens * 14) / total)));
      const tr = el('tr');
      tr.append(el('td', undefined, c.name), el('td', 'num', fmtTokens(c.tokens)), el('td', 'bar', '▰'.repeat(filled) + '▱'.repeat(14 - filled)));
      table.append(tr);
    }
    box.append(table);
    const parts = [['outils', u.toolCalls + u.toolResults], ['pièces jointes', u.attachments], ['réponses de Claude', u.assistant], ['vos messages', u.user]]
      .filter((p) => (p[1] as number) > 0).map((p) => `${p[0]} ${fmtTokens(p[1] as number)}`);
    if (parts.length) box.append(el('div', undefined, `Messages : ${parts.join(' · ')}`));
    const kind = (k: string) => u.categories.filter((c) => c.kind === k).reduce((a, c) => a + c.tokens, 0);
    const foot = [
      u.autoCompactAt ? `compaction automatique à ${fmtTokens(u.autoCompactAt)}` : '',
      kind('buffer') ? `réserve ${fmtTokens(kind('buffer'))}` : '',
      kind('free') ? `libre ${fmtTokens(kind('free'))}` : '',
      kind('deferred') ? `outils différés ${fmtTokens(kind('deferred'))} (hors total)` : '',
    ].filter(Boolean).join(' · ');
    if (foot) box.append(el('div', 'dim', foot));
    if (u.approximate) box.append(el('div', 'dim', 'Mesure sur un processus à part : les outils des serveurs MCP ne sont pas comptés.'));
  }, true);
}

function showUsage(r: UsageReport): void {
  popup($('battery'), (box) => {
    box.classList.add('report');
    for (const l of [r.five, r.week]) {
      box.append(el('b', undefined, l.title));
      box.append(el('div', undefined, l.used === null ? 'pas encore mesuré' : `${l.used} % utilisé · ${100 - l.used} % restant`));
      box.append(el('div', 'dim', l.reset));
    }
    box.append(el('b', undefined, 'Cette conversation'));
    box.append(el('div', undefined, `Modèle : ${r.model}`), el('div', undefined, `Contexte : ${r.context}`), el('div', undefined, `Tokens cumulés : ${r.tokens}`));
    if (r.cost) box.append(el('div', undefined, `Coût estimé : ${r.cost}`));
    if (r.error) box.append(el('div', 'errtext', `Mesure impossible : ${r.error}`));
    if (r.noQuota) box.append(el('div', 'errtext', "La CLI n'a envoyé aucun quota pendant la dernière réponse."));
    box.append(el('div', 'dim', `${r.measured}. Les quotas sont fournis par la CLI après chaque réponse.`));
    const b = el('button', 'btn', 'Actualiser');
    b.title = 'Mesure les quotas maintenant (requête minimale, quelques millièmes de dollar)';
    b.addEventListener('click', () => { b.disabled = true; b.textContent = 'Mesure…'; post({ t: 'refreshQuota' }); });
    box.append(b);
  }, true);
}

// ---------- Barre d'outils et zones de la carte de saisie ----------

const modeLabel = () => PERMISSION_MODES.find((m) => m[0] === state.mode)?.[1] ?? state.mode;

function drawControls(): void {
  $('modeBtn').textContent = `${modeLabel()} ▾`;
  $('modelBtn').textContent = `${modelLabel(state.model)} ▾`;
  const eff = $('effortBtn');
  eff.textContent = `${state.effort} ▾`;
  eff.hidden = state.efforts.length <= 1;
  const ctxBtn = $('ctxBtn');
  ctxBtn.replaceChildren(el('span', undefined, '📄'), el('span', 'lbl', ` ${state.contextOn ? state.contextFile || 'fichier courant' : 'sans contexte'}`));
  ctxBtn.classList.toggle('off', !state.contextOn || !state.contextFile);
  ctxBtn.title = state.contextOn ? 'Le fichier ouvert (ou la sélection) est joint au message — cliquer pour désactiver' : 'Contexte de l\'éditeur désactivé — cliquer pour activer';
  $('remoteBtn').classList.toggle('on', state.remoteOn);
  $('resumeBtn').classList.toggle('on', state.autoResumeOn);
  $('forkBtn').classList.toggle('on', state.forkPending);
  $<HTMLButtonElement>('forkBtn').disabled = !state.canFork && !state.forkPending;
  $('thinkBtn').classList.toggle('on', showThinking);
  $('toolsBtn').classList.toggle('on', showTools);
  drawSend();
}

function drawStatus(): void {
  const s = $('status');
  s.replaceChildren(el('span', undefined, state.statusText));
  s.title = state.statusTip;
  if (state.resume) {
    const a = el('a', 'link', ' annuler');
    a.addEventListener('click', () => post({ t: 'cancelResume' }));
    s.append(a);
  }
  $('usage').textContent = state.usageLine;
  $('usage').title = 'Tokens cumulés de cette conversation (entrée / sortie)';
  const ring = $('ring');
  ring.style.setProperty('--p', String(Math.min(100, state.ringPercent)));
  ring.title = state.ringTip;
  ring.classList.toggle('warn', state.ringPercent >= 80);
  $('hourglass').hidden = !state.streaming;
  drawSend();
}

function drawBattery(): void {
  const b = $('battery');
  const r = state.battery.remaining;
  b.title = state.battery.tip;
  b.textContent = r === null ? '🔋 ?' : `${r < 20 ? '🪫' : '🔋'} ${r} %`;
  b.classList.toggle('low', r !== null && r < 20);
}

function drawSend(): void {
  const hasText = input.value.trim().length > 0 || state.attachments.length > 0;
  const send = $<HTMLButtonElement>('send');
  send.disabled = !hasText;
  const refine = state.busyMode === 'refine';
  send.title = state.streaming
    ? (refine ? 'Préciser l\'exécution en cours (Ctrl+Entrée : mettre en file d\'attente)' : 'Mettre en file d\'attente (Ctrl+Entrée : préciser)')
    : 'Envoyer (Entrée)';
  send.textContent = state.streaming ? (refine ? '⚡' : '⏳') : '➤';
  $('stop').hidden = !state.streaming;
  $('busyMenu').hidden = !state.streaming;
}

function drawHints(): void {
  const strip = $('hints');
  strip.replaceChildren();
  const chip = (icon: string, text: string, tip: string, onClick: () => void, onRemove: () => void, removeTip: string) => {
    const c = el('div', 'chip');
    const t = el('span', 'ctext', `${icon} ${text}`);
    t.title = tip;
    t.addEventListener('click', onClick);
    const x = el('span', 'x', '×');
    x.title = removeTip;
    x.addEventListener('click', onRemove);
    c.append(t, x);
    strip.append(c);
  };
  const ch = state.changes;
  if (ch) {
    chip('📝', `${ch.count} fichier${ch.count > 1 ? 's' : ''} modifié${ch.count > 1 ? 's' : ''} par Claude  +${ch.added} −${ch.removed}`,
      'Voir les modifications de Claude (comparaison avec l\'état avant)', () => post({ t: 'openChanges' }), () => post({ t: 'forgetChanges' }),
      'Oublier ces fichiers (les prochaines modifications repartent de l\'état actuel)');
  }
  if (state.suggestion && !state.streaming) {
    chip('💡', state.suggestion, 'Cliquer (ou Tab dans le champ vide) pour l\'insérer', () => { post({ t: 'acceptSuggestion' }); input.focus(); },
      () => post({ t: 'dismissSuggestion' }), 'Ignorer la suggestion');
  }
  for (const a of state.attachments) {
    const c = el('div', 'chip');
    if (a.thumb) { const im = el('img', 'thumb'); im.src = a.thumb; c.append(im); } else c.append(el('span', undefined, '📄 '));
    const t = el('span', 'ctext', a.label);
    t.title = a.tip;
    const x = el('span', 'x', '×');
    x.title = 'Retirer';
    x.addEventListener('click', () => post({ t: 'removeAttachment', key: a.key }));
    c.append(t, x);
    strip.append(c);
  }
  strip.hidden = !strip.childElementCount;
}

function drawQueue(): void {
  const row = $('queue');
  row.hidden = !state.queue.length;
  row.replaceChildren();
  if (!state.queue.length) return;
  const head = el('div', 'qhead');
  const label = el('span', 'qlabel', `⏳ En attente : ${queuePreview(state.queue)}`);
  label.addEventListener('click', () => { queueOpen = !queueOpen; drawQueue(); });
  const clear = el('a', 'link', 'vider');
  clear.addEventListener('click', () => post({ t: 'queueClear' }));
  head.append(label, clear);
  row.append(head);
  if (queueOpen) {
    state.queue.forEach((q, i) => {
      const r = el('div', 'qitem');
      r.append(el('span', 'qtext', q.split('\n')[0].slice(0, 120)));
      const btn = (t: string, tip: string, run: () => void) => { const b = el('a', 'link', t); b.title = tip; b.addEventListener('click', run); r.append(b); };
      btn('↑', 'Monter', () => post({ t: 'queueMove', i, to: i - 1 }));
      btn('↓', 'Descendre', () => post({ t: 'queueMove', i, to: i + 1 }));
      btn('✕', 'Retirer', () => post({ t: 'queueRemove', i }));
      row.append(r);
    });
  }
}

function drawInteraction(): void {
  const p = $('interaction');
  const i = state.interaction;
  p.hidden = !i;
  p.replaceChildren();
  if (!i) return;
  p.append(el('div', 'ititle', i.title));
  if (i.body.trim()) p.append(el('pre', 'ibody', i.body));
  const buttons = el('div', 'ibuttons');
  if (i.canDiff) {
    const d = el('button', 'btn', 'Voir le diff');
    d.addEventListener('click', () => post({ t: 'viewDiff', id: i.id }));
    buttons.append(d);
  }
  i.options.forEach((o, k) => {
    const b = el('button', 'btn' + (k === 0 ? ' primary' : ''), o);
    b.addEventListener('click', () => post({ t: 'interact', id: i.id, choice: o }));
    buttons.append(b);
  });
  if (i.freeText) {
    const f = el('div', 'ifree');
    const t = el('input');
    t.placeholder = 'Réponse libre…';
    const go = () => { if (t.value.trim()) post({ t: 'interact', id: i.id, choice: t.value.trim() }); };
    t.addEventListener('keydown', (ev) => { if (ev.key === 'Enter') go(); });
    const b = el('button', 'btn', 'Envoyer');
    b.addEventListener('click', go);
    f.append(t, b);
    p.append(f);
  }
  p.append(buttons);
}

// ---------- Saisie : envoi, historique, complétion ----------

function autosize(): void {
  input.style.height = 'auto';
  input.style.height = `${Math.min(input.scrollHeight, 220)}px`;
}

function send(mode?: 'refine' | 'queue'): void {
  const text = input.value.trim();
  if (!text && !state.attachments.length) return;
  history.add(text);
  input.value = '';
  autosize(); hideCompletion(); drawSend();
  post({ t: 'send', text, mode });
}

interface Row { text: string; value: string; header?: boolean; desc?: string }
let rows: Row[] = [];
let sel = 0;
let completionAnchor = 0;
let completionKind: 'mention' | 'slash' | undefined;
let mentionSeq = 0;

function hideCompletion(): void { $('completion').hidden = true; rows = []; completionKind = undefined; }

function showCompletion(list: Row[], kind: 'mention' | 'slash'): void {
  rows = list; completionKind = kind;
  const box = $('completion');
  if (!list.some((r) => !r.header)) { hideCompletion(); return; }
  sel = rows.findIndex((r) => !r.header);
  box.hidden = false;
  box.replaceChildren();
  rows.forEach((r, i) => {
    if (r.header) { box.append(el('div', 'mhead', r.text)); return; }
    const d = el('div', 'crow' + (i === sel ? ' sel' : ''));
    d.append(el('span', undefined, r.text));
    if (r.desc) d.append(el('span', 'cdesc', r.desc));
    d.dataset.i = String(i);
    d.addEventListener('mousedown', (ev) => { ev.preventDefault(); sel = i; accept(); });
    box.append(d);
  });
}

function moveSel(d: number): void {
  const idx = rows.map((r, i) => (r.header ? -1 : i)).filter((i) => i >= 0);
  const at = idx.indexOf(sel);
  sel = idx[(at + d + idx.length) % idx.length];
  $('completion').querySelectorAll('.crow').forEach((n) => {
    const on = (n as HTMLElement).dataset.i === String(sel);
    n.classList.toggle('sel', on);
    if (on) (n as HTMLElement).scrollIntoView({ block: 'nearest' });
  });
}

function accept(): void {
  const r = rows[sel];
  if (!r || r.header) return;
  const caret = input.selectionStart;
  const value = input.value;
  const insert = completionKind === 'slash' ? `/${r.value} ` : `@${r.value} `;
  input.value = value.slice(0, completionAnchor) + insert + value.slice(caret);
  const at = completionAnchor + insert.length;
  input.setSelectionRange(at, at);
  hideCompletion(); autosize(); drawSend(); input.focus();
}

function onInputChange(): void {
  autosize(); drawSend();
  const caret = input.selectionStart;
  const slash = SlashToken.activeQuery(input.value, caret);
  if (slash !== undefined) {
    completionAnchor = 0;
    const rowsList: Row[] = [];
    for (const [cat, list] of SlashCatalog.group(slash, state.slash)) {
      rowsList.push({ text: cat, value: '', header: true });
      list.forEach((e) => rowsList.push({ text: `/${e.name}`, value: e.name, desc: e.description }));
    }
    showCompletion(rowsList, 'slash');
    return;
  }
  const m = Mentions.activeToken(input.value, caret);
  if (m) { completionAnchor = m.start; post({ t: 'mention', seq: ++mentionSeq, q: m.query }); return; }
  hideCompletion();
}

input.addEventListener('input', onInputChange);
input.addEventListener('keydown', (e) => {
  const open = !$('completion').hidden;
  if (open) {
    if (e.key === 'ArrowDown') { e.preventDefault(); moveSel(1); return; }
    if (e.key === 'ArrowUp') { e.preventDefault(); moveSel(-1); return; }
    if (e.key === 'Enter' || e.key === 'Tab') { e.preventDefault(); accept(); return; }
    if (e.key === 'Escape') { e.preventDefault(); hideCompletion(); return; }
  }
  if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
    e.preventDefault();
    // Ctrl+Entrée : l'autre mode d'envoi pendant une réponse
    const other = state.busyMode === 'refine' ? 'queue' : 'refine';
    send(e.ctrlKey || e.metaKey ? other : undefined);
    return;
  }
  if (e.key === 'Tab' && !input.value && state.suggestion) { e.preventDefault(); post({ t: 'acceptSuggestion' }); return; }
  if (e.key === 'Escape' && state.streaming) { e.preventDefault(); post({ t: 'stop' }); return; }
  if (e.key === 'ArrowUp' && !input.value.slice(0, input.selectionStart).includes('\n') && input.selectionStart === input.selectionEnd) {
    const h = history.previous(input.value);
    if (h !== undefined) { e.preventDefault(); input.value = h; input.setSelectionRange(0, 0); autosize(); drawSend(); }
  } else if (e.key === 'ArrowDown' && !input.value.slice(input.selectionEnd).includes('\n') && input.selectionStart === input.selectionEnd) {
    const h = history.next(input.value);
    if (h !== undefined) { e.preventDefault(); input.value = h; autosize(); drawSend(); }
  }
});
input.addEventListener('blur', () => setTimeout(hideCompletion, 120));

/** Collage d'images (Ctrl+V) : envoyées comme pièces jointes. */
input.addEventListener('paste', (e) => {
  const files = [...(e.clipboardData?.files ?? [])].filter((f) => f.type.startsWith('image/'));
  if (!files.length) return;
  e.preventDefault();
  files.forEach((f, i) => {
    const r = new FileReader();
    r.onload = () => {
      const data = String(r.result).split(',')[1] ?? '';
      const ext = f.type.split('/')[1] ?? 'png';
      post({ t: 'pasteImage', name: f.name && f.name !== 'image.png' ? f.name : `capture-${Date.now()}-${i + 1}.${ext}`, media: f.type, data });
    };
    r.readAsDataURL(f);
  });
});

// ---------- Boutons ----------

const on = (id: string, fn: (b: HTMLElement) => void) => { const b = $(id); b.addEventListener('click', () => fn(b)); };

on('send', () => send());
on('stop', () => post({ t: 'stop' }));
on('busyMenu', (b) => menu(b, [
  { label: 'Préciser l\'exécution en cours', desc: 'Injecté dans la réponse en cours, sans l\'interrompre', checked: state.busyMode === 'refine', run: () => post({ t: 'setBusyMode', mode: 'refine' }) },
  { label: 'Mettre en file d\'attente', desc: 'Envoyé quand la réponse en cours est terminée', checked: state.busyMode === 'queue', run: () => post({ t: 'setBusyMode', mode: 'queue' }) },
], true));
on('plus', (b) => menu(b, [
  { label: 'Joindre des fichiers ou des images…', run: () => post({ t: 'attachFiles' }) },
  { label: 'Mentionner un fichier (@)', run: () => { input.focus(); input.setRangeText('@', input.selectionStart, input.selectionEnd, 'end'); onInputChange(); } },
  { label: 'Commandes (/)', run: () => { input.focus(); input.setRangeText('/', input.selectionStart, input.selectionEnd, 'end'); onInputChange(); } },
]));
on('modeBtn', (b) => menu(b, PERMISSION_MODES.map(([id, label, desc]) => ({ label, desc, checked: state.mode === id, run: () => post({ t: 'setMode', id }) }))));
on('effortBtn', (b) => menu(b, state.efforts.map((id) => ({ label: id, checked: state.effort === id, run: () => post({ t: 'setEffort', id }) }))));
on('modelBtn', (b) => {
  const items: MenuItem[] = [];
  const aliases = state.models.filter((m) => m.isAlias);
  const others = state.models.filter((m) => !m.isAlias);
  const add = (m: UiState['models'][number]) => items.push({ label: m.name, desc: m.description || m.id, checked: state.model === m.id, run: () => post({ t: 'setModel', id: m.id }) });
  (aliases.length ? aliases : state.models).forEach(add);
  if (aliases.length && others.length) { items.push({ label: 'Autres versions', header: true }); others.forEach(add); }
  menu(b, items);
});
on('ctxBtn', () => post({ t: 'toggleContext' }));
on('remoteBtn', () => post({ t: 'toggleRemote' }));
on('resumeBtn', () => post({ t: 'toggleAutoResume' }));
on('forkBtn', () => post({ t: 'fork' }));
on('renameBtn', () => post({ t: 'rename' }));
on('searchBtn', () => toggleSearch());
on('thinkBtn', () => { showThinking = !showThinking; renderAll(); drawControls(); });
on('toolsBtn', () => { showTools = !showTools; renderAll(); drawControls(); });
on('ring', () => post({ t: 'showContext' }));
on('battery', () => post({ t: 'showUsage' }));
on('sticky', () => { stickyOpen = !stickyOpen; updateSticky(); });
$('searchInput').addEventListener('input', () => { matchIndex = 0; runSearch(); });
$('searchInput').addEventListener('keydown', (e) => {
  const k = e as KeyboardEvent;
  if (k.key === 'Enter') gotoMatch(k.shiftKey ? -1 : 1);
  if (k.key === 'Escape') toggleSearch();
});
on('searchNext', () => gotoMatch(1));
on('searchPrev', () => gotoMatch(-1));
on('searchClose', () => toggleSearch());
document.addEventListener('keydown', (e) => { if ((e.ctrlKey || e.metaKey) && e.key === 'f') { e.preventDefault(); toggleSearch(); } });

// liens : fichiers du projet (ouverts par l'extension) ; les liens http s'ouvrent via le navigateur
transcript.addEventListener('click', (e) => {
  const a = (e.target as HTMLElement).closest('[data-open]') as HTMLElement | null;
  if (a?.dataset.open) { e.preventDefault(); post({ t: 'openFile', spec: a.dataset.open }); }
});

// ---------- Messages de l'extension ----------

function applyState(p: Partial<UiState>): void {
  const cwdChanged = p.cwd !== undefined && p.cwd !== state.cwd;
  Object.assign(state, p);
  if (cwdChanged && entries.length) renderAll(); // les chemins affichés sont raccourcis par rapport au projet
  const has = (...k: Array<keyof UiState>) => k.some((x) => x in p);
  if (has('mode', 'model', 'effort', 'efforts', 'models', 'busyMode', 'contextOn', 'contextFile', 'remoteOn', 'autoResumeOn', 'canFork', 'forkPending')) drawControls();
  if (has('streaming', 'statusText', 'statusTip', 'resume', 'ringPercent', 'ringTip', 'usageLine', 'canFork')) drawStatus();
  if (has('battery')) drawBattery();
  if (has('queue')) drawQueue();
  if (has('changes', 'suggestion', 'attachments', 'streaming')) drawHints();
  if (has('interaction')) drawInteraction();
  if (has('attachments')) drawSend();
}

window.addEventListener('message', (ev) => {
  const m = ev.data as HostMsg;
  switch (m.t) {
    case 'state': applyState(m.patch); break;
    case 'entries': entries = m.entries.map((e) => ({ ...e })); renderAll(); break;
    case 'add': addEntry(m.entry); break;
    case 'patch': schedulePatch(m.i, m.patch); break;
    case 'mention': {
      if (m.seq !== mentionSeq || !Mentions.activeToken(input.value, input.selectionStart)) break;
      showCompletion(m.results.map((p) => ({ text: p, value: p })), 'mention');
      break;
    }
    case 'files': {
      m.existing.forEach((p) => fileExists.set(p, true));
      // ceux qui n'existent pas ne sont plus demandés avant un moment
      applyKnownFiles(transcript, fileExists);
      break;
    }
    case 'context': showContext(m.usage, m.modelLabel); break;
    case 'usage': showUsage(m.report); break;
    case 'setInput':
      input.value = m.append && input.value ? `${input.value}\n${m.text}` : m.text;
      autosize(); drawSend(); input.focus();
      break;
    case 'focus': input.focus(); break;
    case 'newConversation': history.add(''); stickyOpen = false; tipGroup = Math.floor(Math.random() * Tips.groups); input.focus(); break;
  }
});

drawControls(); drawStatus(); drawBattery(); drawHints();
renderAll();
post({ t: 'ready' });
