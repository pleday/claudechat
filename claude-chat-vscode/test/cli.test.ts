import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { CliTurn, cleanSuggestion, parseRateLimits, timing, type TurnListener } from '../src/core/cli';
import { fakeCli, opts, prompt, Rec, run, tmp } from './helpers';

describe('CliTurn (protocole stream-json, faux exécutable)', () => {
  it('transmet texte, réflexion, outils et usage', async () => {
    const d = tmp();
    const cli = fakeCli(d, `
read -r line
echo '{"type":"system","subtype":"init","session_id":"sid-1","model":"m"}'
echo '{"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"thinking_delta","thinking":"hum"}}}'
echo '{"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"Bon"}}}'
echo '{"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"jour"}}}'
echo '{"type":"assistant","message":{"content":[{"type":"tool_use","id":"t1","name":"Read","input":{"file_path":"/x"}}]}}'
echo '{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t1","content":"ok"}]}}'
echo '{"type":"result","is_error":false,"total_cost_usd":0.5,"usage":{"input_tokens":3,"output_tokens":4}}'`);
    const { rec } = await run(cli, d);
    expect(rec.events).toEqual(['init:sid-1', 'think:hum', 'text:Bon', 'text:jour', 'tool:Read', 'result:ok:false', 'usage:3/4/0.5', 'done:false:']);
  });

  it('ignore un événement malformé sans interrompre la réponse', async () => {
    const d = tmp();
    const cli = fakeCli(d, `
read -r line
echo '{"type":"system","subtype":"init"}'
echo 'pas du json'
echo '{"type":"stream_event"}'
echo '{"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"ok"}}}'
echo '{"type":"result","is_error":false}'`);
    const { rec } = await run(cli, d);
    expect(rec.events.filter((e) => !e.startsWith('init'))).toEqual(['text:ok', 'usage:0/0/0', 'done:false:']);
  });

  it('garde l\'entrée ouverte et reprend après un result', async () => {
    const d = tmp();
    const out = join(d, 'after.txt');
    const cli = fakeCli(d, `
read -r line
echo '{"type":"result","is_error":false}'
sleep 1
echo '{"type":"control_request","request_id":"r9","request":{"subtype":"can_use_tool","tool_name":"Bash","input":{}}}'
read -r answer
echo "$answer" > ${out}
echo '{"type":"result","is_error":false}'`);
    const events: string[] = [];
    let dones = 0;
    let finish!: () => void;
    const second = new Promise<void>((r) => { finish = r; });
    const l: TurnListener = {
      onResumed: () => events.push('resumed'),
      onPermission: (req) => { events.push(`perm:${req.tool}`); t.allow(req.requestId, req.input); },
      onDone: (e) => { events.push(`done:${e}`); if (++dones === 2) finish(); },
    };
    const t = new CliTurn(opts(cli, d), prompt, l);
    t.start();
    await second;
    expect(events).toEqual(['done:false', 'resumed', 'perm:Bash', 'done:false']);
    expect(readFileSync(out, 'utf8')).toContain('"behavior":"allow"');
  });

  it('attend les serveurs MCP avant d\'envoyer et signale les échecs', async () => {
    const d = tmp();
    const out = join(d, 'order.txt');
    const cli = fakeCli(d, `
read -r a; echo "$a" >> ${out}
echo '{"type":"control_response","response":{"subtype":"success","request_id":"mcp-wait-0","response":{"mcpServers":[{"name":"sonarqube","status":"pending"},{"name":"idea","status":"connected"}]}}}'
read -r b; echo "$b" >> ${out}
echo '{"type":"control_response","response":{"subtype":"success","request_id":"mcp-wait-1","response":{"mcpServers":[{"name":"sonarqube","status":"failed"},{"name":"idea","status":"connected"}]}}}'
read -r c; echo "$c" >> ${out}
echo '{"type":"result","is_error":false}'`);
    const problems: string[] = [];
    const rec = new Rec();
    const t = new CliTurn(opts(cli, d, { waitForMcp: true }), prompt, Object.assign(Object.create(rec), { onDone: rec.onDone.bind(rec), onMcpProblem: (s: string[]) => problems.push(...s) }));
    t.start();
    await rec.done;
    const lines = readFileSync(out, 'utf8').trim().split('\n').map((l) => JSON.parse(l).type);
    expect(lines).toEqual(['control_request', 'control_request', 'user']);
    expect(problems).toEqual(['sonarqube (failed)']);
  });

  it('envoie le message sous forme de stream-json', async () => {
    const d = tmp();
    const out = join(d, 'stdin.txt');
    const cli = fakeCli(d, `read -r line; echo "$line" > ${out}; echo '{"type":"result","is_error":false}'`);
    await run(cli, d);
    const sent = JSON.parse(readFileSync(out, 'utf8'));
    expect(sent.type).toBe('user');
    expect(sent.message.content[0].text).toBe('salut');
  });

  it('autorisation : aller-retour avec l\'entrée modifiée', async () => {
    const d = tmp();
    const out = join(d, 'reply.txt');
    const cli = fakeCli(d, `
read -r line
echo '{"type":"control_request","request_id":"r1","request":{"subtype":"can_use_tool","tool_name":"Bash","input":{"command":"ls"},"description":"ls"}}'
read -r reply
echo "$reply" > ${out}
echo '{"type":"result","is_error":false}'`);
    await run(cli, d, undefined, (r) => { r.perm = (req) => setTimeout(() => r.turn.allow(req.requestId, req.input), 100); });
    const reply = JSON.parse(readFileSync(out, 'utf8'));
    expect(reply.type).toBe('control_response');
    expect(reply.response.request_id).toBe('r1');
    expect(reply.response.response).toMatchObject({ behavior: 'allow', updatedInput: { command: 'ls' } });
  });

  it('refus : transmet le message', async () => {
    const d = tmp();
    const out = join(d, 'reply.txt');
    const cli = fakeCli(d, `
read -r line
echo '{"type":"control_request","request_id":"r2","request":{"subtype":"can_use_tool","tool_name":"Edit","input":{}}}'
read -r reply
echo "$reply" > ${out}
echo '{"type":"result","is_error":false}'`);
    await run(cli, d, undefined, (r) => { r.perm = (req) => setTimeout(() => r.turn.deny(req.requestId, 'non merci'), 100); });
    expect(JSON.parse(readFileSync(out, 'utf8')).response.response).toEqual({ behavior: 'deny', message: 'non merci' });
  });

  it('transmet les options (mode, reprise, fork, modèle) et omet effort=auto', async () => {
    const d = tmp();
    const out = join(d, 'args.txt');
    const cli = fakeCli(d, `echo "$@" > ${out}; read -r line; echo '{"type":"result","is_error":false}'`);
    await run(cli, d, opts(cli, d, { permissionMode: 'plan', sessionId: 'abc', fork: true }));
    const args = readFileSync(out, 'utf8');
    expect(args).toContain('--permission-mode plan');
    expect(args).toContain('--resume abc');
    expect(args).toContain('--fork-session');
    expect(args).toContain('--model sonnet');
    expect(args).not.toContain('--effort');
  });

  it('signale un résultat en erreur', async () => {
    const d = tmp();
    const cli = fakeCli(d, `read -r line; echo '{"type":"result","is_error":true,"result":"limite atteinte"}'`);
    const { rec } = await run(cli, d);
    expect(rec.events).toContain('done:true:limite atteinte');
  });

  it('rapporte stderr quand le processus meurt sans résultat', async () => {
    const d = tmp();
    const cli = fakeCli(d, `echo 'non connecté' >&2; exit 1`);
    const { rec } = await run(cli, d);
    expect(rec.events.at(-1)).toMatch(/^done:true:.*non connecté/);
  });

  it('rapporte un exécutable introuvable (une seule fois)', async () => {
    const d = tmp();
    const { rec } = await run('/nonexistent/claude', d);
    expect(rec.events).toHaveLength(1);
    expect(rec.events[0]).toMatch(/^done:true:Impossible de lancer/);
  });

  it('précision en cours de réponse : envoyée avec priority next, consommée au rejeu', async () => {
    const d = tmp();
    const out = join(d, 'refine.txt');
    const cli = fakeCli(d, `
read -r first
echo '{"type":"system","subtype":"init","session_id":"s"}'
read -r second
echo "$second" > ${out}
echo '{"type":"user","message":{"role":"user","content":[{"type":"text","text":"plus court"}]}}'
echo '{"type":"result","is_error":false}'`);
    const lost: string[][] = [];
    const rec = new Rec();
    const t = new CliTurn(opts(cli, d), prompt, Object.assign(Object.create(rec), {
      onDone: rec.onDone.bind(rec), onRefineLost: (x: string[]) => lost.push(x),
      onInit: () => { setTimeout(() => expect(t.refine('plus court')).toBe(true), 50); },
    }));
    t.start();
    await rec.done;
    expect(JSON.parse(readFileSync(out, 'utf8')).priority).toBe('next');
    expect(lost).toEqual([]);
    expect(t.refine('trop tard')).toBe(false);
  });

  it('une précision non consommée est rendue à la fin du tour', async () => {
    const d = tmp();
    const cli = fakeCli(d, `
read -r first
echo '{"type":"system","subtype":"init","session_id":"s"}'
read -r second
echo '{"type":"result","is_error":false}'`);
    const lost: string[][] = [];
    const rec = new Rec();
    const t = new CliTurn(opts(cli, d), prompt, Object.assign(Object.create(rec), {
      onDone: rec.onDone.bind(rec), onRefineLost: (x: string[]) => lost.push(x), onInit: () => { setTimeout(() => t.refine('oubliée'), 50); },
    }));
    t.start();
    await rec.done;
    expect(lost).toEqual([['oubliée']]);
  });

  it('requête de contrôle : réponse, délai dépassé, processus terminé', async () => {
    const d = tmp();
    const cli = fakeCli(d, `
read -r first
echo '{"type":"system","subtype":"init","session_id":"s"}'
read -r req
id=$(echo "$req" | sed -E 's/.*"request_id":"([^"]+)".*/\\1/')
echo '{"type":"control_response","response":{"subtype":"success","request_id":"'$id'","response":{"mode":"plan"}}}'
read -r never
`);
    const rec = new Rec();
    const t = new CliTurn(opts(cli, d), prompt, Object.assign(Object.create(rec), { onDone: rec.onDone.bind(rec) }));
    t.start();
    await new Promise((r) => setTimeout(r, 300));
    const r = await t.setPermissionMode('plan');
    expect(r).toMatchObject({ ok: true, payload: { mode: 'plan' } });
    expect(await t.request('x', {}, 200)).toBeNull(); // la CLI ne répond plus
    t.kill();
    expect(await t.request('x')).toBeNull(); // processus tué
  });

  it('ferme l\'entrée après un long silence', async () => {
    const d = tmp();
    const out = join(d, 'eof.txt');
    const cli = fakeCli(d, `
read -r line
echo '{"type":"result","is_error":false}'
cat > /dev/null
echo closed > ${out}`);
    const old = timing.quietBeforeCloseMs;
    timing.quietBeforeCloseMs = 200;
    try {
      const { rec } = await run(cli, d);
      expect(rec.events).toContain('done:false:');
      await new Promise((r) => setTimeout(r, 1200));
      expect(readFileSync(out, 'utf8').trim()).toBe('closed');
    } finally { timing.quietBeforeCloseMs = old; }
  });
});

describe('quotas et suggestions', () => {
  it('lit les fenêtres de quota', () => {
    const r = parseRateLimits({ rate_limit_info: { status: 'allowed', unifiedWindows: { five_hour: { utilization: 0.4, resetsAt: 100 }, seven_day: { utilization: 0.1, resetsAt: 200 } } } });
    expect(r?.fiveHour).toEqual({ utilization: 0.4, resetsAt: 100 });
    expect(r?.sevenDay?.resetsAt).toBe(200);
    expect(parseRateLimits({ rate_limit_info: { status: 'rejected', rateLimitType: 'five_hour', resetsAt: 5 } })?.limitType).toBe('five_hour');
    expect(parseRateLimits({ rate_limit_info: { status: 'allowed' } })).toBeUndefined();
    expect(parseRateLimits({})).toBeUndefined();
  });

  it('nettoie les suggestions', () => {
    expect(cleanSuggestion('  \n  Lance les tests  \nautre')).toBe('Lance les tests');
    expect(cleanSuggestion('x'.repeat(500))).toHaveLength(300);
    expect(cleanSuggestion('   ')).toBeUndefined();
    expect(cleanSuggestion(undefined)).toBeUndefined();
  });
});
