#!/usr/bin/env node
// Simulates a realistic live demo session: the VibeBot stays online while 4 "AI sessions" edit 4 modules
// concurrently every 5-20 s (good versions, compile errors, half-written saves, runtime exceptions, an infinite
// loop in a command, in enable() and in a per-tick task, an endless async loop, config.yml tweaks), each followed
// by the AI workflow (POST /reload) or only by the file watcher. Meanwhile /api/status is polled every second.
// Records TPS / MSPT, reload latencies per kind, bot command round trips, client-side tick gaps and checks that
// the bot never disconnects and that every good version answers exactly once (no duplicate listeners).
//
//   cd tests/e2e && node tools/live-session.mjs [--duration-s 600] [--api http://127.0.0.1:8765] [--mc-port 25565]
// Module ids: live-alpha, live-beta, live-gamma, live-delta (deleted at the end unless --keep).
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { VibeBot, sleep } from '../lib/bot.mjs';
import { VibeApi } from '../lib/api.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, '..', '..', '..');
const argv = process.argv.slice(2);
const opt = (name, def) => { const i = argv.indexOf(name); return i >= 0 ? argv[i + 1] : def; };
const API = opt('--api', 'http://127.0.0.1:8765');
const MC_PORT = Number(opt('--mc-port', '25565'));
const DURATION_MS = Number(opt('--duration-s', '600')) * 1000;
const MODULES = path.resolve(opt('--modules-dir', path.join(ROOT, 'workspace', 'modules')));
const DATA = path.resolve(opt('--data-dir', path.join(MODULES, '..', 'data')));
const KEEP = argv.includes('--keep');
const USER = opt('--username', 'VibeBot');
const SEED = Number(opt('--seed', String(Date.now() % 100000)));
const RUN = Math.random().toString(36).slice(2, 7);

// deterministic PRNG (mulberry32) so a run can be replayed with --seed
let s0 = SEED >>> 0;
const rnd = () => { s0 = (s0 + 0x6D2B79F5) >>> 0; let t = s0; t = Math.imul(t ^ (t >>> 15), t | 1); t ^= t + Math.imul(t ^ (t >>> 7), t | 61); return ((t ^ (t >>> 14)) >>> 0) / 4294967296; };
const rand = (a, b) => Math.round(a + rnd() * (b - a));

const api = new VibeApi({ base: API, timeoutMs: 15000 });
const t0 = Date.now();
const rel = (t = Date.now()) => ((t - t0) / 1000).toFixed(1);
const log = (...a) => console.log(`[${rel().padStart(6)} s]`, ...a);

const NAMES = ['alpha', 'beta', 'gamma', 'delta'];
const CYCLE = ['good', 'compile-error', 'good', 'runtime-exception', 'good', 'half-written', 'loop-command', 'good',
  'config', 'syntax-error', 'good', 'async-loop', 'good'];
const SEQ = [...CYCLE, 'loop-enable', 'good', ...CYCLE, 'loop-task', 'good', ...CYCLE, ...CYCLE];

const mods = NAMES.map((n, i) => ({
  name: n, id: `live-${n}`, cmd: `l${n}`, pkg: `vibe.live_${n}`, dir: path.join(MODULES, `live-${n}`),
  ver: 0, goodMark: null, note: 'none', seqOffset: i * 3, edits: [],
}));

function source(m, mark, kind) {
  let faultCmd = ''; let faultTask = ''; let faultEnable = '';
  switch (kind) {
    case 'runtime-exception':
      faultCmd = `if (args.length == 0) throw new IllegalStateException("LIVE-BOOM ${mark}");`;
      faultTask = 'if (ticks.get() % 7 == 3) { String s = null; s.length(); }';
      break;
    case 'loop-command':
      faultCmd = 'if (args.length == 0) { boolean spin = true; long x = 0; while (spin) { x++; } }';
      break;
    case 'loop-enable':
      faultEnable = 'boolean spin = true; long y = 0; while (spin) { y++; }';
      break;
    case 'loop-task':
      faultEnable = 'ctx.every(1, () -> { boolean spin = true; long z = 0; while (spin) { z++; } });';
      break;
    case 'async-loop':
      faultEnable = 'ctx.async(() -> { long n = 0; while (true) { n++; try { Thread.sleep(20); } catch (InterruptedException ie) { return; } } });';
      break;
    default:
  }
  let src = `package ${m.pkg};

import dev.vibecraft.api.ModuleContext;
import dev.vibecraft.api.VibeModule;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Bukkit;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

/** live-session test module ${m.id}, edit ${mark} (${kind}) */
public class LiveModule implements VibeModule {

    @Override
    public void enable(ModuleContext ctx) {
        AtomicInteger hits = new AtomicInteger();
        AtomicInteger ticks = new AtomicInteger();
        String note = ctx.config().getString("note", "none");
        ctx.on(PlayerCommandPreprocessEvent.class, e -> {
            if (e.getMessage().startsWith("/${m.cmd}")) {
                hits.incrementAndGet();
            }
        });
        ctx.command("${m.cmd}", (sender, args) -> {
            ${faultCmd}
            sender.sendRichMessage("<green>LIVE ${m.id} ${mark} hits=" + hits.get() + " ticks=" + ticks.get() + " note=" + note);
        });
        ctx.every(10, () -> {
            ticks.incrementAndGet();
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, p.getLocation().add(0, 2.3, 0), 1);
            }
            ${faultTask}
        });
        ${faultEnable}
        ctx.logger().info("${m.id} ${mark} enabled");
    }
}
`;
  if (kind === 'compile-error') src = src.replace('hits.get()', 'hits.getValue()');
  if (kind === 'syntax-error') src = src.replace('ticks.incrementAndGet();', 'ticks.incrementAndGet()');
  return src;
}

const writeJava = (m, text) => fs.writeFileSync(path.join(m.dir, 'LiveModule.java'), text);

// ------------------------------------------------------------------------------------------- metrics
const statusSamples = [];   // {t, tps1m, mspt, apiMs, states}
const msptSamples = [];     // Paper /mspt every 5 s: {t, avg5s, max5s, max1m}
const anomalies = [];       // unexpected results (the verdict)
const edits = [];           // every edit with its measurements
const cmdRtts = [];         // bot command round trips for good versions
const anomaly = (m, what, detail) => { anomalies.push({ t: rel(), module: m?.id, what, detail: String(detail).slice(0, 500) }); log(`!! ${m?.id ?? ''} ${what}: ${String(detail).slice(0, 300)}`); };

let stopPolling = false;
async function poller() {
  let n = 0;
  while (!stopPolling) {
    const t = Date.now();
    if (n++ % 5 === 0) {
      // Paper /mspt: max tick time over the last 5 s (catches stalls that the 1-min TPS average hides)
      api.command('mspt').then((r) => {
        const x = (r.output || []).join(' ').match(/([\d.]+)\/([\d.]+)\/([\d.]+), ([\d.]+)\/([\d.]+)\/([\d.]+), ([\d.]+)\/([\d.]+)\/([\d.]+)/);
        if (x) msptSamples.push({ t, avg5s: +x[1], max5s: +x[3], max1m: +x[9] });
      }).catch(() => {});
    }
    try {
      const r = await api.raw('GET', '/api/status', null, { timeoutMs: 5000 });
      const s = r.body;
      statusSamples.push({
        t, apiMs: r.ms, tps1m: s?.server?.tps?.[0], mspt: s?.server?.mspt, online: s?.server?.online,
        states: Object.fromEntries((s?.modules || []).filter((x) => x.id.startsWith('live-')).map((x) => [x.id, `${x.state}/${x.generation}`])),
      });
      if (r.ms > 1000) anomaly(null, 'slow /api/status', `${r.ms} ms`);
    } catch (e) {
      statusSamples.push({ t, apiMs: Date.now() - t, error: e.message });
      anomaly(null, '/api/status failed', e.message);
    }
    const wait = 1000 - (Date.now() - t);
    if (wait > 0) await sleep(wait);
  }
}

// ------------------------------------------------------------------------------------------- bot checks
const lineRe = (m) => new RegExp(`LIVE ${m.id} (\\S+) hits=(\\d+) ticks=(\\d+) note=(\\S+)`);
async function runCmd(m, windowMs = 4000, until = null) {
  until ??= new RegExp(`LIVE ${m.id} |Erreur dans /${m.cmd}|n'est plus disponible|Unknown or incomplete`);
  const r = await bot.cmd('/' + m.cmd, { until, windowMs, settleMs: 400 });
  const mine = r.lines.filter((l) => l.includes(`LIVE ${m.id} `) || l.includes(`/${m.cmd}`) || l.includes(`module ${m.id}`));
  return { ...r, mine };
}

/** Expect the current good version to answer exactly once per command, with one listener (hits +1). */
async function expectGood(m, why) {
  const re = lineRe(m);
  if (!m.goodMark) return true; // the current version is a faulty one on purpose: nothing to compare
  const a = await runCmd(m, 4000, re);
  const b = await runCmd(m, 4000, re);
  const la = a.mine.filter((l) => re.test(l)); const lb = b.mine.filter((l) => re.test(l));
  if (a.firstMatchMs != null) cmdRtts.push(a.firstMatchMs);
  if (b.firstMatchMs != null) cmdRtts.push(b.firstMatchMs);
  if (la.length !== 1 || lb.length !== 1) { anomaly(m, `${why}: expected exactly one reply`, `a=${JSON.stringify(a.mine)} b=${JSON.stringify(b.mine)}`); return false; }
  const [, markA, hitsA, , noteA] = la[0].match(re); const [, markB, hitsB] = lb[0].match(re);
  if (markA !== m.goodMark || markB !== m.goodMark) { anomaly(m, `${why}: wrong version answers`, `got ${markA}/${markB}, expected ${m.goodMark}`); return false; }
  if (Number(hitsB) - Number(hitsA) !== 1) { anomaly(m, `${why}: listener count`, `hits ${hitsA} -> ${hitsB} (duplicate or missing listener)`); return false; }
  if (noteA !== m.note) { anomaly(m, `${why}: config note`, `got ${noteA}, expected ${m.note}`); return false; }
  return { rtt: a.firstMatchMs };
}

// ------------------------------------------------------------------------------------------- edits
async function waitGenChange(m, gen0, attempt0, timeoutMs = 15000) {
  const end = Date.now() + timeoutMs;
  while (Date.now() < end) {
    const info = await api.module(m.id);
    // lastAttemptAt is set when the swap starts, while state is still COMPILING: wait for a settled state
    if (info && (info.generation !== gen0 || info.lastAttemptAt !== attempt0) && !['COMPILING', 'DISCOVERED'].includes(info.state)) return info;
    await sleep(150);
  }
  return null;
}

async function doEdit(m, kind) {
  m.ver++;
  const mark = `${RUN}.${m.name}.e${m.ver}`;
  const before = await api.module(m.id);
  const e = { t: rel(), module: m.id, kind, mark, genBefore: before?.generation, stateBefore: before?.state };
  edits.push(e);
  const tw = Date.now();
  try {
    if (kind === 'config') {
      // presenter tweaks config.yml in an editor: only the watcher reloads
      m.note = `n${m.ver}`;
      fs.writeFileSync(path.join(m.dir, 'config.yml'), `note: ${m.note}\n`);
      const info = await waitGenChange(m, before?.generation, before?.lastAttemptAt);
      e.watcherMs = Date.now() - tw;
      e.result = info && { ok: !info.lastError, state: info.state, gen: info.generation };
      if (!info || info.state !== 'ENABLED' || info.generation <= (before?.generation ?? 0)) anomaly(m, 'config reload via watcher', JSON.stringify(info && { state: info.state, gen: info.generation, err: info.lastError }));
      else await expectGood(m, 'after config edit');
      return;
    }
    if (kind === 'half-written') {
      const full = source(m, mark, 'good');
      writeJava(m, full.slice(0, Math.floor(full.length * (0.4 + rnd() * 0.3))));
      await sleep(rand(300, 1500));
      writeJava(m, full);
    } else {
      writeJava(m, source(m, mark, kind));
    }
    const tr = Date.now();
    const r = await api.reload(m.id);
    e.apiWallMs = Date.now() - tr;
    e.result = { ok: r.ok, state: r.state, gen: r.generation, durationMs: r.durationMs, rolledBack: r.rolledBack, error: r.error?.slice(0, 160) };
    const goodKinds = ['good', 'half-written', 'async-loop'];
    if (goodKinds.includes(kind)) {
      if (!r.ok || r.state !== 'ENABLED') { anomaly(m, `${kind} reload not ok`, JSON.stringify(e.result)); return; }
      m.goodMark = mark;
      const g = await expectGood(m, `after ${kind}`);
      if (g) e.cmdRttMs = g.rtt;
    } else if (kind === 'compile-error' || kind === 'syntax-error') {
      if (r.ok || r.state !== before?.state || r.generation !== before?.generation || !/compilation failed/.test(r.error || '')) anomaly(m, `${kind} should be refused`, JSON.stringify(e.result));
      if (before?.state === 'ENABLED') await expectGood(m, `after ${kind} (old version must keep running)`);
    } else if (kind === 'runtime-exception') {
      if (!r.ok) { anomaly(m, 'runtime-exception version should load', JSON.stringify(e.result)); return; }
      m.goodMark = null; // its command throws
      const c = await runCmd(m);
      e.cmdRttMs = c.firstMatchMs;
      if (!c.mine.some((l) => /Erreur dans \/l/.test(l))) anomaly(m, 'runtime exception not reported to the sender', JSON.stringify(c.mine));
    } else if (kind === 'loop-command') {
      if (!r.ok) { anomaly(m, 'loop-command version should load', JSON.stringify(e.result)); return; }
      m.goodMark = null;
      const from = Date.now();
      const c = await runCmd(m, 6000, new RegExp(`/${m.cmd} a été interrompue|Erreur dans /${m.cmd}`));
      e.cmdRttMs = c.firstMatchMs;
      e.clientMaxGapMs = bot.tickStats(from - 200, Date.now()).maxGapMs;
      if (!c.matched || (c.firstMatchMs ?? 1e9) > 2500) anomaly(m, 'infinite loop in command not interrupted in time', `${c.firstMatchMs} ms ${JSON.stringify(c.mine)}`);
    } else if (kind === 'loop-enable') {
      e.clientMaxGapMs = bot.tickStats(tr - 200, Date.now()).maxGapMs;
      if (r.ok || !r.rolledBack) anomaly(m, 'infinite loop in enable() should roll back', JSON.stringify(e.result));
      if (before?.state === 'ENABLED' && m.goodMark) await expectGood(m, 'after loop-enable rollback');
    } else if (kind === 'loop-task') {
      if (!r.ok) { anomaly(m, 'loop-task version should load', JSON.stringify(e.result)); return; }
      m.goodMark = null;
      const tl = Date.now();
      let info = null;
      while (Date.now() - tl < 30000) {
        info = await api.module(m.id);
        if (info?.state === 'TRIPPED') break;
        await sleep(250);
      }
      e.tripMs = Date.now() - tl;
      e.clientMaxGapMs = bot.tickStats(tl - 200, Date.now()).maxGapMs;
      e.clientTpsDuringLoop = bot.tickStats(tl, Date.now()).tps;
      if (info?.state !== 'TRIPPED') anomaly(m, 'per-tick infinite loop task should trip the circuit breaker', JSON.stringify(info && { state: info.state, errors: info.runtimeErrorCount }));
    }
  } catch (err) {
    e.error = err.message;
    anomaly(m, `edit ${kind} threw`, err.stack);
  } finally {
    e.ms = Date.now() - tw;
    log(`${m.id.padEnd(10)} ${kind.padEnd(17)} ${JSON.stringify(e.result ?? e.error ?? '')}${e.apiWallMs != null ? ' wall=' + e.apiWallMs : ''}${e.watcherMs != null ? ' watcher=' + e.watcherMs : ''}${e.tripMs != null ? ' trip=' + e.tripMs : ''}${e.clientMaxGapMs != null ? ' gap=' + e.clientMaxGapMs : ''}${e.cmdRttMs != null ? ' rtt=' + e.cmdRttMs : ''}`);
  }
}

async function editor(m, endAt) {
  await sleep(rand(0, 6000));
  let i = m.seqOffset;
  while (Date.now() < endAt) {
    await doEdit(m, SEQ[i % SEQ.length]);
    i++;
    await sleep(rand(5000, 20000));
  }
  await doEdit(m, 'good'); // the session ends with a working version
}

// ------------------------------------------------------------------------------------------- main
let bot;
const pct = (arr, p) => { if (!arr.length) return null; const s = [...arr].sort((a, b) => a - b); return s[Math.min(s.length - 1, Math.floor(p / 100 * s.length))]; };
const stats = (arr) => arr.length ? { n: arr.length, min: Math.min(...arr), p50: pct(arr, 50), p95: pct(arr, 95), max: Math.max(...arr), avg: +(arr.reduce((a, b) => a + b, 0) / arr.length).toFixed(1) } : { n: 0 };
let exitCode = 0;
try {
  for (const m of mods) if (fs.existsSync(m.dir)) { console.error(`${m.dir} exists: refusing`); process.exit(2); }
  log(`seed=${SEED} run=${RUN} duration=${DURATION_MS / 1000}s`);
  // the API refuses admin commands (403): RCON with --rcon-dir <serverDir>
  for (const line of [`whitelist add ${USER}`, `op ${USER}`]) {
    try { await api.command(line); } catch (e) {
      const dir = (() => { const i = process.argv.indexOf('--rcon-dir'); return i >= 0 ? process.argv[i + 1] : null; })();
      if (dir) { const { rcon } = await import('../lib/rcon.mjs'); await rcon(dir, line); }
    }
  }
  bot = new VibeBot({ port: MC_PORT, username: USER, log: (l) => log(l) });
  await bot.connect();
  await api.command(`gamemode creative ${USER}`);
  log('bot online at', JSON.stringify(bot.position()));

  // initial versions of the 4 modules, created at the same time (watcher loads them)
  for (const m of mods) {
    m.ver++;
    m.goodMark = `${RUN}.${m.name}.e${m.ver}`;
    fs.mkdirSync(m.dir, { recursive: true });
    fs.writeFileSync(path.join(m.dir, 'module.yml'), `description: "live-session test module ${m.name}"\n`);
    writeJava(m, source(m, m.goodMark, 'good'));
  }
  const tl = Date.now();
  for (const m of mods) {
    let info = null;
    while (Date.now() - tl < 30000) { info = await api.module(m.id); if (info?.state === 'ENABLED') break; await sleep(200); }
    if (info?.state !== 'ENABLED') anomaly(m, 'initial load via watcher', JSON.stringify(info));
  }
  log(`4 modules loaded by the watcher in ${Date.now() - tl} ms`);
  for (const m of mods) await expectGood(m, 'initial');

  const pollerDone = poller();
  const sessionStart = Date.now();
  const endAt = sessionStart + DURATION_MS;
  const progress = setInterval(() => {
    const last = statusSamples.at(-1);
    log(`-- tps1m=${last?.tps1m} mspt=${last?.mspt} edits=${edits.length} anomalies=${anomalies.length} bot=${bot.isConnected() ? 'online' : 'OFFLINE'}`);
  }, 30000);
  await Promise.all(mods.map((m) => editor(m, endAt)));
  clearInterval(progress);
  const sessionEnd = Date.now();
  await sleep(2000);
  stopPolling = true;
  await pollerDone;

  // final verification
  for (const m of mods) {
    const info = await api.module(m.id);
    if (info?.state !== 'ENABLED') anomaly(m, 'final state', JSON.stringify(info && { state: info.state, err: info.lastError }));
    else await expectGood(m, 'final');
  }
  if (bot.disconnects.length || !bot.isConnected()) anomaly(null, 'bot disconnected', JSON.stringify(bot.disconnects));

  // ------------------------------------------------------------------ summary
  const ok = statusSamples.filter((x) => x.tps1m != null && x.t >= sessionStart && x.t <= sessionEnd);
  const byKind = {};
  for (const e of edits) {
    const k = (byKind[e.kind] ??= { count: 0, apiWallMs: [], durationMs: [], watcherMs: [], tripMs: [], clientMaxGapMs: [], cmdRttMs: [] });
    k.count++;
    if (e.apiWallMs != null) k.apiWallMs.push(e.apiWallMs);
    if (e.result?.durationMs != null) k.durationMs.push(e.result.durationMs);
    if (e.watcherMs != null) k.watcherMs.push(e.watcherMs);
    if (e.tripMs != null) k.tripMs.push(e.tripMs);
    if (e.clientMaxGapMs != null) k.clientMaxGapMs.push(e.clientMaxGapMs);
    if (e.cmdRttMs != null) k.cmdRttMs.push(e.cmdRttMs);
  }
  const kinds = Object.fromEntries(Object.entries(byKind).map(([k, v]) => [k, {
    count: v.count, apiWallMs: stats(v.apiWallMs), durationMs: stats(v.durationMs),
    ...(v.watcherMs.length ? { watcherMs: stats(v.watcherMs) } : {}), ...(v.tripMs.length ? { tripMs: stats(v.tripMs) } : {}),
    ...(v.clientMaxGapMs.length ? { clientMaxGapMs: stats(v.clientMaxGapMs) } : {}), ...(v.cmdRttMs.length ? { cmdRttMs: stats(v.cmdRttMs) } : {}),
  }]));
  const finalInfo = await api.modules();
  const summary = {
    seed: SEED, run: RUN, sessionSeconds: Math.round((sessionEnd - sessionStart) / 1000), edits: edits.length,
    tps1m: stats(ok.map((x) => x.tps1m)), mspt: stats(ok.map((x) => x.mspt)),
    paperMaxTickMs5s: stats(msptSamples.filter((x) => x.t >= sessionStart && x.t <= sessionEnd).map((x) => x.max5s)),
    ticksOver250ms: msptSamples.filter((x) => x.max5s > 250).map((x) => ({ t: rel(x.t), max5s: x.max5s })), statusApiMs: stats(statusSamples.map((x) => x.apiMs)),
    statusPollErrors: statusSamples.filter((x) => x.error).length, statusSamples: statusSamples.length,
    botDisconnects: bot.disconnects.length, botConnectedSeconds: Math.round((Date.now() - bot.spawnedAt) / 1000),
    client: { ...bot.tickStats(sessionStart, sessionEnd), minWindowTps10s: bot.minWindowTps(10000, sessionStart, sessionEnd), worstGaps: bot.timeGaps(sessionStart, sessionEnd, 8) },
    goodCmdRttMs: stats(cmdRtts), kinds,
    modules: finalInfo.filter((x) => x.id.startsWith('live-')).map((x) => ({ id: x.id, state: x.state, generation: x.generation, runtimeErrorCount: x.runtimeErrorCount, msPerTick: x.msPerTick })),
    anomalies,
  };
  console.log('\n===== SUMMARY =====');
  console.log(JSON.stringify(summary, null, 2));
  const dir = path.join(ROOT, 'tests', 'e2e', 'reports');
  fs.mkdirSync(dir, { recursive: true });
  const file = path.join(dir, `live-${new Date(t0).toISOString().replace(/[:.]/g, '-')}.json`);
  fs.writeFileSync(file, JSON.stringify({ summary, edits, statusSamples, msptSamples, botEvents: bot.events, lastChat: bot.messages.slice(-200).map((m) => `${new Date(m.at).toISOString()} ${m.text}`) }, null, 1));
  console.log('report:', file);
  exitCode = anomalies.length || summary.botDisconnects ? 1 : 0;
} catch (e) {
  console.error('live-session crashed:', e.stack);
  exitCode = 1;
} finally {
  stopPolling = true;
  if (!KEEP) {
    for (const m of mods) {
      fs.rmSync(m.dir, { recursive: true, force: true });
      fs.rmSync(path.join(DATA, m.id), { recursive: true, force: true });
    }
    // wait for the unload so the next tool starts clean
    for (let i = 0; i < 50; i++) {
      const list = await api.modules().catch(() => []);
      if (!list.some((x) => x.id.startsWith('live-'))) break;
      await sleep(200);
    }
  }
  try { await bot?.quit('live-session done'); } catch { /* ignore */ }
  process.exit(exitCode);
}
