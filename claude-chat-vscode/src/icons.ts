/** Icônes SVG de l'interface (hôte et webview) : traits uniformes, couleur du thème (currentColor). Contenu statique, sûr en innerHTML. */
export const svg = (d: string, size = 16, extra = ''): string =>
  `<svg viewBox="0 0 16 16" width="${size}" height="${size}" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"${extra}>${d}</svg>`;

export const ICONS = {
  rename: svg('<path d="M11 2.6l2.4 2.4L5.4 13l-3 .6.6-3z"/><path d="M9.6 4l2.4 2.4"/>'),
  search: svg('<circle cx="7" cy="7" r="4.4"/><path d="M10.4 10.4L14 14"/>'),
  fork: svg('<circle cx="4.5" cy="3.5" r="1.6"/><circle cx="4.5" cy="12.5" r="1.6"/><circle cx="11.5" cy="5.5" r="1.6"/><path d="M4.5 5.1v5.8M11.5 7.1c0 2.4-2.2 2.6-5 3.2-1.4.3-2 .6-2 1"/>'),
  think: svg('<path d="M6 11.5h4M6.6 14h2.8"/><path d="M8 1.8a4.2 4.2 0 0 0-2.4 7.7c.4.4.4.8.4 1.2h4c0-.4 0-.8.4-1.2A4.2 4.2 0 0 0 8 1.8z"/>'),
  tools: svg('<path d="M9.8 2.2a3.5 3.5 0 0 0-3.4 4.6L2.5 10.7a1.5 1.5 0 0 0 2.1 2.1l3.9-3.9a3.5 3.5 0 0 0 4.6-3.4l-2.1 2.1-1.8-.4-.4-1.8z"/>'),
  resume: svg('<path d="M13.2 8A5.2 5.2 0 1 1 11.7 4.3"/><path d="M13.4 2.4v3h-3"/><path d="M6.8 6l3 2-3 2z"/>'),
  remote: svg('<rect x="4.5" y="1.5" width="7" height="13" rx="1.6"/><path d="M7 12.5h2"/>'),
  plus: svg('<path d="M8 3v10M3 8h10"/>'),
  file: svg('<path d="M4 1.8h5.2L12.5 5v9.2H4z"/><path d="M9 1.8V5.2h3.5"/>'),
  chevron: svg('<path d="M4 6l4 4 4-4"/>', 10),
  send: svg('<path d="M8 13V3M3.6 7.4L8 3l4.4 4.4"/>', 15, ' stroke-width="1.8"'),
  queue: svg('<path d="M3 4.5h10M3 8h10M3 11.5h6"/>', 15),
  bolt: svg('<path d="M9 1.8L3.6 9h4l-.6 5.2L12.4 7h-4z"/>', 15),
  stop: '<svg viewBox="0 0 16 16" width="12" height="12" fill="currentColor" aria-hidden="true"><rect x="3" y="3" width="10" height="10" rx="1.6"/></svg>',
  busy: svg('<path d="M8 2v3M8 11v3M2 8h3M11 8h3"/>', 14),
  battery: svg('<rect x="1.8" y="4.6" width="10.6" height="6.8" rx="1.4"/><path d="M14.2 7v2"/><path d="M4 6.8v2.4M6.4 6.8v2.4"/>', 15),
  batteryLow: svg('<rect x="1.8" y="4.6" width="10.6" height="6.8" rx="1.4"/><path d="M14.2 7v2"/><path d="M4 6.8v2.4"/>', 15),
  spinner: svg('<path d="M8 2a6 6 0 1 0 6 6"/>', 14),
};
