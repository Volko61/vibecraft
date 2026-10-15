// End-to-end check of: resource pack ON DEMAND (no push on join / on change, compass right click pushes),
// AI conversations (stream-json transcript with thinking / tool steps, /api/conversations contract, persistence
// across a restart, secrets redacted), the in-game Claude dialogs (show_dialog packets, custom click actions) and
// the Meshy provider (textures + text-to-3D) against local fake servers. PRIVATE sandbox only (never the live server).
//
// Usage: node tests/e2e/assets/run-conv.mjs [--sandbox <dir>] [--jar <VibeCraft.jar>] [--keep-running]
//   <dir> defaults to <repo>/.sandbox-conv (a copy of .sandbox-assets): <dir>/server (Paper + Via + VibeCraft),
//   <dir>/workspace/modules. Ports: MC 25670, RCON 25671, API 8870, pack 8871, fake OpenRouter 8872, fake Meshy 8873.
//   The script patches plugins/VibeCraft/config.yml (ports, fake claude, Meshy base URL) and copies --jar
//   (default vibecraft-plugin/out-conv/VibeCraft.jar if it exists) into plugins/.
import fs from 'node:fs';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { VibeBot, sleep } from '../lib/bot.mjs';
import { startFakeOpenRouter } from './fake-openrouter.mjs';
import { startFakeMeshy } from './fake-meshy.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const REPO = path.resolve(HERE, '..', '..', '..');
const args = process.argv.slice(2);
const opt = (k, d) => { const i = args.indexOf(k); return i >= 0 ? args[i + 1] : d; };
const SANDBOX = path.resolve(opt('--sandbox', path.join(REPO, '.sandbox-conv')));
const SERVER = path.join(SANDBOX, 'server');
const MODULES = path.join(SANDBOX, 'workspace', 'modules');
const DATA = path.join(SERVER, 'plugins', 'VibeCraft');
const API = 'http://127.0.0.1:8870';
const MC_PORT = 25670;
const OR_KEY = 'sk-or-v1-FAKE-TEST-KEY-0123456789';
const MESHY_KEY = 'msy_FAKE_TEST_KEY_0123456789abcdef';
const JAVA = path.join(REPO, 'runtime', 'jdk-25', 'bin', 'java');
const JAVA_BIN = fs.existsSync(JAVA + '.exe') || fs.existsSync(JAVA) ? JAVA
  : path.join(path.resolve(REPO, '..', '..', '..'), 'runtime', 'jdk-25', 'bin', 'java'); // worktree -> main checkout

const results = [];
const log = (...a) => console.log(new Date().toISOString().slice(11, 19), ...a);
function check(name, ok, detail = '') {
  results.push({ name, ok: !!ok, detail: String(detail ?? '') });
  log(ok ? 'PASS' : 'FAIL', name, detail ? '- ' + String(detail).slice(0, 300) : '');
}
async function api(method, p, body, headers = {}) {
  const res = await fetch(API + p, { method, body: body == null ? undefined : typeof body === 'string' ? body : JSON.stringify(body),
    headers, signal: AbortSignal.timeout(120_000) });
  const text = await res.text();
  let json = null;
  try { json = JSON.parse(text); } catch { /* text */ }
  return { status: res.status, json, text };
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
const sameKeys = (o, keys) => o && typeof o === 'object' && JSON.stringify(Object.keys(o)) === JSON.stringify(keys);
function copyDir(src, dst) {
  fs.mkdirSync(dst, { recursive: true });
  for (const e of fs.readdirSync(src, { withFileTypes: true })) {
    if (e.name === '.vibe') continue;
    const s = path.join(src, e.name), d = path.join(dst, e.name);
    if (e.isDirectory()) copyDir(s, d); else fs.copyFileSync(s, d);
  }
}

// ------------------------------------------------------------------ sandbox setup
function setup() {
  const jar = opt('--jar', path.join(REPO, 'vibecraft-plugin', 'out-conv', 'VibeCraft.jar'));
  if (fs.existsSync(jar)) fs.copyFileSync(jar, path.join(SERVER, 'plugins', 'VibeCraft.jar'));
  const cfgFile = path.join(DATA, 'config.yml');
  let cfg = fs.readFileSync(cfgFile, 'utf8');
  cfg = cfg.replace(/(\napi:[\s\S]*?\n  port: )\d+/, '$18870')
    .replace(/(\nresource-pack:[\s\S]*?\n  port: )\d+/, '$18871')
    .replace(/base-url: http:\/\/127\.0\.0\.1:\d+\/api\/v1/, 'base-url: http://127.0.0.1:8872/api/v1')
    .replace(/claude-path: .*/, `claude-path: "${path.join(HERE, 'fake-claude.mjs').replace(/\\/g, '/')}"`)
    .replace(/FAKE_CLAUDE_DUMP: .*/, `FAKE_CLAUDE_DUMP: "${path.join(SANDBOX, 'claude-dump.json').replace(/\\/g, '/')}"`);
  if (!/\nmeshy:/.test(cfg)) cfg += '\nmeshy:\n  base-url: http://127.0.0.1:8873\n  poll-interval-ms: 300\n  timeout-seconds: 120\n';
  fs.writeFileSync(cfgFile, cfg);
  fs.rmSync(path.join(DATA, 'conversations'), { recursive: true, force: true });
  fs.rmSync(path.join(DATA, 'ai-sessions.yml'), { force: true });
  fs.rmSync(path.join(SERVER, 'world', 'players', 'data'), { recursive: true, force: true }); fs.rmSync(path.join(SERVER, 'world', 'playerdata'), { recursive: true, force: true }); // fresh inventory (no compass yet)
  fs.writeFileSync(path.join(DATA, 'secrets.yml'), `openrouter: "${OR_KEY}"\nmeshy: "${MESHY_KEY}"\n`);
  fs.mkdirSync(MODULES, { recursive: true });
  for (const id of fs.readdirSync(MODULES)) fs.rmSync(path.join(MODULES, id), { recursive: true, force: true });
  copyDir(path.join(REPO, 'workspace', 'examples', 'laser-gun'), path.join(MODULES, 'laser-gun'));
}

let serverProc = null;
let consoleText = '';
async function startServer() {
  log('starting sandbox server in', SERVER);
  consoleText = '';
  const serverLog = fs.createWriteStream(path.join(SANDBOX, 'server-run.log'), { flags: 'a' });
  serverProc = spawn(JAVA_BIN, ['-Xms512M', '-Xmx2G', '-jar', 'paper.jar', '--nogui'], { cwd: SERVER, stdio: ['pipe', 'pipe', 'pipe'] });
  const onOut = (d) => { serverLog.write(d); consoleText += d.toString(); if (consoleText.length > 4e6) consoleText = consoleText.slice(-2e6); };
  serverProc.stdout.on('data', onOut);
  serverProc.stderr.on('data', onOut);
  await waitFor('server ready', () => /Done \(/.test(consoleText), 180_000, 500);
  await waitFor('API', async () => (await api('GET', '/api/status')).status === 200, 30_000);
}
async function stopServer() {
  if (!serverProc) return;
  const p = serverProc;
  serverProc = null;
  p.stdin.write('stop\n');
  await new Promise((r) => { const t = setTimeout(() => { p.kill(); r(); }, 40000); p.on('exit', () => { clearTimeout(t); r(); }); });
}

// ------------------------------------------------------------------ bot helpers
const varint = (n) => { const out = []; do { let b = n & 0x7f; n >>>= 7; if (n) b |= 0x80; out.push(b); } while (n); return Buffer.from(out); };
const mcString = (s) => { const b = Buffer.from(s, 'utf8'); return Buffer.concat([varint(b.length), b]); };
/** Network NBT (nameless root) compound of string tags. */
function nbtStrings(obj) {
  const parts = [Buffer.from([0x0a])];
  for (const [k, v] of Object.entries(obj)) {
    const kb = Buffer.from(k, 'utf8'); const vb = Buffer.from(String(v), 'utf8');
    const h = Buffer.alloc(3); h[0] = 0x08; h.writeUInt16BE(kb.length, 1);
    const l = Buffer.alloc(2); l.writeUInt16BE(vb.length);
    parts.push(h, kb, l, vb);
  }
  parts.push(Buffer.from([0x00]));
  return Buffer.concat(parts);
}
/**
 * Dialog button click as the vanilla client sends it: serverbound custom_click_action (26.1 play id 0x44,
 * ViaVersion maps it to 26.3) = identifier + length-prefixed optional NBT. Written raw: minecraft-data describes
 * the payload as option(anonymousNbt), which is not what the server decodes (it kicks with a DecoderException).
 */
function click(bot, payload) {
  const tag = nbtStrings(payload);
  bot.bot._client.writeRaw(Buffer.concat([varint(0x44), mcString('vibecraft:claude'), varint(tag.length), tag]));
}

let bot = null;
let meshy = null;
let openrouter = null;
async function main() {
  setup();
  openrouter = await startFakeOpenRouter({ port: 8872, key: OR_KEY });
  meshy = await startFakeMeshy({ port: 8873, key: MESHY_KEY });
  await startServer();
  await waitFor('laser-gun loaded', async () => (await api('GET', '/api/modules/laser-gun')).json?.state === 'ENABLED', 90_000, 500);

  // ================================================================== 1. resource pack on demand
  const pack1 = await waitFor('pack built', async () => { const s = (await api('GET', '/api/pack')).json; return s?.sha1 ? s : null; }, 30000);
  check('pack: built, autoPush=false by default', pack1.sha1 && pack1.autoPush === false, `sha ${pack1.sha1} autoPush ${pack1.autoPush}`);

  bot = new VibeBot({ port: MC_PORT, username: 'VibeBot' });
  const packs = [];
  const dialogs = [];
  const joining = bot.connect();
  bot.bot._client.on('add_resource_pack', (p) => packs.push({ at: Date.now(), ...p }));
  bot.bot._client.on('show_dialog', (p) => dialogs.push({ at: Date.now(), json: JSON.stringify(p) }));
  bot.bot._client.on('clear_dialog', () => dialogs.push({ at: Date.now(), cleared: true, json: '' }));
  const actionBars = [];
  for (const n of ['action_bar', 'set_action_bar_text']) bot.bot._client.on(n, (p) => actionBars.push({ at: Date.now(), json: JSON.stringify(p) }));
  await joining;
  await api('POST', '/api/command', 'gamemode creative VibeBot'); // the sandbox world has slimes: a dead bot drops its compass
  await sleep(5000);
  check('join: NO resource pack pushed', packs.length === 0, `${packs.length} add_resource_pack`);
  const compass = await waitFor('compass given', () => bot.bot.inventory.items().find((i) => i.name === 'compass'), 8000).catch(() => null);
  check('join: a compass is given', !!compass, bot.bot.inventory.items().map((i) => i.name).join(','));
  check('join: told how to load the textures', bot.messages.some((m) => /Textures VibeCraft disponibles.*boussole/.test(m.text)),
    bot.messages.slice(-5).map((m) => m.text).join(' | '));

  // asset change -> rebuilt, NOT pushed, announced
  const msgMark = Date.now();
  const tex = path.join(MODULES, 'laser-gun', 'assets', 'laser', 'textures', 'item', 'laser_gun.png.txt');
  fs.writeFileSync(tex, fs.readFileSync(tex, 'utf8').replace('"#39e6ff"', '"#ff39e6"'));
  const pack2 = await waitFor('pack rebuilt', async () => { const s = (await api('GET', '/api/pack')).json; return s.sha1 !== pack1.sha1 ? s : null; }, 20000);
  await sleep(3000);
  check('asset change: pack rebuilt but NOT pushed', packs.length === 0, `sha ${pack2.sha1.slice(0, 10)}, ${packs.length} packets`);
  const note = bot.messages.filter((m) => m.at >= msgMark && /Nouvelles textures dispo.*boussole/.test(m.text));
  check('asset change: "Nouvelles textures dispo : clic droit avec une boussole" (chat + action bar)',
    note.length >= 1, note.map((m) => `${m.position}: ${m.text}`).join(' | '));

  // right click with the compass -> push
  await bot.bot.equip(compass, 'hand');
  await sleep(300);
  bot.bot.activateItem();
  const pushed = await waitFor('push after compass', () => packs[0], 5000).catch(() => null);
  check('compass right click (main hand) -> current pack pushed', pushed && pushed.hash === pack2.sha1, pushed ? `hash ${pushed.hash}` : 'none');
  bot.bot.deactivateItem();
  if (pushed) {
    bot.bot._client.write('resource_pack_receive', { uuid: pushed.uuid, result: 3 });
    bot.bot._client.write('resource_pack_receive', { uuid: pushed.uuid, result: 0 });
  }
  await sleep(3500); // cooldown
  const upMark = Date.now();
  bot.bot.activateItem();
  await sleep(2000);
  bot.bot.deactivateItem();
  const upBars = actionBars.filter((a) => a.at >= upMark).map((a) => a.json);
  check('compass again (up to date): no new push + "déjà à jour" (action bar)', packs.length === 1
    && (upBars.some((j) => /déjà à jour/.test(j)) || bot.messages.some((m) => m.at >= upMark && /déjà à jour/.test(m.text))),
    `${packs.length} packets; bars ${upBars.join(' | ').slice(0, 200)}`);
  // off hand: new version, compass in the off hand, empty main hand
  fs.writeFileSync(tex, fs.readFileSync(tex, 'utf8').replace('"#ff39e6"', '"#39ff6e"'));
  const pack3 = await waitFor('pack rebuilt (3)', async () => { const s = (await api('GET', '/api/pack')).json; return s.sha1 !== pack2.sha1 ? s : null; }, 20000);
  await sleep(1500);
  await bot.bot.equip(bot.bot.inventory.items().find((i) => i.name === 'compass') || compass, 'off-hand');
  await sleep(300);
  await bot.bot.unequip('hand').catch(() => {});
  await sleep(3200);
  bot.bot.activateItem(true);
  const pushed2 = await waitFor('push after off-hand compass', () => packs[1], 5000).catch(() => null);
  check('compass right click (off hand) -> new pack pushed', pushed2 && pushed2.hash === pack3.sha1, pushed2 ? `hash ${pushed2.hash}` : `none (${packs.length})`);
  bot.bot.deactivateItem();

  // ================================================================== 2. providers
  const prov = (await api('GET', '/api/providers')).json;
  check('GET /api/providers shape', sameKeys(prov, ['openrouter', 'meshy', 'claude']) && sameKeys(prov.openrouter, ['available', 'imageModel'])
    && sameKeys(prov.meshy, ['available']) && sameKeys(prov.claude, ['available', 'reason'])
    && prov.openrouter.available && prov.meshy.available && prov.claude.available, JSON.stringify(prov));

  // ================================================================== 3. Claude dialog (in game)
  let d0 = dialogs.length;
  bot.bot.chat('/vibe');
  const listDlg = await waitFor('show_dialog on /vibe', () => dialogs.slice(d0).find((d) => !d.cleared), 6000).catch(() => null);
  check('/vibe -> show_dialog (conversations list)', listDlg && /conversations/.test(listDlg.json) && /Nouvelle conversation/.test(listDlg.json)
    && /laser-gun/.test(listDlg.json), listDlg?.json.slice(0, 300));
  check('dialog uses the vibecraft:claude custom click action', listDlg && /vibecraft:claude/.test(listDlg.json), '');
  d0 = dialogs.length;
  click(bot, { a: 'new' });
  const newDlg = await waitFor('new conversation dialog', () => dialogs.slice(d0).find((d) => !d.cleared), 5000).catch(() => null);
  check('dialog button "Nouvelle conversation" -> form (id, text, provider)', newDlg && /Ta demande/.test(newDlg.json) && /Meshy/.test(newDlg.json),
    newDlg?.json.slice(0, 200));
  const dumpFile = path.join(SANDBOX, 'claude-dump.json');
  fs.rmSync(dumpFile, { force: true });
  d0 = dialogs.length;
  const sendAt = Date.now();
  click(bot, { a: 'create', id: 'conv-test', text: 'fais une fusée [lent] [secret]', provider: 'meshy' });
  const convDlg = await waitFor('conversation dialog', () => dialogs.slice(d0).find((d) => /conv-test/.test(d.json)), 8000).catch(() => null);
  check('dialog "Envoyer" -> job started, conversation dialog shown', !!convDlg, convDlg?.json.slice(0, 200));
  const st1 = await waitFor('running', async () => { const r = (await api('GET', '/api/conversations/conv-test')).json; return r?.state === 'running' ? r : null; }, 10000).catch(() => null);
  check('conversation state "running" while the job runs', !!st1, JSON.stringify(st1)?.slice(0, 200));
  // streaming: the open assistant message grows (same id) through events?since
  const seen = [];
  let count = 0;
  const t0 = Date.now();
  let lastState = 'running';
  while (Date.now() - t0 < 40000) {
    const ev = (await api('GET', `/api/conversations/conv-test/events?since=${count}`)).json;
    if (ev) {
      if (!sameKeys(ev, ['messages', 'state', 'count'])) { check('events shape', false, JSON.stringify(ev).slice(0, 200)); break; }
      for (const m of ev.messages) if (m.role === 'assistant') seen.push({ id: m.id, blocks: m.blocks.length });
      count = ev.count;
      lastState = ev.state;
      if (ev.state !== 'running' && ev.state !== 'queued') break;
    }
    await sleep(700);
  }
  const ids = [...new Set(seen.map((s) => s.id))];
  check('events?since: the running assistant message is re-sent and grows', ids.length >= 1 && seen.filter((s) => s.id === ids[0]).length >= 3
    && Math.max(...seen.map((s) => s.blocks)) > Math.min(...seen.map((s) => s.blocks)), JSON.stringify(seen.slice(0, 12)));
  check('job ended -> state idle', lastState === 'idle', lastState);
  // the dialog task re-sends the finished conversation within ~2 s
  await waitFor('final dialog', () => dialogs.some((d) => d.at > sendAt && /conv-test/.test(d.json) && /Nouveau message/.test(d.json)), 6000).catch(() => null);
  const during = dialogs.filter((d) => d.at > sendAt && /conv-test/.test(d.json));
  const gaps = during.slice(1).map((d, i) => d.at - during[i].at);
  check('dialog refreshed while the job runs (>= 3 updates, >= 1.5 s apart)', during.length >= 3 && gaps.every((g) => g >= 1500),
    `${during.length} dialogs, gaps ${gaps.join(',')}`);
  const finalDlg = during[during.length - 1];
  check('last refresh shows the finished conversation (text input back)', finalDlg && /Nouveau message/.test(finalDlg.json) && /Fini/.test(finalDlg.json),
    finalDlg?.json.slice(-300));
  const nAfter = dialogs.length;
  await sleep(5000);
  check('no dialog spam once idle', dialogs.length === nAfter, `${dialogs.length - nAfter} extra`);

  // ================================================================== 4. transcript content + exact shapes
  const det = (await api('GET', '/api/conversations/conv-test')).json;
  check('GET /api/conversations/{id} shape', sameKeys(det, ['id', 'title', 'state', 'moduleState', 'generation', 'messages']), Object.keys(det || {}).join(','));
  const msgs = det?.messages || [];
  check('messages shape {id, role, at, blocks}', msgs.length > 0 && msgs.every((m) => sameKeys(m, ['id', 'role', 'at', 'blocks'])
    && ['user', 'assistant', 'system'].includes(m.role) && typeof m.at === 'number'), JSON.stringify(msgs[0]));
  const blocks = msgs.flatMap((m) => m.blocks.map((b) => ({ role: m.role, ...b })));
  const allowed = ['type', 'text', 'name', 'input', 'ok'];
  check('blocks: types and fields', blocks.every((b) => ['text', 'thinking', 'tool_use', 'tool_result', 'status'].includes(b.type)
    && Object.keys(b).every((k) => k === 'role' || allowed.includes(k))), JSON.stringify(blocks.find((b) => !allowed.includes(b.type))));
  check('title = first user prompt', det.title === 'fais une fusée [lent] [secret]', det.title);
  check('user message captured', msgs[0]?.role === 'user' && msgs[0].blocks[0]?.text === 'fais une fusée [lent] [secret]', JSON.stringify(msgs[0]));
  const thinking = blocks.find((b) => b.type === 'thinking');
  check('THINKING captured', thinking && /écrire la classe/.test(thinking.text), JSON.stringify(thinking));
  const tu = blocks.filter((b) => b.type === 'tool_use');
  check('tool_use: name + compact input', tu.some((b) => b.name === 'Write' && b.input === 'FuseeModule.java') && tu.some((b) => b.name === 'Bash' && /\/reload/.test(b.input)),
    JSON.stringify(tu));
  const tr = blocks.filter((b) => b.type === 'tool_result');
  check('tool_result: ok + excerpt (sub-agent noise skipped)', tr.length === 3 && tr.every((b) => b.ok === true) && tr[0].name === 'Write'
    && !tr.some((b) => /sub-agent/.test(b.text)), JSON.stringify(tr));
  const status = blocks.filter((b) => b.type === 'status');
  check('status: reload failed, reload ok (v3), job finished', status.some((b) => b.ok === false && /Rechargement refusé/.test(b.text))
    && status.some((b) => b.ok === true && /v3/.test(b.text)) && msgs[msgs.length - 1].role === 'system' && status[status.length - 1].ok === true,
    JSON.stringify(status));
  const asText = JSON.stringify(det);
  check('secret redacted in the transcript', !asText.includes(OR_KEY) && asText.includes('***'), '');
  const dump = fs.existsSync(dumpFile) ? JSON.parse(fs.readFileSync(dumpFile, 'utf8')) : null;
  check('job: --settings file enables thinking (+summaries)', dump?.settings?.alwaysThinkingEnabled === true && dump.settings.showThinkingSummaries === true,
    JSON.stringify(dump?.settings));
  check('job: imageProvider passed (env + prompt)', dump?.env?.VIBECRAFT_IMAGE_PROVIDER === 'meshy' && /Meshy/.test(dump.promptProviderNote),
    `${dump?.env?.VIBECRAFT_IMAGE_PROVIDER} / ${dump?.promptProviderNote}`);
  check('job prompt: compass + models3d documented', dump?.promptMentionsCompass && dump?.promptMentionsModels3d, '');

  const list = (await api('GET', '/api/conversations')).json;
  const keys = ['id', 'title', 'state', 'updatedAt', 'lastLine', 'moduleState', 'generation'];
  check('GET /api/conversations shape + newest first', Array.isArray(list) && list.every((e) => sameKeys(e, keys))
    && list[0].id === 'conv-test' && list.some((e) => e.id === 'laser-gun' && e.title === 'laser-gun' && e.moduleState === 'ENABLED'),
    JSON.stringify(list).slice(0, 400));
  const ev2 = (await api('GET', `/api/conversations/conv-test/events?since=${msgs.length}`)).json;
  check('events?since=count -> only the last message again', ev2.messages.length === 1 && ev2.messages[0].id === msgs[msgs.length - 1].id && ev2.state === 'idle',
    JSON.stringify(ev2).slice(0, 200));

  // ================================================================== 5. POST messages / stop / errors / delete
  let r = await api('POST', '/api/conversations/conv-two/messages', { text: 'deuxième [lent]', imageProvider: 'openrouter', model: '' });
  check('POST messages -> 201 {jobId}', r.status === 201 && sameKeys(r.json, ['jobId']) && /^job-/.test(r.json.jobId), r.text);
  r = await api('POST', '/api/conversations/conv-two/messages', { text: 'encore', imageProvider: 'auto' });
  check('POST messages while running -> 409', r.status === 409, r.text);
  r = await api('POST', '/api/conversations/conv-two/messages', { text: 'x', imageProvider: 'dalle' });
  check('POST messages bad imageProvider -> 400', r.status === 400, r.text);
  r = await api('POST', '/api/conversations/conv-two/messages', { text: '  ', imageProvider: 'auto' });
  check('POST messages empty text -> 400', r.status === 400, r.text);
  r = await api('POST', '/api/conversations/Bad_ID/messages', { text: 'x' });
  check('invalid id -> 400', r.status === 400, r.text);
  r = await api('POST', '/api/conversations/conv-two/messages', { text: 'x'.repeat(20001) });
  check('text too long -> 413', r.status === 413, r.text.slice(0, 100));
  r = await api('DELETE', '/api/conversations/conv-two');
  check('DELETE while running -> 409', r.status === 409, r.text);
  // stop through the dialog button
  await waitFor('conv-two running', async () => (await api('GET', '/api/conversations/conv-two')).json?.state === 'running', 10000).catch(() => null);
  d0 = dialogs.length;
  click(bot, { a: 'stop', c: 'conv-two' });
  const stopped = await waitFor('conv-two stopped', async () => {
    const x = (await api('GET', '/api/conversations/conv-two')).json;
    return x && x.state === 'idle' && x.messages.some((m) => m.blocks.some((b) => b.type === 'status' && /Arrêté/.test(b.text))) ? x : null;
  }, 15000).catch(() => null);
  check('dialog "Arrêter" -> job stopped (status "Arrêté")', !!stopped, '');
  check('dialog re-shown after Arrêter', dialogs.slice(d0).some((d) => /conv-two/.test(d.json)), '');
  // failing job -> state failed
  r = await api('POST', '/api/conversations/conv-fail/messages', { text: 'casse tout [echec]', imageProvider: 'auto' });
  const failed = await waitFor('failed', async () => ((await api('GET', '/api/conversations/conv-fail')).json?.state === 'failed'), 15000).catch(() => false);
  check('failed job -> state "failed"', r.status === 201 && failed, r.text);
  // stop route
  r = await api('POST', '/api/conversations/conv-two/messages', { text: 'troisième [lent]', imageProvider: 'auto' });
  await sleep(1500);
  const sr = await api('POST', '/api/conversations/conv-two/stop');
  check('POST /stop -> {ok:true}', sr.status === 200 && sr.json?.ok === true, sr.text);
  await waitFor('conv-two idle', async () => (await api('GET', '/api/conversations/conv-two')).json?.state === 'idle', 15000).catch(() => null);
  // old routes still work
  const aiList = await api('GET', '/api/ai');
  check('GET /api/ai still works', aiList.status === 200 && Array.isArray(aiList.json) && aiList.json.length >= 3, `${aiList.status}`);
  r = await api('POST', '/api/ai/conv-legacy', 'demande classique');
  check('POST /api/ai/{id} still works (and is recorded as a conversation)', r.status === 201, r.text.slice(0, 100));
  await waitFor('legacy done', async () => (await api('GET', '/api/conversations/conv-legacy')).json?.messages?.some((m) => m.role === 'system'), 15000).catch(() => null);
  const legacy = (await api('GET', '/api/conversations/conv-legacy')).json;
  check('legacy job transcript', legacy?.messages?.[0]?.blocks?.[0]?.text === 'demande classique', JSON.stringify(legacy?.messages?.[0]));

  // ================================================================== 6. Meshy: textures + 3D
  r = await api('POST', '/api/modules/laser-gun/textures', { path: 'assets/laser/textures/item/emerald.png', prompt: 'a green gem', provider: 'meshy' });
  check('textures provider=meshy -> 200 (Meshy text-to-image)', r.status === 200 && r.json?.provider === 'meshy' && meshy.stats.images === 1
    && fs.existsSync(path.join(MODULES, 'laser-gun', 'assets', 'laser', 'textures', 'item', 'emerald.png')), r.text.slice(0, 300));
  const imgBody = meshy.stats.bodies.find((b) => b.path === '/openapi/v1/text-to-image')?.json;
  check('Meshy text-to-image request shape', imgBody?.ai_model === 'nano-banana' && /green gem/.test(imgBody.prompt) && imgBody.remove_background === true,
    JSON.stringify(imgBody).slice(0, 200));
  // default provider = the running job's imageProvider
  r = await api('POST', '/api/conversations/laser-gun/messages', { text: 'texture [lent]', imageProvider: 'meshy' });
  await sleep(500);
  const before = meshy.stats.images;
  const r2 = await api('POST', '/api/modules/laser-gun/textures', { path: 'assets/laser/textures/item/ruby.png', prompt: 'a red gem' });
  check('textures without provider during a job with imageProvider=meshy -> Meshy', r2.status === 200 && r2.json?.provider === 'meshy' && meshy.stats.images === before + 1,
    r2.text.slice(0, 200));
  await api('POST', '/api/conversations/laser-gun/stop');
  await waitFor('laser-gun idle', async () => (await api('GET', '/api/conversations/laser-gun')).json?.state !== 'running', 15000).catch(() => null);
  const r3 = await api('POST', '/api/modules/laser-gun/textures', { path: 'assets/laser/textures/item/topaz.png', prompt: 'a yellow gem' });
  check('textures without provider and no job -> OpenRouter (auto)', r3.status === 200 && r3.json?.provider === 'openrouter', r3.text.slice(0, 200));

  r = await api('POST', '/api/modules/laser-gun/models3d', { path: 'assets/laser/shapes/fake_sphere', prompt: 'a red and blue ball', maxFaces: 1500, size: 1.5 });
  check('models3d -> 202 {taskId, statusUrl}', r.status === 202 && /^m3d-/.test(r.json?.taskId) && r.json?.statusUrl === `/api/modules/laser-gun/models3d/${r.json?.taskId}`,
    r.text.slice(0, 300));
  const t3 = r.json?.statusUrl ? (await api('GET', r.json.statusUrl + '?wait=50')).json : null;
  check('models3d: GET ?wait -> SUCCEEDED (preview + refine + download + convert)', t3?.status === 'SUCCEEDED' && meshy.stats.previews === 1 && meshy.stats.refines === 1,
    JSON.stringify(t3).slice(0, 400));
  const res3 = t3?.result || {};
  check('models3d: decimated (<= maxFaces) and texture baked into colours', res3.meshyFaces === 4800 && res3.faces <= 1500 && res3.faces > 100
    && res3.textured === true && res3.colors >= 2, JSON.stringify(res3).slice(0, 300));
  const mtl = fs.readFileSync(path.join(MODULES, 'laser-gun', 'assets', 'laser', 'shapes', 'fake_sphere.mtl'), 'utf8');
  const kds = [...mtl.matchAll(/Kd ([\d.]+) ([\d.]+) ([\d.]+)/g)].map((m) => m.slice(1).map(Number));
  check('models3d: red top + blue bottom colours from the texture', kds.some(([r0, g, b]) => r0 > 0.6 && b < 0.3) && kds.some(([r0, g, b]) => b > 0.6 && r0 < 0.3),
    JSON.stringify(kds));
  check('models3d: compiled into the pack (object laser:fake_sphere)', res3.compiled?.id === 'laser:fake_sphere', JSON.stringify(res3.compiled).slice(0, 200));
  const packModels = (await api('GET', '/api/pack')).json?.models || [];
  check('GET /api/pack lists the Meshy object', packModels.some((m) => m.id === 'laser:fake_sphere'), packModels.map((m) => m.id).join(','));
  const preview3d = meshy.stats.bodies.find((b) => b.json.mode === 'preview')?.json;
  check('Meshy text-to-3D preview request shape', preview3d?.prompt === 'a red and blue ball' && preview3d.should_remesh === true && preview3d.target_formats?.includes('obj'),
    JSON.stringify(preview3d));
  r = await api('POST', '/api/modules/laser-gun/models3d', { path: 'assets/laser/shapes/x', prompt: 'x', provider: 'openrouter' });
  check('models3d provider=openrouter -> 400', r.status === 400, r.text.slice(0, 150));
  r = await api('POST', '/api/modules/laser-gun/models3d', { path: '../evil', prompt: 'x' });
  check('models3d bad path -> 400', r.status === 400, r.text.slice(0, 150));
  // no key -> 503
  fs.writeFileSync(path.join(DATA, 'secrets.yml'), `openrouter: "${OR_KEY}"\n`);
  await sleep(2500);
  r = await api('POST', '/api/modules/laser-gun/models3d', { path: 'assets/laser/shapes/y', prompt: 'y' });
  const r4 = await api('POST', '/api/modules/laser-gun/textures', { path: 'assets/laser/textures/item/z.png', prompt: 'z', provider: 'meshy' });
  const prov2 = (await api('GET', '/api/providers')).json;
  check('no Meshy key -> 503 (French hint) + providers.meshy.available=false', r.status === 503 && /secrets\.yml/.test(r.json?.error) && r4.status === 503
    && prov2.meshy.available === false, `${r.status} ${r.json?.error?.slice(0, 80)} / ${r4.status}`);
  fs.writeFileSync(path.join(DATA, 'secrets.yml'), `openrouter: "${OR_KEY}"\nmeshy: "${MESHY_KEY}"\n`);
  const allApi = [(await api('GET', '/api/status')).text, (await api('GET', '/api/conversations')).text, (await api('GET', '/api/providers')).text,
    (await api('GET', '/api/modules/laser-gun/logs?limit=500')).text, consoleText].join('\n');
  check('Meshy key never in API / logs / console', !allApi.includes(MESHY_KEY) && meshy.stats.unauthorized === 0, `unauthorized=${meshy.stats.unauthorized}`);

  // ================================================================== 7. delete
  r = await api('DELETE', '/api/conversations/conv-fail');
  const afterDel = (await api('GET', '/api/conversations/conv-fail')).json;
  check('DELETE -> conversation forgotten, module files kept', r.status === 200 && r.json?.ok === true && afterDel?.messages?.length === 0
    && afterDel.title === 'conv-fail' && fs.existsSync(path.join(MODULES, 'conv-fail')) && !fs.existsSync(path.join(DATA, 'conversations', 'conv-fail.json')),
    `${r.text} / ${JSON.stringify(afterDel).slice(0, 150)}`);

  // ================================================================== 8. restart: persistence
  check('bot never disconnected (before restart)', bot.disconnects.length === 0, JSON.stringify(bot.disconnects));
  const tps = (await api('GET', '/api/status')).json?.server?.tps;
  check('TPS healthy', tps && tps[0] > 15, JSON.stringify(tps));
  const beforeRestart = (await api('GET', '/api/conversations/conv-test')).json;
  const fileText = fs.readFileSync(path.join(DATA, 'conversations', 'conv-test.json'), 'utf8');
  check('conversation file persisted, secret-free', fileText.includes('"thinking"') && !fileText.includes(OR_KEY), `${fileText.length} B`);
  try { await bot.quit(); } catch { /* */ }
  bot = null;
  await stopServer();
  await startServer();
  const afterRestart = (await api('GET', '/api/conversations/conv-test')).json;
  check('restart: conversation identical (messages, blocks)', afterRestart && JSON.stringify(afterRestart.messages) === JSON.stringify(beforeRestart.messages)
    && afterRestart.state === 'idle', `${afterRestart?.messages?.length} vs ${beforeRestart.messages.length}`);
  const list2 = (await api('GET', '/api/conversations')).json || [];
  check('restart: conversations listed again', ['conv-test', 'conv-two', 'conv-legacy'].every((id) => list2.some((e) => e.id === id)),
    list2.map((e) => `${e.id}:${e.state}`).join(','));
  r = await api('POST', '/api/conversations/conv-test/messages', { text: 'suite après redémarrage', imageProvider: 'auto' });
  await waitFor('resumed job done', async () => (await api('GET', '/api/conversations/conv-test')).json?.state === 'idle'
    && (await api('GET', '/api/conversations/conv-test')).json.messages.length > beforeRestart.messages.length + 2, 20000).catch(() => null);
  const dump2 = fs.existsSync(dumpFile) ? JSON.parse(fs.readFileSync(dumpFile, 'utf8')) : null;
  check('restart: next message resumes the Claude session (--resume)', dump2?.argv?.includes('--resume') && dump2.argv.includes('fake-session-conv-test'),
    (dump2?.argv || []).join(' ').slice(0, 300));
}

let exitCode = 1;
try {
  await main();
} catch (e) {
  check('run completed', false, e.stack || e.message);
} finally {
  const failed = results.filter((x) => !x.ok);
  console.log('\n==== ' + (results.length - failed.length) + '/' + results.length + ' passed ====');
  for (const f of failed) console.log('FAILED:', f.name, '-', f.detail.slice(0, 300));
  fs.writeFileSync(path.join(SANDBOX, 'conv-report.json'), JSON.stringify(results, null, 2));
  exitCode = failed.length ? 1 : 0;
  if (!args.includes('--keep-running')) {
    try { await bot?.quit(); } catch { /* */ }
    await stopServer();
    await meshy?.close();
    await openrouter?.close();
    process.exit(exitCode);
  } else {
    log('server left running (--keep-running). Ctrl+C to stop.');
  }
}
