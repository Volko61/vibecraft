#!/usr/bin/env node
// Unit-checks run-e2e.mjs itself against tools/fake-api.mjs + the private sandbox server (no VibeCraft):
//   A. a passing run (api-contract only)                         -> exit 0, report PASSED
//   B. a reload that KICKS the bot (fake engine misbehaving)     -> scenario FAIL "bot kicked", reconnect,
//      global bot-connected FAIL, missing commands reported as FAIL, exit 1, test modules cleaned up
//   C. API unreachable                                           -> exit 1, report SETUP_FAILED
//   D. unknown scenario name                                     -> exit 2
//   E. ai-* scenarios without --fake-claude                      -> SKIP, exit 0
// Prereq: tools/sandbox.sh start (game 25570 / RCON 25571).
// Usage: node tools/selftest-fake-api.mjs [--dir tests/e2e/.sandbox] [--port 25570] [--api-port 18765]
import fs from 'node:fs';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { startFakeApi } from './fake-api.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const E2E = path.resolve(here, '..');
const args = process.argv.slice(2);
const opt = (k, d) => { const i = args.indexOf(k); return i >= 0 ? args[i + 1] : d; };
const DIR = path.resolve(opt('--dir', path.join(E2E, '.sandbox')));
const MC_PORT = opt('--port', '25570');
const API_PORT = Number(opt('--api-port', 18765));
const WORK = path.join(DIR, 'selftest');
const MODULES = path.join(WORK, 'workspace', 'modules');
const REPORTS = path.join(WORK, 'reports');

const checks = [];
const check = (name, ok, detail = '') => {
  checks.push({ name, ok: !!ok, detail });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? ' — ' + detail : ''}`);
};

function runRunner(extra, label) {
  return new Promise((resolve) => {
    const logFile = path.join(WORK, `${label}.log`);
    const out = fs.createWriteStream(logFile);
    const before = new Set(fs.existsSync(REPORTS) ? fs.readdirSync(REPORTS) : []);
    const p = spawn(process.execPath, [path.join(E2E, 'run-e2e.mjs'), '--mc-port', MC_PORT, '--modules-dir', MODULES, '--report-dir', REPORTS, ...extra],
      { cwd: E2E, env: { ...process.env, NO_COLOR: '1' } });
    p.stdout.pipe(out); p.stderr.pipe(out);
    p.on('close', (code) => {
      out.end();
      const added = (fs.existsSync(REPORTS) ? fs.readdirSync(REPORTS) : []).filter((f) => !before.has(f));
      const report = added.length ? JSON.parse(fs.readFileSync(path.join(REPORTS, added.sort().at(-1)), 'utf8')) : null;
      resolve({ code, report, log: fs.readFileSync(logFile, 'utf8') });
    });
  });
}

const byName = (rep, n) => rep?.results?.find((r) => r.name === n);

async function main() {
  fs.rmSync(WORK, { recursive: true, force: true });
  fs.mkdirSync(MODULES, { recursive: true });
  const api = await startFakeApi({ port: API_PORT, serverDir: DIR, modulesDir: MODULES, kickOn: ['e2e-hot'] });
  const base = ['--api', `http://127.0.0.1:${API_PORT}`, '--rcon-dir', DIR];
  try {
    // A
    let r = await runRunner([...base, '--only', 'api-contract'], 'A-pass');
    check('A: exit code 0 on a passing run', r.code === 0, `exit ${r.code}`);
    check('A: report written with status PASSED', r.report?.status === 'PASSED', r.report?.status);
    check('A: api-contract PASS', byName(r.report, 'api-contract')?.status === 'PASS', byName(r.report, 'api-contract')?.reason);
    check('A: global tps PASS', byName(r.report, 'tps')?.status === 'PASS', byName(r.report, 'tps')?.reason);
    check('A: global bot-connected PASS', byName(r.report, 'bot-connected')?.status === 'PASS', byName(r.report, 'bot-connected')?.reason);
    check('A: summary table printed', /Summary[\s\S]*api-contract\s+PASS/.test(r.log));
    if (r.code !== 0) console.log(r.log.split('\n').slice(-40).join('\n'));

    // B
    r = await runRunner([...base, '--only', 'hot-reload,module-removal'], 'B-kick');
    const hotR = byName(r.report, 'hot-reload');
    const rem = byName(r.report, 'module-removal');
    const botR = byName(r.report, 'bot-connected');
    check('B: exit code 1', r.code === 1, `exit ${r.code}`);
    check('B: hot-reload FAIL because the bot was kicked', hotR?.status === 'FAIL' && /bot kicked during the scenario/.test(hotR.reason), hotR?.reason);
    check('B: kick reason captured', /Simulated: reload of e2e-hot/.test(hotR?.reason || ''), hotR?.reason);
    check('B: next scenario ran with a reconnected bot', (r.report?.runnerEvents || []).some((e) => e.type === 'reconnect' && e.before === 'module-removal'), JSON.stringify(r.report?.runnerEvents));
    check('B: module-removal FAIL (fake engine registers no command)', rem?.status === 'FAIL' && /E2E-CMD|E2E-LISTENER/.test(rem.reason), rem?.reason);
    check('B: global bot-connected FAIL', botR?.status === 'FAIL' && /kicked/.test(botR.reason), botR?.reason);
    const left = fs.readdirSync(MODULES).filter((d) => d.startsWith('e2e-'));
    check('B: test modules cleaned up after failures', left.length === 0, left.join(','));

    // C
    r = await runRunner(['--api', 'http://127.0.0.1:18799', '--wait-api', '2'], 'C-unreachable');
    check('C: exit 1 when the API is unreachable', r.code === 1, `exit ${r.code}`);
    check('C: report status SETUP_FAILED', r.report?.status === 'SETUP_FAILED', r.report?.status);

    // D
    r = await runRunner([...base, '--only', 'no-such-scenario'], 'D-usage');
    check('D: exit 2 on unknown scenario', r.code === 2, `exit ${r.code}`);

    // E: the AI scenarios never run without --fake-claude (they would start real Claude jobs): SKIP, exit 0
    r = await runRunner([...base, '--only', 'ai-'], 'E-skip');
    check('E: ai-* SKIPPED without --fake-claude, exit 0', r.code === 0 && ['ai-jobs', 'ai-ideas'].every((n) => byName(r.report, n)?.status === 'SKIP'), `exit ${r.code} ${JSON.stringify(r.report?.counts)}`);
  } finally {
    api.close();
  }
}

main()
  .catch((e) => check('selftest crashed', false, e.stack || String(e)))
  .finally(() => {
    const failed = checks.filter((c) => !c.ok);
    console.log(`\n${checks.length - failed.length}/${checks.length} self-test checks passed (logs in ${WORK})`);
    process.exit(failed.length ? 1 : 0);
  });
