import { chmodSync, mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { CliTurn, type ContentBlock, type PermissionRequest, type TurnListener, type TurnOptions } from '../src/core/cli';

export const tmp = () => mkdtempSync(join(tmpdir(), 'ccg-'));

/** Faux exécutable `claude` : un script shell qui rejoue un protocole. */
export function fakeCli(dir: string, body: string): string {
  const f = join(dir, 'claude');
  writeFileSync(f, `#!/bin/bash\n${body}\n`);
  chmodSync(f, 0o755);
  return f;
}

export const opts = (cli: string, dir: string, extra: Partial<TurnOptions> = {}): TurnOptions => ({
  cli, cwd: dir, model: 'sonnet', effort: 'auto', permissionMode: 'manual', appendSystem: '', mcp: [], ...extra,
});

export const prompt: ContentBlock[] = [{ type: 'text', text: 'salut' }];

/** Enregistre les événements d'un tour sous forme de chaînes. */
export class Rec implements TurnListener {
  events: string[] = [];
  turn!: CliTurn;
  perm?: (r: PermissionRequest) => void;
  private resolveDone!: () => void;
  done = new Promise<void>((r) => { this.resolveDone = r; });
  onInit(sid: string) { this.events.push(`init:${sid}`); }
  onText(d: string) { this.events.push(`text:${d}`); }
  onThinking(d: string) { this.events.push(`think:${d}`); }
  onToolUse(_id: string, name: string) { this.events.push(`tool:${name}`); }
  onToolResult(_id: string, text: string, err: boolean) { this.events.push(`result:${text}:${err}`); }
  onPermission(r: PermissionRequest) { this.events.push(`perm:${r.tool}`); this.perm?.(r); }
  onUsage(i: number, o: number, c: number) { this.events.push(`usage:${i}/${o}/${c}`); }
  onDone(err: boolean, msg?: string) { this.events.push(`done:${err}:${msg ?? ''}`); this.resolveDone(); }
}

export async function run(cli: string, dir: string, o?: TurnOptions, setup?: (r: Rec) => void): Promise<{ rec: Rec; turn: CliTurn }> {
  const rec = new Rec();
  setup?.(rec);
  const turn = new CliTurn(o ?? opts(cli, dir), prompt, rec);
  rec.turn = turn;
  turn.start();
  await Promise.race([rec.done, new Promise((_, rej) => setTimeout(() => rej(new Error(`timeout : ${rec.events}`)), 15_000))]);
  return { rec, turn };
}
