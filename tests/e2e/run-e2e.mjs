#!/usr/bin/env node
// VibeCraft end-to-end test runner (ARCHITECTURE.md section 9).
// A mineflayer bot (VibeBot) joins the server and stays connected for the whole run while every scenario
// writes test modules into the workspace, drives the engine through the HTTP API and checks the results
// through the bot's chat. See README.md for usage.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';

import { VibeBot } from './lib/bot.mjs';
import { VibeApi } from './lib/api.mjs';
import { Workspace, waitForGone, sleep } from './lib/workspace.mjs';
import { TestContext, AssertionError, SkipError, short } from './lib/harness.mjs';
import { SCENARIOS } from './lib/scenarios.mjs';
import { renderFixture } from './lib/fixtures.mjs';
import { rcon } from './lib/rcon.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, '..', '..');
const env = process.env;

const HELP = `Usage: node run-e2e.mjs [options]

Options (env var in brackets):
  --mc-host <host>       Minecraft host                 [VIBE_MC_HOST]      default 127.0.0.1
  --mc-port <port>       Minecraft port                 [VIBE_MC_PORT]      default 25565
  --mc-version <v>       protocol spoken by the bot     [VIBE_MC_VERSION]   default 26.1 (needs ViaVersion+ViaBackwards)
  --username <name>      bot name                       [VIBE_BOT_NAME]     default VibeBot
  --api <url>            VibeCraft HTTP API base        [VIBE_API]          default http://127.0.0.1:8765
  --token <token>        X-Vibe-Token if api.token set  [VIBE_TOKEN]
  --modules-dir <dir>    workspace/modules              [VIBE_MODULES_DIR]  default ${path.join(ROOT, 'workspace', 'modules')}
  --data-dir <dir>       workspace/data                 [VIBE_DATA_DIR]     default <modules-dir>/../data
  --prefix <p>           test module id prefix          [VIBE_PREFIX]       default e2e-
  --only <a,b,...>       run only these scenarios (exact name or prefix)   [VIBE_ONLY]
  --skip <a,b,...>       skip these scenarios
  --rcon-dir <dir>       server dir for RCON fallback (whitelist/op) if POST /api/command fails [VIBE_RCON_DIR]
  --fake-claude <log>    the server's ai.claude-path is tools/fake-claude.mjs writing to <log>: enables the ai-*
                         scenarios (without it they are SKIPPED: they would start REAL Claude jobs) [VIBE_FAKE_CLAUDE]
  --wait-api <sec>       wait up to N s for the API to answer (default 90)
  --min-tps <n>          TPS threshold (default 15)
  --no-reconnect         after an (always fatal) disconnect, do not reconnect for the next scenarios
  --bail                 stop after the first failing scenario
  --keep                 keep test modules on disk after each scenario (debugging)
  --report-dir <dir>     default tests/e2e/reports
  --list                 list scenarios and exit
  -h, --help
Exit code: 0 if every scenario and global check passed, 1 otherwise, 2 on usage errors.`;

function parseArgs(argv) {
  const cfg = {
    mcHost: env.VIBE_MC_HOST || '127.0.0.1',
    mcPort: Number(env.VIBE_MC_PORT || 25565),
    mcVersion: env.VIBE_MC_VERSION || '26.1',
    username: env.VIBE_BOT_NAME || 'VibeBot',
    api: env.VIBE_API || 'http://127.0.0.1:8765',
    token: env.VIBE_TOKEN || '',
    modulesDir: env.VIBE_MODULES_DIR || path.join(ROOT, 'workspace', 'modules'),
    dataDir: env.VIBE_DATA_DIR || null,
    prefix: env.VIBE_PREFIX || 'e2e-',
    only: (env.VIBE_ONLY || '').split(',').map((s) => s.trim()).filter(Boolean),
    skip: [],
    rconDir: env.VIBE_RCON_DIR || null,
    fakeClaudeLog: env.VIBE_FAKE_CLAUDE || null,
    waitApiSec: 90,
    minTps: 15,
    reconnect: true,
    bail: false,
    keep: false,
    reportDir: path.join(HERE, 'reports'),
    list: false,
  };
  const need = (i) => { if (i + 1 >= argv.length) usage(`missing value for ${argv[i]}`); return argv[i + 1]; };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    switch (a) {
      case '--mc-host': cfg.mcHost = need(i); i++; break;
      case '--mc-port': cfg.mcPort = Number(need(i)); i++; break;
      case '--mc-version': cfg.mcVersion = need(i); i++; break;
      case '--username': cfg.username = need(i); i++; break;
      case '--api': cfg.api = need(i); i++; break;
      case '--token': cfg.token = need(i); i++; break;
      case '--modules-dir': cfg.modulesDir = need(i); i++; break;
      case '--data-dir': cfg.dataDir = need(i); i++; break;
      case '--prefix': cfg.prefix = need(i); i++; break;
      case '--only': cfg.only = need(i).split(',').map((s) => s.trim()).filter(Boolean); i++; break;
      case '--skip': cfg.skip = need(i).split(',').map((s) => s.trim()).filter(Boolean); i++; break;
      case '--rcon-dir': cfg.rconDir = need(i); i++; break;
      case '--fake-claude': cfg.fakeClaudeLog = need(i); i++; break;
      case '--wait-api': cfg.waitApiSec = Number(need(i)); i++; break;
      case '--min-tps': cfg.minTps = Number(need(i)); i++; break;
      case '--no-reconnect': cfg.reconnect = false; break;
      case '--bail': cfg.bail = true; break;
      case '--keep': cfg.keep = true; break;
      case '--report-dir': cfg.reportDir = need(i); i++; break;
      case '--list': cfg.list = true; break;
      case '-h': case '--help': console.log(HELP); process.exit(0); break;
      default: usage(`unknown option ${a}`);
    }
  }
  if (!cfg.prefix || !/^[a-z0-9][a-z0-9_-]*$/.test(cfg.prefix)) usage('--prefix must be a valid module id prefix');
  return cfg;
}

function usage(msg) {
  console.error(msg + '\n\n' + HELP);
  process.exit(2);
}

// ------------------------------------------------------------------ output helpers
const tty = process.stdout.isTTY && !env.NO_COLOR;
const color = (c, s) => (tty ? `\x1b[${c}m${s}\x1b[0m` : s);
const green = (s) => color('32', s); const red = (s) => color('31', s); const yellow = (s) => color('33', s);
const bold = (s) => color('1', s); const dim = (s) => color('2', s);
const fmtMs = (ms) => (ms == null ? '-' : ms < 1000 ? `${ms} ms` : `${(ms / 1000).toFixed(1)} s`);
const log = (s) => console.log(s);

// ------------------------------------------------------------------ TPS monitor
class TpsMonitor {
  constructor(api, intervalMs = 2000) {
    this.api = api; this.intervalMs = intervalMs;
    this.samples = []; this.errors = []; this.running = false;
  }
  start() {
    this.running = true;
    this._loop = (async () => {
      while (this.running) {
        const t0 = Date.now();
        try {
          const s = await this.api.status();
          const tps = s?.server?.tps;
          if (Array.isArray(tps) && Number.isFinite(tps[0])) this.samples.push({ at: Date.now(), tps1m: +tps[0].toFixed(2), tps: tps.map((x) => +Number(x).toFixed(2)), online: s.server.online, apiMs: Date.now() - t0 });
          else this.errors.push({ at: Date.now(), error: 'status without server.tps' });
        } catch (e) {
          this.errors.push({ at: Date.now(), error: e.message });
        }
        await sleep(Math.max(0, this.intervalMs - (Date.now() - t0)));
      }
    })();
    return this;
  }
  async stop() { this.running = false; await this._loop; }
  minBetween(from, to) {
    const s = this.samples.filter((x) => x.at >= from && x.at <= to);
    return s.length ? Math.min(...s.map((x) => x.tps1m)) : null;
  }
}

// ------------------------------------------------------------------ main
async function main() {
  const cfg = parseArgs(process.argv.slice(2));
  if (cfg.list) {
    for (const s of SCENARIOS) log(`${s.name.padEnd(26)} ${s.title}`);
    log(`${'(global) tps'.padEnd(26)} 1m TPS from GET /api/status stays > ${cfg.minTps} for the whole run`);
    log(`${'(global) bot-connected'.padEnd(26)} the bot is never kicked / disconnected`);
    return 0;
  }

  let selected = SCENARIOS;
  if (cfg.only.length) {
    const unknown = cfg.only.filter((o) => !SCENARIOS.some((s) => s.name === o || s.name.startsWith(o)));
    if (unknown.length) usage(`unknown scenario(s): ${unknown.join(', ')} (see --list)`);
    selected = SCENARIOS.filter((s) => cfg.only.some((o) => s.name === o || s.name.startsWith(o)));
  }
  if (cfg.skip.length) selected = selected.filter((s) => !cfg.skip.some((o) => s.name === o || s.name.startsWith(o)));

  const run = Date.now().toString(36).slice(-6);
  const startedAt = Date.now();
  const require = createRequire(import.meta.url);
  const versions = { node: process.version, mineflayer: safe(() => require('mineflayer/package.json').version), minecraftProtocol: safe(() => require('minecraft-protocol/package.json').version) };
  log(bold(`VibeCraft e2e — run ${run} — ${new Date(startedAt).toISOString()}`));
  log(dim(`MC ${cfg.mcHost}:${cfg.mcPort} (bot speaks ${cfg.mcVersion}) | API ${cfg.api} | modules ${cfg.modulesDir} | prefix "${cfg.prefix}" | ${selected.length} scenario(s)`));

  const api = new VibeApi({ base: cfg.api, token: cfg.token });
  const ws = new Workspace({ modulesDir: cfg.modulesDir, dataDir: cfg.dataDir || undefined, prefix: cfg.prefix });
  const report = {
    run, startedAt: new Date(startedAt).toISOString(), endedAt: null, durationMs: null, status: 'RUNNING',
    config: { ...cfg, token: cfg.token ? '***' : '' }, versions, setup: { warnings: [] }, results: [], global: {},
  };
  const bots = [];
  let bot = null;
  const runnerEvents = [];
  const allDisconnects = () => bots.flatMap((b) => b.disconnects.map((d) => ({ ...d, bot: bots.indexOf(b) })));

  const finish = async (exitCode, status) => {
    report.status = status;
    report.endedAt = new Date().toISOString();
    report.durationMs = Date.now() - startedAt;
    report.runnerEvents = runnerEvents;
    report.api = { calls: api.calls.length, errors: api.calls.filter((c) => c.status === 'ERR' || c.status >= 500).length, slowest: [...api.calls].sort((a, b) => b.ms - a.ms).slice(0, 10) };
    report.bot = bots.map((b) => ({ events: b.events, disconnects: b.disconnects, client: { ...b.tickStats(), minWindowTps10s: b.minWindowTps(10000) }, commandTreeUpdates: b.commandTreeUpdates.length, lastMessages: b.messages.slice(-200) }));
    fs.mkdirSync(cfg.reportDir, { recursive: true });
    const file = path.join(cfg.reportDir, `${new Date(startedAt).toISOString().replace(/[:.]/g, '-')}.json`);
    fs.writeFileSync(file, JSON.stringify(report, null, 2));
    log(dim(`report: ${file}`));
    for (const b of bots) await b.quit().catch(() => {});
    return exitCode;
  };

  // 1. API reachable?
  const waitUntil = Date.now() + cfg.waitApiSec * 1000;
  let first = true;
  while (!(await api.reachable(3000))) {
    if (Date.now() > waitUntil) {
      log(red(`FATAL: VibeCraft API ${cfg.api} not reachable after ${cfg.waitApiSec} s (is the server running with VibeCraft? api.enabled/port?)`));
      report.setup.error = 'API unreachable';
      return finish(1, 'SETUP_FAILED');
    }
    if (first) { log(yellow(`waiting for ${cfg.api}/api/status ...`)); first = false; }
    await sleep(1000);
  }
  try {
    const st = await api.status();
    report.setup.statusAtStart = { server: st.server, modules: (st.modules || []).map((m) => ({ id: m.id, state: m.state, generation: m.generation })), ai: st.ai };
    log(dim(`server: ${short(st.server, 200)}`));
  } catch (e) {
    log(red(`FATAL: GET /api/status failed: ${e.message}`));
    report.setup.error = e.message;
    return finish(1, 'SETUP_FAILED');
  }

  // 2. Remove leftovers of previous runs (only ids starting with the prefix)
  for (const id of ws.listTestModules()) {
    log(dim(`removing leftover test module ${id}`));
    try { ws.removeModule(id); await waitForGone(api, id, 20000); } catch (e) { report.setup.warnings.push(`leftover ${id}: ${e.message}`); log(yellow(`  ${e.message}`)); }
  }
  for (const id of ws.listTestData()) { try { ws.removeModule(id); } catch { /* ignore */ } }

  // 2b. Sanity check: does the server watch the same modules directory as we write to?
  {
    const probe = `${cfg.prefix}setup-probe`;
    try {
      ws.writeModule(probe, renderFixture('hot', { id: probe, vars: { V: `${run}.probe`, P: 'e2esetupprobe' } }));
      let seen = false;
      try {
        const r = await api.reload(probe);
        seen = !!r && r.state !== undefined && !/unknown/i.test(r.error || '');
      } catch (e) {
        if (e.status !== 404) throw e;
      }
      for (let i = 0; i < 20 && !seen; i++) { await sleep(500); seen = !!(await api.module(probe)); }
      ws.removeModule(probe);
      if (!seen) {
        log(red(`FATAL: the server does not see modules written to ${cfg.modulesDir} (wrong --modules-dir? the server uses "modules-dir" of plugins/VibeCraft/config.yml)`));
        report.setup.error = 'modules dir not watched by the server';
        return finish(1, 'SETUP_FAILED');
      }
      await waitForGone(api, probe, 20000).catch((e) => report.setup.warnings.push(`setup probe removal: ${e.message}`));
    } catch (e) {
      try { ws.removeModule(probe); } catch { /* ignore */ }
      log(red(`FATAL: setup probe module failed: ${e.message}`));
      report.setup.error = 'setup probe failed: ' + e.message;
      return finish(1, 'SETUP_FAILED');
    }
  }

  // 3. Whitelist + op the bot (before it joins), via the API (RCON fallback)
  const consoleCmd = async (line) => {
    try {
      const r = await api.command(line);
      if (r && r.ok !== false) return (r.output || []).join(' ');
      throw new Error(`ok=false ${short(r)}`);
    } catch (e) {
      if (!cfg.rconDir) throw e;
      return rcon(cfg.rconDir, line);
    }
  };
  for (const line of [`whitelist add ${cfg.username}`, `op ${cfg.username}`]) {
    try { log(dim(`console> ${line}: ${short(await consoleCmd(line), 120)}`)); } catch (e) {
      report.setup.warnings.push(`"${line}" failed: ${e.message}`);
      log(yellow(`"${line}" failed: ${e.message}`));
    }
  }

  // 4. Bot joins
  const connectBot = async () => {
    const b = new VibeBot({ host: cfg.mcHost, port: cfg.mcPort, username: cfg.username, version: cfg.mcVersion, log: (s) => log(dim('  ' + s)) });
    bots.push(b);
    await b.connect({ timeoutMs: 45000 });
    return b;
  };
  try {
    bot = await connectBot();
    log(green(`bot ${cfg.username} joined at ${short(bot.position())}`));
  } catch (e) {
    log(red(`FATAL: bot could not join ${cfg.mcHost}:${cfg.mcPort}: ${e.message}`));
    log(red('  (ViaVersion + ViaBackwards installed? see setup-via.sh; whitelist? online-mode=false?)'));
    report.setup.error = 'bot join failed: ' + e.message;
    return finish(1, 'SETUP_FAILED');
  }
  // op check through the API
  try {
    let op = false;
    for (let i = 0; i < 20 && !op; i++) {
      const ps = await api.players();
      op = !!ps.find((p) => p.name === cfg.username && p.op);
      if (!op) await sleep(250);
    }
    if (!op) { report.setup.warnings.push('bot is not op according to /api/players'); log(yellow('bot is not op according to /api/players')); }
  } catch (e) { report.setup.warnings.push('GET /api/players failed: ' + e.message); }
  // creative: the bot can neither die (mobs at night, fall) nor be pushed around while the scenarios run
  try { log(dim(`console> gamemode creative ${cfg.username}: ${short(await consoleCmd(`gamemode creative ${cfg.username}`), 120)}`)); } catch (e) { report.setup.warnings.push('gamemode creative failed: ' + e.message); }
  await sleep(1500); // let the join settle (chunks, command tree)

  // 4b. A server that has just started reports a low 1-minute TPS for a while (its slow first ticks are part of
  // the 60 s rolling average): wait for it to settle so the global TPS check measures the scenarios, not the boot.
  try {
    let s = await api.status();
    if (s.server.uptimeMs < 180000 && s.server.tps[0] < 19.5) {
      log(dim(`server started ${Math.round(s.server.uptimeMs / 1000)} s ago, 1m TPS ${s.server.tps[0]}: waiting up to 75 s for it to settle (>= 19.5)`));
      const until = Date.now() + 75000;
      while (Date.now() < until && s.server.tps[0] < 19.5) { await sleep(2000); s = await api.status(); }
      report.setup.tpsSettled = { tps1m: s.server.tps[0], uptimeMs: s.server.uptimeMs };
    }
  } catch (e) { report.setup.warnings.push('TPS settle wait: ' + e.message); }

  // 5. Scenarios
  const tpsMon = new TpsMonitor(api, 2000).start();
  const windows = [];
  let serverDown = false;
  for (const sc of selected) {
    const t0 = Date.now();
    const res = { name: sc.name, title: sc.title, status: 'PASS', reason: '', durationMs: 0, warnings: [], steps: [], data: {} };
    log(`\n${bold('▶ ' + sc.name)} ${dim('— ' + sc.title)}`);
    if (serverDown || !(await api.reachable(10000))) {
      serverDown = true;
      res.status = 'FAIL'; res.reason = 'server/API unreachable (crashed?) - not run';
      report.results.push(res); log(red(`  FAIL ${res.reason}`));
      continue;
    }
    if (!bot || !bot.isConnected()) {
      if (!cfg.reconnect) {
        res.status = 'FAIL'; res.reason = 'bot is disconnected (--no-reconnect)';
        report.results.push(res); log(red(`  FAIL ${res.reason}`));
        continue;
      }
      try {
        log(yellow('  bot was disconnected earlier: reconnecting a NEW bot for this scenario (the run is already failed)'));
        runnerEvents.push({ at: Date.now(), type: 'reconnect', before: sc.name });
        bot = await connectBot();
        await sleep(1000);
      } catch (e) {
        res.status = 'FAIL'; res.reason = 'bot reconnect failed: ' + e.message;
        report.results.push(res); log(red(`  FAIL ${res.reason}`));
        continue;
      }
    }
    const disc0 = allDisconnects().length;
    const t = new TestContext({ name: sc.name, api, getBot: () => bot, ws, cfg, run, log });
    let timer;
    try {
      await Promise.race([
        sc.run(t),
        new Promise((_, rej) => { timer = setTimeout(() => { t.aborted = true; rej(new Error(`scenario timeout after ${fmtMs(sc.timeoutMs)}`)); }, sc.timeoutMs); }),
      ]);
    } catch (e) {
      res.status = e instanceof SkipError ? 'SKIP' : 'FAIL';
      res.reason = e instanceof AssertionError || e instanceof SkipError ? e.message : `${e?.name || 'Error'}: ${e?.message || e}`;
      if (!(e instanceof AssertionError) && !(e instanceof SkipError) && e?.stack) res.stack = e.stack.split('\n').slice(0, 8).join('\n');
    } finally {
      clearTimeout(timer);
    }
    if (t.softFailures.length) {
      const soft = `${t.softFailures.length} check(s) failed: ${t.softFailures.join(' ; ')}`;
      res.reason = res.status === 'FAIL' ? `${res.reason} ; ${soft}` : soft;
      res.status = 'FAIL';
    }
    // cleanup (always)
    t.aborted = false;
    const cleanupErrors = [];
    for (const c of t.cleanups.reverse()) {
      try {
        await Promise.race([c.fn(), sleep(30000).then(() => { throw new Error('cleanup timeout (30 s)'); })]);
      } catch (e) { cleanupErrors.push(`${c.label}: ${e.message}`); }
    }
    if (cleanupErrors.length) {
      if (res.status !== 'FAIL') { res.status = 'FAIL'; res.reason = 'cleanup failed: ' + cleanupErrors.join('; '); }
      else t.warnings.push('cleanup failed: ' + cleanupErrors.join('; '));
    }
    const newDisc = allDisconnects().slice(disc0);
    if (newDisc.length) {
      const d = newDisc[0];
      const msg = `bot ${d.type} during the scenario at +${d.at - t0} ms: ${d.reason}`;
      res.reason = res.status === 'FAIL' ? `${msg}; ${res.reason}` : msg;
      res.status = 'FAIL';
    }
    const t1 = Date.now();
    windows.push({ name: sc.name, from: t0, to: t1 });
    res.durationMs = t1 - t0;
    res.warnings = t.warnings;
    res.steps = t.steps;
    res.data = t.data;
    res.tpsMin1m = tpsMon.minBetween(t0, t1 + 2000);
    res.client = bot ? { ...bot.tickStats(t0, t1), stallMs: t.stallMs(t0, t1), longestGaps: bot.timeGaps(t0, t1 + 1500, 3) } : null;
    report.results.push(res);
    const tag = res.status === 'PASS' ? green('PASS') : res.status === 'SKIP' ? yellow('SKIP') : red('FAIL');
    log(`  ${tag} ${sc.name} in ${fmtMs(res.durationMs)}${res.reason ? ' — ' + res.reason : ''}${res.warnings.length ? yellow(` (${res.warnings.length} warning(s))`) : ''}`);
    if (cfg.bail && res.status === 'FAIL') { log(yellow('--bail: stopping after the first failure')); break; }
  }
  await sleep(2500); // one more TPS sample after the last scenario
  await tpsMon.stop();

  // 6. Global checks
  const scenarioAt = (at) => windows.find((w) => at >= w.from && at <= w.to + 2000)?.name ?? 'between scenarios';
  {
    const s = tpsMon.samples;
    const g = { name: 'tps', title: `1m TPS (GET /api/status) stays > ${cfg.minTps}`, status: 'PASS', reason: '', durationMs: 0, warnings: [], data: {} };
    if (!s.length) { g.status = 'FAIL'; g.reason = `no TPS sample (${tpsMon.errors.length} status errors: ${short(tpsMon.errors.slice(-2))})`; }
    else {
      const min = s.reduce((a, b) => (b.tps1m < a.tps1m ? b : a));
      g.data = { samples: s.length, min1m: min.tps1m, minAt: new Date(min.at).toISOString(), minDuring: scenarioAt(min.at), statusErrors: tpsMon.errors.length, maxStatusMs: Math.max(...s.map((x) => x.apiMs)) };
      if (!(min.tps1m > cfg.minTps)) { g.status = 'FAIL'; g.reason = `1m TPS fell to ${min.tps1m} during ${scenarioAt(min.at)}`; }
      else g.reason = `min ${min.tps1m} over ${s.length} samples`;
      if (tpsMon.errors.length) g.warnings.push(`${tpsMon.errors.length} GET /api/status failures during the run (last: ${tpsMon.errors.at(-1).error})`);
    }
    const clientMin = bots.length ? Math.min(...bots.map((b) => b.minWindowTps(10000) ?? 20)) : null;
    g.data.clientMinTps10s = clientMin;
    report.global.tps = { samples: s, errors: tpsMon.errors };
    report.results.push(g);
  }
  {
    const d = allDisconnects();
    const g = { name: 'bot-connected', title: 'bot never kicked / disconnected', status: d.length ? 'FAIL' : 'PASS', reason: '', durationMs: 0, warnings: [], data: { disconnects: d } };
    g.reason = d.length ? d.map((x) => `${x.type} at ${new Date(x.at).toISOString()} during ${scenarioAt(x.at)}: ${x.reason}`).join('; ') : `connected for ${fmtMs(Date.now() - bots[0].spawnedAt)}`;
    report.results.push(g);
  }

  // 7. Summary
  log('\n' + bold('Summary'));
  const rows = report.results.map((r, i) => [String(i + 1), r.name, r.status, fmtMs(r.durationMs), (r.reason || '').replace(/\s*\n\s*/g, ' ⏎ ') + (r.warnings?.length ? ` [${r.warnings.length} warn]` : '')]);
  const w = [3, Math.max(8, ...rows.map((r) => r[1].length)), 6, 8];
  log(dim(`${'#'.padStart(w[0])}  ${'Scenario'.padEnd(w[1])}  ${'Result'.padEnd(w[2])}  ${'Time'.padStart(w[3])}  Details`));
  for (const r of rows) {
    const st = r[2] === 'PASS' ? green(r[2].padEnd(w[2])) : r[2] === 'SKIP' ? yellow(r[2].padEnd(w[2])) : red(r[2].padEnd(w[2]));
    log(`${r[0].padStart(w[0])}  ${r[1].padEnd(w[1])}  ${st}  ${r[3].padStart(w[3])}  ${r[4].length > 140 ? r[4].slice(0, 140) + '…' : r[4]}`);
  }
  const failed = report.results.filter((r) => r.status === 'FAIL');
  const skipped = report.results.filter((r) => r.status === 'SKIP');
  const passed = report.results.length - failed.length - skipped.length;
  report.counts = { passed, failed: failed.length, skipped: skipped.length };
  log(bold(`\n${passed}/${report.results.length - skipped.length} passed`)
    + (skipped.length ? yellow(` (${skipped.length} skipped: ${skipped.map((f) => f.name).join(', ')})`) : '')
    + (failed.length ? red(` — ${failed.length} failed: ${failed.map((f) => f.name).join(', ')}`) : green(' — all green')));
  return finish(failed.length ? 1 : 0, failed.length ? 'FAILED' : 'PASSED');
}

function safe(fn) { try { return fn(); } catch { return null; } }

main().then((code) => process.exit(code), (e) => {
  console.error('runner crashed:', e?.stack || e);
  process.exit(1);
});
