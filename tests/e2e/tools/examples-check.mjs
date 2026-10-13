#!/usr/bin/env node
// Loads every workspace/examples/<id> module at the SAME time (copied into workspace/modules/<id>), checks that all
// of them load, exercises each one with the VibeBot (welcome on join, /hello, /pigrain, /wand, /countdown,
// double jump, scoreboard), samples TPS, then deletes all the copies at once and verifies that everything they
// created is gone (commands, boss bar, scoreboard objective, parachute pigs, recipe, flight permission).
//
// Usage (main server, nothing else using the example ids):
//   cd tests/e2e && node tools/examples-check.mjs [--api http://127.0.0.1:8765] [--mc-port 25565] [--rcon-dir ../../server] [--keep]
// Exit code 0 = every check passed. Writes a JSON report to tests/e2e/reports/examples-<ts>.json.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';
import { VibeBot, sleep } from '../lib/bot.mjs';
import { VibeApi } from '../lib/api.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, '..', '..', '..');
const require = createRequire(import.meta.url);

const argv = process.argv.slice(2);
const opt = (name, def) => { const i = argv.indexOf(name); return i >= 0 ? argv[i + 1] : def; };
const API = opt('--api', 'http://127.0.0.1:8765');
const MC_PORT = Number(opt('--mc-port', '25565'));
const MODULES = path.resolve(opt('--modules-dir', path.join(ROOT, 'workspace', 'modules')));
const DATA = path.resolve(opt('--data-dir', path.join(MODULES, '..', 'data')));
const EXAMPLES = path.join(ROOT, 'workspace', 'examples');
const KEEP = argv.includes('--keep');
const USER = opt('--username', 'VibeBot');

const api = new VibeApi({ base: API });
const t0 = Date.now();
const results = [];
const tps = [];
const log = (...a) => console.log(`[${((Date.now() - t0) / 1000).toFixed(1).padStart(6)} s]`, ...a);
function check(name, ok, detail = '') {
  results.push({ name, ok: !!ok, detail: String(detail).slice(0, 400) });
  console.log(`  ${ok ? 'PASS' : 'FAIL'} ${name}${detail ? ' — ' + String(detail).slice(0, 300) : ''}`);
  return !!ok;
}
const RCON_DIR = opt('--rcon-dir', null);
const cmd = async (line) => {
  try { const r = await api.command(line); return (r.output || []).join(' | '); } catch (e) {
    // the API refuses admin commands (whitelist, op...) with 403: use RCON when --rcon-dir <serverDir> is given
    if (RCON_DIR && e.status === 403) {
      try { const { rcon } = await import('../lib/rcon.mjs'); return await rcon(RCON_DIR, line); } catch (e2) { return 'ERR ' + e2.message; }
    }
    return 'ERR ' + e.message;
  }
};
async function sampleTps(label) {
  const s = await api.status();
  const x = { at: Date.now(), label, tps1m: s.server.tps?.[0], mspt: s.server.mspt };
  tps.push(x);
  return x;
}

const ids = fs.readdirSync(EXAMPLES).filter((d) => fs.statSync(path.join(EXAMPLES, d)).isDirectory()).sort();
log('examples:', ids.join(', '));
for (const id of ids) {
  if (fs.existsSync(path.join(MODULES, id))) {
    console.error(`modules/${id} already exists: refusing to overwrite somebody's module`);
    process.exit(2);
  }
}

/** Plain text of a chat component (JSON string, plain object or prismarine "typed" NBT). */
function plain(v) {
  if (v == null) return '';
  if (typeof v === 'string') { try { return plain(JSON.parse(v)); } catch { return v; } }
  if (Array.isArray(v)) return v.map(plain).join('');
  if (typeof v === 'object') {
    if (typeof v.type === 'string' && 'value' in v && Object.keys(v).length <= 2) {
      if (v.type === 'list') return plain(v.value?.value ?? []);
      if (v.type === 'compound') return plain(v.value);
      return typeof v.value === 'string' ? v.value : '';
    }
    let out = typeof v.text === 'string' ? v.text : plain(v.text ?? v[''] ?? '');
    if (v.extra) out += plain(v.extra);
    return out;
  }
  return '';
}

const PIG_SEL = 'minecraft:pig,name="Cochon parachutiste"';
const countPigs = async (sel = PIG_SEL) => {
  const out = await cmd(`execute if entity @e[type=${sel}]`);
  const m = out.match(/count:? (\d+)/i);
  return m ? Number(m[1]) : (/failed/i.test(out) ? 0 : out);
};

let bot;
const packets = { bossTitle: [], bossAdd: [], bossRemove: [], objectives: [], display: [], scores: [], abilities: [], velocity: [], titles: [], lightning: [] };
const report = { startedAt: new Date(t0).toISOString(), api: API, ids, results, tps, timings: {}, packets: null, botEvents: null };

try {
  // ---------------------------------------------------------------- baseline
  await sampleTps('baseline');
  const baseline = {
    objectives: await cmd('scoreboard objectives list'),
    bossbars: await cmd('bossbar list'),
    namedPigs: await countPigs(),
  };
  report.baseline = baseline;
  log('baseline', JSON.stringify(baseline));
  await cmd(`whitelist add ${USER}`);
  await cmd(`op ${USER}`);

  // ---------------------------------------------------------------- copy all 7 at the same time
  const tCopy = Date.now();
  await Promise.all(ids.map((id) => fs.promises.cp(path.join(EXAMPLES, id), path.join(MODULES, id), { recursive: true })));
  log(`copied ${ids.length} modules in ${Date.now() - tCopy} ms`);
  const loadedAt = {};
  const seenStates = {};
  const deadline = Date.now() + 60000;
  while (Date.now() < deadline) {
    const mods = await api.modules();
    for (const m of mods) {
      if (!ids.includes(m.id)) continue;
      (seenStates[m.id] ??= new Set()).add(`${m.state}/g${m.generation}`);
      if (m.state === 'ENABLED' && m.generation >= 1 && !loadedAt[m.id]) loadedAt[m.id] = Date.now() - tCopy;
    }
    if (ids.every((id) => loadedAt[id])) break;
    await sleep(200);
  }
  report.timings.loadMs = loadedAt;
  const mods = await api.modules();
  for (const id of ids) {
    const m = mods.find((x) => x.id === id);
    check(`load ${id}`, m && m.state === 'ENABLED' && m.generation === 1 && !m.lastError,
      m ? `state=${m.state} gen=${m.generation} after ${loadedAt[id]} ms, commands=${JSON.stringify(m.commands)} listeners=${m.listeners} tasks=${m.tasks} seen=${[...(seenStates[id] || [])].join(',')} lastError=${m.lastError}` : 'not listed');
  }
  await sampleTps('after-load');

  // ---------------------------------------------------------------- bot joins (welcome module)
  bot = new VibeBot({ port: MC_PORT, username: USER, log: (l) => log(l) });
  const tJoin = Date.now();
  // Packet probes are installed on the client before 'spawn' (createBot is synchronous inside connect()).
  const origConnect = bot.connect.bind(bot);
  const joinPromise = origConnect({ timeoutMs: 30000 });
  const c = bot.bot._client;
  c.on('boss_bar', (p) => {
    const title = plain(p.title ?? '');
    if (p.action === 0) packets.bossAdd.push({ at: Date.now(), uuid: p.entityUUID, title });
    if (p.action === 3) packets.bossTitle.push({ at: Date.now(), uuid: p.entityUUID, title });
    if (p.action === 1) packets.bossRemove.push({ at: Date.now(), uuid: p.entityUUID });
  });
  c.on('scoreboard_objective', (p) => packets.objectives.push({ at: Date.now(), name: p.name, action: p.action }));
  c.on('scoreboard_display_objective', (p) => packets.display.push({ at: Date.now(), position: p.position, name: p.name }));
  c.on('scoreboard_score', (p) => packets.scores.push({ at: Date.now(), item: p.itemName, obj: p.scoreName, value: p.value }));
  c.on('abilities', (p) => packets.abilities.push({ at: Date.now(), flags: p.flags }));
  c.on('entity_velocity', (p) => { if (bot.bot?.entity && p.entityId === bot.bot.entity.id) packets.velocity.push({ at: Date.now(), v: p.velocity }); });
  c.on('set_title_text', (p) => packets.titles.push({ at: Date.now(), kind: 'title', text: plain(p.text) }));
  c.on('set_title_subtitle', (p) => packets.titles.push({ at: Date.now(), kind: 'subtitle', text: plain(p.text) }));
  const mcData = require('minecraft-data')('26.1');
  const LIGHTNING = mcData.entitiesByName.lightning_bolt?.id;
  c.on('spawn_entity', (p) => { if (p.type === LIGHTNING) packets.lightning.push({ at: Date.now(), x: p.x, y: p.y, z: p.z }); });
  await joinPromise;
  log('bot spawned at', JSON.stringify(bot.position()));
  await cmd(`gamemode creative ${USER}`);
  await sleep(1000);
  baseline.allPigs = await countPigs('minecraft:pig'); // natural pigs in the chunks loaded around the bot
  try {
    const m = await bot.waitForMessage(new RegExp(`${USER}.*a rejoint le serveur`), 5000, { since: tJoin });
    check('welcome: custom join message', true, m.text);
  } catch (e) { check('welcome: custom join message', false, e.message + ' | last: ' + bot.messages.slice(-5).map((m) => m.text).join(' / ')); }
  await sleep(2500);
  const title = packets.titles.find((t) => t.kind === 'title');
  const sub = packets.titles.find((t) => t.kind === 'subtitle');
  check('welcome: title + subtitle ~1 s after join', title && /Bienvenue/.test(title.text) && sub && sub.text.includes(USER),
    `title=${title?.text?.slice(0, 120)} (+${title ? title.at - tJoin : '-'} ms) subtitle=${sub?.text?.slice(0, 160)}`);
  check('scoreboard: sidebar objective sent on join', packets.objectives.some((o) => o.name === 'vibe_mined' && o.action === 0)
    && packets.display.some((d) => d.name === 'vibe_mined'), JSON.stringify(packets.display));

  // ---------------------------------------------------------------- hello
  let r = await bot.cmd('/hello', { until: /Salut/, windowMs: 3000 });
  check('hello: /hello', /Salut VibeBot !/.test(r.text) && r.lines.filter((l) => /Salut/.test(l)).length === 1, `${r.text} [${r.ms} ms]`);
  r = await bot.cmd('/salut feu', { until: /Salut/, windowMs: 3000 });
  check('hello: /salut feu (alias)', /Salut VibeBot !/.test(r.text), r.text);
  try {
    const tc = await bot.tabComplete('/hello ');
    check('hello: tab completion', ['arc-en-ciel', 'feu', 'glace'].every((s) => tc.includes(s)), JSON.stringify(tc));
  } catch (e) { check('hello: tab completion', false, e.message); }

  // ---------------------------------------------------------------- pig rain
  r = await bot.cmd('/pigrain 10', { until: /Alerte/, windowMs: 3000 });
  check('pigrain: /pigrain 10 broadcast', /Alerte m.t.o.*pluie de cochons pendant 10 s/.test(r.text), r.text);
  await sleep(4000);
  const pigs4s = await countPigs();
  check('pigrain: parachute pigs spawned', typeof pigs4s === 'number' && pigs4s >= 8, `named pigs after 4 s: ${pigs4s}`);
  r = await bot.cmd('/pigrain stats', { until: /cochons sont tomb/, windowMs: 3000 });
  check('pigrain: stats (store)', /\d+ cochons sont tomb/.test(r.text), r.text);
  await sampleTps('pigrain');

  // ---------------------------------------------------------------- magic wand
  r = await bot.cmd('/wand', { until: /baguette magique/, windowMs: 3000 });
  check('wand: /wand gives the wand', /re.u la baguette magique/.test(r.text), r.text);
  await sleep(500);
  const rod = bot.bot.inventory.items().find((i) => i.name === 'blaze_rod');
  check('wand: blaze rod in inventory', !!rod, JSON.stringify(bot.bot.inventory.items().map((i) => i.name)));
  if (rod) {
    try {
      await bot.bot.equip(rod, 'hand');
      await sleep(300);
      const before = packets.lightning.length;
      bot.bot.look(0, -0.3, true);
      await sleep(200);
      bot.bot.activateItem();
      await sleep(150);
      bot.bot.deactivateItem();
      await sleep(1500);
      check('wand: right click casts the lightning effect', packets.lightning.length > before, `lightning spawns: ${packets.lightning.length - before}`);
    } catch (e) { check('wand: right click casts the lightning effect', false, e.message); }
  }
  const recipeOut = await cmd(`recipe give ${USER} vibecraft:magic-wand/wand_recipe`);
  check('wand: recipe registered', !/unknown|invalid|incorrect|error/i.test(recipeOut), recipeOut);

  // ---------------------------------------------------------------- countdown (boss-bar)
  const ba = packets.bossAdd.length; const br = packets.bossRemove.length;
  const tCd = Date.now();
  await bot.cmd('/countdown 3 E2E-CD', { windowMs: 500 });
  await sleep(1000);
  const added = packets.bossAdd.slice(ba);
  const named = packets.bossTitle.filter((x) => x.uuid === added[0]?.uuid);
  check('countdown: boss bar shown', added.length === 1 && named.some((x) => /E2E-CD/.test(x.title)), JSON.stringify({ added, named }).slice(0, 300));
  await sleep(3500);
  const removed = packets.bossRemove.slice(br);
  check('countdown: boss bar removed at the end + GO title', removed.length === 1 && removed[0].uuid === added[0]?.uuid
    && packets.titles.some((t) => t.at > tCd && /GO/.test(t.text)), `removed=${removed.length} after ${removed[0] ? removed[0].at - tCd : '-'} ms`);
  // a long countdown left running on purpose: must disappear when the module is unloaded
  const ba2 = packets.bossAdd.length;
  await bot.cmd('/countdown 120 E2E-LONG', { windowMs: 500 });
  await sleep(800);
  const longBar = packets.bossAdd.slice(ba2)[0];
  const longTitle = packets.bossTitle.filter((x) => x.uuid === longBar?.uuid).at(-1)?.title;
  check('countdown: long countdown running', !!longBar && /E2E-LONG/.test(longTitle || ''), longTitle);
  report.vanillaBossbarList = await cmd('bossbar list');

  // ---------------------------------------------------------------- scoreboard: break a block (creative)
  const objList = await cmd('scoreboard objectives list');
  check('scoreboard: objective listed', /Blocs min/.test(objList), objList);
  try {
    const pos = bot.bot.entity.position.floored();
    const blk = bot.bot.findBlock({
      matching: (b) => b && b.boundingBox === 'block' && b.name !== 'bedrock' && !(b.position.x === pos.x && b.position.z === pos.z),
      maxDistance: 4,
      useExtraInfo: true,
    });
    if (!blk) throw new Error('no solid block within 4 blocks');
    await bot.bot.dig(blk, true);
    await sleep(800);
    const sc = await cmd(`scoreboard players get ${USER} vibe_mined`);
    check('scoreboard: block break counted', /has 1 /.test(sc) && packets.scores.some((s) => s.obj === 'vibe_mined' && s.value === 1), `${blk.name}@${blk.position} -> ${sc}`);
  } catch (e) { check('scoreboard: block break counted', false, e.message); }

  // ---------------------------------------------------------------- double jump (survival)
  const abStart = packets.abilities.length; // before the switch: the module may grant flight in the same tick
  await cmd(`gamemode survival ${USER}`);
  let granted = null;
  for (let i = 0; i < 40 && !granted; i++) {
    await sleep(100);
    granted = packets.abilities.slice(abStart).find((a) => a.flags & 0x04);
  }
  check('double-jump: grounded survival player gets allowFlight', !!granted, `abilities packets: ${JSON.stringify(packets.abilities.slice(abStart))} pos=${JSON.stringify(bot.position())}`);
  if (granted) {
    const vb = packets.velocity.length; const ab = packets.abilities.length;
    bot.bot.setControlState('jump', true);
    await sleep(120);
    bot.bot.setControlState('jump', false);
    bot.bot._client.write('abilities', { flags: 0x02 }); // "start flying" = the double-jump trigger
    await sleep(800);
    const vel = packets.velocity.slice(vb);
    const revoked = packets.abilities.slice(ab).find((a) => !(a.flags & 0x04));
    check('double-jump: flight toggle -> boost + flight revoked', vel.length >= 1 && !!revoked,
      `velocity=${JSON.stringify(vel.slice(0, 2))} abilities=${JSON.stringify(packets.abilities.slice(ab))}`);
    // wait until the module re-grants flight (cooldown 1.2 s + on ground), so the unload cleanup has something to revoke
    let regrant = null;
    for (let i = 0; i < 60 && !regrant; i++) { await sleep(100); regrant = packets.abilities.slice(ab).find((a) => a.flags & 0x04); }
    check('double-jump: flight re-granted after landing', !!regrant, JSON.stringify(packets.abilities.slice(ab)));
  }
  await sampleTps('exercised');

  const statuses = await api.modules();
  for (const id of ids) {
    const m = statuses.find((x) => x.id === id);
    check(`no runtime errors in ${id}`, m && m.runtimeErrorCount === 0 && m.state === 'ENABLED', m ? `state=${m.state} errors=${m.runtimeErrorCount} ${JSON.stringify(m.recentErrors).slice(0, 200)} msPerTick=${m.msPerTick}` : 'missing');
  }
  report.modulesBeforeDelete = statuses.filter((m) => ids.includes(m.id));

  // ---------------------------------------------------------------- delete all 7 at the same time
  const pigsBefore = await countPigs();
  log(`named pigs before delete: ${pigsBefore}`);
  const objRm = packets.objectives.length; const bossRm = packets.bossRemove.length; const abRm = packets.abilities.length;
  const tDel = Date.now();
  await Promise.all(ids.map((id) => fs.promises.rm(path.join(MODULES, id), { recursive: true, force: true })));
  const goneAt = {};
  const delDeadline = Date.now() + 30000;
  while (Date.now() < delDeadline && !ids.every((id) => goneAt[id])) {
    const list = await api.modules();
    for (const id of ids) if (!goneAt[id] && !list.some((m) => m.id === id)) goneAt[id] = Date.now() - tDel;
    await sleep(200);
  }
  report.timings.unloadMs = goneAt;
  check('delete: all 7 modules unloaded', ids.every((id) => goneAt[id]), JSON.stringify(goneAt));
  await sleep(1000);

  for (const c2 of ['hello', 'salut', 'pigrain', 'wand', 'countdown']) {
    const rr = await bot.cmd('/' + c2, { until: /Unknown or incomplete command|Unknown command/, windowMs: 2500 });
    check(`delete: /${c2} gone`, rr.matched && !/Salut|Alerte|baguette|Usage/.test(rr.text), rr.text);
  }
  const tree = bot.commandTree ? [...bot.commandTree] : [];
  check('delete: commands gone from the client command tree', !['hello', 'salut', 'pigrain', 'pluiedecochons', 'wand', 'countdown'].some((n) => tree.includes(n) || tree.includes('vibe:' + n)), tree.filter((n) => /hello|salut|pig|wand|count/.test(n)).join(','));
  const objAfter = await cmd('scoreboard objectives list');
  check('delete: scoreboard objective unregistered', !/Blocs min/.test(objAfter) && packets.objectives.slice(objRm).some((o) => o.name === 'vibe_mined' && o.action === 1), objAfter);
  const bossAfter = await cmd('bossbar list');
  check('delete: vanilla bossbar list unchanged', bossAfter === baseline.bossbars, bossAfter);
  check('delete: running countdown boss bar hidden from the player', packets.bossRemove.slice(bossRm).some((b) => b.uuid === longBar?.uuid), JSON.stringify(packets.bossRemove.slice(bossRm)));
  const pigsAfter = await countPigs();
  const killOut = await cmd(`kill @e[type=${PIG_SEL}]`);
  check('delete: parachute pigs removed', pigsAfter === 0 && /no entity was found/i.test(killOut), `before=${pigsBefore} after=${pigsAfter} kill: ${killOut}`);
  const allPigs = await countPigs('minecraft:pig');
  report.allPigsAfter = allPigs;
  // informational only: more chunks (and their natural pigs) load around the bot over time
  log(`all pigs (natural, loaded chunks): at join=${baseline.allPigs} after delete=${allPigs}`);
  const recipeAfter = await cmd(`recipe give ${USER} vibecraft:magic-wand/wand_recipe`);
  check('delete: wand recipe removed', /unknown|invalid|incorrect|error|found/i.test(recipeAfter) && !/^Unlocked/i.test(recipeAfter), recipeAfter);
  const lastAb = packets.abilities.slice(abRm).at(-1);
  check('delete: double-jump flight permission revoked (cleanup)', lastAb && !(lastAb.flags & 0x04), JSON.stringify(packets.abilities.slice(abRm)));
  await cmd(`gamemode creative ${USER}`);
  for (const id of ['pig-rain', 'welcome', 'scoreboard']) {
    const f = path.join(DATA, id, 'store.yml');
    report[`store_${id}`] = fs.existsSync(f) ? fs.readFileSync(f, 'utf8').slice(0, 300) : null;
  }
  check('store: welcome visits persisted on unload', /visits/.test(report.store_welcome || ''), report.store_welcome);
  await sampleTps('after-delete');
  const minTps = Math.min(...tps.map((x) => x.tps1m));
  check('TPS (1 min) stayed >= 19', minTps >= 19, JSON.stringify(tps.map((x) => `${x.label}:${x.tps1m?.toFixed?.(2)}/${x.mspt}ms`)));
  check('bot never disconnected', bot.disconnects.length === 0 && bot.isConnected(), JSON.stringify(bot.disconnects));
} catch (e) {
  check('script error', false, e.stack);
} finally {
  if (!KEEP) {
    for (const id of ids) {
      fs.rmSync(path.join(MODULES, id), { recursive: true, force: true });
      fs.rmSync(path.join(DATA, id), { recursive: true, force: true });
    }
  }
  report.packets = packets;
  report.botEvents = bot?.events;
  report.botTick = bot ? { stats: bot.tickStats(t0), gaps: bot.timeGaps(t0, Infinity, 5) } : null;
  report.lastChat = bot?.messages.slice(-60).map((m) => m.text);
  try { await bot?.quit('examples-check done'); } catch { /* ignore */ }
  const dir = path.join(ROOT, 'tests', 'e2e', 'reports');
  fs.mkdirSync(dir, { recursive: true });
  const file = path.join(dir, `examples-${new Date(t0).toISOString().replace(/[:.]/g, '-')}.json`);
  fs.writeFileSync(file, JSON.stringify(report, null, 2));
  const failed = results.filter((x) => !x.ok);
  console.log(`\n${results.length - failed.length}/${results.length} checks passed. Report: ${file}`);
  process.exit(failed.length ? 1 : 0);
}
