// Deux bundles : l'extension (Node, sans vscode) et l'interface du webview (navigateur).
import esbuild from 'esbuild';
import { cpSync, mkdirSync } from 'node:fs';

const watch = process.argv.includes('--watch');
const common = { bundle: true, sourcemap: true, minify: !watch, logLevel: 'info' };

// Ressources communes avec le plugin JetBrains (../shared) : copiées dans media/, jamais committées.
mkdirSync('media', { recursive: true });
cpSync('../shared/assets/avatar.png', 'media/avatar.png');
cpSync('../shared/assets/claude.svg', 'media/claude.svg');

const builds = [
  esbuild.context({
    ...common,
    entryPoints: ['src/host/extension.ts'],
    outfile: 'dist/extension.js',
    platform: 'node',
    format: 'cjs',
    target: 'node18',
    external: ['vscode'],
  }),
  esbuild.context({
    ...common,
    entryPoints: ['src/webview/main.ts'],
    outfile: 'dist/webview.js',
    platform: 'browser',
    format: 'iife',
    target: 'es2022',
  }),
];

for (const b of await Promise.all(builds)) {
  if (watch) await b.watch();
  else { await b.rebuild(); await b.dispose(); }
}
