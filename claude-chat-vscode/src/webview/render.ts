import DOMPurify from 'dompurify';
import { marked } from 'marked';
import type { Entry } from '../core/sessions';
import { filePathOf, toolSummary } from '../core/textTools';

marked.setOptions({ gfm: true, breaks: false });

/** Markdown vers HTML assaini : le contenu vient du modèle, donc jamais inséré tel quel. */
export function mdToHtml(src: string): string {
  return DOMPurify.sanitize(marked.parse(src, { async: false }) as string);
}

export function el<K extends keyof HTMLElementTagNameMap>(tag: K, cls?: string, text?: string): HTMLElementTagNameMap[K] {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text !== undefined) e.textContent = text;
  return e;
}

const PATH_RE = /^[\w@./\\-]+\.[A-Za-z0-9]+(?::\d+)?$/;

export interface RenderCtx {
  cwd: string;
  showThinking: boolean;
  showTools: boolean;
  modelLabel: (id: string) => string;
  /** Fichiers dont l'existence est connue (chemin -> existe). */
  fileExists: Map<string, boolean>;
  askFiles: (paths: string[]) => void;
}

/**
 * `true` pour le premier texte d'une réponse de Claude (affiche l'en-tête « Claude ») : aucun autre texte entre le dernier
 * message de l'utilisateur et lui. Réflexion et outils, qui peuvent être masqués, ne comptent pas.
 */
export function startsResponse(entries: Entry[], i: number): boolean {
  for (let k = i - 1; k >= 0; k--) {
    if (entries[k].kind === 'user') return true;
    if (entries[k].kind === 'text') return false;
  }
  return true;
}

const copyBtn = (getText: () => string): HTMLButtonElement => {
  const b = el('button', 'copy', 'Copier');
  b.title = 'Copier le contenu';
  b.addEventListener('click', (ev) => {
    ev.stopPropagation();
    void navigator.clipboard.writeText(getText()).then(() => { b.textContent = 'Copié ✓'; setTimeout(() => { b.textContent = 'Copier'; }, 1200); });
  });
  return b;
};

/** Boutons « copier » sur les blocs de code et les citations ; liens vers les fichiers cités. */
function decorate(root: HTMLElement, ctx: RenderCtx): void {
  root.querySelectorAll('pre, blockquote').forEach((blk) => {
    const wrap = el('div', 'copywrap');
    blk.replaceWith(wrap);
    wrap.append(blk, copyBtn(() => blk.textContent ?? ''));
  });
  root.querySelectorAll('a').forEach((a) => {
    const href = a.getAttribute('href') ?? '';
    if (/^(https?:|mailto:)/i.test(href)) { a.setAttribute('target', '_blank'); a.setAttribute('rel', 'noreferrer'); return; }
    if (href && !href.startsWith('#')) { a.dataset.open = decodeURIComponent(href); a.removeAttribute('href'); a.classList.add('filelink'); }
  });
  const want: string[] = [];
  root.querySelectorAll('code').forEach((c) => {
    if (c.closest('pre') || c.closest('a')) return;
    const t = c.textContent ?? '';
    if (!PATH_RE.test(t)) return;
    const known = ctx.fileExists.get(t.replace(/:\d+$/, ''));
    if (known === undefined) want.push(t.replace(/:\d+$/, ''));
    else if (known) linkCode(c, t);
  });
  if (want.length) ctx.askFiles([...new Set(want)]);
}

export function linkCode(c: Element, spec: string): void {
  const a = el('a', 'filelink');
  a.dataset.open = spec;
  c.replaceWith(a);
  a.append(c);
}

/** Relie les `<code>chemin</code>` d'un conteneur dont l'existence vient d'être connue. */
export function applyKnownFiles(root: HTMLElement, exists: Map<string, boolean>): void {
  root.querySelectorAll('code').forEach((c) => {
    if (c.closest('pre') || c.closest('a')) return;
    const t = c.textContent ?? '';
    if (PATH_RE.test(t) && exists.get(t.replace(/:\d+$/, ''))) linkCode(c, t);
  });
}

function toolCard(e: Entry, ctx: RenderCtx): HTMLElement {
  const input = e.input ?? {};
  const card = el('div', 'toolcard' + (e.result === undefined ? ' running' : e.isError ? ' failed' : ' ok'));
  card.dataset.tool = e.toolName ?? '';
  const status = e.result === undefined ? '⏳' : e.isError ? '✗' : '✓';
  const head = el('div', 'toolhead');
  head.append(el('b', undefined, `${status} ${e.toolName ?? '?'}`), document.createTextNode(' '));
  const p = filePathOf(input);
  if (p) {
    const a = el('a', 'filelink', ctx.cwd && p.startsWith(ctx.cwd + '/') ? p.slice(ctx.cwd.length + 1) : p);
    a.dataset.open = p;
    head.append(a);
  } else head.append(el('code', undefined, toolSummary(e.toolName ?? '?', input).slice(0, 200)));
  card.append(head);
  if (e.result?.trim()) card.append(el('div', 'toolres' + (e.isError ? ' err' : ''), e.result.slice(0, 500)));
  return card;
}

/** DOM d'une entrée du transcript. `header` : afficher « Claude » (premier bloc d'une réponse). */
export function renderEntry(e: Entry, header: boolean, ctx: RenderCtx): HTMLElement {
  const root = el('div', `entry ${e.kind}`);
  switch (e.kind) {
    case 'user': {
      const top = el('div', 'who user', 'Vous');
      if (e.note) top.append(el('span', 'sub', ` ${e.note}`));
      root.append(top);
      if (e.attached) root.append(el('div', 'attached', `📎 ${e.attached}`));
      root.append(el('div', 'usertext', e.text));
      break;
    }
    case 'text': {
      if (header) {
        const who = el('div', 'who claude', 'Claude');
        if (e.model) who.append(el('span', 'sub', ` ${ctx.modelLabel(e.model)}`));
        root.append(who);
      }
      const body = el('div', 'md');
      body.innerHTML = mdToHtml(e.text);
      decorate(body, ctx);
      root.append(body);
      break;
    }
    case 'thinking': {
      const d = el('details', 'think');
      d.append(el('summary', undefined, '💭 Réflexion'), el('div', 'md', e.text));
      if (!ctx.showThinking) root.hidden = true;
      root.append(d);
      break;
    }
    case 'tool':
      if (!ctx.showTools) root.hidden = true;
      root.append(toolCard(e, ctx));
      break;
    case 'notice': root.append(el('div', 'notice', e.text)); break;
    case 'error': { const d = el('div', 'errtext'); d.append(el('b', undefined, 'Erreur : '), document.createTextNode(e.text)); root.append(d); break; }
  }
  return root;
}
