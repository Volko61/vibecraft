// Long-lived observer bot for the REDTEAM-LEAKS session.
// Connects one mineflayer bot (reusing tests/e2e/lib/bot.mjs, incl. the tick_end shim) and stays connected
// while modules are reloaded. It is driven through files (no extra TCP port): botctl.mjs writes
// <dir>/req-<id>.json, the daemon executes it and writes <dir>/res-<id>.json.
//
// Usage: node bot-daemon.mjs --port 25610 --dir <ipcDir> [--name LeakBot]
import fs from 'node:fs';
import path from 'node:path';
import { VibeBot, sleep } from '../../e2e/lib/bot.mjs';

const args = process.argv.slice(2);
const opt = { port: 25610, dir: null, name: 'LeakBot' };
for (let i = 0; i < args.length; i++) {
  if (args[i] === '--port') opt.port = Number(args[++i]);
  else if (args[i] === '--dir') opt.dir = args[++i];
  else if (args[i] === '--name') opt.name = args[++i];
}
if (!opt.dir) { console.error('--dir required'); process.exit(2); }
fs.mkdirSync(opt.dir, { recursive: true });

const log = (s) => console.log(new Date().toISOString().slice(11, 23) + ' ' + s);
const vb = new VibeBot({ port: opt.port, username: opt.name, log });

// raw chat JSON (to extract click events of Adventure callbacks)
const rawChat = [];
// last abilities packet (allowFlight = flags & 0x04)
let abilities = null;
let worldBorder = {};
const bossBars = new Map();

await vb.connect({ timeoutMs: 60000 });
const c = vb.bot._client;
c.on('systemChat', (d) => { rawChat.push({ at: Date.now(), json: d.formattedMessage ?? d.content ?? d }); if (rawChat.length > 3000) rawChat.splice(0, 1000); });
c.on('abilities', (p) => { abilities = { at: Date.now(), flags: p.flags, allowFlight: !!(p.flags & 0x04), flying: !!(p.flags & 0x02) }; });
c.on('initialize_world_border', (p) => { worldBorder = { at: Date.now(), ...p }; });
c.on('world_border_size', (p) => { worldBorder = { ...worldBorder, at: Date.now(), diameter: p.diameter }; });
c.on('world_border_lerp_size', (p) => { worldBorder = { ...worldBorder, at: Date.now(), newDiameter: p.newDiameter }; });
c.on('boss_bar', (p) => {
  const id = String(p.entityUUID);
  if (p.action === 1) bossBars.delete(id); else bossBars.set(id, { ...(bossBars.get(id) || {}), action: p.action, title: p.title ?? bossBars.get(id)?.title });
});
log('bot spawned at ' + JSON.stringify(vb.position()));

function windowInfo() {
  const w = vb.bot.currentWindow;
  if (!w) return null;
  return {
    id: w.id, type: w.type, title: typeof w.title === 'string' ? w.title : JSON.stringify(w.title),
    slots: w.slots.slice(0, w.inventoryStart).map((s, i) => s ? { i, name: s.name, count: s.count } : null).filter(Boolean),
  };
}

function snapshot() {
  const b = vb.bot;
  return {
    connected: vb.connected,
    disconnects: vb.disconnects,
    pos: vb.position(),
    gameMode: b.game?.gameMode,
    health: b.health,
    effects: Object.entries(b.entity?.effects || {}).map(([id, e]) => ({ id: Number(id), amplifier: e.amplifier, duration: e.duration })),
    abilities,
    worldBorder,
    bossBars: [...bossBars.values()].map((x) => (typeof x.title === 'string' ? x.title : JSON.stringify(x.title))),
    sidebar: Object.values(b.scoreboards || {}).map((s) => ({ name: s.name, title: String(s.title), items: (s.items || []).length })),
    sidebarShown: b.scoreboard?.sidebar?.name ?? null,
    window: windowInfo(),
    inventory: b.inventory.items().map((it) => ({ slot: it.slot, name: it.name, count: it.count })),
    commandTreeSize: vb.commandTree?.size ?? null,
    timeOfDay: b.time?.timeOfDay,
    isRaining: b.isRaining,
    messages: vb.messages.length,
  };
}

async function handle(req) {
  const b = vb.bot;
  switch (req.op) {
    case 'status': return snapshot();
    case 'cmd': return await vb.cmd(req.text, { windowMs: req.windowMs ?? 1500, until: req.until ? new RegExp(req.until) : null });
    case 'chat': vb.say(req.text); await sleep(req.waitMs ?? 300); return { ok: true };
    case 'messages': return vb.messagesSince(req.since ?? 0, req.re ? new RegExp(req.re) : null).map((m) => m.text);
    case 'rawchat': return rawChat.filter((m) => m.at >= (req.since ?? 0)).map((m) => (typeof m.json === 'string' ? m.json : JSON.stringify(m.json)));
    case 'commandTree': return vb.commandTree ? [...vb.commandTree].sort() : null;
    case 'tab': return await vb.tabComplete(req.text);
    case 'click': {
      if (!b.currentWindow) return { error: 'no window open' };
      await b.clickWindow(req.slot, req.button ?? 0, req.mode ?? 0);
      await sleep(req.waitMs ?? 500);
      return snapshot();
    }
    case 'closeWindow': if (b.currentWindow) b.closeWindow(b.currentWindow); await sleep(200); return snapshot();
    case 'payload': c.write('custom_payload', { channel: req.channel, data: Buffer.from(req.data ?? 'x') }); await sleep(req.waitMs ?? 300); return { ok: true };
    case 'write': c.write(req.name, req.params); await sleep(req.waitMs ?? 300); return { ok: true };
    case 'customClick': { // click on a "custom" click event (Paper ClickEvent.callback => paper:click_callback {id})
      // vanilla encodes the payload as a length-prefixed optional network NBT tag WITHOUT a presence boolean (TAG_End = empty),
      // which minecraft-data 26.1 gets wrong (it adds a boolean): write the packet by hand.
      const str = (s) => { const b = Buffer.from(s, 'utf8'); return Buffer.concat([Buffer.from([b.length]), b]); };
      const nbtStr = (s) => { const b = Buffer.from(s, 'utf8'); const l = Buffer.alloc(2); l.writeUInt16BE(b.length); return Buffer.concat([l, b]); };
      // Paper stores the callback id as a UUID = NBT int array of 4 ints
      const uuid = Buffer.from(req.payloadId.replace(/-/g, ''), 'hex'); const ia = Buffer.alloc(4); ia.writeInt32BE(4);
      const payload = Buffer.concat([Buffer.from([0x0a, 0x0b]), nbtStr('id'), ia, uuid, Buffer.from([0x00])]);
      const pkt = Buffer.concat([Buffer.from([req.packetId ?? 0x44]), str(req.id), Buffer.from([payload.length]), payload]); // lengthPrefixed(65536) optional tag
      c.writeRaw(pkt);
      await sleep(req.waitMs ?? 500); return { ok: true, hex: pkt.toString('hex') };
    }
    case 'look':await b.look(req.yaw ?? 0, req.pitch ?? 0, true); return { ok: true };
    case 'sneak': b.setControlState('sneak', !!req.on); await sleep(req.waitMs ?? 300); return { ok: true };
    case 'quit': await vb.quit('done'); setTimeout(() => process.exit(0), 500); return { ok: true };
    default: return { error: 'unknown op ' + req.op };
  }
}

const busy = new Set();
setInterval(() => {
  let files;
  try { files = fs.readdirSync(opt.dir).filter((f) => f.startsWith('req-') && f.endsWith('.json')); } catch { return; }
  for (const f of files) {
    if (busy.has(f)) continue;
    busy.add(f);
    const id = f.slice(4, -5);
    let req;
    try { req = JSON.parse(fs.readFileSync(path.join(opt.dir, f), 'utf8')); } catch { busy.delete(f); continue; }
    fs.unlinkSync(path.join(opt.dir, f));
    handle(req).then((r) => ({ ok: true, result: r }), (e) => ({ ok: false, error: String(e?.stack || e) }))
      .then((out) => { fs.writeFileSync(path.join(opt.dir, 'res-' + id + '.json.tmp'), JSON.stringify(out)); fs.renameSync(path.join(opt.dir, 'res-' + id + '.json.tmp'), path.join(opt.dir, 'res-' + id + '.json')); busy.delete(f); });
  }
}, 50);

setInterval(() => {
  fs.writeFileSync(path.join(opt.dir, 'heartbeat.json'), JSON.stringify({ at: Date.now(), connected: vb.connected, disconnects: vb.disconnects }));
}, 1000);
vb.bot.on('end', () => log('bot ended: ' + JSON.stringify(vb.disconnects)));
