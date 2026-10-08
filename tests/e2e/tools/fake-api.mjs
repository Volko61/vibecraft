// A tiny fake of the VibeCraft HTTP API (ARCHITECTURE.md section 5) for unit-checking the e2e runner
// without the real plugin. Console commands / players / TPS are proxied to a real Paper server over RCON;
// modules are simulated from the directories present in `modulesDir` (no code is compiled or loaded).
// `kickOn`: module ids whose reload kicks the bot (simulates an engine that disconnects players).
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { rcon } from '../lib/rcon.mjs';

export function startFakeApi({ port, serverDir, modulesDir, kickOn = [], username = 'VibeBot', log = () => {} }) {
  const mods = new Map();
  const startedAt = Date.now();
  let tpsCache = { at: 0, tps: [20, 20, 20] };

  const dirs = () => {
    try { return fs.readdirSync(modulesDir, { withFileTypes: true }).filter((e) => e.isDirectory()).map((e) => e.name); } catch { return []; }
  };
  const sync = () => {
    const present = new Set(dirs());
    for (const id of present) {
      if (!mods.has(id)) mods.set(id, { generation: 1, state: 'ENABLED', lastReloadAt: Date.now(), lastAttemptAt: Date.now() });
    }
    for (const id of [...mods.keys()]) if (!present.has(id)) mods.delete(id);
  };
  const info = (id) => {
    const m = mods.get(id);
    return {
      id, state: m.state, generation: m.generation, description: '', lastReloadAt: m.lastReloadAt, lastAttemptAt: m.lastAttemptAt,
      lastError: null, diagnostics: [], runtimeErrorCount: 0, recentErrors: [], commands: [], listeners: 0, tasks: 0, rolledBack: false, msPerTick: 0,
    };
  };
  const reloadResult = (id) => {
    const m = mods.get(id);
    return { id, ok: m.state === 'ENABLED', state: m.state, generation: m.generation, error: m.state === 'DISABLED' ? 'module is disabled' : null, diagnostics: [], durationMs: 5, rolledBack: false };
  };
  const tps = async () => {
    if (Date.now() - tpsCache.at > 1000) {
      const out = await rcon(serverDir, 'tps');
      const m = out.match(/:\s*\*?([\d.]+),\s*\*?([\d.]+),\s*\*?([\d.]+)/);
      if (m) tpsCache = { at: Date.now(), tps: [Number(m[1]), Number(m[2]), Number(m[3])] };
    }
    return tpsCache.tps;
  };
  const players = async () => {
    const out = await rcon(serverDir, 'list');
    const names = (out.split(':').slice(1).join(':') || '').split(',').map((s) => s.trim()).filter(Boolean);
    let ops = [];
    try { ops = JSON.parse(fs.readFileSync(path.join(serverDir, 'ops.json'), 'utf8')).map((o) => o.name); } catch { /* none */ }
    return names.map((name) => ({ name, uuid: '', world: 'world', x: 0, y: 0, z: 0, gameMode: 'SURVIVAL', health: 20, op: ops.includes(name) }));
  };

  const server = http.createServer(async (req, res) => {
    const send = (status, body) => {
      const isStr = typeof body === 'string';
      res.writeHead(status, { 'Content-Type': isStr ? 'text/html; charset=utf-8' : 'application/json; charset=utf-8' });
      res.end(isStr ? body : JSON.stringify(body));
    };
    let body = '';
    req.on('data', (c) => { body += c; });
    await new Promise((r) => req.on('end', r));
    const url = new URL(req.url, 'http://x');
    const seg = url.pathname.split('/').filter(Boolean);
    log(`${req.method} ${url.pathname}`);
    try {
      sync();
      if (url.pathname === '/') return send(200, '<!doctype html><html><body>fake VibeCraft dashboard</body></html>');
      if (seg[0] !== 'api') return send(404, { error: 'not found' });
      if (seg[1] === 'status') {
        const ps = await players();
        return send(200, { server: { name: 'fake', version: 'fake', online: ps.length, max: 20, tps: await tps(), uptimeMs: Date.now() - startedAt }, modules: [...mods.keys()].map(info), jobs: [], ideas: [], ai: { available: false, reason: 'fake API' } });
      }
      if (seg[1] === 'players') return send(200, await players());
      if (seg[1] === 'ai' && req.method === 'POST') return send(503, { error: 'AI unavailable (fake API)' });
      if (seg[1] === 'ai' || seg[1] === 'ideas') return send(200, []);
      if (seg[1] === 'command' && req.method === 'POST') {
        const out = await rcon(serverDir, body.replace(/^\//, ''));
        return send(200, { ok: true, output: out.split(/\r?\n/).filter((l) => l !== '') });
      }
      if (seg[1] === 'modules') {
        if (seg.length === 2) return send(200, [...mods.keys()].map(info));
        const id = seg[2];
        if (!/^[a-z0-9][a-z0-9_-]{0,39}$/.test(id)) return send(400, { error: `invalid module id '${id}'` });
        const action = seg[3];
        if (action === 'create' && req.method === 'POST') {
          const dir = path.join(modulesDir, id);
          if (fs.existsSync(dir)) return send(200, { ok: true, created: false });
          fs.mkdirSync(dir, { recursive: true });
          fs.writeFileSync(path.join(dir, 'module.yml'), `description: ${body}\n`);
          fs.writeFileSync(path.join(dir, 'FakeModule.java'), '// fake\n');
          return send(200, { ok: true, created: true });
        }
        if (!mods.has(id)) return send(404, { error: `unknown module '${id}'` });
        const m = mods.get(id);
        if (!action) return send(200, info(id));
        if (action === 'logs') return send(200, { lines: [] });
        if (req.method !== 'POST') return send(405, { error: 'use POST' });
        if (action === 'reload' || action === 'enable') {
          m.lastAttemptAt = Date.now();
          if (action === 'enable') m.state = 'ENABLED';
          if (m.state !== 'DISABLED') { m.generation++; m.lastReloadAt = Date.now(); }
          if (kickOn.includes(id)) await rcon(serverDir, `kick ${username} Simulated: reload of ${id} disconnected the player`).catch(() => {});
          return send(200, reloadResult(id));
        }
        if (action === 'disable') { m.state = 'DISABLED'; return send(200, { ok: true }); }
      }
      return send(404, { error: 'no such endpoint' });
    } catch (e) {
      return send(500, { error: String(e.message || e) });
    }
  });
  return new Promise((resolve) => server.listen(port, '127.0.0.1', () => resolve(server)));
}
