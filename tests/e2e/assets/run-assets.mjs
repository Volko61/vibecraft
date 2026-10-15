// End-to-end check of the "mod-like content" features on a PRIVATE sandbox server (never the live one):
// hot resource pack (push to a real client connection, served zip, re-push on asset change, no disconnect),
// example modules (laser gun, robot mob, custom GUI, hover car, AI NPC), OpenRouter helper (fake server,
// never on the main thread), texture generation endpoint, secrets never leaked, AI job env/MCP flags.
//
// Usage: node tests/e2e/assets/run-assets.mjs --sandbox <dir> [--keep-running]
//   <dir>/server  : Paper (paper.jar, libraries, versions, plugins/{Via*,VibeCraft.jar}, plugins/VibeCraft/config.yml)
//                   server.properties: server-port 25650, rcon 25651; config.yml: api.port 8850,
//                   resource-pack.port 8851, openrouter.base-url http://127.0.0.1:8852/api/v1,
//                   ai.claude-path <this dir>/fake-claude.mjs, ai.env.FAKE_CLAUDE_DUMP <dir>/claude-dump.json
//   <dir>/workspace/modules : modules dir (the test copies the examples into it)
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import zlib from 'node:zlib';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { VibeBot, sleep } from '../lib/bot.mjs';
import { rcon } from '../lib/rcon.mjs';
import { startFakeOpenRouter } from './fake-openrouter.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const REPO = path.resolve(HERE, '..', '..', '..');
const args = process.argv.slice(2);
const opt = (k, d) => { const i = args.indexOf(k); return i >= 0 ? args[i + 1] : d; };
const SANDBOX = path.resolve(opt('--sandbox', path.join(REPO, '.sandbox-assets')));
const SERVER = path.join(SANDBOX, 'server');
const MODULES = path.join(SANDBOX, 'workspace', 'modules');
const API = 'http://127.0.0.1:8850';
const MC_PORT = 25650;
const FAKE_KEY = 'sk-or-v1-FAKE-TEST-KEY-0123456789';
const JAVA = path.join(REPO, 'runtime', 'jdk-25', 'bin', 'java');
const JAVA_BIN = fs.existsSync(JAVA + '.exe') || fs.existsSync(JAVA) ? JAVA
  : path.join(path.resolve(REPO, '..', '..', '..'), 'runtime', 'jdk-25', 'bin', 'java'); // worktree -> main checkout

const results = [];
const log = (...a) => console.log(new Date().toISOString().slice(11, 19), ...a);
function check(name, ok, detail = '') {
  results.push({ name, ok: !!ok, detail });
  log(ok ? 'PASS' : 'FAIL', name, detail ? '- ' + String(detail).slice(0, 300) : '');
}
async function api(method, p, body, headers = {}) {
  const res = await fetch(API + p, { method, body, headers, signal: AbortSignal.timeout(180_000) });
  const text = await res.text();
  let json = null;
  try { json = JSON.parse(text); } catch { /* text */ }
  return { status: res.status, json, text };
}
async function cmd(line) {
  const r = await api('POST', '/api/command', line);
  return (r.json?.output || []).join('\n');
}
async function waitFor(what, fn, timeoutMs = 20000, every = 250) {
  const t0 = Date.now();
  let last;
  while (Date.now() - t0 < timeoutMs) {
    try { last = await fn(); if (last) return last; } catch (e) { last = e; }
    await sleep(every);
  }
  throw new Error(`timeout waiting for ${what} (last: ${last instanceof Error ? last.message : JSON.stringify(last)?.slice(0, 200)})`);
}

/** Minimal zip reader (central directory, stored/deflated). */
function unzip(buf) {
  let eocd = buf.length - 22;
  while (eocd >= 0 && buf.readUInt32LE(eocd) !== 0x06054b50) eocd--;
  if (eocd < 0) throw new Error('not a zip (no EOCD)');
  const count = buf.readUInt16LE(eocd + 10);
  let p = buf.readUInt32LE(eocd + 16);
  const files = new Map();
  for (let i = 0; i < count; i++) {
    if (buf.readUInt32LE(p) !== 0x02014b50) throw new Error('bad central directory');
    const method = buf.readUInt16LE(p + 10);
    const csize = buf.readUInt32LE(p + 20);
    const nlen = buf.readUInt16LE(p + 28), xlen = buf.readUInt16LE(p + 30), clen = buf.readUInt16LE(p + 32);
    const lho = buf.readUInt32LE(p + 42);
    const name = buf.toString('utf8', p + 46, p + 46 + nlen);
    const lnlen = buf.readUInt16LE(lho + 26), lxlen = buf.readUInt16LE(lho + 28);
    const data = buf.subarray(lho + 30 + lnlen + lxlen, lho + 30 + lnlen + lxlen + csize);
    files.set(name, method === 8 ? zlib.inflateRawSync(data) : Buffer.from(data));
    p += 46 + nlen + xlen + clen;
  }
  return files;
}
const pngSize = (b) => (b.subarray(1, 4).toString() === 'PNG' ? [b.readUInt32BE(16), b.readUInt32BE(20)] : null);

function copyDir(src, dst) {
  fs.mkdirSync(dst, { recursive: true });
  for (const e of fs.readdirSync(src, { withFileTypes: true })) {
    if (e.name === '.vibe') continue;
    const s = path.join(src, e.name), d = path.join(dst, e.name);
    if (e.isDirectory()) copyDir(s, d); else fs.copyFileSync(s, d);
  }
}

const PROBE = `package vibe.or_probe;

import dev.vibecraft.api.ModuleContext;
import dev.vibecraft.api.OpenRouter;
import dev.vibecraft.api.Secrets;
import dev.vibecraft.api.VibeModule;
import org.bukkit.Bukkit;

public class OrProbeModule implements VibeModule {
    @Override
    public void enable(ModuleContext ctx) {
        ctx.command("orprobe", (sender, args) -> {
            boolean callerMain = Bukkit.isPrimaryThread();
            ctx.logger().info("leak test: " + Secrets.get("openrouter"));
            OpenRouter.chat("Tu es un test.", "ping depuis orprobe")
                .thenAccept(answer -> ctx.logger().info("probe answer=" + answer.length() + "chars thread="
                    + Thread.currentThread().getName() + " primary=" + Bukkit.isPrimaryThread() + " caller=" + callerMain))
                .exceptionally(err -> { ctx.logger().warning("probe failed: " + OpenRouter.errorMessage(err)); return null; });
            sender.sendRichMessage("<green>orprobe sent, available=" + OpenRouter.available());
        });
    }
}
`;

let serverProc = null;
let fake = null;
let bot = null;
async function main() {
  // ------------------------------------------------------------------ setup
  fake = await startFakeOpenRouter({ port: 8852, key: FAKE_KEY });
  fs.mkdirSync(MODULES, { recursive: true });
  for (const id of fs.readdirSync(MODULES)) fs.rmSync(path.join(MODULES, id), { recursive: true, force: true });
  for (const id of ['laser-gun', 'robot-mob', 'custom-gui', 'hover-car', 'ai-npc']) {
    copyDir(path.join(REPO, 'workspace', 'examples', id), path.join(MODULES, id));
  }
  fs.mkdirSync(path.join(MODULES, 'or-probe'), { recursive: true });
  fs.writeFileSync(path.join(MODULES, 'or-probe', 'OrProbeModule.java'), PROBE);
  // this test checks the automatic push (join + asset change): force resource-pack.auto-push: true
  // (the default is on-demand delivery with a compass, checked by run-conv.mjs)
  const cfgFile = path.join(SERVER, 'plugins', 'VibeCraft', 'config.yml');
  const cfg = fs.readFileSync(cfgFile, 'utf8');
  if (!/\n  auto-push: true/.test(cfg)) {
    fs.writeFileSync(cfgFile, cfg.replace(/\n  auto-push: \w+/, '').replace(/\nresource-pack:\n/, '\nresource-pack:\n  auto-push: true\n'));
  }
  const secretsFile = path.join(SERVER, 'plugins', 'VibeCraft', 'secrets.yml');
  fs.writeFileSync(secretsFile, `openrouter: "${FAKE_KEY}"\n`);
  const dumpFile = path.join(SANDBOX, 'claude-dump.json');
  fs.rmSync(dumpFile, { force: true });
  // The client is not available here, but the server validates pack.mcmeta with the SAME codec for data packs:
  // a data pack whose pack.mcmeta has the exact shape VibeCraft generates (min_format/max_format, no pack_format)
  // must be listed by /datapack list.
  const dp = path.join(SERVER, 'world', 'datapacks', 'vibe-meta-test');
  fs.mkdirSync(dp, { recursive: true });
  fs.writeFileSync(path.join(dp, 'pack.mcmeta'), JSON.stringify({ pack: { description: 'meta shape test', min_format: 121, max_format: 121 } }));

  log('starting sandbox server in', SERVER);
  const serverLog = fs.createWriteStream(path.join(SANDBOX, 'server-run.log'));
  serverProc = spawn(JAVA_BIN, ['-Xms512M', '-Xmx2G', '-jar', 'paper.jar', '--nogui'], { cwd: SERVER, stdio: ['pipe', 'pipe', 'pipe'] });
  let consoleText = '';
  const onOut = (d) => { serverLog.write(d); consoleText += d.toString(); if (consoleText.length > 4e6) consoleText = consoleText.slice(-2e6); };
  serverProc.stdout.on('data', onOut);
  serverProc.stderr.on('data', onOut);
  await waitFor('server ready', () => /Done \(/.test(consoleText), 180_000, 500);
  await waitFor('API', async () => (await api('GET', '/api/status')).status === 200, 30_000);
  await waitFor('modules loaded', async () => {
    const m = (await api('GET', '/api/modules')).json || [];
    return m.length >= 6 && m.every((x) => x.state === 'ENABLED' || x.state === 'FAILED') ? m : null;
  }, 90_000, 500);
  const mods = (await api('GET', '/api/modules')).json;
  for (const m of mods) check(`module ${m.id} enabled`, m.state === 'ENABLED', m.lastError || '');

  // ------------------------------------------------------------------ pack built
  const pack1 = await waitFor('pack built', async () => { const s = (await api('GET', '/api/pack')).json; return s?.sha1 ? s : null; }, 20000);
  check('pack: built from example modules', pack1.files > 10 && pack1.modules.length >= 4, `${pack1.files} files, modules ${pack1.modules}, ${pack1.sizeBytes} B`);
  const errs = pack1.issues.filter((i) => i.level === 'ERROR');
  check('pack: no asset ERROR in the examples', errs.length === 0, JSON.stringify(pack1.issues).slice(0, 400));
  check('pack: format from version.json', pack1.packFormat === 97, 'packFormat=' + pack1.packFormat);
  const dpl = await cmd('datapack list');
  check('pack.mcmeta shape accepted by the game codec (data pack twin)', /vibe-meta-test/.test(dpl) && !/incompatible/i.test(dpl.split('\n').find((l) => /vibe-meta-test/.test(l)) || ''), dpl.slice(0, 300));

  // ------------------------------------------------------------------ bot joins, receives the push
  await cmd('whitelist add VibeBot');
  bot = new VibeBot({ port: MC_PORT, username: 'VibeBot' });
  const packets = [];
  const joining = bot.connect(); // bot.bot exists synchronously: listen before the join push can arrive
  bot.bot._client.on('add_resource_pack', (p) => packets.push({ at: Date.now(), ...p }));
  bot.bot._client.on('remove_resource_pack', (p) => packets.push({ at: Date.now(), removed: true, ...p }));
  await joining;
  const first = await waitFor('add_resource_pack on join', () => packets.find((p) => !p.removed), 10000).catch(() => null);
  check('push on join: add_resource_pack received', !!first, first ? `url=${first.url} hash=${first.hash} uuid=${first.uuid}` : 'none');
  if (first) {
    check('push: hash = current pack sha1', first.hash === pack1.sha1, `${first.hash} vs ${pack1.sha1}`);
    check('push: not forced (required=false) + prompt', first.forced === false && first.promptMessage != null, `forced=${first.forced}`);
    const res = await fetch(first.url);
    const zip = Buffer.from(await res.arrayBuffer());
    const sha = crypto.createHash('sha1').update(zip).digest('hex');
    check('pack URL serves the zip with the pushed hash', res.status === 200 && sha === first.hash, `HTTP ${res.status}, ${zip.length} B, sha1 ${sha}`);
    const files = unzip(zip);
    const meta = JSON.parse(files.get('pack.mcmeta').toString());
    check('zip: pack.mcmeta min_format/max_format', meta.pack.min_format === 97 && meta.pack.max_format >= 97, JSON.stringify(meta));
    let jsonOk = 0, jsonBad = [];
    for (const [n, b] of files) {
      if (n.endsWith('.json') || n.endsWith('.mcmeta')) {
        try { JSON.parse(b.toString('utf8')); jsonOk++; } catch (e) { jsonBad.push(n + ': ' + e.message); }
      }
    }
    check('zip: every JSON parses', jsonBad.length === 0 && jsonOk > 5, `${jsonOk} ok ${jsonBad.join('; ')}`);
    const laserPng = files.get('assets/laser/textures/item/laser_gun.png');
    check('zip: pixel-art source compiled to a 16x16 PNG', laserPng && String(pngSize(laserPng)) === '16,16', laserPng ? String(pngSize(laserPng)) : 'missing');
    const gui = files.get('assets/vgui/textures/gui/menu.png');
    check('zip: GUI drawn texture 176x166', gui && String(pngSize(gui)) === '176,166', gui ? String(pngSize(gui)) : 'missing');
    check('zip: no text sources leaked into the pack', ![...files.keys()].some((n) => n.endsWith('.png.txt') || n.endsWith('.java')), [...files.keys()].join(' ').slice(0, 300));
    check('zip: deterministic (same bytes when re-downloaded)', crypto.createHash('sha1').update(Buffer.from(await (await fetch(first.url)).arrayBuffer())).digest('hex') === sha);
    fs.mkdirSync(path.join(SANDBOX, 'pack-out'), { recursive: true });
    fs.writeFileSync(path.join(SANDBOX, 'pack-out', 'pack.zip'), zip);
    bot.bot._client.write('resource_pack_receive', { uuid: first.uuid, result: 3 }); // ACCEPTED
    bot.bot._client.write('resource_pack_receive', { uuid: first.uuid, result: 0 }); // SUCCESSFULLY_LOADED
    await sleep(500);
    const st = (await api('GET', '/api/pack')).json;
    check('status event tracked (SUCCESSFULLY_LOADED)', st.players?.VibeBot === 'SUCCESSFULLY_LOADED', JSON.stringify(st.players));
  }

  // ------------------------------------------------------------------ asset change -> re-push, same UUID
  const n0 = packets.length;
  const tex = path.join(MODULES, 'laser-gun', 'assets', 'laser', 'textures', 'item', 'laser_gun.png.txt');
  fs.writeFileSync(tex, fs.readFileSync(tex, 'utf8').replace('"#39e6ff"', '"#ff39e6"'));
  const t0 = Date.now();
  const second = await waitFor('re-push after asset change', () => packets.slice(n0).find((p) => !p.removed), 15000).catch(() => null);
  check('asset edit -> new pack pushed', second && first && second.hash !== first.hash && second.uuid === first.uuid,
    second ? `in ${second.at - t0} ms, hash ${second.hash}, same uuid ${second.uuid === first?.uuid}` : 'no packet');

  // broken asset: reported, other assets still there
  const badModel = path.join(MODULES, 'laser-gun', 'assets', 'laser', 'models', 'item', 'broken.json');
  fs.writeFileSync(badModel, '{"textures": {"0": "laser:item/does_not_exist"}, "elements": [ }');
  const badPx = path.join(MODULES, 'robot-mob', 'assets', 'robot', 'textures', 'entity', 'bad.png.txt');
  fs.writeFileSync(badPx, '{"palette": {"a": "#ff0000"}, "pixels": ["aa", "ab"]}');
  const st2 = await waitFor('issues reported', async () => {
    const s = (await api('GET', '/api/pack')).json;
    return s.issues.some((i) => i.file.endsWith('broken.json')) && s.issues.some((i) => i.file.endsWith('bad.png.txt')) ? s : null;
  }, 15000).catch((e) => ({ issues: [], error: e.message }));
  const bj = st2.issues.find((i) => i.file.endsWith('broken.json'));
  const bp = st2.issues.find((i) => i.file.endsWith('bad.png.txt'));
  check('invalid JSON reported (module + file + line)', bj && bj.level === 'ERROR' && /line/i.test(bj.message), JSON.stringify(bj));
  check('bad pixel art reported (unknown palette char)', bp && /palette/.test(bp.message), JSON.stringify(bp));
  check('other assets still in the pack', st2.files >= pack1.files, `files ${st2.files}`);
  const logs = (await api('GET', '/api/modules/laser-gun/logs?limit=50')).json?.lines || [];
  check('asset problems in the module logs', logs.some((l) => /assets: .*broken\.json/.test(l)), logs.slice(-5).join(' | '));
  check('.vibe/assets.log written', fs.existsSync(path.join(MODULES, 'laser-gun', '.vibe', 'assets.log')));
  fs.rmSync(badModel); fs.rmSync(badPx);

  // missing texture reference
  const ref = path.join(MODULES, 'robot-mob', 'assets', 'robot', 'models', 'entity', 'ghost.json');
  fs.writeFileSync(ref, JSON.stringify({ textures: { 0: 'robot:entity/nope' }, elements: [] }));
  const st3 = await waitFor('missing texture', async () => { const s = (await api('GET', '/api/pack')).json; return s.issues.some((i) => /nope/.test(i.message)) ? s : null; }, 15000).catch(() => null);
  check('missing texture reference reported', !!st3);
  fs.rmSync(ref);

  // ------------------------------------------------------------------ examples in game
  await cmd('gamemode creative VibeBot');
  await cmd('tp VibeBot 0 -60 0');
  let out = await cmd('lasergun VibeBot');
  check('laser-gun: /lasergun gives the item', /donné/.test(out), out);
  out = await cmd('data get entity VibeBot Inventory');
  check('laser-gun: item has item_model laser:laser_gun', /laser:laser_gun/.test(out), out.slice(0, 300));
  out = await cmd('robot VibeBot 2');
  check('robot-mob: /robot spawns', /robot/.test(out), out);
  await sleep(1500);
  out = await cmd('execute if entity @e[type=minecraft:item_display]');
  const displays = Number((out.match(/(\d+)/) || [])[1] || 0);
  check('robot-mob: 2 robots = 6 display parts', displays >= 6, out);
  out = await cmd('execute if entity @e[type=minecraft:husk]');
  check('robot-mob: 2 invisible husks', /2/.test(out), out);
  out = await cmd('data get entity @e[type=minecraft:item_display,limit=1] item');
  check('robot-mob: parts carry robot:* item models', /robot:robot_/.test(out), out.slice(0, 200));
  out = await cmd('data get entity @e[type=minecraft:item_display,limit=1] interpolation_duration');
  check('robot-mob: parts animated (interpolation set)', /\d/.test(out), out.slice(0, 200));
  out = await cmd('menu VibeBot');
  check('custom-gui: /menu opens', /Menu ouvert/.test(out), out);
  const win = await waitFor('open_window', () => bot.bot.currentWindow, 5000).catch(() => null);
  const title = win ? JSON.stringify(win.title) : '';
  check('custom-gui: window title uses the vgui:menu font glyph', /vgui:menu/.test(title) && title.includes(''), title.slice(0, 200));
  if (win) bot.bot.closeWindow(win);
  out = await cmd('hovercar VibeBot');
  check('hover-car: /hovercar spawns + mounts', /Voiture donn/.test(out), out);
  await sleep(1000);
  out = await cmd('execute if entity @e[type=minecraft:interaction]');
  check('hover-car: vehicle entity exists', /1/.test(out), out);
  check('hover-car: bot is riding', !!bot.bot.entity?.vehicle, String(bot.bot.entity?.vehicle?.name));
  // drive: forward for 1 s through the player_input packet
  const vehicleBefore = (await cmd('data get entity @e[type=minecraft:interaction,limit=1] Pos'));
  try {
    bot.bot._client.write('player_input', { inputs: { forward: true, backward: false, left: false, right: false, jump: true, shift: false, sprint: false } });
  } catch (e) { log('player_input write failed', e.message); }
  await sleep(1200);
  try { bot.bot._client.write('player_input', { inputs: { forward: false, backward: false, left: false, right: false, jump: false, shift: false, sprint: false } }); } catch { /* */ }
  const vehicleAfter = (await cmd('data get entity @e[type=minecraft:interaction,limit=1] Pos'));
  check('hover-car: moves with PlayerInput', vehicleBefore !== vehicleAfter, `${vehicleBefore} -> ${vehicleAfter}`);

  // ------------------------------------------------------------------ AI NPC + OpenRouter
  out = await cmd('merlin VibeBot');
  check('ai-npc: /merlin spawns', /Merlin est/.test(out), out);
  const chatBefore = fake.stats.chat;
  bot.bot.chat('Merlin, quel temps fait-il ?');
  const ans = await waitFor('Merlin answer', () => bot.messages.find((m) => /\[Merlin\]/.test(m.text)), 15000).catch(() => null);
  check('ai-npc: answer from (fake) OpenRouter in chat', ans && fake.stats.chat > chatBefore, ans?.text);

  out = await cmd('orprobe');
  const probe = await waitFor('probe log', async () => {
    const l = (await api('GET', '/api/modules/or-probe/logs?limit=50')).json?.lines || [];
    return l.find((x) => /probe answer=/.test(x) || /probe failed/.test(x));
  }, 15000).catch(() => '');
  check('OpenRouter helper: callback OFF the main thread', /thread=VibeCraft-OpenRouter-\d+ primary=false caller=true/.test(probe), probe);
  const probeLogs = (await api('GET', '/api/modules/or-probe/logs?limit=50')).json?.lines || [];
  check('secret redacted in module logs', probeLogs.some((l) => /leak test: \*\*\*/.test(l)) && !probeLogs.some((l) => l.includes(FAKE_KEY)), probeLogs.find((l) => /leak test/.test(l)));
  check('fake server saw Bearer auth only with the right key', fake.stats.unauthorized === 0, `unauthorized=${fake.stats.unauthorized}`);

  // ------------------------------------------------------------------ texture generation endpoint
  const n1 = packets.length;
  const body = JSON.stringify({ path: 'assets/laser/textures/item/ruby.png', prompt: 'a shiny red ruby gem', size: 16 });
  let r = await api('POST', '/api/modules/laser-gun/textures', body);
  check('textures: generate item 16x16 (images API)', r.status === 200 && r.json?.ok && r.json.width === 16, r.text.slice(0, 300));
  const rubyFile = path.join(MODULES, 'laser-gun', 'assets', 'laser', 'textures', 'item', 'ruby.png');
  if (fs.existsSync(rubyFile)) {
    const b = fs.readFileSync(rubyFile);
    // corners must be transparent (magenta keyed out), centre opaque
    const px = decodePngAlpha(b);
    check('textures: background keyed out (corners transparent, centre opaque)', px && px.alpha(0, 0) === 0 && px.alpha(15, 15) === 0 && px.alpha(8, 8) === 255,
      px ? `a(0,0)=${px.alpha(0, 0)} a(8,8)=${px.alpha(8, 8)}` : 'decode failed');
  }
  r = await api('POST', '/api/modules/laser-gun/textures', JSON.stringify({ path: 'assets/laser/textures/block/ruby_block.png', prompt: 'ruby ore', kind: 'block', size: 32 }));
  check('textures: seamless block 32x32', r.status === 200 && r.json?.seamless === true && r.json.width === 32, r.text.slice(0, 200));
  const third = await waitFor('re-push after generated texture', () => packets.slice(n1).find((p) => !p.removed), 15000).catch(() => null);
  check('textures: generated PNG pushed in a new pack', !!third, third?.hash);
  // provider without the /images endpoint -> chat completions with modalities [image, text] (message.images data URL)
  fake.stats.noImagesApi = true;
  const ci = fake.stats.chatImages;
  r = await api('POST', '/api/modules/laser-gun/textures', JSON.stringify({ path: 'assets/laser/textures/entity/slime_skin.png', prompt: 'green slime skin', kind: 'entity', size: 64 }));
  check('textures: fallback to chat completions (modalities image) + entity 64x64', r.status === 200 && r.json?.width === 64 && fake.stats.chatImages === ci + 1,
    r.text.slice(0, 200));
  const lastChat = fake.stats.bodies.filter((b) => b.path === '/api/v1/chat/completions').pop()?.json;
  check('textures: chat request shape (modalities, image_config)', JSON.stringify(lastChat?.modalities) === '["image","text"]' && lastChat?.image_config?.aspect_ratio === '1:1',
    JSON.stringify(lastChat).slice(0, 200));
  fake.stats.noImagesApi = false;
  r = await api('POST', '/api/modules/laser-gun/textures', JSON.stringify({ path: '../../evil.png', prompt: 'x' }));
  check('textures: path traversal rejected (400)', r.status === 400, r.text.slice(0, 150));
  r = await api('POST', '/api/modules/laser-gun/textures', JSON.stringify({ path: 'assets/laser/textures/item/a.png', prompt: 'x', size: 4096 }));
  check('textures: size limit (400)', r.status === 400, r.text.slice(0, 150));
  r = await api('POST', '/api/modules/nope-nope/textures', JSON.stringify({ path: 'assets/x/textures/item/a.png', prompt: 'x' }));
  check('textures: unknown module 404', r.status === 404, r.text.slice(0, 150));
  // no key -> 503 with a French hint (secrets.yml re-read live)
  fs.writeFileSync(secretsFile, '# empty\n');
  await sleep(2500);
  r = await api('POST', '/api/modules/laser-gun/textures', JSON.stringify({ path: 'assets/laser/textures/item/b.png', prompt: 'x' }));
  check('textures: no key -> 503 + where to put it', r.status === 503 && /secrets\.yml/.test(r.json?.error || ''), r.text.slice(0, 200));
  const orStatus = (await api('GET', '/api/openrouter')).json;
  check('GET /api/openrouter: available=false without key', orStatus?.available === false, JSON.stringify(orStatus).slice(0, 150));
  fs.writeFileSync(secretsFile, `openrouter: "${FAKE_KEY}"\n`);
  await sleep(2500);

  // ------------------------------------------------------------------ AI job: env + MCP flags (fake claude)
  r = await api('POST', '/api/ai/ai-job-test', 'fais un objet avec une texture');
  check('AI job started (fake claude)', r.status === 201 || r.status === 200, r.text.slice(0, 200));
  const dump = await waitFor('fake claude dump', () => (fs.existsSync(dumpFile) ? JSON.parse(fs.readFileSync(dumpFile, 'utf8')) : null), 30000).catch(() => null);
  if (dump) {
    const a = dump.argv.join(' ');
    check('AI job: --mcp-config passed', /--mcp-config \S*mcp-test\.json/.test(a), a.slice(0, 400));
    check('AI job: MCP server tools allowed (mcp__fake-images)', dump.argv.includes('mcp__fake-images'), '');
    check('AI job: MCP rule not in --tools', !/--tools \S*mcp__/.test(a), '');
    check('AI job: OPENROUTER_API_KEY in env', dump.env.OPENROUTER_API_KEY_present && dump.env.OPENROUTER_API_KEY_length === FAKE_KEY.length, JSON.stringify(dump.env));
    check('AI job: prompt mentions assets/ASSETS.md', dump.promptMentionsAssets, '');
  } else {
    check('AI job: fake claude ran', false, 'no dump');
  }

  // ------------------------------------------------------------------ secrets never exposed
  const everything = [
    (await api('GET', '/api/status')).text, (await api('GET', '/api/pack')).text, (await api('GET', '/api/openrouter')).text,
    (await api('GET', '/api/ai')).text,
    ...(await Promise.all(mods.map(async (m) => (await api('GET', `/api/modules/${m.id}/logs?limit=500`)).text))),
    ...mods.map((m) => { try { return fs.readFileSync(path.join(MODULES, m.id, '.vibe', 'status.json'), 'utf8'); } catch { return ''; } }),
    consoleText,
  ].join('\n');
  check('secret never in API / status files / console', !everything.includes(FAKE_KEY), '');

  // ------------------------------------------------------------------ module disabled -> its assets leave the pack
  const n2 = packets.length;
  await api('POST', '/api/modules/hover-car/disable');
  const fourth = await waitFor('re-push after disable', () => packets.slice(n2).find((p) => !p.removed), 15000).catch(() => null);
  const st4 = (await api('GET', '/api/pack')).json;
  check('disable module -> pack rebuilt without its assets', fourth && !st4.modules.includes('hover-car'), `modules ${st4.modules}`);
  await api('POST', '/api/modules/hover-car/enable');

  // ------------------------------------------------------------------ final
  const tps = (await api('GET', '/api/status')).json?.server?.tps;
  check('bot never disconnected', bot.disconnects.length === 0, JSON.stringify(bot.disconnects));
  check('TPS healthy', tps && tps[0] > 15, JSON.stringify(tps));
}

/** Tiny PNG alpha reader (8-bit RGBA, filter types 0-4) for the keyed-background check. */
function decodePngAlpha(buf) {
  try {
    const w = buf.readUInt32BE(16), h = buf.readUInt32BE(20);
    const colorType = buf[25];
    let p = 8; const idat = [];
    while (p < buf.length) {
      const len = buf.readUInt32BE(p); const type = buf.toString('ascii', p + 4, p + 8);
      if (type === 'IDAT') idat.push(buf.subarray(p + 8, p + 8 + len));
      p += 12 + len;
    }
    const raw = zlib.inflateSync(Buffer.concat(idat));
    const bpp = colorType === 6 ? 4 : colorType === 2 ? 3 : null;
    if (!bpp) return null;
    const stride = w * bpp; const out = Buffer.alloc(stride * h);
    for (let y = 0; y < h; y++) {
      const f = raw[y * (stride + 1)];
      for (let x = 0; x < stride; x++) {
        const v = raw[y * (stride + 1) + 1 + x];
        const a = x >= bpp ? out[y * stride + x - bpp] : 0;
        const b = y > 0 ? out[(y - 1) * stride + x] : 0;
        const c = x >= bpp && y > 0 ? out[(y - 1) * stride + x - bpp] : 0;
        let r;
        if (f === 0) r = v; else if (f === 1) r = v + a; else if (f === 2) r = v + b; else if (f === 3) r = v + ((a + b) >> 1);
        else { const pp = a + b - c, pa = Math.abs(pp - a), pb = Math.abs(pp - b), pc = Math.abs(pp - c); r = v + (pa <= pb && pa <= pc ? a : pb <= pc ? b : c); }
        out[y * stride + x] = r & 255;
      }
    }
    return { alpha: (x, y) => (bpp === 4 ? out[y * stride + x * 4 + 3] : 255) };
  } catch { return null; }
}

let exitCode = 1;
try {
  await main();
} catch (e) {
  check('run completed', false, e.stack || e.message);
} finally {
  const failed = results.filter((r) => !r.ok);
  console.log('\n==== ' + (results.length - failed.length) + '/' + results.length + ' passed ====');
  for (const f of failed) console.log('FAILED:', f.name, '-', f.detail.slice(0, 300));
  fs.writeFileSync(path.join(SANDBOX, 'assets-report.json'), JSON.stringify(results, null, 2));
  exitCode = failed.length ? 1 : 0;
  if (!args.includes('--keep-running')) {
    try { bot?.bot?.quit(); } catch { /* */ }
    if (serverProc) {
      serverProc.stdin.write('stop\n');
      await new Promise((r) => { const t = setTimeout(() => { serverProc.kill(); r(); }, 30000); serverProc.on('exit', () => { clearTimeout(t); r(); }); });
    }
    await fake?.close();
    process.exit(exitCode);
  } else {
    log('server left running (--keep-running); fake OpenRouter too. Ctrl+C to stop.');
  }
}
void rcon;
