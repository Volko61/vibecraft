// e2e scenarios (ARCHITECTURE.md section 9). Each one is independent: setup -> assertions -> cleanup.
// A scenario throws (AssertionError or any Error) to FAIL; t.expect() records a failed check and goes on
// (the scenario FAILS at the end); t.warn()/t.check() record non-fatal observations; t.skip() = SKIP.
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { COMPILE_ERROR, SYNTAX_ERROR, FORBIDDEN, FORBIDDEN_INDIRECT, forbiddenInjection, forbiddenHelperFile, MODULE_CLASS_VARIANTS } from './fixtures.mjs';
import { packageFor } from './workspace.mjs';
import { escapeRe, sleep, UNKNOWN_CMD } from './harness.mjs';

const INTERNAL_ERROR = /internal error occurred|An internal error/i;
/** Minecraft's own reply when a command does not exist (NOT VibeCraft's French "Sous-commande inconnue"). */
const MC_UNKNOWN = /Unknown or incomplete command/;

// Guard budgets from the default config.yml (guard.*). Used for timing assertions (generous slack).
const HANDLER_BUDGET_MS = 250;
const ENABLE_BUDGET_MS = 5000;

/** Parse "E2E-XXX-STAT <v> [rawThreads=n] key=value key=value" into {v, ...}. */
function parseStat(line) {
  const parts = line.trim().split(/\s+/);
  const out = { _v: parts[1] };
  for (const p of parts.slice(2)) {
    const i = p.indexOf('=');
    if (i > 0) out[p.slice(0, i)] = Number(p.slice(i + 1));
  }
  return out;
}

async function stat(t, cmd, regex) {
  const r = await t.cmd(cmd, { until: regex, windowMs: 8000, settleMs: 200 });
  const line = r.lines.find((l) => regex.test(l));
  t.assert(line, `no stat line from ${cmd}`, r.lines);
  return parseStat(line);
}

/** Hot-fixture expectations: exactly one CMD and one LISTENER line for version v, none for other versions. */
async function expectHot(t, P, v) {
  const r = await t.cmd('/' + P, { until: new RegExp(`^E2E-CMD ${escapeRe(v)}$|${UNKNOWN_CMD.source}`, 'i'), windowMs: 8000, settleMs: 700 });
  const cmd = t.matching(r, /^E2E-CMD /);
  const lis = t.matching(r, /^E2E-LISTENER /);
  t.assert(cmd.length === 1 && cmd[0] === `E2E-CMD ${v}`, `/${P}: expected exactly one "E2E-CMD ${v}"`, r.lines);
  t.assert(lis.length === 1 && lis[0] === `E2E-LISTENER ${v}`, `/${P}: expected exactly one "E2E-LISTENER ${v}" (listener duplicated or missing?)`, r.lines);
  t.assert(!r.lines.some((l) => INTERNAL_ERROR.test(l)), `/${P}: internal error leaked to the player`, r.lines);
  return r;
}

async function expectGone(t, P) {
  const r = await t.cmd('/' + P, { windowMs: 1500 });
  t.assert(!r.lines.some((l) => /^E2E-/.test(l)), `/${P} should not exist any more`, r.lines);
  t.check(r.lines.some((l) => UNKNOWN_CMD.test(l)), `/${P}: expected an "unknown command" reply, got ${JSON.stringify(r.lines)}`);
  return r;
}

/** The client's command tree (tab completion) must contain / not contain /P (and, when absent, its namespaced forms). */
async function expectTree(t, P, present, { timeoutMs = 4000, alts = ['vibe:' + P] } = {}) {
  const ok = (names) => (present ? names.has(P) : !names.has(P) && !alts.some((a) => names.has(a)));
  try {
    await t.bot.waitForCommandTree(ok, timeoutMs);
    return true;
  } catch (e) {
    const tree = t.bot.commandTree;
    t.expect(false, `client command tree (tab completion) should ${present ? 'contain' : 'no longer contain'} /${P} within ${timeoutMs} ms (Player#updateCommands())`,
      { error: e.message, has: tree ? [P, ...alts].filter((n) => tree.has(n)) : null, updates: t.bot.commandTreeUpdates.length });
    return false;
  }
}

// ---------------------------------------------------------------- AI (fake Claude CLI) helpers

/** Invocations recorded by tools/fake-claude.mjs (one JSON object per line). */
function fakeCalls(t) {
  let text = '';
  try { text = fs.readFileSync(t.cfg.fakeClaudeLog, 'utf8'); } catch { return []; }
  return text.split('\n').filter(Boolean).map((l) => { try { return JSON.parse(l); } catch { return null; } }).filter(Boolean);
}

/** SKIP unless the server runs the fake CLI (never start a real, paid Claude job from the test suite). */
async function aiPreflight(t) {
  if (!t.cfg.fakeClaudeLog) {
    t.skip('needs --fake-claude <log> (server with ai.claude-path = tools/fake-claude.mjs, e.g. tools/integ.sh): never starts a real Claude job');
  }
  const st = await t.api.status();
  t.assert(fakeCalls(t).some((e) => e.kind === 'probe'), `the server does not seem to use the fake CLI: no probe recorded in ${t.cfg.fakeClaudeLog} (refusing to start AI jobs)`);
  t.assert(st.ai && st.ai.available === true, 'with the fake CLI (auth status: loggedIn) the AI must be available', st.ai);
  return st.ai;
}

const JOB_DONE = new Set(['SUCCEEDED', 'FAILED', 'STOPPED']);

/** Poll GET /api/ai until the job is finished; returns the AiJob. */
async function waitJob(t, jobId, timeoutMs = 60000) {
  const t0 = Date.now();
  let last = null;
  while (Date.now() - t0 < timeoutMs) {
    last = (await t.api.aiJobs()).find((j) => j.id === jobId) || last;
    if (last && JOB_DONE.has(last.state)) {
      t.step(`job ${jobId} ${last.state} after ${Date.now() - t0} ms: ${String(last.result ?? '').slice(0, 120)}`);
      return last;
    }
    await sleep(300);
  }
  t.assert(false, `job ${jobId} not finished within ${timeoutMs} ms`, last && { state: last.state, progress: (last.progress || []).slice(-5) });
  return last;
}

/** Is a process alive? (signal 0) */
function alive(pid) {
  if (!pid) return false;
  try { process.kill(pid, 0); return true; } catch (e) { return e.code === 'EPERM'; }
}

/** Parse "E2E-XXX <v> key=value ..." keeping string values. */
function parseKv(line) {
  const parts = line.trim().split(/\s+/);
  const out = { _v: parts[1] };
  for (const p of parts.slice(2)) {
    const i = p.indexOf('=');
    if (i > 0) out[p.slice(0, i)] = p.slice(i + 1);
  }
  return out;
}

function hot(t, id, n, P, inject) {
  return t.fixture('hot', { id, vars: { V: t.ver(n), P }, inject });
}

/**
 * Send a misbehaving command, then a probe command; returns how long until the probe answered
 * (= how long the main thread was blocked) plus every line received meanwhile.
 */
async function blockedFor(t, badCmd, probeCmd, probeRe, { windowMs = 20000 } = {}) {
  const t0 = Date.now();
  t.bot.say(badCmd); // both packets leave immediately; the server handles them in order on the main thread
  const r = await t.bot.cmd(probeCmd, { until: probeRe, windowMs, settleMs: 300 });
  const lines = t.bot.messagesSince(t0).filter((m) => m.position !== 'game_info').map((m) => m.text);
  const elapsed = r.firstMatchMs; // ms between sending the probe (≈ t0) and its answer
  t.step(`${badCmd} then ${probeCmd}: probe answered after ${elapsed ?? 'never'} ms; lines: ${JSON.stringify(lines).slice(0, 300)}`);
  return { elapsed, lines, stall: t.stallMs(t0) };
}

export const SCENARIOS = [
  // -------------------------------------------------------------------------------------------
  {
    name: 'api-contract',
    title: 'HTTP API shapes (status, modules, 404s, command, players, ai, ideas, dashboard)',
    timeoutMs: 60000,
    async run(t) {
      const st = await t.api.status();
      t.assert(st && typeof st === 'object' && st.server, 'GET /api/status: missing "server"', st);
      t.assert(Array.isArray(st.server.tps) && st.server.tps.length === 3 && st.server.tps.every(Number.isFinite), 'server.tps must be [1m,5m,15m] numbers', st.server);
      t.assert(Number.isFinite(st.server.online) && Number.isFinite(st.server.max), 'server.online/max must be numbers', st.server);
      t.assert(Array.isArray(st.modules), 'status.modules must be an array', st);
      t.assert(Array.isArray(st.jobs), 'status.jobs must be an array', st);
      t.assert(Array.isArray(st.ideas), 'status.ideas must be an array', st);
      t.assert(st.ai && typeof st.ai.available === 'boolean', 'status.ai.available must be a boolean', st.ai);
      t.record('server', st.server);
      t.record('ai', st.ai);

      const mods = await t.api.modules();
      t.assert(Array.isArray(mods), 'GET /api/modules must return an array', mods);
      for (const m of mods) {
        t.assert(typeof m.id === 'string' && typeof m.state === 'string' && Number.isInteger(m.generation), 'ModuleInfo shape', m);
      }

      const missing = t.id('does-not-exist-404');
      const g = await t.api.raw('GET', `/api/modules/${missing}`);
      t.eq(g.status, 404, 'GET unknown module -> 404', g.body);
      t.assert(g.body && typeof g.body.error === 'string', '404 body must be {error}', g.body);
      const p = await t.api.raw('POST', `/api/modules/${missing}/reload`);
      t.eq(p.status, 404, 'POST reload of unknown module -> 404', p.body);

      const c = await t.api.command('list');
      t.assert(c && c.ok === true && Array.isArray(c.output), 'POST /api/command "list" -> {ok:true, output:[...]}', c);
      t.assert(c.output.join('\n').includes(t.bot.username), `"list" output should mention ${t.bot.username}`, c.output);
      const c2 = await t.api.command('/time query gametime');
      t.assert(c2.ok === true && /\d+/.test(c2.output.join(' ')), 'leading "/" accepted and output captured', c2);

      const players = await t.api.players();
      t.assert(Array.isArray(players), 'GET /api/players must be an array', players);
      const me = players.find((x) => x.name === t.bot.username);
      t.assert(me, `${t.bot.username} missing from /api/players`, players);
      for (const k of ['uuid', 'world', 'x', 'y', 'z', 'gameMode', 'health', 'op']) t.assert(k in me, `player.${k} missing`, me);
      t.eq(me.op, true, `${t.bot.username} should be op`, me);

      t.assert(Array.isArray(await t.api.aiJobs()), 'GET /api/ai must be an array');
      t.assert(Array.isArray(await t.api.ideas()), 'GET /api/ideas must be an array');
      // AI endpoints: never start a real job. Only when the AI is unavailable, check the 503 contract.
      if (st.ai && st.ai.available === false) {
        const ai = await t.api.raw('POST', `/api/ai/${t.id('ai-probe')}`, 'e2e: this must not start a job (AI unavailable)');
        t.expect(ai.status === 503 && typeof ai.body?.error === 'string', 'POST /api/ai/{id} while the AI is unavailable -> 503 {error}', { status: ai.status, body: ai.body });
        if (ai.status >= 200 && ai.status < 300) {
          t.warn('POST /api/ai/{id} started a job although ai.available=false: stopping it');
          await t.api.raw('POST', `/api/ai/${t.id('ai-probe')}/stop`).catch(() => {});
        }
        t.check(!t.ws.exists(t.id('ai-probe')), 'POST /api/ai/{id} (503) created a module directory anyway');
        if (t.ws.exists(t.id('ai-probe'))) await t.removeModule(t.id('ai-probe'));
      } else {
        t.step('AI available: POST /api/ai/{id} not exercised (it would start a real Claude job)');
      }
      const noRoute = await t.api.raw('GET', '/api/e2e-no-such-endpoint');
      t.expect(noRoute.status === 404 && typeof noRoute.body?.error === 'string', 'unknown /api endpoint -> 404 {error}', { status: noRoute.status, body: noRoute.body });
    },
  },

  // -------------------------------------------------------------------------------------------
  {
    name: 'api-validation',
    title: 'Invalid conversation requests (non-string text, non-UTF-8 body, negative since) are refused before any AI job',
    timeoutMs: 30000,
    async run(t) {
      // Invalid conversation requests are refused BEFORE any job starts (these must never reach the AI).
      const probe = t.id('conv-probe');
      const msgPath = `/api/conversations/${probe}/messages`;
      for (const body of ['{"text":123}', '{"text":true}', '{"text":["a"]}', '{"text":" "}', '{"text":"ok","model":5}']) {
        const r = await t.api.raw('POST', msgPath, body, { contentType: 'application/json' });
        t.expect(r.status === 400 && typeof r.body?.error === 'string', `POST .../messages ${body} -> 400 {error}`, { status: r.status, body: r.body });
      }
      // Latin-1 bytes without a declared charset (Windows PowerShell 5.1 default): 400, never a silently corrupted request
      const latin1 = Buffer.from('{"text":"Crée une fusée"}', 'latin1');
      const l1 = await t.api.raw('POST', msgPath, latin1, { contentType: 'application/json' });
      t.expect(l1.status === 400 && /UTF-8/.test(String(l1.body?.error)), 'non-UTF-8 body without charset -> 400 {error}', { status: l1.status, body: l1.body });
      const ev = await t.api.raw('GET', `/api/conversations/${probe}/events?since=-5`);
      t.expect(ev.status === 400, 'GET .../events?since=-5 -> 400', { status: ev.status, body: ev.body });
      t.check(!t.ws.exists(probe), 'a refused conversation request created a module directory anyway');
      if (t.ws.exists(probe)) {
        await t.api.raw('POST', `/api/ai/${probe}/stop`).catch(() => {});
        await t.removeModule(probe);
      }
    },
  },

  // -------------------------------------------------------------------------------------------
  {
    name: 'dashboard',
    title: 'Dashboard HTML served at GET / (self-contained) + full GET /api/status shape',
    timeoutMs: 60000,
    async run(t) {
      // --- GET / : the dashboard
      const dash = await t.api.raw('GET', '/', null, { accept: 'text/html' });
      t.eq(dash.status, 200, 'GET / (dashboard) status');
      const html = typeof dash.body === 'string' ? dash.body : '';
      t.expect(/^text\/html/i.test(dash.contentType || ''), 'GET / Content-Type should be text/html', dash.contentType);
      t.expect(/charset=utf-8/i.test(dash.contentType || '') || /<meta charset="?utf-8/i.test(html), 'dashboard should declare UTF-8', dash.contentType);
      t.assert(/^\s*<!doctype html>/i.test(html) && /<\/html>\s*$/i.test(html), 'GET / must return a complete HTML document', html.slice(0, 120));
      t.expect(/<html[^>]*\blang="fr"/i.test(html), 'dashboard should be French (<html lang="fr">)', (html.match(/<html[^>]*>/i) || [])[0]);
      t.expect(/<title>[^<]*\S[^<]*<\/title>/i.test(html), 'dashboard should have a <title>', (html.match(/<title>[^<]*<\/title>/i) || [])[0]);
      t.expect(/id="btn-new"/.test(html) && /\$\('btn-new'\)\.addEventListener\('click'/.test(html), 'the "Nouvelle conversation" button must have a click handler');
      t.expect(html.includes('/api/status'), 'dashboard should poll /api/status');
      // No external resources: it must work offline on the conference network.
      const external = [...html.matchAll(/<(script|link|img|iframe|audio|video|source)\b[^>]*\b(?:src|href)\s*=\s*["']((?:https?:)?\/\/[^"']+)/gi)].map((m) => `${m[1]} ${m[2]}`);
      const cssImports = [...html.matchAll(/@import\s+(?:url\()?["']?((?:https?:)?\/\/[^"')\s]+)/gi)].map((m) => m[1]);
      t.expect(external.length === 0 && cssImports.length === 0, 'dashboard must not load external resources (offline)', [...external, ...cssImports]);
      // every inline <script> must at least parse (a syntax error = blank dashboard on the projector)
      const scripts = [...html.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script>/gi)].filter((m) => !/\bsrc\s*=/.test(m[1]) && !/type\s*=\s*["'](?!module|text\/javascript)/i.test(m[1]));
      t.expect(scripts.length > 0, 'dashboard should contain its (inline) script');
      for (const [i, m] of scripts.entries()) {
        try {
          if (/type\s*=\s*["']module/i.test(m[1])) new vm.SourceTextModule(m[2]);
          else new vm.Script(m[2], { filename: `dashboard-inline-${i}.js` });
        } catch (e) {
          if (e instanceof SyntaxError) t.expect(false, `dashboard inline script #${i} has a syntax error`, e.message);
          else if (!/SourceTextModule/.test(String(e))) t.warn(`dashboard script #${i} not checked: ${e.message}`);
        }
      }
      t.record('dashboard', { bytes: html.length, contentType: dash.contentType, ms: dash.ms, inlineScripts: scripts.length });
      const idx = await t.api.raw('GET', '/index.html', null, { accept: 'text/html' });
      t.check(idx.status === 200 && idx.body === html, `GET /index.html should serve the same dashboard (got HTTP ${idx.status})`);
      const missing = await t.api.raw('GET', '/e2e-no-such-file.js');
      t.expect(missing.status === 404, 'unknown static file -> 404', { status: missing.status });
      const trav = await t.api.raw('GET', '/..%2f..%2fserver.properties');
      t.expect(trav.status >= 400 && !/rcon\.password/.test(String(trav.body)), 'path traversal must be refused', { status: trav.status, body: String(trav.body).slice(0, 100) });

      // --- GET /api/status with one known module, so ModuleInfo can be checked field by field
      const id = await t.useModule(t.id('dash'));
      await t.deployOk(id, hot(t, id, 1, 'e2edash'));
      const raw = await t.api.raw('GET', '/api/status');
      t.eq(raw.status, 200, 'GET /api/status');
      t.expect(/application\/json/i.test(raw.contentType || ''), 'GET /api/status Content-Type should be application/json', raw.contentType);
      const st = raw.body;
      t.assert(st && typeof st === 'object' && !Array.isArray(st), '/api/status must be a JSON object', st);
      for (const k of ['server', 'modules', 'jobs', 'ideas', 'ai']) t.expect(k in st, `/api/status.${k} missing`, Object.keys(st));

      const srv = st.server || {};
      const isNum = (x) => typeof x === 'number' && Number.isFinite(x);
      const isInt = (x) => Number.isInteger(x);
      const isStr = (x) => typeof x === 'string';
      const strOrNull = (x) => x === null || isStr(x);
      t.expect(isStr(srv.name) && srv.name.length > 0, 'server.name: non-empty string', srv.name);
      t.expect(isStr(srv.version) && srv.version.length > 0, 'server.version: non-empty string', srv.version);
      t.expect(isInt(srv.online) && srv.online >= 1, 'server.online: integer >= 1 (the bot is online)', srv.online);
      t.expect(isInt(srv.max) && srv.max >= srv.online, 'server.max: integer >= online', srv.max);
      t.expect(Array.isArray(srv.tps) && srv.tps.length === 3 && srv.tps.every((x) => isNum(x) && x >= 0 && x <= 21), 'server.tps: [1m,5m,15m] numbers in 0..21', srv.tps);
      t.expect(isNum(srv.uptimeMs) && srv.uptimeMs > 0, 'server.uptimeMs: positive number', srv.uptimeMs);

      t.expect(Array.isArray(st.modules), 'status.modules: array');
      const mi = (st.modules || []).find((m) => m.id === id);
      t.expect(mi, `status.modules should contain ${id}`, (st.modules || []).map((m) => m.id));
      const STATES = ['DISCOVERED', 'COMPILING', 'ENABLED', 'DISABLED', 'FAILED', 'TRIPPED'];
      const moduleShape = {
        id: isStr, state: (x) => STATES.includes(x), generation: isInt, description: isStr,
        lastReloadAt: isNum, lastAttemptAt: isNum, lastError: strOrNull, diagnostics: Array.isArray,
        runtimeErrorCount: isInt, recentErrors: (x) => Array.isArray(x) && x.every(isStr),
        commands: (x) => Array.isArray(x) && x.every(isStr), listeners: isInt, tasks: isInt,
        rolledBack: (x) => typeof x === 'boolean', msPerTick: isNum,
      };
      for (const m of st.modules || []) {
        const bad = Object.entries(moduleShape).filter(([k, ok]) => !(k in m) || !ok(m[k])).map(([k]) => `${k}=${JSON.stringify(m[k])}`);
        t.expect(bad.length === 0, `ModuleInfo ${m.id}: wrong/missing fields`, bad);
        for (const d of m.diagnostics || []) {
          t.expect(['ERROR', 'WARNING', 'NOTE'].includes(d.kind) && isStr(d.file) && isInt(d.line) && isInt(d.column) && isStr(d.message) && isStr(d.sourceLine), `Diagnostic shape (${m.id})`, d);
        }
      }
      if (mi) {
        t.expect(mi.state === 'ENABLED' && mi.generation >= 1 && mi.lastReloadAt > 0 && mi.lastError === null, `${id}: ENABLED, generation >= 1, lastReloadAt set, lastError null`, t.infoSummary(mi));
        t.expect(mi.commands.includes('e2edash') && mi.listeners === 1, `${id}: commands [e2edash], listeners 1`, { commands: mi.commands, listeners: mi.listeners });
        t.expect(mi.description === 'e2e fixture (hot reload of a command + a listener)', `${id}: description from module.yml`, mi.description);
      }

      const JOB_STATES = ['QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'STOPPED']; // ai/AiJobState
      t.expect(Array.isArray(st.jobs), 'status.jobs: array');
      const jobShape = {
        id: isStr, moduleId: isStr, prompt: isStr, requestedBy: isStr, state: isStr, startedAt: isNum, endedAt: isNum,
        progress: (x) => Array.isArray(x) && x.every(isStr), result: strOrNull, sessionId: strOrNull, costUsd: isNum,
      };
      for (const j of st.jobs || []) {
        const bad = Object.entries(jobShape).filter(([k, ok]) => !(k in j) || !ok(j[k])).map(([k]) => `${k}=${JSON.stringify(j[k])}`);
        t.expect(bad.length === 0, `AiJob ${j.id}: wrong/missing fields`, bad);
        t.check(JOB_STATES.includes(j.state), `AiJob ${j.id}: unexpected state ${j.state}`);
      }
      t.expect(Array.isArray(st.ideas), 'status.ideas: array');
      const ideaShape = { id: isInt, author: isStr, text: isStr, at: isNum, status: (x) => ['NEW', 'ACCEPTED', 'DISMISSED'].includes(x), moduleId: strOrNull };
      for (const i of st.ideas || []) {
        const bad = Object.entries(ideaShape).filter(([k, ok]) => !(k in i) || !ok(i[k])).map(([k]) => `${k}=${JSON.stringify(i[k])}`);
        t.expect(bad.length === 0, `Idea ${i.id}: wrong/missing fields`, bad);
      }
      const ai = st.ai || {};
      t.expect(typeof ai.available === 'boolean', 'status.ai.available: boolean', ai);
      t.expect(isStr(ai.reason) || ai.reason === null, 'status.ai.reason: string', ai);
      if (ai.available === false) t.expect(isStr(ai.reason) && ai.reason.trim().length > 0, 'status.ai.reason must explain why the AI is unavailable', ai);
      t.record('status', { server: srv, ai, modules: (st.modules || []).length, jobs: (st.jobs || []).length, ideas: (st.ideas || []).length, ms: raw.ms });
      // status must be cheap: the dashboard polls it every second
      t.check(raw.ms < 500, `GET /api/status took ${raw.ms} ms (the dashboard polls it every second)`);

      // --- token: when the runner was given one, a request without it must be refused
      if (t.cfg.token) {
        const anon = await fetch(t.api.base + '/api/status').then((r) => r.status, (e) => e.message);
        t.expect(anon === 401, 'api.token set: /api/status without X-Vibe-Token must answer 401', anon);
      }
    },
  },

  // -------------------------------------------------------------------------------------------
  {
    name: 'hot-reload',
    title: 'Hot reload of a command + a listener (multi-file module) without duplicates',
    timeoutMs: 90000,
    async run(t) {
      const id = await t.useModule(t.id('hot'));
      const P = 'e2ehot';
      let r = await t.deployOk(id, hot(t, id, 1, P));
      let gen = r.generation;
      t.assert(gen >= 1, 'generation >= 1 after first load', r);
      await expectHot(t, P, t.ver(1));
      await expectTree(t, P, true);
      let info = await t.info(id);
      t.assert(info.commands.includes(P), 'ModuleInfo.commands should list the command', info);
      t.eq(info.listeners, 1, 'ModuleInfo.listeners after first load', info);

      // An explicit reload must not be followed by a redundant watcher reload of the same files
      // (that would double every "chargé à chaud" announcement and re-run enable()).
      const g0 = (await t.info(id)).generation;
      const write = Date.now();
      r = await t.deployOk(id, hot(t, id, 2, P));
      await sleep(Math.max(0, 3200 - (Date.now() - write)));
      info = await t.info(id);
      t.check(info.generation === r.generation, `explicit reload was followed by a redundant watcher reload (generation ${r.generation} -> ${info.generation})`);
      t.record('redundantWatcherReload', info.generation !== r.generation);
      t.assert(r.generation > g0, 'generation should increase', r);
      gen = info.generation;
      await expectHot(t, P, t.ver(2));

      for (const n of [3, 4, 5]) {
        const since = Date.now();
        r = await t.deployOk(id, hot(t, id, n, P));
        t.assert(r.generation > gen, `generation should increase (was ${gen})`, r);
        gen = r.generation;
        await expectHot(t, P, t.ver(n));
        info = await t.info(id);
        t.eq(info.listeners, 1, `listeners after reload #${n - 1} (duplicates?)`, info);
        t.eq(info.commands.filter((c) => c === P).length, 1, 'command listed once', info.commands);
        if (n === 3) {
          // Notifier: "✔ <id> v<gen> chargé à chaud" to everyone (notify.broadcast-reloads)
          const ann = await t.bot.waitForMessage(new RegExp(escapeRe(id)), 4000, { since }).catch(() => null);
          t.check(ann, `no reload announcement mentioning ${id} received in chat (notify.broadcast-reloads)`);
          if (ann) t.record('announcement', ann.text);
        }
      }
      t.record('finalGeneration', gen);
    },
  },

  // -------------------------------------------------------------------------------------------
  {
    name: 'vibe-files-and-logs',
    title: '.vibe/status.json, module logs endpoint (logger output)',
    timeoutMs: 60000,
    async run(t) {
      const id = await t.useModule(t.id('files'));
      const P = 'e2efiles';
      const r = await t.deployOk(id, hot(t, id, 1, P));
      await sleep(500);
      const raw = t.ws.readVibe(id, 'status.json');
      t.assert(raw, '.vibe/status.json should exist');
      let st;
      try { st = JSON.parse(raw); } catch (e) { t.assert(false, '.vibe/status.json is not valid JSON', raw.slice(0, 300)); }
      t.eq(st.id, id, 'status.json id');
      t.eq(st.state, 'ENABLED', 'status.json state', st);
      t.assert(st.generation >= r.generation, 'status.json generation', { statusJson: st.generation, reload: r.generation });
      t.check(t.ws.readVibe(id, 'build.log') !== null, '.vibe/build.log not written after a successful compile');
      const lines = await t.api.logs(id, 200);
      t.assert(Array.isArray(lines), 'logs must be {lines:[...]}', lines);
      t.assert(lines.some((l) => l.includes('e2e hot enabled ' + t.ver(1))), 'ctx.logger() output should be visible via GET /api/modules/{id}/logs', lines.slice(-10));
      const limited = await t.api.logs(id, 1);
      t.assert(limited.length <= 1, 'logs?limit=1 must return at most 1 line', limited);
      for (const bad of ['-1', '0', 'abc']) {
        const r = await t.api.raw('GET', `/api/modules/${id}/logs?limit=${bad}`);
        t.expect(r.status === 400, `logs?limit=${bad} -> 400`, { status: r.status, body: r.body });
      }
      const huge = await t.api.raw('GET', `/api/modules/${id}/logs?limit=99999999999`);
      t.expect(huge.status === 200 && Array.isArray(huge.body?.lines), 'logs?limit=99999999999 -> 200 (capped)', { status: huge.status });
    },
  },

  // -------------------------------------------------------------------------------------------
  {
    name: 'watcher-auto-reload',
    title: 'File watcher: new dir loads, config.yml / .java edits reload, half-written file debounced',
    timeoutMs: 120000,
    async run(t) {
      const id = await t.useModule(t.id('watch'));
      const files = (n, msg) => t.fixture('watch', { id, vars: { V: t.ver(n) }, extra: { 'config.yml': `msg: ${msg}\n` } });
      let r = await t.deploy(id, files(1, 'alpha'), { via: 'watcher' });
      t.assert(r.state === 'ENABLED', 'watcher should discover and load a new module directory (no API call)', r);
      await t.expectCmd('/e2ewatch', new RegExp(`^E2E-WATCH ${escapeRe(t.ver(1))} msg=alpha$`));
      let gen = r.generation;

      r = await t.deploy(id, { 'config.yml': 'msg: beta\n' }, { via: 'watcher', clean: false });
      t.assert(r.state === 'ENABLED' && r.generation > gen, 'editing config.yml should reload the module', r);
      gen = r.generation;
      await t.expectCmd('/e2ewatch', new RegExp(`^E2E-WATCH ${escapeRe(t.ver(1))} msg=beta$`));

      r = await t.deploy(id, files(2, 'beta'), { via: 'watcher' });
      t.assert(r.state === 'ENABLED' && r.generation > gen, 'editing the .java should reload the module', r);
      gen = r.generation;
      await t.expectCmd('/e2ewatch', new RegExp(`^E2E-WATCH ${escapeRe(t.ver(2))} msg=beta$`));

      // Half-written file followed quickly by the fixed one: debounce should only compile the final state.
      const since = Date.now();
      const broken = files(3, 'gamma');
      broken['WatchModule.java'] = broken['WatchModule.java'].replace('ctx.command(', 'ctx.command(( /* half written');
      t.ws.writeModule(id, broken);
      await sleep(300);
      t.ws.writeModule(id, files(3, 'gamma'));
      let info = await t.waitInfo(id, (i) => i && i.lastAttemptAt >= since && i.state === 'ENABLED' && i.generation > gen && !i.rolledBack,
        { timeoutMs: 30000, what: 'reload of the fixed file' });
      await t.expectCmd('/e2ewatch', new RegExp(`^E2E-WATCH ${escapeRe(t.ver(3))} msg=gamma$`));
      t.check(info.generation === gen + 1, `half-written file: ${info.generation - gen} reloads instead of 1 (debounce-ms not honoured?)`);
      const failNotes = t.bot.messagesSince(since, new RegExp(`${escapeRe(id)}.*(compil|erreur|error|échec|fail)`, 'i'));
      t.check(failNotes.length === 0, `half-written file was compiled before being fixed (debounce): ${JSON.stringify(failNotes.map((m) => m.text))}`);
      gen = info.generation;

      // .java files in sub-directories belong to the module too (recursive), and deleting a file reloads
      const deep = (body) => `package vibe.e2e_watch;\n\nfinal class Deep {\n    private Deep() {\n    }\n\n    static int value() {\n        ${body}\n    }\n}\n`;
      let r2 = await t.deploy(id, { 'util/Deep.java': deep('return 42;') }, { via: 'watcher', clean: false });
      t.expect(r2.state === 'ENABLED' && r2.generation > gen && !r2.rolledBack, 'adding util/Deep.java (sub-directory) should reload the module', r2);
      gen = r2.generation ?? gen;
      r2 = await t.deploy(id, { 'util/Deep.java': deep('return "not an int"; // E2E-DEEP-ERROR') }, { via: 'watcher', clean: false });
      const dd = (r2.diagnostics || []).find((d) => d.kind === 'ERROR');
      t.expect(r2.rolledBack === true && r2.state === 'ENABLED' && dd && String(dd.file).replace(/\\/g, '/') === 'util/Deep.java' && dd.line === 8,
        'a compile error in util/Deep.java must be reported as util/Deep.java:8 (path relative to the module dir)', { rolledBack: r2.rolledBack, state: r2.state, diag: dd });
      const t0 = Date.now();
      t.ws.removeFile(id, 'util/Deep.java');
      info = await t.waitInfo(id, (i) => i && i.lastAttemptAt >= t0 && !['COMPILING', 'DISCOVERED'].includes(i.state), { timeoutMs: 30000, what: 'reload after deleting util/Deep.java' }).catch((e) => e);
      t.expect(!(info instanceof Error) && info.state === 'ENABLED' && !info.rolledBack && info.generation > gen, 'deleting the broken file should reload the module successfully',
        info instanceof Error ? info.message : t.infoSummary(info));
      await t.expectCmd('/e2ewatch', new RegExp(`^E2E-WATCH ${escapeRe(t.ver(3))} msg=gamma$`));
    },
  },

  // -------------------------------------------------------------------------------------------
  {
    name: 'compile-error',
    title: 'Compile errors keep the old version running (diagnostics, build.log)',
    timeoutMs: 90000,
    async run(t) {
      const id = await t.useModule(t.id('comp'));
      const P = 'e2ecomp';
      const ok1 = await t.deployOk(id, hot(t, id, 1, P));
      await expectHot(t, P, t.ver(1));

      for (const [n, variant, label] of [[2, COMPILE_ERROR, 'type error'], [3, SYNTAX_ERROR, 'syntax error']]) {
        const files = hot(t, id, n, P, variant.inject);
        const markerLine = files[variant.file].split('\n').findIndex((l) => l.includes(variant.marker)) + 1;
        const genBefore = (await t.info(id)).generation;
        const r = await t.deploy(id, files);
        t.eq(r.ok, false, `${label}: reload must fail`, r);
        t.eq(r.state, 'ENABLED', `${label}: previous version must keep running`, r);
        t.eq(r.generation, genBefore, `${label}: generation must not change`, r);
        t.assert(/compil/i.test(r.error || ''), `${label}: error should say compilation failed`, r.error);
        const errs = (r.diagnostics || []).filter((d) => d.kind === 'ERROR');
        t.assert(errs.length >= 1, `${label}: at least one ERROR diagnostic`, r.diagnostics);
        const d = errs[0];
        t.assert(String(d.file).replace(/\\/g, '/').endsWith(variant.file), `${label}: diagnostic file should be ${variant.file}`, d);
        t.assert(Math.abs(d.line - markerLine) <= 1, `${label}: diagnostic line ${d.line}, expected ~${markerLine}`, d);
        t.assert(d.column >= 1, `${label}: diagnostic column should be >= 1`, d);
        const info = await t.info(id);
        t.eq(info.rolledBack, true, `${label}: ModuleInfo.rolledBack (previous generation still running)`, t.infoSummary(info));
        t.assert(info.lastError, `${label}: ModuleInfo.lastError should be set`, t.infoSummary(info));
        const log = t.ws.readVibe(id, 'build.log');
        t.assert(log && log.includes(`${variant.file}:${d.line}`), `${label}: .vibe/build.log should contain "${variant.file}:${d.line}"`, log && log.slice(0, 400));
        await expectHot(t, P, t.ver(1));
      }

      const fixed = await t.deployOk(id, hot(t, id, 4, P));
      t.assert(fixed.generation > ok1.generation, 'fixed version loads as a new generation', fixed);
      const info = await t.info(id);
      t.eq(info.rolledBack, false, 'rolledBack cleared after a successful reload', t.infoSummary(info));
      t.eq(info.lastError ?? null, null, 'lastError cleared after a successful reload', t.infoSummary(info));
      await expectHot(t, P, t.ver(4));
      const notes = t.bot.messagesSince(t.startedAt, new RegExp(`${escapeRe(id)}.*(compil|erreur|error|échec|fail)`, 'i'));
      t.check(notes.length > 0, `ops got no chat notification about the failed compilations of ${id} (Notifier FAILED/ROLLED_BACK)`);
      t.record('opsNotifications', notes.slice(0, 3).map((m) => m.text));

      // The usual AI flow: a NEW module whose very first version does not compile, then gets fixed.
      const fid = await t.useModule(t.id('comp-fresh'));
      const FP = 'e2ecompfresh';
      const bad = await t.deploy(fid, hot(t, fid, 1, FP, COMPILE_ERROR.inject));
      t.expect(bad.ok === false && bad.state === 'FAILED' && bad.generation === 0 && bad.rolledBack === false,
        'new module with a compile error: FAILED, generation 0, not rolled back', { ok: bad.ok, state: bad.state, generation: bad.generation, rolledBack: bad.rolledBack, error: bad.error });
      t.expect((bad.diagnostics || []).some((d) => d.kind === 'ERROR' && String(d.file).endsWith(COMPILE_ERROR.file)), 'new module with a compile error: ERROR diagnostic', bad.diagnostics);
      t.expect(/HotModule\.java:\d+/.test(t.ws.readVibe(fid, 'build.log') || ''), 'new module with a compile error: .vibe/build.log written', (t.ws.readVibe(fid, 'build.log') || '').slice(0, 200));
      await expectGone(t, FP);
      // fixed by editing the file only (no API call): the watcher must pick it up although the module is FAILED
      const good = await t.deploy(fid, hot(t, fid, 2, FP), { via: 'watcher' });
      t.expect(good.state === 'ENABLED' && good.generation >= 1 && !good.rolledBack, 'FAILED module fixed on disk must be loaded by the watcher', { state: good.state, generation: good.generation, error: good.error });
      if (good.state === 'ENABLED') await expectHot(t, FP, t.ver(2));
    },
  },

  // -------------------------------------------------------------------------------------------
  {
    name: 'module-class-errors',
    title: 'Zero / two module classes, no no-arg constructor, ctx.on(abstract event) -> rollback; abstract base OK',
    timeoutMs: 120000,
    async run(t) {
      const id = await t.useModule(t.id('cls'));
      const P = 'e2ecls';
      const pkg = packageFor(id);
      await t.deployOk(id, hot(t, id, 1, P));
      let good = 1; let n = 1;
      const table = {};
      for (const v of MODULE_CLASS_VARIANTS) {
        n++;
        const genBefore = (await t.info(id)).generation;
        const r = await t.deploy(id, v.apply(hot(t, id, n, P), pkg));
        table[v.name] = { ok: r.ok, state: r.state, generation: r.generation, rolledBack: r.rolledBack, error: r.error };
        if (v.expect === 'fail') {
          t.expect(r.ok === false && r.state === 'ENABLED' && r.generation === genBefore && r.rolledBack === true,
            `${v.name}: the load must fail and v${good} must be restored (ok=false, ENABLED, same generation, rolledBack)`, table[v.name]);
          t.check(!!r.error && v.errorRe.test(r.error), `${v.name}: error message does not explain the problem: ${JSON.stringify(r.error)}`);
        } else {
          t.expect(r.ok === true && r.state === 'ENABLED' && r.generation > genBefore, `${v.name}: must load`, table[v.name]);
          if (r.ok) good = n;
        }
        // whatever happened, exactly one version answers, the expected one
        await expectHot(t, P, t.ver(good));
      }
      t.record('variants', table);
      const sec = await t.cmd('/e2esecond', { windowMs: 1200 });
      t.expect(!sec.lines.some((l) => /E2E-SECOND/.test(l)), '/e2esecond of the rejected 2nd module class must not be registered', sec.lines);

      // brand new module without any module class: nothing to restore -> FAILED, generation 0
      const fid = await t.useModule(t.id('cls-fresh'));
      const noClass = MODULE_CLASS_VARIANTS.find((v) => v.name === 'no module class');
      const fr = await t.deploy(fid, noClass.apply(hot(t, fid, 1, 'e2eclsfresh'), packageFor(fid)));
      t.expect(fr.ok === false && fr.state === 'FAILED' && fr.generation === 0 && fr.rolledBack === false,
        'a new module without a VibeModule class must end FAILED (generation 0, not rolled back)', { ok: fr.ok, state: fr.state, generation: fr.generation, rolledBack: fr.rolledBack, error: fr.error });
    },
  },

  // -------------------------------------------------------------------------------------------
  {
    name: 'context-cleanup',
    title: 'ctx.recipe/entity/track/onCleanup/listen undone on reload & disable (disable() first, reverse order); stale lambdas killed',
    timeoutMs: 90000,
    async run(t) {
      const id = await t.useModule(t.id('ctx'));
      const P = 'e2ectx';
      const v = (n) => t.ver(n);
      const files = (n) => t.fixture('ctxres', { id, vars: { V: v(n), P, MODE: 'full', TARGET: id, BOT: t.bot.username } });
      const re = /^E2E-CTXRES /;
      const stat = async (cmd) => {
        const r = await t.cmd(cmd, { until: re, windowMs: 6000, settleMs: 600 });
        const line = r.lines.find((l) => re.test(l));
        t.assert(line, `${cmd}: no answer`, r.lines);
        return { kv: parseKv(line), listen: t.matching(r, /^E2E-CTXRES-LISTEN /), lines: r.lines };
      };

      await t.deployOk(id, files(1));
      let s = await stat('/' + P);
      t.expect(s.kv.recipe === 'present', 'v1: ctx.recipe() registered', s.kv);
      t.expect(s.kv[`entity.${v(1)}`] === 'alive', 'v1: ctx.entity() entity alive (spawned next to the bot)', s.kv);
      t.expect(s.listen.length === 1 && s.listen[0] === `E2E-CTXRES-LISTEN ${v(1)}`, 'v1: ctx.listen() listener fires exactly once', s.lines);
      t.expect(s.kv.journal === `enable:${v(1)}`, 'v1 journal', s.kv.journal);

      await t.deployOk(id, files(2));
      s = await stat('/' + P);
      const j2 = [`enable:${v(1)}`, `disable:${v(1)}`, `onCleanup:${v(1)}`, `track:res-${v(1)}`, `enable:${v(2)}`].join(',');
      t.expect(s.kv.journal === j2, 'reload order: old disable(), then its cleanups in REVERSE registration order (onCleanup before track), then the new enable()', { got: s.kv.journal, expected: j2 });
      t.expect(s.kv.recipe === 'present', 'v2: recipe registered again (same key)', s.kv);
      t.expect(s.kv[`entity.${v(1)}`] === 'gone', 'v1 entity removed on reload', s.kv);
      t.expect(s.kv[`entity.${v(2)}`] === 'alive', 'v2 entity alive', s.kv);
      t.expect(s.listen.length === 1 && s.listen[0] === `E2E-CTXRES-LISTEN ${v(2)}`, 'v2: exactly one ctx.listen() listener (v1 unregistered)', s.lines);

      // kill switch: a lambda of v1 kept in ctx.shared() and called by v2 must not run any more
      const st = await stat(`/${P} stale`);
      t.expect(st.kv[`stale.${v(1)}`] === 'KILLED:ModuleKilledError', 'calling a lambda of the unloaded v1 must throw ModuleKilledError', st.kv);
      t.expect(st.kv.staleRuns === '0', 'the body of the v1 lambda must never run after unload', st.kv);

      // disable: everything of v2 undone (checked from a probe module that registers nothing)
      const probe = await t.useModule(t.id('ctx-probe'));
      await t.deployOk(probe, t.fixture('ctxres', { id: probe, vars: { V: t.ver(99), P: 'e2ectxprobe', MODE: 'probe', TARGET: id, BOT: t.bot.username } }));
      const d = await t.api.disable(id);
      t.assert(d && d.ok === true, 'disable', d);
      s = await stat('/e2ectxprobe');
      const j3 = `${j2},disable:${v(2)},onCleanup:${v(2)},track:res-${v(2)}`;
      t.expect(s.kv.journal === j3, 'disable order: disable(), then cleanups in reverse order', { got: s.kv.journal, expected: j3 });
      t.expect(s.kv.recipe === 'absent', 'recipe removed (Bukkit.removeRecipe) on disable', s.kv);
      t.expect(s.kv[`entity.${v(2)}`] === 'gone', 'v2 entity removed on disable', s.kv);
      const lis = await t.cmd('/' + P, { windowMs: 1500 });
      t.expect(!lis.lines.some((l) => /^E2E-CTXRES/.test(l)), 'no listener / command of the disabled module may answer', lis.lines);
      const st2 = await stat('/e2ectxprobe stale');
      t.expect(st2.kv[`stale.${v(2)}`] === 'KILLED:ModuleKilledError' && st2.kv.staleRuns === '0', 'lambdas of a disabled module are killed too', st2.kv);
    },
  },

  // -------------------------------------------------------------------------------------------
  {
    name: 'runtime-exception',
    title: 'Exceptions in command / listener / task are isolated and recorded',
    timeoutMs: 60000,
    async run(t) {
      const id = await t.useModule(t.id('exc'));
      const P = 'e2eexc';
      const v = t.ver(1);
      const r0 = await t.deployOk(id, t.fixture('guard', { id, vars: { V: v, P } }));
      const before = (await t.info(id)).runtimeErrorCount;

      let r = await t.cmd(`/${P}boom`, { windowMs: 1500 });
      t.assert(!r.lines.some((l) => INTERNAL_ERROR.test(l)), 'exception leaked to Bukkit ("internal error" shown to the player)', r.lines);
      t.check(r.lines.length > 0, `/${P}boom: the sender got no feedback message at all`);
      await t.expectCmd(`/${P}ok`, new RegExp(`^E2E-GUARD-OK ${escapeRe(v)}$`));

      r = await t.cmd(`/${P}npe`, { windowMs: 1500 });
      t.assert(!r.lines.some((l) => INTERNAL_ERROR.test(l) || /NOT-REACHED/.test(l)), 'NPE leaked / code continued', r.lines);

      // listener throws for this exact command line; the command itself must still run
      r = await t.expectCmd(`/${P}ok listener-boom`, new RegExp(`^E2E-GUARD-OK ${escapeRe(v)}$`));
      t.assert(!r.lines.some((l) => INTERNAL_ERROR.test(l)), 'listener exception leaked', r.lines);

      await t.expectCmd(`/${P}taskboom`, /^E2E-TASKBOOM-SCHEDULED /);
      const info = await t.waitInfo(id, (i) => i && i.runtimeErrorCount >= before + 4, { timeoutMs: 5000, what: '4 runtime errors recorded' });
      t.eq(info.state, 'ENABLED', 'module still enabled', t.infoSummary(info));
      t.eq(info.generation, r0.generation, 'no reload happened', t.infoSummary(info));
      const recent = (info.recentErrors || []).join('\n');
      for (const marker of ['E2E-BOOM', 'NullPointerException', 'E2E-LISTENER-BOOM', 'E2E-TASK-BOOM']) {
        t.assert(recent.includes(marker), `recentErrors should mention ${marker}`, info.recentErrors);
      }
      const errorsLog = t.ws.readVibe(id, 'errors.log');
      t.assert(errorsLog && errorsLog.includes('E2E-BOOM'), '.vibe/errors.log should contain the error', errorsLog && errorsLog.slice(0, 300));
      t.check(/GuardModule\.java:\d+/.test(recent) || /GuardModule\.java:\d+/.test(errorsLog || ''), 'errors do not point at the module source line (GuardModule.java:N)');
      const logs = await t.api.logs(id, 200);
      t.check(logs.some((l) => l.includes('E2E-BOOM')), 'runtime errors are not visible in GET /api/modules/{id}/logs');
      await t.expectCmd(`/${P}ok`, new RegExp(`^E2E-GUARD-OK ${escapeRe(v)}$`));
    },
  },

  // -------------------------------------------------------------------------------------------
  {
    name: 'infinite-loop-command',
    title: 'Infinite loops in a command (empty, catch-Throwable, with calls) are interrupted',
    timeoutMs: 90000,
    async run(t) {
      const id = await t.useModule(t.id('loop'));
      const P = 'e2eloop';
      const v = t.ver(1);
      await t.deployOk(id, t.fixture('guard', { id, vars: { V: v, P } }));
      const okRe = new RegExp(`^E2E-GUARD-OK ${escapeRe(v)}$`);
      const before = (await t.info(id)).runtimeErrorCount;
      const timings = {};
      for (const kind of ['loop', 'loopcatch', 'loopcall']) {
        const res = await blockedFor(t, `/${P}${kind}`, `/${P}ok`, okRe);
        timings[kind] = { probeMs: res.elapsed, stallMs: res.stall };
        t.assert(res.elapsed != null, `/${P}${kind}: the server never answered the next command (main thread stuck?)`, res.lines);
        t.assert(res.lines.some((l) => l.startsWith(`E2E-${kind.toUpperCase()}-START`)), `/${P}${kind} did not start`, res.lines);
        t.assert(!res.lines.some((l) => /NOT-REACHED/.test(l)), `/${P}${kind}: code after the loop ran?!`, res.lines);
        t.assert(!res.lines.some((l) => INTERNAL_ERROR.test(l)), `/${P}${kind}: error leaked to Bukkit`, res.lines);
        t.assert(res.elapsed < HANDLER_BUDGET_MS + 2750, `/${P}${kind}: main thread blocked ${res.elapsed} ms (budget ${HANDLER_BUDGET_MS} ms)`);
        t.assert(res.stall < HANDLER_BUDGET_MS + 3000, `/${P}${kind}: client saw a ${res.stall} ms tick stall`);
      }
      t.record('timings', timings);
      const info = await t.waitInfo(id, (i) => i && i.runtimeErrorCount >= before + 3, { timeoutMs: 5000, what: '3 timeouts recorded as errors' });
      t.eq(info.state, 'ENABLED', 'module still enabled after 3 timeouts', t.infoSummary(info));
      t.check((info.recentErrors || []).some((e) => /timeout|budget|ModuleTimeoutError|took|ms/i.test(e)), `recentErrors do not look like timeouts: ${JSON.stringify(info.recentErrors)}`);
    },
  },

  // -------------------------------------------------------------------------------------------
  {
    name: 'deep-recursion',
    title: 'Infinite recursion (StackOverflowError) is caught',
    timeoutMs: 60000,
    async run(t) {
      const id = await t.useModule(t.id('recur'));
      const P = 'e2erec';
      const v = t.ver(1);
      await t.deployOk(id, t.fixture('guard', { id, vars: { V: v, P } }));
      const before = (await t.info(id)).runtimeErrorCount;
      const res = await blockedFor(t, `/${P}recurse`, `/${P}ok`, new RegExp(`^E2E-GUARD-OK ${escapeRe(v)}$`));
      t.assert(res.elapsed != null, 'server did not answer after the recursion', res.lines);
      t.assert(res.lines.some((l) => l.startsWith('E2E-RECURSE-START')), 'recursion command did not start', res.lines);
      t.assert(!res.lines.some((l) => /NOT-REACHED|internal error/i.test(l)), 'recursion not contained', res.lines);
      const info = await t.waitInfo(id, (i) => i && i.runtimeErrorCount >= before + 1, { timeoutMs: 5000, what: 'error recorded' });
      t.assert((info.recentErrors || []).some((e) => /StackOverflow/i.test(e)), 'recentErrors should mention StackOverflowError', info.recentErrors);
      t.eq(info.state, 'ENABLED', 'module still enabled', t.infoSummary(info));
      // and once more: the module still works after a stack overflow
      await blockedFor(t, `/${P}recurse`, `/${P}ok`, new RegExp(`^E2E-GUARD-OK ${escapeRe(v)}$`));
      await t.expectCmd(`/${P}ok`, new RegExp(`^E2E-GUARD-OK ${escapeRe(v)}$`));
    },
  },

  // -------------------------------------------------------------------------------------------
  {
    name: 'thread-sleep',
    title: 'Thread.sleep on the main thread throws instead of freezing the server',
    timeoutMs: 90000,
    async run(t) {
      const id = await t.useModule(t.id('sleep'));
      const P = 'e2esleep';
      const v = t.ver(1);
      await t.deployOk(id, t.fixture('guard', { id, vars: { V: v, P } }));
      const okRe = new RegExp(`^E2E-GUARD-OK ${escapeRe(v)}$`);
      const timings = {};
      for (const kind of ['sleep', 'sleepd']) {
        const res = await blockedFor(t, `/${P}${kind}`, `/${P}ok`, okRe);
        timings[kind] = { probeMs: res.elapsed, stallMs: res.stall };
        t.assert(res.elapsed != null, `/${P}${kind}: no answer`, res.lines);
        t.assert(res.lines.some((l) => l.startsWith(`E2E-${kind.toUpperCase()}-START`)), `/${P}${kind} did not start`, res.lines);
        t.assert(!res.lines.some((l) => /NOT-REACHED/.test(l)), `/${P}${kind}: the sleep returned normally (it slept on the main thread)`, res.lines);
        t.assert(res.elapsed < 2500, `/${P}${kind}: main thread blocked ${res.elapsed} ms (Thread.sleep(10 s) not redirected?)`);
      }
      t.record('timings', timings);
      const info = await t.info(id);
      t.check((info.recentErrors || []).some((e) => /sleep/i.test(e)), `recentErrors should explain that Thread.sleep is not allowed on the main thread: ${JSON.stringify(info.recentErrors)}`);
      t.eq(info.state, 'ENABLED', 'module still enabled', t.infoSummary(info));
    },
  },

  // -------------------------------------------------------------------------------------------
  {
    name: 'infinite-loop-enable',
    title: 'enable() that loops / swallows / throws / recurses -> rollback (or FAILED when new)',
    timeoutMs: 180000,
    async run(t) {
      const id = await t.useModule(t.id('enable'));
      const P = 'e2eenable';
      const files = (n, mode) => t.fixture('enable', { id, vars: { V: t.ver(n), P, MODE: mode } });
      const good = await t.deployOk(id, files(1, 'ok'));
      const okRe = (n) => new RegExp(`^E2E-ENABLE ${escapeRe(t.ver(n))}$`);
      await t.expectCmd('/' + P, okRe(1));
      const timings = {};
      let n = 1;
      for (const mode of ['loop', 'loopcatch', 'throw', 'recurse']) {
        n++;
        const genBefore = (await t.info(id)).generation;
        const t0 = Date.now();
        const r = await t.deploy(id, files(n, mode));
        const stall = t.stallMs(t0);
        timings[mode] = { reloadMs: r.durationMs, wallMs: r.wallMs, stallMs: stall };
        t.eq(r.ok, false, `enable ${mode}: reload must fail`, r);
        t.eq(r.state, 'ENABLED', `enable ${mode}: previous generation must be restored`, r);
        t.eq(r.generation, genBefore, `enable ${mode}: generation`, r);
        t.eq(r.rolledBack, true, `enable ${mode}: rolledBack`, r);
        t.assert(r.error, `enable ${mode}: error message`, r);
        if (mode.startsWith('loop')) {
          t.assert(stall < ENABLE_BUDGET_MS + 3500, `enable ${mode}: server stalled ${stall} ms (enable budget ${ENABLE_BUDGET_MS} ms)`);
        }
        // the failed attempt registered the command before failing: it must be cleaned up, and the
        // restored generation must answer exactly once
        const res = await t.cmd('/' + P, { until: /^E2E-ENABLE /, windowMs: 12000, settleMs: 700 });
        const hits = t.matching(res, /^E2E-ENABLE /);
        t.assert(hits.length === 1 && hits[0] === `E2E-ENABLE ${t.ver(1)}`, `enable ${mode}: /${P} should answer once from v1`, res.lines);
      }
      t.record('timings', timings);

      // A brand new module whose enable() fails: nothing to roll back to -> FAILED, nothing registered.
      // ('throw' rather than 'loop': a 3rd 5 s freeze in the same minute would push the 1m TPS to ~15
      // by itself; the FAILED path does not depend on the failure kind.)
      const fid = await t.useModule(t.id('enable-fresh'));
      const FP = 'e2eenablefresh';
      const fr = await t.deploy(fid, t.fixture('enable', { id: fid, vars: { V: t.ver(1), P: FP, MODE: 'throw' } }));
      t.eq(fr.ok, false, 'fresh failing module must fail', fr);
      t.eq(fr.state, 'FAILED', 'fresh failing module state', fr);
      t.eq(fr.rolledBack, false, 'fresh module: nothing rolled back', fr);
      await expectGone(t, FP);

      // and the first module still accepts a good version afterwards
      n++;
      await t.deployOk(id, files(n, 'ok'));
      await t.expectCmd('/' + P, okRe(n));
    },
  },

  // -------------------------------------------------------------------------------------------
  {
    name: 'forbidden-calls',
    title: 'Forbidden calls (System.exit, ProcessBuilder, Bukkit.shutdown...) are rejected at load',
    timeoutMs: 240000,
    async run(t) {
      const id = await t.useModule(t.id('forbid'));
      const P = 'e2eforbid';
      const good = await t.deployOk(id, hot(t, id, 1, P));
      const failures = [];
      const table = {};
      let n = 1;
      void good;
      for (const [label, snippet] of Object.entries(FORBIDDEN)) {
        n++;
        const genBefore = (await t.info(id)).generation;
        const r = await t.deploy(id, hot(t, id, n, P, { CMD: forbiddenInjection(snippet) }));
        const diag = (r.diagnostics || []).filter((d) => d.kind === 'ERROR').map((d) => `${d.file}:${d.line} ${d.message}`);
        table[label] = { ok: r.ok, state: r.state, error: r.error, diag: diag.slice(0, 2) };
        if (r.ok !== false || r.state !== 'ENABLED' || r.generation !== genBefore) {
          failures.push(`${label}: accepted or wrong state (ok=${r.ok}, state=${r.state}, gen=${r.generation})`);
          continue;
        }
        if (!diag.length) failures.push(`${label}: rejected but no ERROR diagnostic (error="${r.error}")`);
        else if (!diag.some((d) => /HotModule\.java:\d+/.test(d))) t.warn(`${label}: diagnostic has no file/line: ${diag[0]}`);
      }
      // the same calls hidden behind method references (invokedynamic), and a forbidden call in another class file
      for (const [label, snippet] of Object.entries(FORBIDDEN_INDIRECT)) {
        n++;
        const genBefore = (await t.info(id)).generation;
        const r = await t.deploy(id, hot(t, id, n, P, { CMD: forbiddenInjection(snippet) }));
        const diag = (r.diagnostics || []).filter((d) => d.kind === 'ERROR').map((d) => `${d.file}:${d.line} ${d.message}`);
        table[label] = { ok: r.ok, state: r.state, error: r.error, diag: diag.slice(0, 2) };
        if (r.ok !== false || r.state !== 'ENABLED' || r.generation !== genBefore) failures.push(`${label}: accepted or wrong state (ok=${r.ok}, state=${r.state}, gen=${r.generation})`);
        else if (!diag.length) failures.push(`${label}: rejected but no ERROR diagnostic (error="${r.error}")`);
      }
      {
        n++;
        const h = forbiddenHelperFile(packageFor(id));
        const files = { ...hot(t, id, n, P), [h.file]: h.content };
        const line = h.content.split('\n').findIndex((l) => l.includes(h.marker)) + 1;
        const genBefore = (await t.info(id)).generation;
        const r = await t.deploy(id, files);
        const errs = (r.diagnostics || []).filter((d) => d.kind === 'ERROR');
        table['Runtime.halt in a helper class'] = { ok: r.ok, state: r.state, error: r.error, diag: errs.slice(0, 2).map((d) => `${d.file}:${d.line} ${d.message}`) };
        if (r.ok !== false || r.state !== 'ENABLED' || r.generation !== genBefore) failures.push(`Runtime.halt in ${h.file}: accepted or wrong state (ok=${r.ok}, state=${r.state})`);
        else if (!errs.some((d) => String(d.file).endsWith(h.file) && d.line === line)) t.warn(`forbidden call in ${h.file}: diagnostic does not point at ${h.file}:${line}: ${JSON.stringify(errs.slice(0, 2))}`);
      }
      t.record('policy', table);
      // the original version must still be the one running, exactly once
      await expectHot(t, P, t.ver(1));
      const total = Object.keys(FORBIDDEN).length + Object.keys(FORBIDDEN_INDIRECT).length + 1;
      t.assert(failures.length === 0, `${failures.length}/${total} forbidden calls not handled: ${failures.join(' ; ')}`);
    },
  },

  // -------------------------------------------------------------------------------------------
  {
    name: 'raw-registrations',
    title: 'Raw Bukkit registerEvents / scheduler tasks are cleaned up on reload',
    timeoutMs: 90000,
    async run(t) {
      const id = await t.useModule(t.id('raw'));
      const files = (n) => t.fixture('raw', { id, vars: { V: t.ver(n) } });
      const statRe = /^E2E-RAW-STAT /;
      const lisRe = /^E2E-RAW-LISTENER /;
      // every aspect is checked with t.expect() so one run reports everything that is broken
      const rawStat = async () => {
        const r = await t.cmd('/e2erawstat', { until: statRe, windowMs: 8000, settleMs: 600 });
        const line = r.lines.find((l) => statRe.test(l));
        t.assert(line, '/e2erawstat did not answer', r.lines);
        return { s: parseStat(line), listener: t.matching(r, lisRe), lines: r.lines };
      };

      // raw CommandMap.register(...) command: answers exactly once, from the running generation only
      const rawCmd = async (n) => {
        const r = await t.cmd('/e2erawcmd', { until: /^E2E-RAWCMD |Unknown or incomplete command/, windowMs: 6000, settleMs: 600 });
        const hits = t.matching(r, /^E2E-RAWCMD /);
        t.expect(hits.length === 1 && hits[0] === `E2E-RAWCMD ${t.ver(n)}`, `v${n}: raw CommandMap command /e2erawcmd should answer exactly once from v${n}`, r.lines);
        t.expect(!r.lines.some((l) => INTERNAL_ERROR.test(l)), `v${n}: /e2erawcmd leaked an internal error`, r.lines);
      };

      await t.deployOk(id, files(1));
      const info1 = await t.info(id);
      const v1EnabledAt = info1.lastReloadAt || Date.now();
      t.record('v1', { listeners: info1.listeners, tasks: info1.tasks, commands: info1.commands });
      const s1 = await rawStat();
      t.expect(s1.listener.length === 1 && s1.listener[0] === `E2E-RAW-LISTENER ${t.ver(1)}`, 'v1: raw registerEvents listener should fire exactly once', s1.lines);
      await rawCmd(1);
      await sleep(300);

      await t.deployOk(id, files(2));
      const swapAfterMs = Date.now() - v1EnabledAt;
      const a = (await rawStat()).s;
      await rawCmd(2);
      // an endless loop inside a raw Command#execute: interrupted like any guarded command
      {
        const res = await blockedFor(t, '/e2erawcmd loop', '/e2erawstat', statRe);
        t.record('rawCmdLoop', { probeMs: res.elapsed, stallMs: res.stall });
        t.expect(res.elapsed != null && res.elapsed < HANDLER_BUDGET_MS + 2750, `raw command loop: main thread blocked ${res.elapsed} ms (budget ${HANDLER_BUDGET_MS} ms)`, res.lines);
        t.expect(res.lines.some((l) => l.startsWith('E2E-RAWCMD-LOOP-START')), 'raw command loop did not start', res.lines);
        t.expect(!res.lines.some((l) => /NOT-REACHED/.test(l) || INTERNAL_ERROR.test(l)), 'raw command loop: not contained / internal error leaked', res.lines);
      }
      await sleep(1500);
      const s2 = await rawStat();
      const b = s2.s;
      t.expect(s2.listener.length === 1 && s2.listener[0] === `E2E-RAW-LISTENER ${t.ver(2)}`, 'v2: raw listener should fire exactly once, from v2 only (old one unregistered)', s2.lines);
      for (const kind of ['timer', 'runnable', 'repeating']) {
        const k1 = `e2e-raw.${kind}.${t.ver(1)}`; const k2 = `e2e-raw.${kind}.${t.ver(2)}`;
        t.expect(a[k1] > 0, `v1 ${kind} task never ran`, { [k1]: a[k1] });
        t.expect(b[k1] === a[k1], `v1 ${kind} task still running after reload`, { before: a[k1], after: b[k1] });
        t.expect(b[k2] > a[k2], `v2 ${kind} task should be running`, { before: a[k2], after: b[k2] });
      }
      const info2 = await t.info(id);
      t.record('v2', { listeners: info2.listeners, tasks: info2.tasks });
      t.expect(info2.listeners === info1.listeners, 'listener count must not grow across reloads', { v1: info1.listeners, v2: info2.listeners });
      t.check(info2.tasks <= info1.tasks, `task count grew across reloads: v1=${info1.tasks} v2=${info2.tasks}`);
      t.check(info1.tasks >= 3, `raw scheduler tasks not tracked in ModuleInfo.tasks (v1 tasks=${info1.tasks}, expected >= 3)`);

      // the 5 s one-shot task (runTaskLater 100 ticks) of v1 must never run
      const wait = v1EnabledAt + 6200 - Date.now();
      if (wait > 0) await sleep(wait);
      const c = (await rawStat()).s;
      if (swapAfterMs < 4500) t.expect((c[`e2e-raw.later.${t.ver(1)}`] || 0) === 0, 'v1 runTaskLater fired after v1 was unloaded', c);
      else t.warn(`v1 lived ${swapAfterMs} ms (> 5 s): one-shot task check inconclusive`);

      // disable: every raw registration must be gone (command map + client command tree)
      const d = await t.api.disable(id);
      t.assert(d && d.ok === true, 'disable', d);
      const gone = await t.cmd('/e2erawcmd', { windowMs: 1500 });
      t.expect(!gone.lines.some((l) => /^E2E-RAWCMD/.test(l)) && gone.lines.some((l) => UNKNOWN_CMD.test(l)), '/e2erawcmd must be unknown once the module is disabled', gone.lines);
      await expectTree(t, 'e2erawcmd', false, { alts: ['e2eraw:e2erawcmd'] });
    },
  },

  // -------------------------------------------------------------------------------------------
  {
    name: 'async-loop-kill',
    title: 'Endless async loops (ctx.async, Bukkit async, raw Thread, executor, CompletableFuture) die on unload',
    timeoutMs: 120000,
    async run(t) {
      const id = await t.useModule(t.id('async'));
      const files = (n, mode) => t.fixture('async', { id, vars: { V: t.ver(n), MODE: mode, P: 'e2easyncstat' } });
      const re = /^E2E-ASYNC-STAT /;
      const kinds = ['ctx', 'bukkit', 'thread', 'exec', 'cf'];
      const keyOf = (kind, n) => `e2e-async.${kind}.${t.ver(n)}`;

      await t.deployOk(id, files(1, 'loops'));
      await sleep(1000);
      const s1 = await stat(t, '/e2easyncstat', re);
      for (const k of kinds) t.expect(s1[keyOf(k, 1)] > 0, `v1 ${k} loop not running`, s1);
      t.expect(s1.rawThreads >= 1, 'raw thread should be alive', s1);

      // reload to a version without loops: every v1 loop must stop
      await t.deployOk(id, files(2, 'none'));
      await sleep(1200);
      const s2 = await stat(t, '/e2easyncstat', re);
      await sleep(1500);
      const s3 = await stat(t, '/e2easyncstat', re);
      for (const k of kinds) t.expect(s3[keyOf(k, 1)] === s2[keyOf(k, 1)], `v1 ${k} loop still running after reload`, { s2: s2[keyOf(k, 1)], s3: s3[keyOf(k, 1)] });
      t.expect(s3.rawThreads === 0, 'raw thread of the unloaded generation should be dead', s3);

      // unload through DISABLE: a second module ("probe", no loops) reads the shared counters
      await t.deployOk(id, files(3, 'loops'));
      const probe = await t.useModule(t.id('async-probe'));
      await t.deployOk(probe, t.fixture('async', { id: probe, vars: { V: t.ver(99), MODE: 'none', P: 'e2easyncprobe' } }));
      const s4 = await stat(t, '/e2easyncprobe', re);
      for (const k of kinds) t.expect(s4[keyOf(k, 3)] > 0, `v3 ${k} loop not running`, s4);
      const d = await t.api.disable(id);
      t.assert(d && d.ok === true, 'disable should succeed', d);
      await sleep(1200);
      const s5 = await stat(t, '/e2easyncprobe', re);
      await sleep(1500);
      const s6 = await stat(t, '/e2easyncprobe', re);
      for (const k of kinds) t.expect(s6[keyOf(k, 3)] === s5[keyOf(k, 3)], `v3 ${k} loop still running after disable`, { s5: s5[keyOf(k, 3)], s6: s6[keyOf(k, 3)] });
      t.expect(s6.rawThreads === 0, 'no raw thread should survive a disable', s6);
    },
  },

  // -------------------------------------------------------------------------------------------
  {
    name: 'store-persistence',
    title: 'ModuleStore survives reload and disable/enable (store.yml), shared() survives reload',
    timeoutMs: 90000,
    async run(t) {
      const id = await t.useModule(t.id('store'));
      const files = (n) => t.fixture('store', { id, vars: { V: t.ver(n) } });
      const re = /^E2E-STORE /;
      const get = async (args) => {
        const r = await t.cmd('/e2estore ' + args, { until: re, windowMs: 6000, settleMs: 200 });
        const line = r.lines.find((l) => re.test(l));
        t.assert(line, `/e2estore ${args}: no answer`, r.lines);
        return Object.fromEntries(line.split(/\s+/).slice(1).map((kv) => kv.split('=')));
      };
      await t.deployOk(id, files(1));
      for (let i = 0; i < 3; i++) await get('inc');
      let s = await get('put hello');
      t.eq(s.count, '3', 'count after 3 inc', s);
      t.eq(s.note, 'hello', 'note', s);
      t.eq(s.hits, '4', 'shared hits', s);
      const boots1 = Number(s.boots);

      await t.deployOk(id, files(2));
      s = await get('get');
      t.eq(s.v, t.ver(2), 'new version running', s);
      t.eq(s.count, '3', 'store count survived reload', s);
      t.eq(s.note, 'hello', 'store note survived reload', s);
      t.assert(Number(s.boots) > boots1, 'boots must keep counting across reloads (store reset?)', s);
      t.eq(s.hits, '5', 'ctx.shared() value survived reload', s);
      const boots2 = Number(s.boots);

      const d = await t.api.disable(id);
      t.assert(d && d.ok === true, 'disable', d);
      const info = await t.waitInfo(id, (i) => i && i.state === 'DISABLED', { timeoutMs: 10000, what: 'DISABLED' });
      void info;
      const yml = t.ws.readData(id, 'store.yml');
      t.assert(yml && /count:\s*3/.test(yml) && /hello/.test(yml), 'data/<id>/store.yml should be saved on unload', yml);

      const e = await t.api.enable(id);
      t.assert(e && e.ok === true, 'enable', e);
      s = await get('get');
      t.eq(s.count, '3', 'store count survived disable/enable', s);
      t.assert(Number(s.boots) > boots2, 'boots after re-enable', s);
    },
  },

  // -------------------------------------------------------------------------------------------
  {
    name: 'french-text',
    title: 'Accents end to end (source, config.yml, module.yml, store, logs, diagnostics, /api/command): no mojibake',
    timeoutMs: 60000,
    async run(t) {
      const id = await t.useModule(t.id('fr'));
      const files = (n, inject) => t.fixture('fr', { id, vars: { V: t.ver(n) }, inject });
      const re = /^E2E-FR /;
      const lines = async () => {
        const r = await t.cmd('/e2efr', { until: /^E2E-FR store=/, windowMs: 5000, settleMs: 300 });
        return Object.fromEntries(t.matching(r, re).map((l) => { const kv = l.slice(7); const i = kv.indexOf('='); return [kv.slice(0, i), kv.slice(i + 1)]; }));
      };
      await t.deployOk(id, files(1));
      let fr = await lines();
      t.expect(fr.source === 'Éclair à la crème brûlée ✔ ça marche', 'string literal of the .java (UTF-8 source) reaches the player intact', fr.source);
      t.expect(fr.config === "Noël à l'île — œuf ✔", 'config.yml value read as UTF-8', fr.config);
      t.expect(fr.store === 'vide', 'empty store on first load', fr.store);
      const info = await t.info(id);
      t.expect(info.description === 'Démo française — accents é è à ç œ', 'module.yml description read as UTF-8', info.description);
      const logs = await t.api.logs(id, 50);
      t.expect(logs.some((l) => l.includes(`E2E-FR log=déjà vu ${t.ver(1)}`)), 'ctx.logger() text with accents in GET /logs', logs.slice(-3));
      const viaApi = await t.api.command('e2efr');
      t.expect((viaApi.output || []).some((l) => l.includes('Éclair à la crème brûlée ✔ ça marche')), 'POST /api/command output keeps the accents', viaApi.output);

      await t.deployOk(id, files(2));
      fr = await lines();
      t.expect(fr.store === `été-${t.ver(1)}`, 'ModuleStore value with accents survives a reload', fr.store);
      const yml = t.ws.readData(id, 'store.yml') || '';
      t.expect(yml.includes('été-') || /\\u00e9t\\u00e9-|\\xe9t\\xe9-/.test(yml), 'data/<id>/store.yml keeps the accents (UTF-8 or escaped)', yml.slice(0, 200));

      // a compile error on a line with accents: the diagnostic's source line must not be mojibake
      const bad = await t.deploy(id, files(3, { ENABLE: 'int e2eAccent = "café crème"; // E2E-FR-ERROR' }));
      const d = (bad.diagnostics || []).find((x) => x.kind === 'ERROR');
      t.expect(bad.ok === false && d && d.sourceLine.includes('"café crème"'), 'diagnostic sourceLine keeps the accents', d);
      const blog = t.ws.readVibe(id, 'build.log') || '';
      t.expect(blog.includes('"café crème"'), '.vibe/build.log keeps the accents (UTF-8)', blog.slice(0, 300));
    },
  },

  // -------------------------------------------------------------------------------------------
  {
    name: 'disable-enable',
    title: 'Disable unloads + marker; reload refused while disabled; enable restores',
    timeoutMs: 90000,
    async run(t) {
      const id = await t.useModule(t.id('toggle'));
      const P = 'e2etoggle';
      await t.deployOk(id, hot(t, id, 1, P));
      await expectHot(t, P, t.ver(1));
      const d = await t.api.disable(id);
      t.assert(d && d.ok === true, 'POST disable -> {ok:true}', d);
      let info = await t.waitInfo(id, (i) => i && i.state === 'DISABLED', { timeoutMs: 10000, what: 'DISABLED' });
      t.assert(t.ws.vibeExists(id, 'disabled'), '.vibe/disabled marker should exist');
      t.eq((info.commands || []).length, 0, 'no command while disabled', info.commands);
      await expectGone(t, P);
      await expectTree(t, P, false);

      const rr = await t.api.reload(id);
      t.eq(rr.ok, false, 'reload of a disabled module must be refused', rr);
      t.eq(rr.state, 'DISABLED', 'state stays DISABLED', rr);
      t.assert(/disabled/i.test(rr.error || ''), 'error should say "module is disabled"', rr.error);

      // editing files of a disabled module must not re-enable it
      t.ws.writeModule(id, hot(t, id, 2, P));
      await sleep(3500);
      info = await t.info(id);
      t.eq(info.state, 'DISABLED', 'file change must not re-enable a disabled module', t.infoSummary(info));

      const e = await t.api.enable(id);
      t.assert(e && e.ok === true && e.state === 'ENABLED', 'POST enable', e);
      t.assert(!t.ws.vibeExists(id, 'disabled'), '.vibe/disabled marker should be removed');
      await expectHot(t, P, t.ver(2));
      await expectTree(t, P, true);
    },
  },

  // -------------------------------------------------------------------------------------------
  {
    name: 'module-removal',
    title: 'Deleting a module directory unloads it (command + listener gone)',
    timeoutMs: 60000,
    async run(t) {
      const id = await t.useModule(t.id('remove'));
      const P = 'e2eremove';
      await t.deployOk(id, hot(t, id, 1, P));
      await expectHot(t, P, t.ver(1));
      const t0 = Date.now();
      t.ws.removeModule(id, { data: false });
      await t.waitInfo(id, (i) => i === null, { timeoutMs: 15000, intervalMs: 40, what: 'module removed (404)' });
      t.record('removedAfterMs', Date.now() - t0);
      // 404 should mean "unloaded": right after it, neither the command nor the listener may still run
      const early = await t.cmd('/' + P, { windowMs: 1500 });
      t.expect(!early.lines.some((l) => /^E2E-(CMD|LISTENER) /.test(l)),
        'GET /api/modules/{id} already answered 404 while the module was still loaded (its command/listener ran right after the 404)', early.lines);
      await sleep(500);
      await expectGone(t, P);
      await expectTree(t, P, false);
    },
  },

  // -------------------------------------------------------------------------------------------
  {
    name: 'scaffold-create',
    title: 'POST /api/modules/{id}/create scaffolds a compiling module that the watcher loads',
    timeoutMs: 60000,
    async run(t) {
      const id = await t.useModule(t.id('scaffold'));
      const desc = 'e2e scaffold test';
      const c = await t.api.create(id, desc);
      t.assert(c && c.ok === true && c.created === true, 'create -> {ok:true, created:true}', c);
      const files = t.ws.sourceFiles(id);
      t.assert(files.some((f) => f.endsWith('.java')), 'scaffold should contain a .java file', files);
      t.assert(files.includes('module.yml'), 'scaffold should contain module.yml', files);
      const info = await t.waitInfo(id, (i) => i && i.state === 'ENABLED' && i.generation >= 1, { timeoutMs: 20000, what: 'scaffold loaded by the watcher' });
      t.eq(info.description, desc, 'description from module.yml', info.description);
      const logs = await t.api.logs(id, 50);
      t.check(logs.some((l) => /enabled/i.test(l)), `scaffold enable() should log "enabled": ${JSON.stringify(logs.slice(-5))}`);
      const again = await t.api.raw('POST', `/api/modules/${id}/create`, 'again');
      t.assert(again.status >= 400 || (again.body && again.body.created === false), 'creating an existing module must not overwrite it', again);
      const bad = await t.api.raw('POST', '/api/modules/Bad..ID!/create', '');
      t.assert(bad.status >= 400 && bad.status < 500, 'invalid id must be rejected with 4xx', bad);
    },
  },

  // -------------------------------------------------------------------------------------------
  {
    name: 'parallel-reloads',
    title: 'Several modules compiled/reloaded in parallel; one failure does not affect the others',
    timeoutMs: 150000,
    async run(t) {
      const letters = ['a', 'b', 'c', 'd'];
      const ids = [];
      for (const l of letters) ids.push(await t.useModule(t.id('par-' + l)));
      const P = (l) => 'e2epar' + l;
      const files = (i, n) => t.fixture('par', { id: ids[i], vars: { V: t.ver(n), P: P(letters[i]) } });
      const expect = async (i, n) => t.expectCmd('/' + P(letters[i]), new RegExp(`^E2E-PAR ${P(letters[i])} ${escapeRe(t.ver(n))} `));

      let t0 = Date.now();
      const first = await Promise.all(ids.map((id, i) => t.deploy(id, files(i, 1))));
      t.record('firstLoadWallMs', Date.now() - t0);
      first.forEach((r, i) => t.assert(r.ok, `${ids[i]} first load`, r));
      for (let i = 0; i < ids.length; i++) await expect(i, 1);

      // v2: write everything, then fire the 4 reloads at once
      ids.forEach((id, i) => t.ws.writeModule(id, files(i, 2)));
      t0 = Date.now();
      const second = await Promise.all(ids.map((id) => t.api.reload(id)));
      const wall = Date.now() - t0;
      const sum = second.reduce((a, r) => a + (r.durationMs || 0), 0);
      t.record('parallel', { wallMs: wall, sumDurationsMs: sum, durations: second.map((r) => r.durationMs) });
      second.forEach((r, i) => t.assert(r.ok && r.generation > first[i].generation, `${ids[i]} parallel reload`, r));
      t.check(wall < sum, `no visible parallelism: wall ${wall} ms >= sum of durations ${sum} ms`);
      for (let i = 0; i < ids.length; i++) await expect(i, 2);

      // v3: module b gets a compile error while the others reload fine at the same time
      const genB = (await t.info(ids[1])).generation;
      ids.forEach((id, i) => {
        const f = files(i, 3);
        if (letters[i] === 'b') f['Broken.java'] = 'package vibe.e2e_par_b;\nclass Broken { int x = ; }\n';
        t.ws.writeModule(id, f);
      });
      const third = await Promise.all(ids.map((id) => t.api.reload(id)));
      third.forEach((r, i) => {
        if (letters[i] === 'b') t.assert(!r.ok && r.state === 'ENABLED' && r.generation === genB, 'b must fail and keep v2', r);
        else t.assert(r.ok, `${ids[i]} must reload despite b failing`, r);
      });
      for (let i = 0; i < ids.length; i++) await expect(i, letters[i] === 'b' ? 2 : 3);
    },
  },

  // -------------------------------------------------------------------------------------------
  {
    name: 'rapid-reloads',
    title: '30 rapid edits + reload requests (some half-written) -> final version, no duplicates',
    timeoutMs: 240000,
    async run(t) {
      const id = await t.useModule(t.id('rapid'));
      const P = 'e2erapid';
      const r0 = await t.deployOk(id, hot(t, id, 0, P));
      const N = 30;
      const pending = [];
      const t0 = Date.now();
      for (let i = 1; i <= N; i++) {
        const broken = i % 7 === 3; // some half-written versions in the middle
        t.ws.writeModule(id, hot(t, id, i, P, broken ? COMPILE_ERROR.inject : undefined));
        pending.push(t.api.reload(id).then((r) => ({ i, r }), (e) => ({ i, err: e.message })));
        await sleep(100);
      }
      const results = await Promise.all(pending);
      const errors = results.filter((x) => x.err);
      t.assert(errors.length === 0, `${errors.length} reload requests failed at HTTP level`, errors.slice(0, 3));
      const final = await t.api.reload(id);
      t.assert(final.ok && final.state === 'ENABLED', 'final reload', final);
      const gens = new Set(results.map((x) => x.r.generation));
      t.record('rapid', {
        totalMs: Date.now() - t0,
        okCount: results.filter((x) => x.r.ok).length,
        distinctGenerations: gens.size,
        generationsUsed: final.generation - r0.generation,
        maxDurationMs: Math.max(...results.map((x) => x.r.durationMs || 0)),
        stallMs: t.stallMs(t0),
      });
      t.check(final.generation - r0.generation <= N + 2, `more generations than requests: ${final.generation - r0.generation}`);
      await expectHot(t, P, t.ver(N));

      // burst: 10 concurrent reload requests on unchanged files -> one compile + ONE coalesced follow-up (spec 4.1)
      const gb = (await t.info(id)).generation;
      const burst = await Promise.all(Array.from({ length: 10 }, () => t.api.reload(id).catch((e) => ({ err: e.message }))));
      const ga = (await t.info(id)).generation;
      t.record('burst', { reloads: ga - gb, results: burst.map((r) => (r.err ? 'ERR ' + r.err : `${r.ok}/v${r.generation}`)) });
      t.expect(burst.every((r) => !r.err && r.ok === true), 'every request of a burst of 10 concurrent reloads must answer ok', burst.map((r) => r.err || r.error || r.ok));
      t.expect(ga - gb <= 2, `10 concurrent reload requests caused ${ga - gb} reloads (requests arriving during a compile must be coalesced into one follow-up compile)`, { before: gb, after: ga });
      await expectHot(t, P, t.ver(N));
      const info = await t.info(id);
      t.eq(info.listeners, 1, 'listeners after 30 reloads', t.infoSummary(info));
      t.eq(JSON.stringify(info.commands), JSON.stringify([P]), 'commands after 30 reloads', info.commands);
      t.eq(info.rolledBack, false, 'final state not rolled back', t.infoSummary(info));
    },
  },

  // -------------------------------------------------------------------------------------------
  {
    name: 'generation-gc',
    title: 'Unloaded generations become garbage (no class-loader leak over a day of reloads)',
    timeoutMs: 120000,
    async run(t) {
      const id = await t.useModule(t.id('leak'));
      const probe = await t.useModule(t.id('leak-probe'));
      const P = 'e2eleak'; const PP = 'e2eleakprobe';
      const N = 8;
      const target = (n) => t.fixture('leak', { id, vars: { V: t.ver(n), P, MODE: 'target', TARGET: id } });
      await t.deployOk(probe, t.fixture('leak', { id: probe, vars: { V: t.ver(99), P: PP, MODE: 'probe', TARGET: id } }));
      for (let n = 1; n <= N; n++) await t.deployOk(id, target(n));
      await t.expectCmd(`/${P}`, new RegExp(`^E2E-LEAK ${escapeRe(t.ver(N))}$`));
      await t.cmd(`/${P} ping`, { until: /^E2E-LEAK-PING /, windowMs: 3000 }); // events fired once more: Bukkit re-bakes its handler arrays
      const re = /^E2E-LEAK-STAT /;
      const statNow = async () => {
        const r = await t.cmd(`/${PP}`, { until: re, windowMs: 5000, settleMs: 200 });
        const line = r.lines.find((l) => re.test(l));
        t.assert(line, `/${PP}: no answer`, r.lines);
        const kv = parseKv(line);
        return { ...kv, alive: (kv.alive || '').split(',').filter(Boolean), dead: (kv.dead || '').split(',').filter(Boolean) };
      };
      const before = await statNow();
      let st = before;
      for (let i = 0; i < 3; i++) {
        await t.cmd(`/${PP} gc`, { until: /E2E-LEAK-GC-STARTED/, windowMs: 3000 });
        await sleep(2500);
        st = await statNow();
        if (st.alive.length <= 1) break;
      }
      const oldAlive = st.alive.filter((v) => v !== t.ver(N));
      t.record('generations', { alive: st.alive, dead: st.dead, majorGcs: [before.majorGcs, st.majorGcs], unloadedClasses: [before.unloadedClasses, st.unloadedClasses] });
      t.expect(st.alive.includes(t.ver(N)), 'the running generation must still be reachable', st);
      if (Number(st.majorGcs) <= Number(before.majorGcs)) {
        t.warn(`System.gc() did not trigger a full GC (-XX:+DisableExplicitGC?): class-loader leak check inconclusive (${oldAlive.length} old generations still reachable)`);
        return;
      }
      t.expect(oldAlive.length <= 1, `${oldAlive.length}/${N - 1} unloaded generations are still strongly reachable after full GCs (class-loader / metaspace leak)`, { alive: st.alive, dead: st.dead });
    },
  },

  // -------------------------------------------------------------------------------------------
  {
    name: 'ai-jobs',
    title: 'AI job pipeline with the FAKE Claude CLI: lifecycle, chat progress, 409 busy, resume, reset, stop (tree kill), failure',
    timeoutMs: 240000,
    async run(t) {
      const ai = await aiPreflight(t);
      const id = await t.useModule(t.id('ai-a'));
      await t.api.raw('POST', `/api/ai/${id}/reset`); // forget a conversation of a previous run
      const mark = (n) => `m${t.run}x${n}`;
      const CMD = 'e2eaia';

      // 1. a job that writes + reloads a module
      const t0 = Date.now();
      const c = await t.api.raw('POST', `/api/ai/${id}`, `Fais une commande E2E-CMD=${CMD} E2E-MARK=${mark(1)}`);
      t.assert(c.status === 201 && c.body && c.body.id, 'POST /api/ai/{id} -> 201 AiJob', { status: c.status, body: c.body });
      const job = c.body;
      t.expect(job.moduleId === id && ['QUEUED', 'RUNNING'].includes(job.state) && String(job.prompt).includes(mark(1)), 'new AiJob: moduleId, state QUEUED|RUNNING, prompt', job);
      const busy = await t.api.raw('POST', `/api/ai/${id}`, 'une autre demande pendant que la premiere tourne');
      t.expect(busy.status === 409 && typeof busy.body?.error === 'string', 'a 2nd job on a busy module -> 409 {error}', { status: busy.status, body: busy.body });
      const busyReset = await t.api.raw('POST', `/api/ai/${id}/reset`);
      t.expect(busyReset.status === 409 && typeof busyReset.body?.error === 'string', 'reset while a job runs -> 409 {error} (like DELETE /api/conversations/{id})', { status: busyReset.status, body: busyReset.body });
      const done = await waitJob(t, job.id, 60000);
      t.expect(done.state === 'SUCCEEDED', 'job SUCCEEDED', { state: done.state, result: done.result, progress: (done.progress || []).slice(-5) });
      t.expect(String(done.result).includes(mark(1)), 'AiJob.result = final answer of the agent', done.result);
      t.expect(Math.abs(Number(done.costUsd) - 0.0123) < 1e-6, 'AiJob.costUsd from the result line', done.costUsd);
      t.expect(/^e2e-fake-/.test(done.sessionId || ''), 'AiJob.sessionId from system/init', done.sessionId);
      t.expect(done.startedAt >= t0 - 1000 && done.endedAt >= done.startedAt, 'startedAt / endedAt', { startedAt: done.startedAt, endedAt: done.endedAt });
      const prog = (done.progress || []).join('\n');
      t.expect(/Écrit .*Module\.java/.test(prog) && /Rechargement/.test(prog) && /Rechargé à chaud/.test(prog), 'progress lines: write, reload, reloaded', done.progress);
      const info = await t.info(id);
      t.expect(info && info.state === 'ENABLED', 'the module written by the job is loaded', t.infoSummary(info));
      await t.expectCmd(`/${CMD}`, new RegExp(`^E2E-AI ${mark(1)}$`));
      // chat: "[<robot> <id>] ..." progress lines (the bot cannot decode the robot emoji, match the id) + the final line
      const chat = t.bot.messagesSince(t0, new RegExp(`${escapeRe(id)}\\]`)).map((m) => m.text);
      t.record('chat', chat.slice(0, 12));
      t.expect(chat.some((l) => l.includes(mark(1))), 'the final answer of the job is announced in chat', chat);
      t.expect(chat.length >= 3, 'job progress is shown in chat (new request, progress, final line)', chat);
      const spanS = Math.max(1, (done.endedAt - done.startedAt) / 1000);
      t.check(chat.length <= spanS / 1.5 + 4, `AI progress not throttled: ${chat.length} chat lines in ${spanS.toFixed(1)} s (<= 1 line / 1.5 s / job)`);
      const calls1 = fakeCalls(t).filter((e) => e.kind === 'job' && e.moduleId === id);
      const first = calls1.at(-1);
      t.assert(first, `the fake CLI log ${t.cfg.fakeClaudeLog} has no entry for ${id}`);
      t.expect(path.resolve(first.cwd) === path.resolve(t.ws.modulesDir, '..'), 'the CLI runs in the workspace root (modules-dir/..)', { cwd: first.cwd, expected: path.resolve(t.ws.modulesDir, '..') });
      for (const flag of ['-p', '--output-format', 'stream-json', '--verbose', '--permission-mode', '--allowedTools']) {
        t.expect(first.argv.includes(flag), `CLI argv contains ${flag}`, first.argv);
      }
      t.expect(first.resume === null, 'first job of a module: no --resume', first.argv);
      t.expect(first.request.includes(mark(1)), 'the request reaches the CLI inside <demande>', first.request);

      // 2. follow-up request: same conversation (--resume <sessionId>)
      const c2 = await t.api.json('POST', `/api/ai/${id}`, `Change le message E2E-CMD=${CMD} E2E-MARK=${mark(2)}`);
      const done2 = await waitJob(t, c2.id, 60000);
      t.expect(done2.state === 'SUCCEEDED', 'follow-up job SUCCEEDED', done2.state);
      const second = fakeCalls(t).filter((e) => e.kind === 'job' && e.moduleId === id).at(-1);
      t.expect(second && second.resume === done.sessionId, 'follow-up job resumes the module conversation (--resume <sessionId>)', { resume: second?.resume, expected: done.sessionId });
      await t.expectCmd(`/${CMD}`, new RegExp(`^E2E-AI ${mark(2)}$`));

      // 3. reset: the next job starts a new conversation
      const rs = await t.api.raw('POST', `/api/ai/${id}/reset`);
      t.expect(rs.status === 200 && rs.body?.ok === true, 'POST /api/ai/{id}/reset -> {ok:true}', rs.body);
      const c3 = await t.api.json('POST', `/api/ai/${id}`, `Encore E2E-CMD=${CMD} E2E-MARK=${mark(3)}`);
      await waitJob(t, c3.id, 60000);
      const third = fakeCalls(t).filter((e) => e.kind === 'job' && e.moduleId === id).at(-1);
      t.expect(third && third.resume === null, 'after reset: no --resume', third?.argv);

      // 4. stop a running job: state STOPPED, the whole process tree is killed
      const tSlow = Date.now();
      const c4 = await t.api.json('POST', `/api/ai/${id}`, 'Une demande tres longue E2E-SLOW');
      let slow = null;
      for (let i = 0; i < 100 && !slow; i++) {
        await sleep(200);
        slow = fakeCalls(t).find((e) => e.mode === 'slow' && e.moduleId === id && e.at >= tSlow - 1000);
      }
      t.assert(slow, 'the slow fake job never started');
      const running = await t.api.json('GET', '/api/ai');
      t.expect((running.find((j) => j.id === c4.id) || {}).state === 'RUNNING', 'slow job RUNNING', running.find((j) => j.id === c4.id));
      const tStop = Date.now();
      const stop = await t.api.raw('POST', `/api/ai/${id}/stop`);
      t.expect(stop.status === 200 && stop.body?.ok === true, 'POST /api/ai/{id}/stop -> {ok:true}', stop.body);
      const stopped = await waitJob(t, c4.id, 20000);
      t.record('stopMs', Date.now() - tStop);
      t.expect(stopped.state === 'STOPPED', 'stopped job state STOPPED', stopped.state);
      await sleep(1000);
      t.expect(!alive(slow.pid), `the fake CLI process (pid ${slow.pid}) must be killed`);
      t.expect(!alive(slow.childPid), `its child process (pid ${slow.childPid}) must be killed too (process tree)`);
      if (alive(slow.childPid)) { try { process.kill(slow.childPid); } catch { /* ignore */ } }
      if (alive(slow.pid)) { try { process.kill(slow.pid); } catch { /* ignore */ } }

      // 4b. disabling the module during a job is an emergency stop: the AI is stopped and the module STAYS disabled
      const tDis = Date.now();
      const c4b = await t.api.json('POST', `/api/ai/${id}`, 'Une autre demande tres longue E2E-SLOW');
      let slow2 = null;
      for (let i = 0; i < 100 && !slow2; i++) {
        await sleep(200);
        slow2 = fakeCalls(t).find((e) => e.mode === 'slow' && e.moduleId === id && e.at >= tDis - 1000);
      }
      t.assert(slow2, 'the 2nd slow fake job never started');
      const dis = await t.api.raw('POST', `/api/modules/${id}/disable`);
      t.expect(dis.status === 200 && dis.body?.ok === true && dis.body?.aiStopped === true, 'POST disable during a job -> {ok:true, aiStopped:true}', dis.body);
      const stopped2 = await waitJob(t, c4b.id, 20000);
      t.expect(stopped2.state === 'STOPPED', 'disable stopped the AI job (STOPPED)', stopped2.state);
      await sleep(3000); // > watcher debounce: nothing may reload / re-enable it behind our back
      const infoDis = await t.info(id);
      t.expect(infoDis && infoDis.state === 'DISABLED', 'the module is still DISABLED after the job ended', t.infoSummary(infoDis));
      if (alive(slow2.childPid)) { try { process.kill(slow2.childPid); } catch { /* ignore */ } }
      if (alive(slow2.pid)) { try { process.kill(slow2.pid); } catch { /* ignore */ } }
      const en = await t.api.json('POST', `/api/modules/${id}/enable`);
      t.expect(en && en.ok === true, 're-enable after the emergency stop', en);

      // 5. a failing agent run -> FAILED, the module keeps its last good version
      const c5 = await t.api.json('POST', `/api/ai/${id}`, 'Une demande qui echoue E2E-FAIL');
      const failed = await waitJob(t, c5.id, 60000);
      t.expect(failed.state === 'FAILED', 'failing agent -> job FAILED', { state: failed.state, result: failed.result });
      await t.expectCmd(`/${CMD}`, new RegExp(`^E2E-AI ${mark(3)}$`));
      t.record('ai', ai);
    },
  },

  // -------------------------------------------------------------------------------------------
  {
    name: 'ai-ideas',
    title: 'Ideas -> AI jobs with the FAKE Claude CLI: /idea, POST /api/ideas, accept, dismiss, /vibe ai',
    timeoutMs: 180000,
    async run(t) {
      await aiPreflight(t);
      // POST /api/ideas + accept -> job on the given module
      const idB = await t.useModule(t.id('ai-b'));
      await t.api.raw('POST', `/api/ai/${idB}/reset`);
      const markB = `i${t.run}b`;
      const idea = await t.api.raw('POST', '/api/ideas', `E2E idee E2E-CMD=e2eaib E2E-MARK=${markB}`);
      t.assert(idea.status >= 200 && idea.status < 300 && Number.isInteger(idea.body?.id), 'POST /api/ideas -> Idea', { status: idea.status, body: idea.body });
      t.expect(idea.body.status === 'NEW' && idea.body.text.includes(markB), 'new Idea: status NEW, text', idea.body);
      const acc = await t.api.raw('POST', `/api/ideas/${idea.body.id}/accept?module=${idB}`);
      t.assert(acc.status >= 200 && acc.status < 300 && acc.body?.id, 'POST /api/ideas/{n}/accept?module= -> AiJob', { status: acc.status, body: acc.body });
      t.expect(acc.body.moduleId === idB && String(acc.body.prompt).includes(markB), 'accepted idea: job on the requested module with the idea text', acc.body);
      const doneB = await waitJob(t, acc.body.id, 60000);
      t.expect(doneB.state === 'SUCCEEDED', 'idea job SUCCEEDED', doneB.state);
      await t.expectCmd('/e2eaib', new RegExp(`^E2E-AI ${markB}$`));
      const ideas = await t.api.ideas();
      const after = ideas.find((i) => i.id === idea.body.id);
      t.expect(after && after.status === 'ACCEPTED' && after.moduleId === idB, 'idea status ACCEPTED with moduleId', after);

      // dismiss
      const d0 = await t.api.json('POST', '/api/ideas', `E2E idee a rejeter ${t.run}`);
      const dis = await t.api.raw('POST', `/api/ideas/${d0.id}/dismiss`);
      t.expect(dis.status === 200 && dis.body?.status === 'DISMISSED', 'POST /api/ideas/{n}/dismiss -> Idea DISMISSED', dis.body);
      const unknown = await t.api.raw('POST', '/api/ideas/999999/dismiss');
      t.expect(unknown.status === 404, 'dismiss of an unknown idea -> 404', { status: unknown.status, body: unknown.body });

      // in game: /idea <text> (everyone) then /vibe ai <id> <request> (ops)
      const tIdea = Date.now();
      const r = await t.cmd(`/idea E2E idee du bot ${t.run}`, { windowMs: 2000 });
      t.expect(!r.lines.some((l) => MC_UNKNOWN.test(l) || INTERNAL_ERROR.test(l)) && r.lines.length > 0, '/idea <text>: answered', r.lines);
      const mine = (await t.api.ideas()).find((i) => i.text.includes(`E2E idee du bot ${t.run}`));
      t.expect(mine && mine.author === t.bot.username && mine.at >= tIdea - 2000, '/idea creates an Idea authored by the player', mine);
      if (mine) t.onCleanup(() => t.api.raw('POST', `/api/ideas/${mine.id}/dismiss`), 'dismiss the /idea of the bot');
      const idC = await t.useModule(t.id('ai-c'));
      await t.api.raw('POST', `/api/ai/${idC}/reset`);
      const markC = `v${t.run}c`;
      await t.cmd(`/vibe ai ${idC} E2E-CMD=e2eaic E2E-MARK=${markC}`, { windowMs: 1500 });
      let jobC = null;
      for (let i = 0; i < 30 && !jobC; i++) {
        jobC = (await t.api.aiJobs()).find((j) => j.moduleId === idC && String(j.prompt).includes(markC));
        if (!jobC) await sleep(200);
      }
      t.assert(jobC, '/vibe ai <id> <request> did not start a job');
      t.expect(jobC.requestedBy === t.bot.username, 'AiJob.requestedBy = the player', jobC.requestedBy);
      const doneC = await waitJob(t, jobC.id, 60000);
      t.expect(doneC.state === 'SUCCEEDED', '/vibe ai job SUCCEEDED', doneC.state);
      await t.expectCmd('/e2eaic', new RegExp(`^E2E-AI ${markC}$`));
    },
  },

  // -------------------------------------------------------------------------------------------
  {
    name: 'circuit-breaker',
    title: 'A task throwing every tick trips the module; reload re-arms; fixed code runs',
    timeoutMs: 120000,
    async run(t) {
      const id = await t.useModule(t.id('breaker'));
      const P = 'e2ebreaker';
      const files = (n, mode) => t.fixture('breaker', { id, vars: { V: t.ver(n), P, MODE: mode } });
      const t0 = Date.now();
      const r = await t.deploy(id, files(1, 'throw'));
      t.assert(r.state === 'ENABLED' || r.state === 'TRIPPED', 'throwing module loads (then trips)', r);
      let info = await t.waitInfo(id, (i) => i && i.state === 'TRIPPED', { timeoutMs: 20000, what: 'TRIPPED' });
      t.record('tripAfterMs', Date.now() - t0);
      t.eq(info.tasks, 0, 'tasks cancelled when tripped', t.infoSummary(info));
      t.eq((info.commands || []).length, 0, 'commands unregistered when tripped', info.commands);
      t.assert(info.lastError, 'lastError should explain the trip', t.infoSummary(info));
      await expectGone(t, P);
      await sleep(500);
      const note = t.bot.messagesSince(t0, new RegExp(escapeRe(id)));
      t.check(note.length > 0, 'ops received no chat notification about the trip');
      // ~25 errors in ~1.3 s: RUNTIME_ERROR notifications must be throttled (<= 1/s/module), no chat flood
      const spanS = Math.ceil((Date.now() - t0) / 1000);
      t.record('opsMessagesUntilTrip', { count: note.length, spanS, sample: note.slice(0, 4).map((m) => m.text) });
      t.expect(note.length <= spanS + 4, `ops chat flooded: ${note.length} messages about ${id} in ${spanS} s (RUNTIME_ERROR notifications must be throttled)`, note.slice(0, 6).map((m) => m.text));

      // reload re-arms (same code -> trips again)
      const t1 = Date.now();
      const rr = await t.api.reload(id);
      t.assert(rr.ok === true, 'reload must re-arm a TRIPPED module', rr);
      info = await t.waitInfo(id, (i) => i && i.state === 'TRIPPED' && i.lastAttemptAt >= t1, { timeoutMs: 20000, what: 'TRIPPED again' });

      // fixed code
      await t.deployOk(id, files(2, 'calm'));
      await sleep(2000);
      info = await t.info(id);
      t.eq(info.state, 'ENABLED', 'calm version stays enabled', t.infoSummary(info));
      await t.expectCmd('/' + P, new RegExp(`^E2E-BREAKER-CMD ${escapeRe(t.ver(2))}$`));
    },
  },

  // -------------------------------------------------------------------------------------------
  {
    name: 'circuit-breaker-timeouts',
    title: 'A task that loops forever every tick is interrupted each time, then tripped',
    timeoutMs: 120000,
    async run(t) {
      const id = await t.useModule(t.id('breaker-spin'));
      const P = 'e2ebreakerspin';
      const files = (n, mode) => t.fixture('breaker', { id, vars: { V: t.ver(n), P, MODE: mode } });
      const t0 = Date.now();
      await t.deploy(id, files(1, 'spin'));
      const info = await t.waitInfo(id, (i) => i && i.state === 'TRIPPED', { timeoutMs: 40000, what: 'TRIPPED after repeated timeouts' });
      const stall = t.stallMs(t0);
      t.record('tripAfterMs', Date.now() - t0);
      t.record('maxStallMs', stall);
      t.eq(info.tasks, 0, 'spinning task cancelled', t.infoSummary(info));
      t.assert(stall < HANDLER_BUDGET_MS + 3000, `server stalled ${stall} ms while the task was spinning`);
      await t.deployOk(id, files(2, 'calm'));
      await t.expectCmd('/' + P, new RegExp(`^E2E-BREAKER-CMD ${escapeRe(t.ver(2))}$`));
    },
  },

  // -------------------------------------------------------------------------------------------
  {
    name: 'vibe-command',
    title: 'In-game /vibe list|info|reload|disable|enable (and /vc alias) as op',
    timeoutMs: 90000,
    async run(t) {
      const id = await t.useModule(t.id('vibecmd'));
      const P = 'e2evibecmd';
      const r = await t.deployOk(id, hot(t, id, 1, P));
      const idRe = new RegExp(escapeRe(id));
      let res = await t.cmd('/vibe list', { until: idRe, windowMs: 5000 });
      t.assert(res.lines.some((l) => idRe.test(l)), '/vibe list should mention the module', res.lines);
      t.assert(!res.lines.some((l) => UNKNOWN_CMD.test(l) || INTERNAL_ERROR.test(l)), '/vibe list failed', res.lines);
      res = await t.cmd(`/vibe info ${id}`, { until: idRe, windowMs: 5000 });
      t.assert(res.lines.some((l) => idRe.test(l)), '/vibe info should mention the module', res.lines);

      await t.cmd(`/vibe reload ${id}`, { windowMs: 1000 });
      await t.waitInfo(id, (i) => i && i.generation > r.generation && i.state === 'ENABLED', { timeoutMs: 20000, what: 'reloaded via /vibe reload' });
      await t.cmd(`/vibe disable ${id}`, { windowMs: 1000 });
      await t.waitInfo(id, (i) => i && i.state === 'DISABLED', { timeoutMs: 10000, what: 'disabled via /vibe disable' });
      await t.cmd(`/vibe enable ${id}`, { windowMs: 1000 });
      await t.waitInfo(id, (i) => i && i.state === 'ENABLED', { timeoutMs: 20000, what: 'enabled via /vibe enable' });
      await expectHot(t, P, t.ver(1));
      res = await t.cmd('/vc list', { until: idRe, windowMs: 5000 });
      t.check(res.lines.some((l) => idRe.test(l)), '/vc alias did not list the module');

      const clean = (r, what) => t.expect(r.lines.length > 0 && !r.lines.some((l) => MC_UNKNOWN.test(l) || INTERNAL_ERROR.test(l)), `${what}: no answer, unknown command or internal error`, r.lines);
      clean(await t.cmd('/vibe help', { windowMs: 2000 }), '/vibe help');
      clean(await t.cmd(`/vibe errors ${id}`, { windowMs: 2000 }), `/vibe errors ${id}`);
      clean(await t.cmd('/vibe e2e-no-such-subcommand', { windowMs: 2000 }), '/vibe <unknown subcommand>');
      // /vibe dashboard prints the dashboard URL with the configured API port
      const port = new URL(t.cfg.api).port || '80';
      res = await t.cmd('/vibe dashboard', { until: /https?:\/\//, windowMs: 3000 });
      t.expect(res.lines.some((l) => l.includes(`:${port}`)), `/vibe dashboard should print the dashboard URL (port ${port})`, res.lines);

      // tab completion (spec: "Tab completion everywhere")
      const tab = async (text, want) => {
        let got;
        try { got = await t.bot.tabComplete(text); } catch (e) { got = e.message; }
        t.step(`tab "${text}" -> ${JSON.stringify(got).slice(0, 200)}`);
        t.expect(Array.isArray(got) && want.every((w) => got.includes(w)), `tab completion of "${text}" should offer ${want.join(', ')}`, got);
      };
      await tab('/vibe ', ['reload', 'list', 'info', 'disable', 'enable']);
      await tab('/vibe reload e2e-vibec', [id]);
      await tab('/vibe info ', [id]);

      // /vibe reload all: only where every module is a test module (never reload a live demo's modules)
      const others = (await t.api.modules()).filter((m) => !m.id.startsWith(t.cfg.prefix));
      if (others.length === 0) {
        const g0 = (await t.info(id)).generation;
        clean(await t.cmd('/vibe reload all', { windowMs: 1500 }), '/vibe reload all');
        const ra = await t.waitInfo(id, (i) => i && i.generation > g0 && i.state === 'ENABLED', { timeoutMs: 20000, what: 'reloaded by /vibe reload all' }).catch((e) => e);
        t.expect(!(ra instanceof Error), '/vibe reload all should reload the module', ra instanceof Error ? ra.message : null);
      } else {
        t.step(`/vibe reload all not exercised: ${others.length} non-test module(s) on this server`);
      }

      // /vibe new <id> <description...> scaffolds a module that the watcher loads
      const nid = await t.useModule(t.id('vibecmd-new'));
      clean(await t.cmd(`/vibe new ${nid} module e2e via vibe new`, { windowMs: 1500 }), `/vibe new ${nid}`);
      const ni = await t.waitInfo(nid, (i) => i && i.state === 'ENABLED', { timeoutMs: 20000, what: '/vibe new module loaded' }).catch((e) => e);
      t.expect(!(ni instanceof Error) && ni.description === 'module e2e via vibe new', `/vibe new ${nid}: scaffold loaded with its description`, ni instanceof Error ? ni.message : t.infoSummary(ni));
    },
  },
];

export const SCENARIO_NAMES = SCENARIOS.map((s) => s.name);
