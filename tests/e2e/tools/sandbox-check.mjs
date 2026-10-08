#!/usr/bin/env node
// Validates the e2e bot library against a plain Paper 26.3 server (+ ViaVersion/ViaBackwards, no VibeCraft):
//   1. the bot joins speaking 26.1 (translated by ViaBackwards)
//   2. it is not op at first; it gets op'ed through RCON
//   3. it runs vanilla commands and parses their output
//   4. it stays connected for --stay seconds (default 130) while the server ticks normally
//   5. an RCON kick is detected as a failure (recorded 'kicked' event, no auto-reconnect)
//
// Usage: node tools/sandbox-check.mjs [--dir tests/e2e/.sandbox] [--port 25570] [--stay 130]
// Prereq: tools/sandbox.sh create && tools/sandbox.sh start
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { VibeBot, sleep } from '../lib/bot.mjs';
import { rcon } from '../lib/rcon.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const args = process.argv.slice(2);
const opt = (k, d) => { const i = args.indexOf(k); return i >= 0 ? args[i + 1] : d; };
const DIR = path.resolve(opt('--dir', path.join(here, '..', '.sandbox')));
const PORT = Number(opt('--port', 25570));
const STAY_S = Number(opt('--stay', 130));
const NAME = opt('--username', 'VibeBot');

const checks = [];
const t0 = Date.now();
const el = () => `${((Date.now() - t0) / 1000).toFixed(1)}s`;
function check(name, ok, detail = '') {
  checks.push({ name, ok: !!ok, detail, at: el() });
  console.log(`${ok ? 'PASS' : 'FAIL'}  [${el()}] ${name}${detail ? ' — ' + detail : ''}`);
  return !!ok;
}

const bot = new VibeBot({ port: PORT, username: NAME, log: (s) => console.log(`      ${s}`) });

async function main() {
  // --- preparation through RCON
  const wl = await rcon(DIR, `whitelist add ${NAME}`, `deop ${NAME}`);
  console.log(`rcon> whitelist add / deop: ${wl.trim().replace(/\n/g, ' | ')}`);

  // --- 1. join
  const tj = Date.now();
  await bot.connect({ timeoutMs: 30000 });
  check('bot joins the 26.3 server', bot.isConnected(), `spawned in ${Date.now() - tj} ms at ${JSON.stringify(bot.position())}`);
  check('bot speaks 26.1 (protocol 775)', bot.bot.version === '26.1' && bot.bot.protocolVersion === 775, `version=${bot.bot.version} protocol=${bot.bot.protocolVersion}`);
  const via = await rcon(DIR, 'viaversion list').catch((e) => e.message);
  check('ViaVersion sees the bot as a 26.1 client', /26\.1/.test(via) && via.includes(NAME), via.trim().replace(/\n/g, ' | '));
  await sleep(1500);

  // --- 2. not op yet -> op via RCON
  let r = await bot.cmd('/time query gametime', { windowMs: 1500 });
  check('non-op: /time query gametime is refused', !r.lines.some((l) => /game time is/.test(l)), JSON.stringify(r.lines));
  const opOut = await rcon(DIR, `op ${NAME}`);
  check('RCON op', /Made .* a server operator|already/i.test(opOut), opOut.trim());
  await sleep(500);

  // --- 3. vanilla commands + parsing
  // 26.x syntax: "/time query day" -> "Timeline minecraft:day is at N tick(s)"
  r = await bot.cmd('/time query day', { until: /minecraft:day is at \d+/, windowMs: 3000 });
  const day = r.lines.join('\n').match(/minecraft:day is at (\d+) tick/);
  check('/time query day parsed', day, day ? `day=${day[1]}` : JSON.stringify(r.lines));

  r = await bot.cmd('/list', { until: /players online/, windowMs: 3000 });
  const list = r.lines.join('\n').match(/There are (\d+) of a max of (\d+) players online: ?(.*)/);
  check('/list parsed (1 player: the bot)', list && list[1] === '1' && list[3].includes(NAME), list ? `online=${list[1]} max=${list[2]} names=${list[3]}` : JSON.stringify(r.lines));

  r = await bot.cmd('/seed', { until: /Seed: \[/, windowMs: 3000 });
  const seed = r.lines.join('\n').match(/Seed: \[(-?\d+)\]/);
  check('/seed parsed', seed, seed ? `seed=${seed[1]}` : JSON.stringify(r.lines));

  r = await bot.cmd('/gamemode creative', { until: /game mode/i, windowMs: 3000 });
  await sleep(300);
  check('/gamemode creative applied (client state)', bot.bot.game.gameMode === 'creative', `gameMode=${bot.bot.game.gameMode}; ${JSON.stringify(r.lines)}`);

  r = await bot.cmd('/tp @s 0 120 0', { until: /Teleported/, windowMs: 3000 });
  await sleep(500);
  const p = bot.position();
  check('/tp moved the bot (position packets accepted)', p && Math.abs(p.x - 0.5) <= 1 && Math.abs(p.z - 0.5) <= 1 && p.y > 100, `pos=${JSON.stringify(p)}; ${JSON.stringify(r.lines)}`);

  r = await bot.cmd('/e2e_definitely_unknown', { windowMs: 1500 });
  check('unknown command reply detected', r.lines.some((l) => /Unknown or incomplete command/.test(l)), JSON.stringify(r.lines));

  r = await bot.cmd('/say hello-from-vibebot', { until: /hello-from-vibebot/, windowMs: 3000 });
  check('/say broadcast received back', r.lines.some((l) => l.includes('hello-from-vibebot')), JSON.stringify(r.lines));

  bot.say('plain chat message from VibeBot');
  const chat = await bot.waitForMessage(/plain chat message from VibeBot/, 3000).catch((e) => e);
  check('plain chat round trip', !(chat instanceof Error), chat instanceof Error ? chat.message : chat.text);

  // --- 4. stay connected, measure ticks
  console.log(`staying connected for ${STAY_S} s ...`);
  const stayFrom = Date.now();
  let last = null; const tpsPoints = [];
  while (Date.now() - stayFrom < STAY_S * 1000) {
    if (!bot.isConnected()) break;
    r = await bot.cmd('/time query gametime', { until: /game time is \d+/, windowMs: 3000, settleMs: 50 });
    const m = r.lines.join('\n').match(/game time is (\d+)/);
    if (m) {
      const now = { at: Date.now(), ticks: Number(m[1]) };
      if (last) tpsPoints.push(+((now.ticks - last.ticks) / ((now.at - last.at) / 1000)).toFixed(2));
      last = now;
    }
    // wander a little so the server keeps receiving real movement packets
    bot.bot.setControlState('jump', true); await sleep(400); bot.bot.setControlState('jump', false);
    await sleep(14600);
  }
  const stayed = (Date.now() - stayFrom) / 1000;
  check(`stays connected >= ${STAY_S} s`, bot.isConnected() && stayed >= STAY_S && bot.disconnects.length === 0, `stayed ${stayed.toFixed(0)} s, disconnects=${bot.disconnects.length}`);
  const ts = bot.tickStats(stayFrom);
  check('server ticks at ~20 TPS seen by the client', ts.tps > 18 && tpsPoints.every((x) => x > 15), `world-age TPS=${ts.tps}, max gap ${ts.maxGapMs} ms, /time-derived TPS ${JSON.stringify(tpsPoints)}`);
  check('no errors from the protocol stack', bot.errors.length === 0, bot.errors.map((e) => e.reason.split('\n')[0]).join(' | '));

  // --- 5. kick detection
  const before = bot.events.length;
  const kickOut = await rcon(DIR, `kick ${NAME} E2E kick test`);
  console.log(`rcon> kick: ${kickOut.trim()}`);
  await sleep(1500);
  const kicked = bot.events.slice(before).find((e) => e.type === 'kicked');
  check('kick recorded as a "kicked" event with its reason', kicked && /E2E kick test/.test(kicked.reason), kicked ? kicked.reason : JSON.stringify(bot.events.slice(before)));
  check('kick counted in bot.disconnects (=> run fails)', bot.disconnects.length === 1 && !bot.isConnected(), `disconnects=${bot.disconnects.length} connected=${bot.isConnected()}`);
  let threw = false;
  try { await bot.cmd('/list'); } catch { threw = true; }
  check('commands fail loudly once disconnected', threw);
  await sleep(5000);
  const after = await rcon(DIR, 'list');
  check('no auto-reconnect (server shows 0 players 5 s later)', /There are 0 of/.test(after), after.trim());
}

main()
  .catch((e) => check('sandbox check crashed', false, e.stack || String(e)))
  .finally(async () => {
    await bot.quit().catch(() => {});
    await rcon(DIR, `deop ${NAME}`).catch(() => {});
    const failed = checks.filter((c) => !c.ok);
    const out = { at: new Date().toISOString(), dir: DIR, port: PORT, stayS: STAY_S, checks, events: bot.events, tickStats: bot.tickStats() };
    const rep = path.join(here, '..', 'reports');
    fs.mkdirSync(rep, { recursive: true });
    const file = path.join(rep, `sandbox-check-${new Date().toISOString().replace(/[:.]/g, '-')}.json`);
    fs.writeFileSync(file, JSON.stringify(out, null, 2));
    console.log(`\n${checks.length - failed.length}/${checks.length} checks passed${failed.length ? ' — FAILED: ' + failed.map((f) => f.name).join('; ') : ''}\nreport: ${file}`);
    process.exit(failed.length ? 1 : 0);
  });
