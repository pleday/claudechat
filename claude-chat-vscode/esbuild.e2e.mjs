import esbuild from 'esbuild';

await esbuild.build({
  entryPoints: ['e2e/index.ts'], outfile: 'dist-e2e/index.js', bundle: true, platform: 'node', format: 'cjs', target: 'node18',
  external: ['vscode'], sourcemap: true, logLevel: 'warning',
});
