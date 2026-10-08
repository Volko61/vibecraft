#!/usr/bin/env node
// Reproduces (and explains) the "multiplayer.disconnect.invalid_player_movement" kick of mineflayer bots on 26.x:
// joins once WITHOUT the serverbound tick_end shim of lib/bot.mjs, then once WITH it, and reports what happened.
//
// Root cause: since 1.21.2 the server accepts at most one position-carrying move packet per CLIENT tick; client
// ticks are delimited by the serverbound `tick_end` packet (ServerGamePacketListenerImpl: receivedPositionThisTick,
// reset in handleClientTickEnd). mineflayer 4.39 never sends tick_end, so the 2nd position packet it ever sends
// (e.g. physics tick right after the teleport confirm, i.e. ~1 s after spawn while it falls/settles) gets it kicked.
// A bot that spawns exactly on the ground and never moves may survive for a while, which is why it looked random.
//
// Usage: node tools/kick-repro.mjs [--port 25572] [--rcon-dir .sandbox-integ] [--stay 20] [--username VibeRepro]
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { VibeBot, sleep } from '../lib/bot.mjs';
import { rcon } from '../lib/rcon.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const args = process.argv.slice(2);
const opt = (k, d) => { const i = args.indexOf(k); return i >= 0 ? args[i + 1] : d; };
const PORT = Number(opt('--port', 25572));
const RCON_DIR = opt('--rcon-dir', null) ? path.resolve(here, '..', opt('--rcon-dir')) : null;
const STAY_MS = Number(opt('--stay', 20)) * 1000;
const NAME = opt('--username', 'VibeRepro');

async function attempt(shim) {
  const bot = new VibeBot({ port: PORT, username: NAME, tickEndShim: shim });
  const t0 = Date.now();
  try {
    await bot.connect({ timeoutMs: 30000 });
  } catch (e) {
    return { shim, joined: false, error: e.message };
  }
  const spawnAt = Date.now() - t0;
  // make it move a little, like any real client (and like mineflayer does after a teleport/fall)
  try { bot.bot.setControlState('jump', true); } catch { /* ignore */ }
  const end = Date.now() + STAY_MS;
  while (Date.now() < end && bot.isConnected()) await sleep(200);
  try { bot.bot.setControlState('jump', false); } catch { /* ignore */ }
  const d = bot.disconnects[0];
  const res = { shim, joined: true, spawnMs: spawnAt, connectedMs: d ? d.at - bot.spawnedAt : Date.now() - bot.spawnedAt, kicked: !!d, reason: d?.reason ?? null };
  await bot.quit();
  return res;
}

if (RCON_DIR) await rcon(RCON_DIR, `whitelist add ${NAME}`).catch((e) => console.log('rcon: ' + e.message));
const without = await attempt(false);
console.log('WITHOUT tick_end shim:', JSON.stringify(without));
await sleep(1500);
const withShim = await attempt(true);
console.log('WITH tick_end shim:   ', JSON.stringify(withShim));
const explained = without.kicked && /invalid_player_movement/.test(without.reason || '') && withShim.joined && !withShim.kicked;
console.log(explained
  ? 'REPRODUCED: kicked for invalid_player_movement without tick_end, stable with it'
  : 'not reproduced as described (see the two lines above)');
process.exit(explained ? 0 : 1);
