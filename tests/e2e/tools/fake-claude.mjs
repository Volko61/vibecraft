#!/usr/bin/env node
// FAKE Claude Code CLI for the e2e tests of the AI job pipeline (dev.vibecraft.ai). It never calls any model.
// The integration sandbox points `ai.claude-path` at this file (VibeCraft runs .mjs paths with `node`), see
// tools/integ.sh (INTEG_FAKE_CLAUDE=1) and the `ai-jobs` scenario (run-e2e.mjs --fake-claude <log>).
//
//   claude --version | --help | auth status --json      -> answers like the real CLI (logged in)
//   claude -p --output-format stream-json ... < prompt  -> a scripted "agent" in stream-json:
//       system/init, a short French text, Write of modules/<id>/<Pascal>Module.java, the curl reload (really
//       POSTed to the API found in the prompt), the tool result, and a final "result" with a cost.
// Behaviour switches inside the request text (between <demande> tags):
//   E2E-CMD=<name> E2E-MARK=<text>  command the generated module registers and the line it answers
//   E2E-SLOW                         spawns a child process and waits 120 s (to test stop / 409 busy / tree kill)
//   E2E-FAIL                         ends with an error result (is_error) and exit code 1
// Every invocation is appended (JSON line) to $FAKE_CLAUDE_LOG: argv, cwd, pid, child pid, module, resume id.
import fs from 'node:fs';
import path from 'node:path';
import { spawn } from 'node:child_process';

const argv = process.argv.slice(2);
const LOG = process.env.FAKE_CLAUDE_LOG;
const log = (o) => { if (LOG) { try { fs.appendFileSync(LOG, JSON.stringify({ at: Date.now(), pid: process.pid, ...o }) + '\n'); } catch { /* ignore */ } } };
const out = (o) => process.stdout.write(JSON.stringify(o) + '\n');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

if (!argv.includes('-p') && !argv.includes('--print')) log({ kind: 'probe', argv });
if (argv[0] === '--version') { console.log('2.1.999 (Claude Code) [e2e fake]'); process.exit(0); }
if (argv[0] === '--help') {
  console.log(`Usage: claude [options] [command] [prompt]
Options:
  -p, --print  --output-format <format>  --input-format <format>  --verbose  --permission-mode <mode>
  --model <model>  --resume <id>  --continue  --allowedTools <tools...>  --tools <tools...>  --disallowedTools <tools...>
  --strict-mcp-config  --mcp-config <configs...>  --effort <level>  --max-budget-usd <amount>  --permission-prompts
  --append-system-prompt <prompt>  --settings <file>  --add-dir <dirs...>  -h, --help  -v, --version
Commands:
  auth  mcp  config  doctor  update`);
  process.exit(0);
}
if (argv[0] === 'auth' && argv[1] === 'status') {
  console.log(JSON.stringify({ loggedIn: true, authMethod: 'e2e-fake', apiProvider: 'firstParty' }));
  process.exit(0);
}
if (!argv.includes('-p') && !argv.includes('--print')) {
  console.error('fake claude: unsupported invocation ' + JSON.stringify(argv));
  process.exit(2);
}

const chunks = [];
for await (const c of process.stdin) chunks.push(c);
const prompt = Buffer.concat(chunks).toString('utf8');
const ri = argv.indexOf('--resume');
const resume = ri >= 0 ? argv[ri + 1] : null;
const moduleId = (prompt.match(/Module : `([a-z0-9][a-z0-9_-]{0,39})`/) || [])[1] || null;
const apiBase = (prompt.match(/curl -s -X POST (http:\/\/[^\s/]+)\/api\/modules\//) || [])[1] || null;
const tokenHeader = (prompt.match(/-H "X-Vibe-Token: ([^"]+)"/) || [])[1] || null;
const request = ((prompt.match(/<demande>\s*([\s\S]*?)\s*<\/demande>/) || [])[1] || '').trim();
const cmdName = (request.match(/E2E-CMD=([a-z0-9]+)/) || [])[1] || 'e2eaicmd';
const mark = (request.match(/E2E-MARK=(\S+)/) || [])[1] || 'hello';
const session = resume || `e2e-fake-${process.pid}-${Date.now().toString(36)}`;
const recommendedPackage = (prompt.match(/Package recommandé : `([^`]+)`/) || [])[1] || null;
const entry = { kind: 'job', argv, cwd: process.cwd(), moduleId, apiBase, resume, request, recommendedPackage, promptChars: prompt.length };

out({ type: 'system', subtype: 'init', session_id: session, model: 'e2e-fake', cwd: process.cwd(), tools: ['Read', 'Write', 'Edit', 'Bash'], permissionMode: 'acceptEdits' });
await sleep(150);

if (request.includes('E2E-SLOW')) {
  const child = spawn(process.execPath, ['-e', 'setTimeout(() => {}, 120000)'], { stdio: 'ignore' });
  log({ ...entry, mode: 'slow', childPid: child.pid });
  out({ type: 'assistant', session_id: session, message: { model: 'e2e-fake', content: [{ type: 'text', text: 'Je réfléchis très longtemps à cette demande…' }] } });
  await sleep(120000);
  process.exit(0);
}
if (request.includes('E2E-FAIL') || !moduleId) {
  log({ ...entry, mode: 'fail' });
  out({ type: 'assistant', session_id: session, message: { model: 'e2e-fake', content: [{ type: 'text', text: 'Je n\'y arrive pas.' }] } });
  out({ type: 'result', subtype: 'error_during_execution', is_error: true, result: '', session_id: session, total_cost_usd: 0.001, num_turns: 1, errors: ['e2e fake failure'] });
  process.exit(1);
}
log({ ...entry, mode: 'ok' });

const pascal = moduleId.split(/[-_]/).filter(Boolean).map((s) => s[0].toUpperCase() + s.slice(1)).join('') + 'Module';
// an obedient agent: uses the package the prompt recommends
const pkg = (prompt.match(/Package recommandé : `([^`]+)`/) || [])[1] || 'vibe.' + moduleId.replace(/-/g, '_');
const file = path.join(process.cwd(), 'modules', moduleId, `${pascal}.java`);
const java = `package ${pkg};

import dev.vibecraft.api.ModuleContext;
import dev.vibecraft.api.VibeModule;

/** Written by the e2e FAKE Claude (tests/e2e/tools/fake-claude.mjs). */
public class ${pascal} implements VibeModule {
    @Override
    public void enable(ModuleContext ctx) {
        ctx.command("${cmdName}", (s, a) -> s.sendMessage("E2E-AI ${mark}"));
    }
}
`;
const say = (text) => out({ type: 'assistant', session_id: session, message: { model: 'e2e-fake', content: [{ type: 'text', text }] } });
const tool = (id, name, input) => out({ type: 'assistant', session_id: session, message: { model: 'e2e-fake', content: [{ type: 'tool_use', id, name, input }] } });
const result = (id, content, isError = false) => out({ type: 'user', session_id: session, message: { content: [{ type: 'tool_result', tool_use_id: id, content, is_error: isError }] } });

say('Je crée la commande demandée pour le public.');
await sleep(300);
tool('toolu_e2e_1', 'Write', { file_path: file, content: java });
fs.mkdirSync(path.dirname(file), { recursive: true });
fs.writeFileSync(file, java);
result('toolu_e2e_1', `File created successfully at: ${file}`);
await sleep(300);
const curl = `curl -s -X POST ${apiBase}/api/modules/${moduleId}/reload${tokenHeader ? ` -H "X-Vibe-Token: ${tokenHeader}"` : ''}`;
tool('toolu_e2e_2', 'Bash', { command: curl, description: 'Recharge le module' });
let reloadBody;
try {
  const r = await fetch(`${apiBase}/api/modules/${moduleId}/reload`, { method: 'POST', headers: tokenHeader ? { 'X-Vibe-Token': tokenHeader } : {} });
  reloadBody = await r.text();
} catch (e) {
  reloadBody = `curl: (7) ${e.message}`;
}
result('toolu_e2e_2', reloadBody);
await sleep(300);
say('Le module est rechargé, je vérifie.');
await sleep(1700); // > 1.5 s: lets the notifier's per-job throttle deliver this line too
const ok = /"ok"\s*:\s*true/.test(reloadBody);
out({
  type: 'result', subtype: ok ? 'success' : 'error_during_execution', is_error: !ok,
  result: ok ? `Tapez /${cmdName} pour voir ${mark} !${resume ? ` (suite de ${resume})` : ''}` : 'Le rechargement a échoué.',
  session_id: session, total_cost_usd: 0.0123, num_turns: 3,
});
process.exit(ok ? 0 : 1);
