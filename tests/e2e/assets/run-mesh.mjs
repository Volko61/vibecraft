// End-to-end check of "realistic 3D objects" on a PRIVATE sandbox server (never the live one):
// shape.json / OBJ sources compiled into vanilla 26.3 item models (validated here against the rules read in the
// 26.3 client jar), element counts, atlas, previews, Models.spawn display entities (sports car driven by a bot,
// floor lamp with a light block), realistic texture generation used by a shape, hot edits, determinism,
// error reporting, and a bot that stays connected.
//
// Usage: node tests/e2e/assets/run-mesh.mjs --sandbox <dir> [--jar <VibeCraft.jar>] [--keep-running]
//   <dir>/server : Paper 26.3 + Via + VibeCraft (copy of .sandbox-assets) with ports MC 25660, RCON 25661,
//                  api.port 8860, resource-pack.port 8861, openrouter.base-url http://127.0.0.1:8862/api/v1
//   <dir>/workspace/modules : modules dir (the test fills it)
// Previews (rendered by the server's software rasteriser) are copied to tests/e2e/assets/previews/.
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import zlib from 'node:zlib';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { VibeBot, sleep } from '../lib/bot.mjs';
import { startFakeOpenRouter } from './fake-openrouter.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const REPO = path.resolve(HERE, '..', '..', '..');
const args = process.argv.slice(2);
const opt = (k, d) => { const i = args.indexOf(k); return i >= 0 ? args[i + 1] : d; };
const SANDBOX = path.resolve(opt('--sandbox', path.join(REPO, '.sandbox-mesh')));
const SERVER = path.join(SANDBOX, 'server');
const MODULES = path.join(SANDBOX, 'workspace', 'modules');
const JAR = opt('--jar', path.join(REPO, 'vibecraft-plugin', 'out-mesh', 'VibeCraft.jar'));
const API = 'http://127.0.0.1:8860';
const MC_PORT = 25660;
const FAKE_KEY = 'sk-or-v1-FAKE-TEST-KEY-0123456789';
const PREVIEWS = path.join(HERE, 'previews');
const javaIn = (root) => path.join(root, 'runtime', 'jdk-25', 'bin', 'java');
const JAVA_BIN = [REPO, path.resolve(REPO, '..', '..', '..')].map(javaIn).find((j) => fs.existsSync(j + '.exe') || fs.existsSync(j)) || 'java';

const results = [];
const report = { models: [], limits: {} };
const log = (...a) => console.log(new Date().toISOString().slice(11, 19), ...a);
function check(name, ok, detail = '') {
  results.push({ name, ok: !!ok, detail: String(detail) });
  log(ok ? 'PASS' : 'FAIL', name, detail ? '- ' + String(detail).slice(0, 400) : '');
}
async function api(method, p, body) {
  const res = await fetch(API + p, { method, body, signal: AbortSignal.timeout(180_000) });
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
function unzip(buf) {
  let eocd = buf.length - 22;
  while (eocd >= 0 && buf.readUInt32LE(eocd) !== 0x06054b50) eocd--;
  const count = buf.readUInt16LE(eocd + 10);
  let p = buf.readUInt32LE(eocd + 16);
  const files = new Map();
  for (let i = 0; i < count; i++) {
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
const pngSize = (b) => (b && b.subarray(1, 4).toString() === 'PNG' ? [b.readUInt32BE(16), b.readUInt32BE(20)] : null);
function copyDir(src, dst) {
  fs.mkdirSync(dst, { recursive: true });
  for (const e of fs.readdirSync(src, { withFileTypes: true })) {
    if (e.name === '.vibe') continue;
    const s = path.join(src, e.name), d = path.join(dst, e.name);
    if (e.isDirectory()) copyDir(s, d); else fs.copyFileSync(s, d);
  }
}

// ---------------------------------------------------------------------------------------------------------------
// Client rules, read in the 26.3 client jar (net.minecraft.client.resources.model.cuboid.*):
//  CuboidModelElement.Deserializer: from/to components in [-16, 32]; "faces" 1..6 (else "Expected between 1 and 6
//    unique faces, got 0"); rotation = {origin [3], axis x|y|z + angle (ANY float)} or {origin, x, y, z} (Euler,
//    Matrix4f.rotationZYX, any angle), optional rescale; light_emission integer 0..15; shade_direction_override.
//  CuboidFace.Deserializer: uv = 4 floats (optional), texture "#var" required, rotation quadrant 0/90/180/270,
//    tintindex, cullface.  ItemTransform.Deserializer: translation clamped to +-80 px (5 blocks), scale +-4.
//  SpriteLoader: sprite sizes not multiple of 16 lower the atlas mip level ("limits mip level").
// ---------------------------------------------------------------------------------------------------------------
const DIRS = new Set(['down', 'up', 'north', 'south', 'west', 'east']);
function validateModel(name, m, zipFiles) {
  const errs = [];
  const num = (v) => typeof v === 'number' && Number.isFinite(v);
  const vec = (v, n) => Array.isArray(v) && v.length === n && v.every(num);
  const tex = m.textures || {};
  for (const [k, v] of Object.entries(tex)) {
    if (typeof v !== 'string') { errs.push(`textures.${k} not a string`); continue; }
    if (v.startsWith('#')) continue;
    const [ns, p] = v.includes(':') ? v.split(':') : ['minecraft', v];
    if (ns !== 'minecraft' && !zipFiles.has(`assets/${ns}/textures/${p}.png`)) errs.push(`texture ${v} missing in the pack`);
  }
  (m.elements || []).forEach((e, i) => {
    for (const k of ['from', 'to']) {
      if (!vec(e[k], 3)) errs.push(`elements[${i}].${k} not [x,y,z]`);
      else if (e[k].some((c) => c < -16 || c > 32)) errs.push(`elements[${i}].${k} outside -16..32: ${e[k]}`);
    }
    const faces = e.faces && typeof e.faces === 'object' ? Object.keys(e.faces) : [];
    if (faces.length < 1 || faces.length > 6) errs.push(`elements[${i}] has ${faces.length} faces`);
    for (const f of faces) {
      if (!DIRS.has(f)) errs.push(`elements[${i}].faces.${f}: unknown direction`);
      const fo = e.faces[f];
      if (typeof fo.texture !== 'string') errs.push(`elements[${i}].faces.${f}.texture missing`);
      else if (fo.texture.startsWith('#') && !(fo.texture.slice(1) in tex)) errs.push(`elements[${i}].faces.${f}: ${fo.texture} undefined`);
      if (fo.uv !== undefined && (!vec(fo.uv, 4) || fo.uv.some((c) => c < 0 || c > 16))) errs.push(`elements[${i}].faces.${f}.uv invalid: ${fo.uv}`);
      if (fo.rotation !== undefined && ![0, 90, 180, 270].includes(fo.rotation)) errs.push(`elements[${i}].faces.${f}.rotation`);
    }
    if (e.rotation !== undefined) {
      const r = e.rotation;
      if (!vec(r.origin, 3)) errs.push(`elements[${i}].rotation.origin`);
      if ('axis' in r || 'angle' in r) {
        if (!['x', 'y', 'z'].includes(r.axis) || !num(r.angle)) errs.push(`elements[${i}].rotation axis/angle`);
      } else if (!['x', 'y', 'z'].some((k) => k in r) || ['x', 'y', 'z'].some((k) => k in r && !num(r[k]))) {
        errs.push(`elements[${i}].rotation needs axis+angle or x/y/z`);
      }
    }
    if (e.light_emission !== undefined && !(Number.isInteger(e.light_emission) && e.light_emission >= 0 && e.light_emission <= 15)) {
      errs.push(`elements[${i}].light_emission`);
    }
  });
  for (const [ctx, t] of Object.entries(m.display || {})) {
    if (t.translation && t.translation.some((c) => Math.abs(c) > 80)) errs.push(`display.${ctx}.translation clamped (>80)`);
    if (t.scale && t.scale.some((c) => Math.abs(c) > 4)) errs.push(`display.${ctx}.scale clamped (>4)`);
  }
  return errs;
}

// Dense OBJ for the voxel path: UV sphere 32 x 16 (512 faces) with a stripe material, written as text.
function blobObj() {
  const L = ['# vibecraft {"mode": "voxel", "resolution": 40, "size": 1.5}', 'mtllib blob.mtl'];
  const N = 32, M = 16;
  const id = (i, j) => 2 + (i - 1) * N + (j % N);
  L.push('v 0 -1 0');
  for (let i = 1; i < M; i++) {
    const t = Math.PI * i / M;
    for (let j = 0; j < N; j++) {
      const a = 2 * Math.PI * j / N;
      const r = Math.sin(t) * (1 + 0.15 * Math.sin(3 * a));
      L.push(`v ${(r * Math.cos(a)).toFixed(4)} ${(-Math.cos(t)).toFixed(4)} ${(r * Math.sin(a)).toFixed(4)}`);
    }
  }
  L.push('v 0 1 0');
  const top = 2 + (M - 1) * N;
  for (let i = 1; i <= M; i++) {
    L.push(`usemtl ${i % 2 ? 'teal' : 'cream'}`);
    for (let j = 0; j < N; j++) {
      // counter-clockwise seen from outside (angle grows from +x to +z, rings bottom to top)
      if (i === 1) L.push(`f 1 ${id(1, j)} ${id(1, j + 1)}`);
      else if (i === M) L.push(`f ${id(M - 1, j)} ${top} ${id(M - 1, j + 1)}`);
      else L.push(`f ${id(i - 1, j)} ${id(i, j)} ${id(i, j + 1)} ${id(i - 1, j + 1)}`);
    }
  }
  return L.join('\n') + '\n';
}

const LAB_JAVA = `package vibe.mesh_lab;

import dev.vibecraft.api.ModuleContext;
import dev.vibecraft.api.Models;
import dev.vibecraft.api.VibeModule;

public class MeshLabModule implements VibeModule {
    @Override
    public void enable(ModuleContext ctx) {
        ctx.command("meshlab", (sender, args) -> {
            if (args.length > 0 && args[0].equals("spawn")) {
                var m = Models.spawn(ctx, args[1], sender instanceof org.bukkit.entity.Player p ? p.getLocation()
                        : ctx.server().getWorlds().get(0).getSpawnLocation(), Double.parseDouble(args[2]));
                sender.sendRichMessage("<green>spawned " + m.id() + " displays=" + m.displays().size() + " parts=" + m.parts());
                return;
            }
            if (args.length > 0 && args[0].equals("missing")) {
                try {
                    Models.spawn(ctx, "lab:nope", ctx.server().getWorlds().get(0).getSpawnLocation(), 1);
                } catch (IllegalArgumentException e) {
                    sender.sendRichMessage("<red>" + e.getMessage());
                }
                return;
            }
            sender.sendRichMessage("<gray>ids=" + Models.ids());
        });
    }
}
`;

let serverProc = null;
let fake = null;
let bot = null;
async function main() {
  // ------------------------------------------------------------------ setup
  if (fs.existsSync(JAR)) fs.copyFileSync(JAR, path.join(SERVER, 'plugins', 'VibeCraft.jar'));
  fake = await startFakeOpenRouter({ port: 8862, key: FAKE_KEY });
  fs.mkdirSync(MODULES, { recursive: true });
  for (const id of fs.readdirSync(MODULES)) fs.rmSync(path.join(MODULES, id), { recursive: true, force: true });
  for (const id of ['sports-car', 'floor-lamp']) copyDir(path.join(REPO, 'workspace', 'examples', id), path.join(MODULES, id));
  const lab = path.join(MODULES, 'mesh-lab');
  const w = (rel, text) => { const f = path.join(lab, rel); fs.mkdirSync(path.dirname(f), { recursive: true }); fs.writeFileSync(f, text); };
  w('MeshLabModule.java', LAB_JAVA);
  w('assets/lab/shapes/crate.shape.json', JSON.stringify({
    detail: 'medium',
    materials: { oak: { texture: 'lab:block/oak_real', tile: 0.5 }, iron: '#6b6f78' },
    shapes: [
      { type: 'box', material: 'oak', size: [1, 1, 1], at: [0, 0.5, 0], round: 0.04 },
      { type: 'box', material: 'iron', size: [1.04, 0.08, 1.04], at: [0, 0.2, 0] },
      { type: 'box', material: 'iron', size: [1.04, 0.08, 1.04], at: [0, 0.8, 0] },
    ],
  }, null, 1));
  w('assets/lab/shapes/blob.obj', blobObj());
  w('assets/lab/shapes/blob.mtl', 'newmtl teal\nKd 0.1 0.6 0.6\nnewmtl cream\nKd 0.95 0.9 0.8\n');
  // hand-written model using the 26.3 rotation forms (any angle, Euler x/y/z): must NOT be reported
  w('assets/lab/models/item/euler.json', JSON.stringify({
    textures: { 0: 'lab:item/mesh/crate' },
    elements: [
      { from: [4, 4, 4], to: [12, 12, 12], rotation: { origin: [8, 8, 8], x: 30, y: 60, z: 10 }, faces: { north: { uv: [0, 0, 4, 4], texture: '#0' } } },
      { from: [4, 0, 4], to: [12, 2, 12], rotation: { origin: [8, 8, 8], axis: 'y', angle: 67.5 }, faces: { up: { texture: '#0' } } },
    ],
  }));
  // element without faces: the client rejects the whole model -> must be reported as ERROR
  w('assets/lab/models/item/nofaces.json', JSON.stringify({ textures: { 0: 'lab:item/mesh/crate' }, elements: [{ from: [0, 0, 0], to: [1, 1, 1], faces: {} }] }));
  fs.writeFileSync(path.join(SERVER, 'plugins', 'VibeCraft', 'secrets.yml'), `openrouter: "${FAKE_KEY}"\n`);

  log('starting sandbox server in', SERVER, 'with', JAVA_BIN);
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
    return m.length >= 3 && m.every((x) => x.state === 'ENABLED' || x.state === 'FAILED') ? m : null;
  }, 90_000, 500);
  for (const m of (await api('GET', '/api/modules')).json) check(`module ${m.id} enabled`, m.state === 'ENABLED', m.lastError || '');

  // ------------------------------------------------------------------ pack + compiled objects
  const pack = await waitFor('pack with models', async () => {
    const s = (await api('GET', '/api/pack')).json;
    return s?.sha1 && (s.models || []).length >= 4 ? s : null;
  }, 30000);
  const byId = Object.fromEntries(pack.models.map((m) => [m.id, m]));
  for (const m of pack.models) {
    report.models.push({ id: m.id, mode: m.mode, elements: m.elements, quads: m.quads, atlas: m.atlas, sizeBlocks: m.sizeBlocks,
      parts: Object.fromEntries(Object.entries(m.parts).map(([k, v]) => [k, v.elements])) });
    log(`  model ${m.id}: ${m.elements} elements, ${m.quads} quads, mode ${m.mode}, atlas ${m.atlas}, size ${m.sizeBlocks}`);
  }
  check('car compiled (exact mode, 5 parts)', byId['sportscar:roadster']?.mode === 'exact' && Object.keys(byId['sportscar:roadster'].parts).length === 5,
    JSON.stringify(byId['sportscar:roadster'] && { e: byId['sportscar:roadster'].elements, parts: Object.keys(byId['sportscar:roadster'].parts) }));
  check('car element count reasonable (< 1500)', byId['sportscar:roadster']?.elements < 1500, byId['sportscar:roadster']?.elements);
  check('lamp compiled from OBJ (exact, < 1500 elements)', byId['lamp:floor_lamp']?.mode === 'exact' && byId['lamp:floor_lamp'].elements < 1500,
    `${byId['lamp:floor_lamp']?.mode} ${byId['lamp:floor_lamp']?.elements}`);
  check('dense OBJ compiled in voxel mode', byId['lab:blob']?.mode === 'voxel' && byId['lab:blob'].elements > 50, `${byId['lab:blob']?.mode} ${byId['lab:blob']?.elements}`);
  check('size normalisation ("size": 1.5)', byId['lab:blob'] && Math.abs(Math.max(...byId['lab:blob'].sizeBlocks) - 1.5) < 0.1, JSON.stringify(byId['lab:blob']?.sizeBlocks));
  const exErr = pack.issues.filter((i) => i.level === 'ERROR' && i.module !== 'mesh-lab');
  check('no asset ERROR in the 3D examples', exErr.length === 0, JSON.stringify(exErr).slice(0, 400));
  const exWarn = pack.issues.filter((i) => i.module !== 'mesh-lab');
  check('no asset WARNING in the 3D examples', exWarn.length === 0, JSON.stringify(exWarn).slice(0, 400));
  const labIssues = pack.issues.filter((i) => i.module === 'mesh-lab');
  check('Euler / any-angle rotations accepted (26.3 rules)', !labIssues.some((i) => i.file.endsWith('euler.json')), JSON.stringify(labIssues.filter((i) => i.file.endsWith('euler.json'))));
  check('element without faces reported as ERROR', labIssues.some((i) => i.file.endsWith('nofaces.json') && i.level === 'ERROR' && /faces/.test(i.message)));
  check('missing material texture -> WARNING, colour fallback', labIssues.some((i) => i.file.endsWith('crate.shape.json') && i.level === 'WARNING' && /oak_real/.test(i.message)),
    JSON.stringify(labIssues).slice(0, 300));
  const alog = fs.readFileSync(path.join(MODULES, 'sports-car', '.vibe', 'assets.log'), 'utf8');
  check('assets.log reports the 3D object (elements, preview)', /3D sportscar:roadster: \d+ elements/.test(alog) && /previews/.test(alog), alog.slice(0, 300));

  // ------------------------------------------------------------------ zip: every model valid for the 26.3 client
  const zipBuf = Buffer.from(await (await fetch('http://127.0.0.1:8861/pack.zip')).arrayBuffer());
  const files = unzip(zipBuf);
  let models = 0, elements = 0, maxEls = 0, maxPartEls = 0, bad = [];
  let eulerEls = 0, rotatedEls = 0, flatEls = 0;
  for (const [n, b] of files) {
    if (!/^assets\/(sportscar|lamp|lab)\/models\/.*\.json$/.test(n) || n.endsWith('nofaces.json')) continue;
    let m;
    try { m = JSON.parse(b.toString('utf8')); } catch (e) { bad.push(`${n}: ${e.message}`); continue; }
    models++;
    const els = m.elements || [];
    elements += els.length;
    maxEls = Math.max(maxEls, els.length);
    const isPart = /\/models\/[^/]+\/[^/]+\.json$/.test(n) && !/\/models\/item\//.test(n); // models/<object>/<part>.json
    if (isPart) maxPartEls = Math.max(maxPartEls, els.length);
    for (const e of els) {
      if (e.rotation) { rotatedEls++; if (!('axis' in e.rotation)) eulerEls++; }
      if (e.from.some((c, i) => c === e.to[i])) flatEls++;
    }
    for (const err of validateModel(n, m, files)) bad.push(`${n}: ${err}`);
  }
  report.limits = { models, elements, maxElementsPerModel: maxEls, maxElementsPerPartModel: maxPartEls, rotatedEls, eulerEls, flatEls };
  check('zip: generated models valid for the 26.3 client (bounds, faces, rotation forms, uv, refs)', bad.length === 0 && models >= 8,
    `${models} models, ${elements} elements (max ${maxEls}/model, ${rotatedEls} rotated incl. ${eulerEls} Euler, ${flatEls} flat) ${bad.slice(0, 5).join('; ')}`);
  check('zip: part models (display entities) split at <= 1024 elements', maxPartEls > 0 && maxPartEls <= 1024, maxPartEls);
  let atlasBad = [];
  for (const [n, b] of files) {
    if (!/textures\/item\/mesh\/.*\.png$/.test(n)) continue;
    const s = pngSize(b);
    if (!s || s[0] % 16 || s[1] % 16 || s[0] > 1024 || s[1] > 1024) atlasBad.push(`${n} ${s}`);
  }
  check('zip: baked atlases are multiples of 16 (no mip-level drop) and <= 1024', atlasBad.length === 0 && files.has('assets/sportscar/textures/item/mesh/roadster.png'), atlasBad.join(' '));
  const itemsOk = ['sportscar/items/roadster.json', 'sportscar/items/roadster/wheel_fl.json', 'lamp/items/floor_lamp.json'].every((p) => files.has('assets/' + p));
  check('zip: item definitions for the whole object and each part', itemsOk);
  check('zip: no 3D text sources in the pack (.shape.json/.obj/.mtl)', ![...files.keys()].some((n) => /\.(shape\.json|obj|mtl)$/.test(n)));

  // ------------------------------------------------------------------ previews
  fs.mkdirSync(PREVIEWS, { recursive: true });
  let previews = 0;
  for (const id of ['sports-car', 'floor-lamp', 'mesh-lab']) {
    const dir = path.join(MODULES, id, '.vibe', 'previews');
    if (!fs.existsSync(dir)) continue;
    for (const f of fs.readdirSync(dir)) {
      if (!f.endsWith('.png')) continue;
      fs.copyFileSync(path.join(dir, f), path.join(PREVIEWS, f));
      previews++;
    }
  }
  check('previews rendered (.vibe/previews/*.png) and copied to tests/e2e/assets/previews', previews >= 4 && pngSize(fs.readFileSync(path.join(PREVIEWS, 'sportscar_roadster.png')))?.[0] >= 512, `${previews} previews`);

  // ------------------------------------------------------------------ determinism
  const shapeFile = path.join(MODULES, 'sports-car', 'assets', 'sportscar', 'shapes', 'roadster.shape.json');
  const sha0 = (await api('GET', '/api/pack')).json.sha1;
  const built0 = (await api('GET', '/api/pack')).json.builtAt;
  fs.writeFileSync(shapeFile, fs.readFileSync(shapeFile)); // same bytes, new mtime -> rebuild
  const st = await waitFor('rebuild after touch', async () => { const s = (await api('GET', '/api/pack')).json; return s.builtAt !== built0 ? s : null; }, 15000).catch(() => null);
  check('deterministic: same sources -> same pack sha1 after a rebuild', st && st.sha1 === sha0, `${sha0} -> ${st?.sha1}`);

  // ------------------------------------------------------------------ bot joins, accepts the pack
  await cmd('whitelist add VibeBot');
  bot = new VibeBot({ port: MC_PORT, username: 'VibeBot' });
  const packets = [];
  const joining = bot.connect();
  bot.bot._client.on('add_resource_pack', (p) => packets.push({ at: Date.now(), ...p }));
  await joining;
  const first = await waitFor('pack push', () => packets[0], 10000).catch(() => null);
  check('pack pushed on join', !!first, first?.hash);
  if (first) {
    bot.bot._client.write('resource_pack_receive', { uuid: first.uuid, result: 3 });
    bot.bot._client.write('resource_pack_receive', { uuid: first.uuid, result: 0 });
  }
  await cmd('gamemode creative VibeBot');
  await cmd('tp VibeBot 0.5 -60 0.5 0 0');
  await sleep(1000);

  // ------------------------------------------------------------------ floor lamp (OBJ) in the world
  let out = await cmd('lampe VibeBot');
  check('/lampe places the OBJ lamp', /Lampadaire posé/.test(out), out);
  await sleep(500);
  out = await cmd('execute if block 0 -59 2 minecraft:light');
  check('lamp: real light block at the bulb', /passed/i.test(out), out);
  out = await cmd('execute if entity @e[type=minecraft:item_display]');
  const lampDisplays = Number((out.match(/(\d+)/) || [])[1] || 0);
  check('lamp: display entities spawned', lampDisplays >= 1, out);
  out = await cmd('data get entity @e[type=minecraft:item_display,limit=1] item_display');
  check('displays use the "none" transform (model frame = world)', /none/.test(out), out);
  out = await cmd('data get entity @e[type=minecraft:item_display,limit=1] item.components');
  check('lamp display item_model = lamp:floor_lamp/main', /lamp:floor_lamp\/main/.test(out), out.slice(0, 200));
  out = await cmd('lampe item VibeBot');
  out = await cmd('data get entity VibeBot Inventory');
  check('/lampe item: held item with the whole-object model', /lamp:floor_lamp/.test(out), out.slice(0, 200));
  out = await cmd('lampe clear');
  out = await cmd('execute if block 0 -59 2 minecraft:light');
  check('lamp clear: light block removed', /failed/i.test(out), out);

  // ------------------------------------------------------------------ sports car: spawn, ride, drive
  const msBefore = (await api('GET', '/api/modules/sports-car')).json?.msPerTick;
  out = await cmd('sportscar VibeBot');
  check('/sportscar spawns the car', /Voiture de sport donn/.test(out), out);
  await sleep(1200);
  out = await cmd('execute if entity @e[type=minecraft:item_display]');
  const carDisplays = Number((out.match(/(\d+)/) || [])[1] || 0);
  const expected = Object.values(byId['sportscar:roadster'].parts).reduce((a, p) => a + p.models.length, 0);
  check('car: one display per part model', carDisplays === expected, `${carDisplays} displays, expected ${expected}`);
  check('car: bot is riding the seat', !!bot.bot.entity?.vehicle, String(bot.bot.entity?.vehicle?.name));
  // Model.ride: displays ride the seat at its passenger attachment (seat top); their translation subtracts it
  const seatY = Number(((await cmd('data get entity @e[type=minecraft:interaction,limit=1] Pos[1]')).match(/(-?[\d.]+)d/) || [])[1]);
  const dispY = Number(((await cmd('data get entity @e[type=minecraft:item_display,limit=1] Pos[1]')).match(/(-?[\d.]+)d/) || [])[1]);
  const trY = Number(((await cmd('data get entity @e[type=minecraft:item_display,limit=1] transformation.translation[1]')).match(/(-?[\d.]+)f/) || [])[1]);
  report.ride = { seatY, dispY, translationY: trY };
  check('car: displays ride the seat at its top (attachment = 0.45)', Math.abs(dispY - seatY - 0.45) < 0.01, `seat ${seatY}, display ${dispY}, translation y ${trY}`);
  out = await cmd('execute as @e[type=minecraft:item_display] run data get entity @s transformation.scale');
  check('car: displays scaled by the part unit', /\d/.test(out), out.slice(0, 200));
  const posBefore = await cmd('data get entity @e[type=minecraft:interaction,limit=1] Pos');
  const rotBefore = await cmd('execute as @e[type=minecraft:item_display] run data get entity @s transformation.left_rotation');
  bot.bot._client.write('player_input', { inputs: { forward: true, backward: false, left: true, right: false, jump: false, shift: false, sprint: false } });
  await sleep(2500);
  bot.bot._client.write('player_input', { inputs: { forward: false, backward: false, left: false, right: false, jump: false, shift: false, sprint: false } });
  const posAfter = await cmd('data get entity @e[type=minecraft:interaction,limit=1] Pos');
  const rotAfter = await cmd('execute as @e[type=minecraft:item_display] run data get entity @s transformation.left_rotation');
  check('car: drives with PlayerInput (seat moved)', posBefore !== posAfter, `${posBefore} -> ${posAfter}`);
  check('car: wheels animated (part rotations changed)', rotBefore !== rotAfter, rotAfter.slice(0, 200));
  out = await cmd('execute as @e[type=minecraft:item_display,limit=1] run data get entity @s Rotation');
  check('car: displays turned with the car (yaw != 0 after steering)', !/\[0\.0f, 0\.0f\]/.test(out), out);
  const ms = (await api('GET', '/api/modules/sports-car')).json?.msPerTick;
  check('car: cheap on the main thread (< 1 ms/tick)', ms !== undefined && ms < 1, `msPerTick ${msBefore} -> ${ms}`);

  // Models API: spawn by command, missing id message
  out = await cmd('meshlab spawn lab:blob 2');
  check('Models.spawn of the voxel object (scale 2)', /spawned lab:blob displays=\d+/.test(out), out);
  out = await cmd('meshlab missing');
  check('Models.spawn unknown id -> clear message', /not found: compiled objects are/.test(out), out.slice(0, 200));

  // ------------------------------------------------------------------ hot reload: displays cleaned
  const nBefore = Number(((await cmd('execute if entity @e[type=minecraft:item_display]')).match(/(\d+)/) || [])[1] || 0);
  // a reload with unchanged files is a no-op: disable + enable unloads the generation for real
  let r = await api('POST', '/api/modules/sports-car/disable');
  await sleep(500);
  const nAfter = Number(((await cmd('execute if entity @e[type=minecraft:item_display]')).match(/(\d+)/) || [])[1] || 0);
  check('unload removes the car displays (tracked by ctx)', nAfter === nBefore - carDisplays, `${nBefore} -> ${nAfter}`);
  r = await api('POST', '/api/modules/sports-car/enable');
  check('sports-car enabled again', r.status === 200 && (await api('GET', '/api/modules/sports-car')).json?.state === 'ENABLED', r.text.slice(0, 200));

  // ------------------------------------------------------------------ realistic texture -> material of a shape
  const n0 = packets.length;
  r = await api('POST', '/api/modules/mesh-lab/textures', JSON.stringify({ path: 'assets/lab/textures/block/oak_real.png', prompt: 'old oak planks', kind: 'realistic' }));
  check('textures: kind realistic -> 128x128, no palette reduction, seamless', r.status === 200 && r.json?.width === 128 && r.json?.seamless === true && r.json?.colors > 48,
    r.text.slice(0, 250));
  const st2 = await waitFor('crate rebuilt with the texture', async () => {
    const s = (await api('GET', '/api/pack')).json;
    return !s.issues.some((i) => /oak_real/.test(i.message)) && s.models.some((m) => m.id === 'lab:crate') ? s : null;
  }, 20000).catch(() => null);
  const crate = st2?.models.find((m) => m.id === 'lab:crate');
  const area = (a) => (a ? a.split('x').reduce((x, y) => x * Number(y), 1) : 0);
  check('shape material uses the generated texture (warning gone, bigger textured atlas)', !!crate && area(crate.atlas) > area(byId['lab:crate']?.atlas),
    `${byId['lab:crate']?.atlas} -> ${crate?.atlas}`);
  const pushed = await waitFor('re-push', () => packets.slice(n0)[0], 15000).catch(() => null);
  check('new pack pushed after the texture', !!pushed);

  // ------------------------------------------------------------------ hot edit of a shape + broken shape
  const n1 = packets.length;
  fs.writeFileSync(shapeFile, fs.readFileSync(shapeFile, 'utf8').replace('"paint": "#c8102e"', '"paint": "#1040c8"'));
  const p3 = await waitFor('re-push after shape edit', () => packets.slice(n1)[0], 15000).catch(() => null);
  check('shape edit (repaint) -> new pack pushed, no disconnect', !!p3 && bot.disconnects.length === 0, p3?.hash);
  w('assets/lab/shapes/broken.shape.json', JSON.stringify({ shapes: [{ type: 'box', size: [1, 1, 1] }, { type: 'pyramid' }] }));
  const st3 = await waitFor('broken shape reported', async () => {
    const s = (await api('GET', '/api/pack')).json;
    return s.issues.some((i) => i.file.endsWith('broken.shape.json')) ? s : null;
  }, 15000).catch(() => null);
  const bi = st3?.issues.find((i) => i.file.endsWith('broken.shape.json'));
  check('broken shape: ERROR naming the JSON path, object left out', bi?.level === 'ERROR' && /shapes\[1\]/.test(bi.message) && /pyramid/.test(bi.message), JSON.stringify(bi));
  check('other objects still compiled', st3?.models.some((m) => m.id === 'sportscar:roadster'));
  const labLogs = (await api('GET', '/api/modules/mesh-lab/logs?limit=80')).json?.lines || [];
  check('3D problems in the module logs', labLogs.some((l) => /broken\.shape\.json/.test(l)), labLogs.slice(-3).join(' | '));

  // ------------------------------------------------------------------ final
  const tps = (await api('GET', '/api/status')).json?.server?.tps;
  check('bot never disconnected', bot.disconnects.length === 0, JSON.stringify(bot.disconnects));
  check('TPS healthy', tps && tps[0] > 15, JSON.stringify(tps));
  report.buildMs = (await api('GET', '/api/pack')).json.buildMs;
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
  console.log('report:', JSON.stringify(report, null, 1));
  fs.writeFileSync(path.join(SANDBOX, 'mesh-report.json'), JSON.stringify({ results, report }, null, 2));
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
    log('server left running (--keep-running). Ctrl+C to stop.');
  }
}
void crypto;
