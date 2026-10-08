#!/usr/bin/env node
// VibeCraft proxy safety-net test.
//
// Bots connect THROUGH Velocity to a test "main" backend. The main backend is then hard-killed
// (TerminateProcess on the JVM we started - never any other process) or stopped gracefully
// ("stop" over RCON), restarted, and we check that:
//   * no bot is ever disconnected ('end' / 'kicked' never fire),
//   * every bot lands in the limbo backend (seen by the limbo's own "list"),
//   * the bots received the "Le serveur redémarre…" title,
//   * once main is ready again ("Done (" in its log), every bot is back on main (seen by main's
//     "list") within --max-return-s seconds, and is functional there (receives a /msg).
//
// Usage: node failover-test.mjs [--rounds 3] [--bots 2] [--modes kill,stop] [--start-proxy]
//          [--stop-proxy] [--supervisor] [--max-return-s 10] [--freeze-s 25] [--idle-s 15] [--no-extras]
// Prerequisite: proxy/test/setup-test-main.ps1 (creates the throwaway backend proxy/test-main).
// Defaults match the private test ports: Velocity 25577, main 25581 (rcon 25591), limbo 25582 (rcon 25592).
import { spawn, execFile } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import mineflayer from 'mineflayer';
import { ping } from './lib/mcping.mjs';
import { applyTickEndFix } from './lib/tick-end-fix.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const PROXY = path.resolve(HERE, '..');
const ROOT = path.resolve(PROXY, '..');

const args = process.argv.slice(2);
const opt = (name, def) => {
  const i = args.indexOf('--' + name);
  if (i < 0) return def;
  const v = args[i + 1];
  return v === undefined || v.startsWith('--') ? true : v;
};
const CFG = {
  rounds: Number(opt('rounds', 3)),
  bots: Number(opt('bots', 2)),
  modes: String(opt('modes', 'kill,stop')).split(','),
  proxyHost: '127.0.0.1',
  proxyPort: Number(opt('proxy-port', 25577)),
  mainDir: path.resolve(String(opt('main-dir', path.join(PROXY, 'test-main')))),
  limboDir: path.resolve(String(opt('limbo-dir', path.join(PROXY, 'limbo')))),
  mainXmx: String(opt('main-xmx', '1536M')),
  downtimeS: Number(opt('downtime-s', 4)),
  maxReturnS: Number(opt('max-return-s', 10)),
  maxToLimboS: Number(opt('max-to-limbo-s', 20)),
  startProxy: !!opt('start-proxy', false),
  stopProxy: !!opt('stop-proxy', false),
  supervisor: !!opt('supervisor', false), // let run-main-forever.ps1 restart main instead of the test
  version: String(opt('version', '26.1')),
  noExtras: !!opt('no-extras', false),
  freezeS: Number(opt('freeze-s', 25)),
  idleS: Number(opt('idle-s', 15)), // idle time on main checked for false "frozen server" evacuations
  ps: String(opt('ps', 'pwsh')), // PowerShell used for the .ps1 scripts (pwsh or powershell)
};
const JAVA = path.join(ROOT, 'runtime', 'jdk-25', 'bin', process.platform === 'win32' ? 'java.exe' : 'java');
const RCON = path.join(ROOT, 'tests', 'tools', 'rcon.mjs');
const LOG_DIR = path.join(HERE, 'logs');
const VELOCITY_LOG = path.join(PROXY, 'velocity', 'logs', 'latest.log');
const REPORT_DIR = path.join(HERE, 'reports');
fs.mkdirSync(LOG_DIR, { recursive: true });
fs.mkdirSync(REPORT_DIR, { recursive: true });

const T0 = Date.now();
const ts = () => ((Date.now() - T0) / 1000).toFixed(1).padStart(6) + 's';
const log = (...a) => console.log(ts(), ...a);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ------------------------------------------------------------------ helpers

function rcon(dir, ...cmds) {
  return new Promise((resolve) => {
    execFile(process.execPath, [RCON, '--dir', dir, ...cmds], { timeout: 20000 }, (err, stdout, stderr) => {
      resolve({ ok: !err, out: String(stdout || ''), err: String(stderr || (err && err.message) || '') });
    });
  });
}

async function listPlayers(dir) {
  const r = await rcon(dir, 'list');
  if (!r.ok) return null; // server down
  const m = r.out.match(/online:\s*(.*)$/m);
  if (!m) return [];
  return m[1].split(',').map((s) => s.trim()).filter(Boolean);
}

async function waitFor(desc, fn, timeoutMs, everyMs = 250) {
  const start = Date.now();
  for (;;) {
    let v;
    try { v = await fn(); } catch { v = false; }
    if (v) return { ok: true, ms: Date.now() - start, value: v };
    if (Date.now() - start > timeoutMs) return { ok: false, ms: Date.now() - start, desc };
    await sleep(everyMs);
  }
}

function fileHas(file, re, fromOffset = 0) {
  try {
    const buf = fs.readFileSync(file);
    return re.test(buf.subarray(fromOffset).toString('utf8'));
  } catch { return false; }
}

function fileSize(file) {
  try { return fs.statSync(file).size; } catch { return 0; }
}

function fileFrom(file, offset) {
  try { const b = fs.readFileSync(file); return b.subarray(Math.min(offset, b.length)).toString('utf8'); } catch { return ''; }
}

const count = (text, re) => (text.match(re) || []).length;

// What Velocity itself logged since `offset`: server switches per bot, kicks from limbo, failed returns.
function velocityAudit(offset) {
  const text = fileFrom(VELOCITY_LOG, offset);
  return {
    perBot: bots.map((b) => ({
      name: b.name,
      toLimbo: count(text, new RegExp(`\] ${b.name} -> limbo has connected`, 'g')),
      toMain: count(text, new RegExp(`\] ${b.name} -> main has connected`, 'g')),
    })),
    limboKicks: count(text, /kicked from server limbo/g),
    failedReturns: count(text, /vers 'main' impossible/g),
    errors: count(text, /\[vibereconnect\]: Erreur|ERROR\] \[vibereconnect\]/g),
  };
}

function isAlive(pid) {
  try { process.kill(pid, 0); return true; } catch { return false; }
}

// ------------------------------------------------------------------ main backend control

let mainProc = null;
let mainRun = 0;
let mainLogFile = null;
let supervisorProc = null;
const SUPERVISOR_PID_NAME = 'testmain';

function supervisorPidFile() {
  return path.join(PROXY, 'run', SUPERVISOR_PID_NAME + '.pid');
}

function readSupervisedJavaPid() {
  try { return Number(fs.readFileSync(supervisorPidFile(), 'utf8').split('\n')[0].trim()) || null; } catch { return null; }
}

async function startMain() {
  mainRun++;
  if (CFG.supervisor) {
    // run-main-forever.ps1 restarts by itself; just wait for a new JVM to be up
    return;
  }
  mainLogFile = path.join(LOG_DIR, `main-run${mainRun}.log`);
  const fd = fs.openSync(mainLogFile, 'w');
  mainProc = spawn(JAVA, [`-Xms512M`, `-Xmx${CFG.mainXmx}`, '-jar', 'paper.jar', '--nogui'], {
    cwd: CFG.mainDir, stdio: ['ignore', fd, fd], windowsHide: true,
  });
  fs.closeSync(fd);
  mainProc.exitCodeSeen = null;
  mainProc.on('exit', (code) => { if (mainProc) mainProc.exitCodeSeen = code; });
  log(`main: started JVM pid=${mainProc.pid} (run ${mainRun}), log ${path.relative(HERE, mainLogFile)}`);
}

async function startSupervisor() {
  const ps = CFG.ps;
  supervisorProc = spawn(ps, ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', path.join(PROXY, 'run-main-forever.ps1'),
    '-ServerDir', CFG.mainDir, '-Xms', '512M', '-Xmx', CFG.mainXmx, '-PidName', SUPERVISOR_PID_NAME, '-Hidden',
    '-StopFile', path.join(PROXY, 'run', 'stop-testmain.flag'), '-RestartDelaySec', '1'], { stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true });
  const out = fs.createWriteStream(path.join(LOG_DIR, 'supervisor.log'));
  supervisorProc.stdout.pipe(out);
  supervisorProc.stderr.pipe(out);
  log(`supervisor: run-main-forever.ps1 started (pid ${supervisorProc.pid})`);
}

function currentMainPid() {
  return CFG.supervisor ? readSupervisedJavaPid() : (mainProc && mainProc.pid);
}

function currentMainLog() {
  return CFG.supervisor ? path.join(PROXY, 'run', SUPERVISOR_PID_NAME + '.log') : mainLogFile;
}

async function waitMainReady(prevPid = null, timeoutMs = 120000) {
  // ready = "Done (" in the log of the current JVM (with the supervisor: a NEW JVM, the log file is
  // truncated when it starts, before its PID file is written)
  const r = await waitFor('main Done', async () => {
    if (CFG.supervisor) {
      const pid = readSupervisedJavaPid();
      if (!pid || pid === prevPid || !isAlive(pid)) return false;
    }
    const lf = currentMainLog();
    return !!lf && fileHas(lf, /Done \(\d/);
  }, timeoutMs, 200);
  return r;
}

async function killMain() {
  const pid = currentMainPid();
  if (!pid) throw new Error('no main pid');
  log(`main: HARD KILL pid=${pid}`);
  process.kill(pid, 'SIGKILL'); // TerminateProcess on Windows
  const r = await waitFor('main exit', async () => !isAlive(pid), 15000, 100);
  return r.ok;
}

async function stopMainGracefully() {
  const pid = currentMainPid();
  log(`main: graceful "stop" via RCON (pid=${pid})`);
  const r = await rcon(CFG.mainDir, 'stop');
  if (!r.ok) log('  rcon stop failed: ' + r.err.trim());
  const w = await waitFor('main exit', async () => !isAlive(pid), 90000, 200);
  return w.ok;
}

// ------------------------------------------------------------------ bots

const bots = [];

function createBot(name) {
  const state = { name, events: [], disconnects: [], titles: [], messages: [], backendKicks: [], logins: 0, loginTimes: [], spawns: 0, ended: false };
  const bot = mineflayer.createBot({
    host: CFG.proxyHost, port: CFG.proxyPort, username: name, auth: 'offline', version: CFG.version,
    hideErrors: false, checkTimeoutInterval: 60000,
  });
  state.bot = bot;
  bot._client.setMaxListeners(100); // mineflayer adds listeners on every server switch (harmless warning otherwise)
  applyTickEndFix(bot); // otherwise 26.3 kicks mineflayer for "invalid_player_movement" (see lib/tick-end-fix.mjs)
  bot.on('spawn', () => { state.spawns++; });
  bot._client.on('login', () => { state.logins++; state.loginTimes.push(Date.now()); }); // one per backend joined (initial + each server switch)
  bot.on('kicked', (reason) => { state.disconnects.push({ at: ts(), type: 'kicked', reason: JSON.stringify(reason) }); log(`!! ${name} KICKED: ${JSON.stringify(reason)}`); });
  bot.on('end', (reason) => { state.ended = true; state.disconnects.push({ at: ts(), type: 'end', reason: String(reason) }); log(`!! ${name} END: ${reason}`); });
  bot.on('error', (err) => { state.events.push({ at: ts(), type: 'error', msg: String(err && err.message) }); log(`?? ${name} error: ${err && err.message}`); });
  bot.on('title', (text, type) => { state.titles.push({ t: Date.now(), type, text: String(typeof text === 'string' ? text : JSON.stringify(text)) }); });
  bot._client.on('set_title_text', (p) => { state.titles.push({ t: Date.now(), type: 'raw', text: JSON.stringify(p.text) }); });
  bot.on('messagestr', (msg) => {
    state.messages.push({ t: Date.now(), msg });
    if (/kicked from|expuls|invalid_player_movement|Invalid move/i.test(msg)) { state.backendKicks.push({ at: ts(), msg }); log(`?? ${name} backend kick notice: ${msg}`); }
  });
  return state;
}

function noDisconnects() {
  return bots.every((b) => b.disconnects.length === 0);
}

// ------------------------------------------------------------------ main flow

const results = [];
let failures = 0;
function check(name, ok, details = '') {
  results.push({ name, ok, details });
  if (!ok) failures++;
  log(`${ok ? 'PASS' : 'FAIL'}  ${name}${details ? '  -- ' + details : ''}`);
}

async function ensureProxy() {
  const up = await ping(CFG.proxyHost, CFG.proxyPort).then(() => true, () => false);
  if (up) { log(`proxy: Velocity answers on ${CFG.proxyPort}`); return; }
  if (!CFG.startProxy) throw new Error(`Velocity not reachable on ${CFG.proxyPort} (use --start-proxy)`);
  log('proxy: starting limbo + Velocity via start-proxy.ps1 -Background');
  await new Promise((resolve, reject) => {
    const p = spawn(CFG.ps, ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', path.join(PROXY, 'start-proxy.ps1'), '-Background',
      '-ProxyPort', String(CFG.proxyPort), '-BindAddress', '127.0.0.1', '-ServerDir', CFG.mainDir, '-MainPort', '25581',
      '-LimboPort', '25582', '-LimboRconPort', '25592'],
      { stdio: 'inherit', windowsHide: true });
    p.on('exit', (code) => (code === 0 ? resolve() : reject(new Error('start-proxy.ps1 exit ' + code))));
  });
}

async function botsOn(dir) {
  const names = await listPlayers(dir);
  if (!names) return false;
  return bots.every((b) => names.includes(b.name)) ? names : false;
}

async function cycle(round, mode) {
  const label = `round ${round} / ${mode}`;
  log(`===== ${label} =====`);
  const tStart = Date.now();
  bots.forEach((b) => { b.titles = []; });
  const loginsBefore = bots.map((b) => b.logins);
  const velocityOffset = fileSize(VELOCITY_LOG);
  const kicksBefore = bots.map((b) => b.backendKicks.length);
  const disconnectsBefore = bots.reduce((n, b) => n + b.disconnects.length, 0);
  const prevPid = currentMainPid();

  // 1. take main down
  const down = mode === 'kill' ? await killMain() : await stopMainGracefully();
  check(`${label}: main JVM exited`, down);

  // 2. bots must arrive in limbo
  const toLimbo = await waitFor('bots in limbo', () => botsOn(CFG.limboDir), CFG.maxToLimboS * 1000);
  const limboLoginMs = Math.max(...bots.map((b) => (b.loginTimes.find((t) => t >= tStart) || Infinity) - tStart));
  check(`${label}: all bots moved to limbo`, toLimbo.ok, `joined limbo ${(limboLoginMs / 1000).toFixed(2)}s after ${mode}, limbo "list" ok after ${(Date.now() - tStart) / 1000}s`);

  // 3. title shown
  const titled = await waitFor('titles', async () => bots.every((b) => b.titles.some((t) => /red\S{0,6}marre/i.test(t.text))), 5000);
  check(`${label}: "Le serveur redémarre…" title received by every bot`, titled.ok,
    bots.map((b) => `${b.name}:${b.titles.filter((t) => t.type !== 'raw').map((t) => t.text).join('|').slice(0, 80)}`).join(' ; '));

  // 4. bot functional in limbo: /vibeproxy status answered by the proxy plugin
  const b0 = bots[0];
  const mark = Date.now();
  b0.bot.chat('/vibeproxy status');
  const statusSeen = await waitFor('vibeproxy status', async () => b0.messages.some((m) => m.t >= mark && /Tu es sur : limbo/.test(m.msg)), 5000);
  check(`${label}: /vibeproxy status works from limbo ("Tu es sur : limbo")`, statusSeen.ok,
    b0.messages.filter((m) => m.t >= mark).map((m) => m.msg).join(' / ').slice(0, 300));

  // 5. downtime, then restart main
  await sleep(CFG.downtimeS * 1000);
  const stillLimbo = await botsOn(CFG.limboDir);
  check(`${label}: bots still connected in limbo after ${CFG.downtimeS}s of downtime`, !!stillLimbo && noDisconnects());
  let tReady;
  if (!CFG.supervisor) {
    await startMain();
  }
  const ready = await waitMainReady(prevPid);
  tReady = Date.now();
  check(`${label}: main restarted`, ready.ok, `ready ${(tReady - tStart) / 1000}s after ${mode}`);

  // 6. back on main within maxReturnS of "Done"
  const back = await waitFor('bots back on main', () => botsOn(CFG.mainDir), 60000, 200);
  const backS = (Date.now() - tReady) / 1000;
  check(`${label}: all bots back on main <= ${CFG.maxReturnS}s after main ready`, back.ok && backS <= CFG.maxReturnS, `${backS.toFixed(1)}s`);

  // 7. bots functional on main: receive a private message sent by main's console
  const token = `vibe-${round}-${mode}-${Date.now() % 100000}`;
  const mark2 = Date.now();
  await sleep(500);
  for (const b of bots) await rcon(CFG.mainDir, `msg ${b.name} ${token}`);
  const gotMsg = await waitFor('msg on main', async () => bots.every((b) => b.messages.some((m) => m.t >= mark2 && m.msg.includes(token))), 8000);
  check(`${label}: every bot receives a message from main after returning`, gotMsg.ok);

  // 8. stable on main: no bouncing between servers (exactly 2 switches: -> limbo, -> main)
  await sleep(3000);
  const switches = bots.map((b, i) => b.logins - loginsBefore[i]);
  check(`${label}: exactly 2 server switches per bot (main->limbo->main), no bouncing`, switches.every((n) => n === 2), `switches=${switches.join(',')}`);
  const kickNotices = bots.map((b, i) => b.backendKicks.length - kicksBefore[i]);
  check(`${label}: no backend kick notice in chat`, kickNotices.every((n) => n === 0), `notices=${kickNotices.join(',')}`);
  const audit = velocityAudit(velocityOffset);
  check(`${label}: Velocity log: each bot -> limbo once, -> main once, no kick from limbo, no failed return`,
    audit.perBot.every((p) => p.toLimbo === 1 && p.toMain === 1) && audit.limboKicks === 0 && audit.failedReturns === 0 && audit.errors === 0,
    `${audit.perBot.map((p) => `${p.name}:limbo=${p.toLimbo},main=${p.toMain}`).join(' ')} limboKicks=${audit.limboKicks} failedReturns=${audit.failedReturns} pluginErrors=${audit.errors}`);
  const disconnectsAfter = bots.reduce((n, b) => n + b.disconnects.length, 0);
  check(`${label}: NO disconnect ('end'/'kicked') during the cycle`, disconnectsAfter === disconnectsBefore);
  return { label, toLimboS: limboLoginMs / 1000, backAfterReadyS: backS, totalS: (Date.now() - tStart) / 1000 };
}

// Extra scenarios: a player joining while main is down, and a deliberate /kick on main.
const extraBots = [];
async function extras() {
  // --- join while main is down -> straight to limbo, then back to main
  log('===== extra / join-while-down =====');
  const prevPid = currentMainPid();
  const ok1 = CFG.supervisor ? await stopMainGracefully() : await killMain();
  check('extra: main down', ok1);
  await waitFor('bots in limbo', () => botsOn(CFG.limboDir), CFG.maxToLimboS * 1000);
  await sleep(2500); // let VibeReconnect notice that main is down
  const late = createBot('VibeLate');
  extraBots.push(late);
  const lateSpawn = await waitFor('late spawn', async () => late.spawns > 0, 20000);
  const lateInLimbo = await waitFor('late in limbo', async () => (await listPlayers(CFG.limboDir) || []).includes('VibeLate'), 10000);
  check('extra: a player joining while main is down lands in limbo (no disconnect)', lateSpawn.ok && lateInLimbo.ok && late.disconnects.length === 0,
    late.disconnects.map((d) => d.type + ' ' + d.reason).join(';'));
  const lateTitle = await waitFor('late title', async () => late.titles.some((t) => /red\S{0,6}marre/i.test(t.text)), 5000);
  check('extra: late joiner sees the "Le serveur redémarre…" title', lateTitle.ok);
  if (!CFG.supervisor) await startMain();
  const ready = await waitMainReady(prevPid);
  const tReady = Date.now();
  const back = await waitFor('all on main', async () => {
    const names = await listPlayers(CFG.mainDir);
    return names && [...bots, late].every((b) => names.includes(b.name));
  }, 60000, 200);
  const backS = (Date.now() - tReady) / 1000;
  check(`extra: late joiner + bots back on main <= ${CFG.maxReturnS}s after main ready`, ready.ok && back.ok && backS <= CFG.maxReturnS, `${backS.toFixed(1)}s`);
  check('extra: late joiner never disconnected', late.disconnects.length === 0);
  late.bot.removeAllListeners('end'); late.bot.quit();
  await sleep(1000);

  // --- deliberate kick (/kick) on main must disconnect for real (not limbo)
  log('===== extra / deliberate-kick =====');
  const kb = createBot('VibeKickMe');
  extraBots.push(kb);
  const kbOnMain = await waitFor('kickme on main', async () => (await listPlayers(CFG.mainDir) || []).includes('VibeKickMe'), 20000);
  check('extra: VibeKickMe joined main', kbOnMain.ok);
  await rcon(CFG.mainDir, 'kick VibeKickMe');
  const kicked = await waitFor('kicked', async () => kb.disconnects.some((d) => d.type === 'kicked'), 8000);
  await sleep(2000);
  const inLimbo = (await listPlayers(CFG.limboDir) || []).includes('VibeKickMe');
  check('extra: /kick on main really disconnects the player (not sent to limbo)', kicked.ok && !inLimbo,
    kb.disconnects.map((d) => d.type + ' ' + d.reason).join(';').slice(0, 200));
  check('extra: other bots unaffected by the kick', noDisconnects() && !!(await botsOn(CFG.mainDir)));

  // --- frozen main (main thread blocked, JVM alive): needs the TEST plugin VibeFreezeTest in main
  if (!fs.existsSync(path.join(CFG.mainDir, 'plugins', 'VibeFreezeTest.jar'))) {
    log('extra / frozen-main skipped (proxy/test/freeze-plugin/VibeFreezeTest.jar not installed in main)');
    return;
  }
  log(`===== extra / frozen-main (${CFG.freezeS}s) =====`);
  const loginsBefore = bots.map((b) => b.logins);
  const vOffFreeze = fileSize(VELOCITY_LOG);
  const tFreeze = Date.now();
  rcon(CFG.mainDir, `vibefreeze ${CFG.freezeS}`); // blocks main's main thread; do not await
  const evac = await waitFor('bots evacuated', () => botsOn(CFG.limboDir), 25000, 250);
  const evacS = (Date.now() - tFreeze) / 1000;
  check(`extra: frozen main -> bots evacuated to limbo before Velocity's 30 s read timeout`, evac.ok && evacS < 25 && noDisconnects(), `${evacS.toFixed(1)}s after freeze`);
  const backF = await waitFor('bots back after freeze', () => botsOn(CFG.mainDir), (CFG.freezeS + 120) * 1000, 250);
  const backFS = (Date.now() - tFreeze) / 1000;
  check('extra: bots back on main after the freeze ends, never disconnected', backF.ok && noDisconnects(),
    `back ${backFS.toFixed(1)}s after freeze start (freeze ${CFG.freezeS}s)`);
  await sleep(3000);
  const sw = bots.map((b, i) => b.logins - loginsBefore[i]);
  check('extra: frozen main -> exactly 2 switches per bot', sw.every((n) => n === 2), `switches=${sw.join(',')}`);
  const fa = velocityAudit(vOffFreeze);
  // A return attempt starts force-return-after-ms (15 s) after the evacuation; while main is still frozen
  // it stays pending (player kept in limbo) and fails after Velocity's 30 s read timeout, then is retried.
  // So failed returns (and extra "main has connected" lines) are expected only for freezes > ~55 s.
  check('extra: frozen main -> Velocity log: one evacuation per bot, no kick from limbo, no plugin error',
    fa.perBot.every((p) => p.toLimbo === 1 && p.toMain >= 1) && fa.limboKicks === 0 && fa.errors === 0
      && (CFG.freezeS > 55 || fa.failedReturns === 0),
    `${fa.perBot.map((p) => `${p.name}:limbo=${p.toLimbo},main-attempts=${p.toMain}`).join(' ')} failedReturns=${fa.failedReturns}`);
}

async function cleanup() {
  for (const b of extraBots) {
    try { b.bot.removeAllListeners('end'); b.bot.quit(); } catch {}
  }
  for (const b of bots) {
    try { b.bot.removeAllListeners('end'); b.bot.quit(); } catch {}
  }
  if (CFG.supervisor) {
    try {
      fs.writeFileSync(path.join(PROXY, 'run', 'stop-testmain.flag'), 'stop');
      const pid = readSupervisedJavaPid();
      await rcon(CFG.mainDir, 'stop');
      if (pid) await waitFor('main exit', async () => !isAlive(pid), 60000, 200);
      if (supervisorProc) await waitFor('supervisor exit', async () => supervisorProc.exitCode !== null, 15000, 200);
      log(`supervisor: exited with code ${supervisorProc && supervisorProc.exitCode}`);
      check('supervisor: stop-file + "stop" ends run-main-forever.ps1 (no restart)',
        supervisorProc.exitCode === 0 && (!pid || !isAlive(pid)) && !readSupervisedJavaPid(), `exit=${supervisorProc.exitCode}`);
    } catch (e) { log('cleanup supervisor: ' + e.message); }
  } else if (mainProc && isAlive(mainProc.pid)) {
    log('main: graceful stop (cleanup)');
    await rcon(CFG.mainDir, 'stop');
    const ok = await waitFor('main exit', async () => !isAlive(mainProc.pid), 60000, 200);
    if (!ok.ok) { try { process.kill(mainProc.pid, 'SIGKILL'); } catch {} }
  }
  if (CFG.stopProxy) {
    log('proxy: stop-proxy.ps1');
    await new Promise((resolve) => {
      const p = spawn(CFG.ps, ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', path.join(PROXY, 'stop-proxy.ps1')], { stdio: 'inherit', windowsHide: true });
      p.on('exit', resolve);
    });
  }
}

async function run() {
  log(`config: ${JSON.stringify({ ...CFG })}`);
  await ensureProxy();
  if (CFG.supervisor) await startSupervisor(); else await startMain();
  const ready = await waitMainReady();
  check('initial: main backend started', ready.ok);
  if (!ready.ok) return;
  // give VibeReconnect one ping to notice main is up
  await sleep(2500);

  for (let i = 1; i <= CFG.bots; i++) {
    bots.push(createBot(`VibeBot${i}`));
    await sleep(700); // Velocity login rate limit (500 ms)
  }
  const spawned = await waitFor('bots spawned', async () => bots.every((b) => b.spawns > 0), 30000);
  check('initial: bots joined through the proxy', spawned.ok);
  const onMain = await waitFor('bots on main', () => botsOn(CFG.mainDir), 20000);
  check('initial: bots are on main (main "list")', onMain.ok, onMain.value ? onMain.value.join(',') : '');
  if (!onMain.ok) return;
  await sleep(4000);
  check('initial: bots stay on main (no bouncing, single login each)', bots.every((b) => b.logins === 1) && noDisconnects(),
    bots.map((b) => `${b.name}:logins=${b.logins}`).join(' '));
  // a healthy but idle main must not be taken for a frozen one (freeze-threshold-ms = 10 s)
  const vOffIdle = fileSize(VELOCITY_LOG);
  await sleep(CFG.idleS * 1000);
  const idleLog = fileFrom(VELOCITY_LOG, vOffIdle);
  check(`initial: ${CFG.idleS}s idle on main -> no false "frozen" evacuation, bots still on main`,
    count(idleLog, /FIGE/g) === 0 && !!(await botsOn(CFG.mainDir)) && noDisconnects());

  const cycles = [];
  for (let r = 1; r <= CFG.rounds; r++) {
    for (const mode of CFG.modes) {
      cycles.push(await cycle(r, mode));
      if (bots.some((b) => b.ended)) { log('a bot ended: aborting'); break; }
    }
    if (bots.some((b) => b.ended)) break;
  }
  if (!CFG.noExtras && !bots.some((b) => b.ended)) await extras();
  check('overall: no bot ever disconnected', noDisconnects(),
    bots.flatMap((b) => b.disconnects.map((d) => `${b.name} ${d.type} ${d.reason}`)).join(' ; '));
  return cycles;
}

let cycles = [];
try {
  cycles = (await run()) || [];
} catch (e) {
  check('runner exception', false, e.stack || String(e));
} finally {
  await cleanup();
}

console.log('\n================ SUMMARY ================');
for (const c of cycles) {
  console.log(`${c.label.padEnd(18)} to-limbo ${c.toLimboS.toFixed(1).padStart(5)}s   back-after-ready ${c.backAfterReadyS.toFixed(1).padStart(5)}s   cycle ${c.totalS.toFixed(1)}s`);
}
const passed = results.filter((r) => r.ok).length;
console.log(`${passed}/${results.length} checks passed, ${failures} failed`);
for (const r of results.filter((x) => !x.ok)) console.log('  FAIL: ' + r.name + (r.details ? ' -- ' + r.details : ''));
const report = {
  date: new Date().toISOString(), config: CFG, cycles, results,
  bots: bots.map((b) => ({ name: b.name, spawns: b.spawns, disconnects: b.disconnects, errors: b.events })),
};
const reportFile = path.join(REPORT_DIR, new Date().toISOString().replace(/[:.]/g, '-') + '.json');
fs.writeFileSync(reportFile, JSON.stringify(report, null, 2));
console.log('report: ' + path.relative(process.cwd(), reportFile));
process.exit(failures ? 1 : 0);
