// Test d'intégration : lance un vrai VS Code (téléchargé dans .vscode-test) avec l'extension et un faux `claude`.
import { runTests } from '@vscode/test-electron';
import { chmodSync, mkdirSync, mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const work = mkdtempSync(join(tmpdir(), 'ccg-e2e-'));
const project = join(work, 'projet');
mkdirSync(join(project, '.vscode'), { recursive: true });
writeFileSync(join(project, 'hello.txt'), 'bonjour\n');

// faux exécutable claude : répond comme la vraie CLI aux requêtes de contrôle, et répond « Bonjour de la CLI » aux messages
const fake = join(work, 'claude');
writeFileSync(fake, `#!/bin/bash
while IFS= read -r line; do
  id=$(echo "$line" | sed -nE 's/.*"request_id":"([^"]+)".*/\\1/p')
  case "$line" in
    *'"subtype":"initialize"'*) echo '{"type":"control_response","response":{"subtype":"success","request_id":"'$id'","response":{"models":[{"value":"sonnet","resolvedModel":"claude-sonnet-5-5","displayName":"Sonnet 5.5","supportsEffort":true,"supportedEffortLevels":["low","high"]}],"commands":[{"name":"compact","description":"Résumer"}]}}}';;
    *'"subtype":"mcp_status"'*) echo '{"type":"control_response","response":{"subtype":"success","request_id":"'$id'","response":{"mcpServers":[]}}}';;
    *'"type":"user"'*)
      echo '{"type":"system","subtype":"init","session_id":"e2e-session","model":"claude-sonnet-5-5"}'
      echo '{"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"Bonjour de la CLI"}}}'
      echo '{"type":"result","is_error":false,"usage":{"input_tokens":1,"output_tokens":2},"total_cost_usd":0}'
      exit 0;;
  esac
done
`);
chmodSync(fake, 0o755);
writeFileSync(join(project, '.vscode', 'settings.json'), JSON.stringify({
  'claudeChat.cliPath': fake, 'claudeChat.notify': false, 'claudeChat.promptSuggestions': false, 'security.workspace.trust.enabled': false,
}));

try {
  await runTests({
    extensionDevelopmentPath: root,
    extensionTestsPath: join(root, 'dist-e2e', 'index.js'),
    launchArgs: [project, '--disable-extensions', '--disable-workspace-trust', '--skip-welcome', '--skip-release-notes', `--user-data-dir=${join(work, 'user')}`],
    extensionTestsEnv: { CCG_HOME: join(work, 'data'), CCG_E2E_PROJECT: project },
    version: process.env.VSCODE_VERSION ?? '1.100.0',
  });
} catch (e) {
  console.error('Échec des tests d\'intégration :', e instanceof Error ? e.message : e);
  process.exit(1);
}
