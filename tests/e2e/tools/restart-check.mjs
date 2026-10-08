#!/usr/bin/env node
// Server RESTART check (ARCHITECTURE.md 4.1 "Startup" + store persistence): only for a PRIVATE sandbox, because the
// server is stopped and started in the middle. Driven by `tools/integ.sh restart-check`:
//   1. --phase prepare : creates e2e-boot-ok (store), e2e-boot-off (then disabled), e2e-boot-bad (compile error),
//                        e2e-boot-ok2; increments the store twice through POST /api/command (console sender)
//   2. (integ.sh stops and starts the sandbox)
//   3. --phase verify  : every module is back in the right state, the store survived, the disabled module was
//                        NOT loaded, the broken one did not prevent the others; then everything is removed.
// Usage: node tools/restart-check.mjs --phase prepare|verify [--api http://127.0.0.1:8772] --modules-dir <dir> [--data-dir <dir>]
import fs from 'node:fs';
import path from 'node:path';
import { VibeApi } from '../lib/api.mjs';
import { Workspace, waitForModule, waitForGone, sleep } from '../lib/workspace.mjs';
import { renderFixture, COMPILE_ERROR } from '../lib/fixtures.mjs';

const args = process.argv.slice(2);
const opt = (k, d) => { const i = args.indexOf(k); return i >= 0 ? args[i + 1] : d; };
const PHASE = opt('--phase');
const api = new VibeApi({ base: opt('--api', 'http://127.0.0.1:8772'), token: opt('--token', '') });
const ws = new Workspace({ modulesDir: opt('--modules-dir'), dataDir: opt('--data-dir') || undefined, prefix: 'e2e-boot' });
const STATE = path.join(ws.modulesDir, '..', 'restart-check.json');
const RUN = Date.now().toString(36).slice(-6);

const checks = [];
const check = (name, ok, detail = '') => {
  checks.push({ name, ok: !!ok, detail });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? ' — ' + (typeof detail === 'string' ? detail : JSON.stringify(detail)) : ''}`);
  return !!ok;
};
const consoleCmd = async (line) => (await api.command(line)).output || [];
const storeLine = async () => (await consoleCmd('e2estoreboot get')).find((l) => l.startsWith('E2E-STORE')) || '';
const kv = (line) => Object.fromEntries(line.split(/\s+/).slice(1).map((p) => p.split('=')));

async function waitApi(sec = 120) {
  const until = Date.now() + sec * 1000;
  while (!(await api.reachable(3000))) {
    if (Date.now() > until) throw new Error(`API ${api.base} not reachable`);
    await sleep(1000);
  }
}

async function prepare() {
  await waitApi();
  for (const id of ws.listTestModules()) { ws.removeModule(id); await waitForGone(api, id, 20000).catch(() => {}); }
  for (const id of ws.listTestData()) ws.removeModule(id);
  const v = `${RUN}.v1`;
  // store module: its command is renamed so it cannot clash with the e2e suite's /e2estore
  const store = renderFixture('store', { id: 'e2e-boot-ok', vars: { V: v } });
  store['StoreModule.java'] = store['StoreModule.java'].replace('ctx.command("e2estore"', 'ctx.command("e2estoreboot"');
  ws.writeModule('e2e-boot-ok', store);
  ws.writeModule('e2e-boot-off', renderFixture('hot', { id: 'e2e-boot-off', vars: { V: v, P: 'e2ebootoff' } }));
  ws.writeModule('e2e-boot-ok2', renderFixture('hot', { id: 'e2e-boot-ok2', vars: { V: v, P: 'e2ebootok' } }));
  ws.writeModule('e2e-boot-bad', renderFixture('hot', { id: 'e2e-boot-bad', vars: { V: v, P: 'e2ebootbad' }, inject: COMPILE_ERROR.inject }));
  for (const id of ['e2e-boot-ok', 'e2e-boot-off', 'e2e-boot-ok2']) {
    const r = await api.reload(id);
    check(`prepare: ${id} loads`, r.ok, r.error);
  }
  const bad = await api.reload('e2e-boot-bad');
  check('prepare: e2e-boot-bad fails (FAILED)', !bad.ok && bad.state === 'FAILED', bad.state);
  await consoleCmd('e2estoreboot inc');
  await consoleCmd('e2estoreboot inc');
  const s = kv(await storeLine());
  check('prepare: store count=2 boots=1', s.count === '2' && s.boots === '1', s);
  const d = await api.disable('e2e-boot-off');
  check('prepare: e2e-boot-off disabled', d.ok === true && ws.vibeExists('e2e-boot-off', 'disabled'), d);
  fs.writeFileSync(STATE, JSON.stringify({ run: RUN, preparedAt: Date.now() }));
}

async function verify() {
  await waitApi();
  const st = JSON.parse(fs.readFileSync(STATE, 'utf8'));
  const status = await api.status();
  check('restart: server uptime is fresh (the server really restarted)', status.server.uptimeMs < Date.now() - st.preparedAt, status.server.uptimeMs);
  const ok = await waitForModule(api, 'e2e-boot-ok', (i) => i && i.state !== 'DISCOVERED' && i.state !== 'COMPILING', { timeoutMs: 30000 }).catch((e) => e);
  check('restart: e2e-boot-ok loaded at startup (ENABLED, generation 1)', ok?.state === 'ENABLED' && ok.generation === 1, ok?.state ?? ok?.message);
  const ok2 = await api.module('e2e-boot-ok2');
  check('restart: e2e-boot-ok2 loaded at startup despite e2e-boot-bad failing', ok2?.state === 'ENABLED', ok2?.state);
  const off = await api.module('e2e-boot-off');
  check('restart: e2e-boot-off stays DISABLED (.vibe/disabled honoured at startup)', off?.state === 'DISABLED' && (off.commands || []).length === 0, off && { state: off.state, commands: off.commands });
  const offCmd = await consoleCmd('e2ebootoff').catch((e) => [e.message]);
  check('restart: /e2ebootoff not registered', !offCmd.some((l) => /E2E-CMD/.test(l)), offCmd);
  const bad = await api.module('e2e-boot-bad');
  check('restart: e2e-boot-bad FAILED with its compile diagnostics', bad?.state === 'FAILED' && (bad.diagnostics || []).some((x) => x.kind === 'ERROR'), bad && { state: bad.state, diags: (bad.diagnostics || []).length });
  const s = kv(await storeLine());
  check('restart: ModuleStore survived the restart (count=2) and enable() ran once more (boots=2)', s.count === '2' && s.boots === '2', s);
  const okCmd = await consoleCmd('e2ebootok');
  check('restart: /e2ebootok answers', okCmd.some((l) => l === `E2E-CMD ${st.run}.v1`), okCmd);
  // cleanup
  for (const id of ws.listTestModules()) { ws.removeModule(id, { data: false }); await waitForGone(api, id, 20000).catch((e) => check(`cleanup ${id}`, false, e.message)); }
  for (const id of ws.listTestData()) ws.removeModule(id);
  fs.rmSync(STATE, { force: true });
}

(PHASE === 'prepare' ? prepare() : PHASE === 'verify' ? verify() : Promise.reject(new Error('--phase prepare|verify')))
  .catch((e) => check(`restart-check ${PHASE} crashed`, false, e.stack || String(e)))
  .finally(() => {
    const failed = checks.filter((c) => !c.ok);
    console.log(`${PHASE}: ${checks.length - failed.length}/${checks.length} checks passed`);
    process.exit(failed.length ? 1 : 0);
  });
