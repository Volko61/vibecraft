#!/usr/bin/env node
// VibeCraft dashboard mock server: zero dependencies, follows docs/ARCHITECTURE.md section 5.
// Serves src/main/resources/web/index.html (re-read on every request) and a fake, evolving JSON API:
// modules compiling / reloading / rolling back / tripping, AI conversations (one per module) whose jobs stream
// thinking -> tool_use -> tool_result -> text -> status blocks, audience ideas arriving.
// Conversation API: GET /api/conversations, GET /api/conversations/{id}, GET .../{id}/events?since=n,
// POST .../{id}/messages {text, imageProvider, model?}, POST .../{id}/stop, DELETE .../{id}, GET /api/providers.
//
// Usage: node vibecraft-plugin/dev/mock-api.mjs [--port 8768] [--host 127.0.0.1]
//          [--token secret]   require the X-Vibe-Token header (401 otherwise)
//          [--no-ai]          ai.available=false (POST /api/ai/{id} and conversation messages -> 503)
//          [--meshy]          Meshy AI available (off by default, to show the disabled pill)
//          [--no-openrouter]  OpenRouter unavailable
//          [--static]         no automatic demo activity (only reacts to requests)
//          [--verbose]        log every request
// Test helpers (outside /api, never need the token):
//   GET /__mock/outage?seconds=5     /api/* requests are dropped (simulates a dead server)
//   GET /__mock/ai?on=0|1            toggle AI availability
//   GET /__mock/token?value=xyz      change the required token at runtime (empty = none)
//   GET /__mock/job?module=x&prompt=y  start a fake AI job
//   GET /__mock/providers?meshy=1&openrouter=0  toggle image providers

import http from 'node:http';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

// ---------------------------------------------------------------------------- options
function parseArgs(argv) {
  const out = {};
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (!a.startsWith('--')) continue;
    const key = a.slice(2);
    const next = argv[i + 1];
    if (next != null && !next.startsWith('--')) { out[key] = next; i++; } else out[key] = true;
  }
  return out;
}
const args = parseArgs(process.argv.slice(2));
const PORT = Number(args.port || process.env.PORT || 8768);
const HOST = typeof args.host === 'string' ? args.host : '127.0.0.1';
let TOKEN = typeof args.token === 'string' ? args.token : (process.env.MOCK_TOKEN || '');
let aiAvailable = !args['no-ai'];
const AUTO = !args.static;
const VERBOSE = !!args.verbose;
const HERE = path.dirname(fileURLToPath(import.meta.url));
const INDEX = path.resolve(HERE, '../src/main/resources/web/index.html');

const ID_RE = /^[a-z0-9][a-z0-9_-]{0,39}$/;
const MAX_CONCURRENT = 2;
const STARTED = Date.now();
let outageUntil = 0;

const rnd = (a, b) => a + Math.random() * (b - a);
const pick = (xs) => xs[Math.floor(Math.random() * xs.length)];
const pascal = (id) => id.split(/[-_]+/).filter(Boolean).map((w) => w[0].toUpperCase() + w.slice(1)).join('') || 'Module';
const hhmmss = (t = Date.now()) => new Date(t).toTimeString().slice(0, 8);
const short = (s, n) => (s.length > n ? `${s.slice(0, n - 1)}…` : s);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ---------------------------------------------------------------------------- modules
/** Internal module record: ModuleInfo fields + simulation fields prefixed with "_". */
const modules = new Map();

function diag(kind, file, line, column, message, sourceLine) {
  return { kind, file, line, column, message, sourceLine };
}

function addModule(id, fields = {}) {
  const t = Date.now();
  const m = {
    id, state: 'DISCOVERED', generation: 0, description: '', lastReloadAt: 0, lastAttemptAt: 0,
    lastError: null, diagnostics: [], runtimeErrorCount: 0, recentErrors: [], commands: [],
    listeners: 0, tasks: 0, rolledBack: false, msPerTick: 0,
    _broken: null, _logs: [], _baseMs: rnd(0.05, 0.6), _errRate: 0, _compiling: null, _cmds: [],
    ...fields
  };
  if (m.generation > 0 && !m.lastReloadAt) m.lastReloadAt = t - rnd(20e3, 600e3);
  if (!m.lastAttemptAt) m.lastAttemptAt = m.lastReloadAt || 0;
  modules.set(id, m);
  log(m, 'INFO', `module découvert (${id})`);
  return m;
}

function info(m) {
  return {
    id: m.id, state: m.state, generation: m.generation, description: m.description,
    lastReloadAt: m.lastReloadAt, lastAttemptAt: m.lastAttemptAt, lastError: m.lastError,
    diagnostics: m.diagnostics, runtimeErrorCount: m.runtimeErrorCount, recentErrors: m.recentErrors,
    commands: m.commands, listeners: m.listeners, tasks: m.tasks, rolledBack: m.rolledBack,
    msPerTick: Math.round(m.msPerTick * 1000) / 1000
  };
}

function log(m, level, msg) {
  m._logs.push(`[${hhmmss()} ${level}] [VibeCraft/${m.id}] ${msg}`);
  if (m._logs.length > 500) m._logs.splice(0, m._logs.length - 500);
}

function compileErrors(m) {
  const file = `${pascal(m.id)}Module.java`;
  return pick([
    [diag('ERROR', file, 42, 17, 'cannot find symbol\n  symbol:   class Pig\n  location: class ' + pascal(m.id) + 'Module', 'Pig pig = world.spawn(loc, Pig.class);')],
    [diag('ERROR', file, 18, 9, "';' expected", 'ctx.broadcast("<green>Hop !")'),
      diag('WARNING', file, 7, 1, '[removal] Player.setBedSpawnLocation(Location) has been deprecated', 'p.setBedSpawnLocation(loc);')],
    [diag('ERROR', file, 31, 33, 'incompatible types: possible lossy conversion from double to int', 'int height = loc.getY() + 10;')],
    [diag('ERROR', '', -1, -1, 'interdit : appel à System.exit (ligne 25)', '')]
  ]);
}

/** Simulated compile + hot swap. outcome: 'ok' | 'compile' (compile error) | 'enable' (enable() throws). */
function simReload(m, outcome, delayMs = rnd(700, 1800)) {
  if (m._compiling) return m._compiling;
  const t0 = Date.now();
  const before = m.state;
  m.state = 'COMPILING';
  log(m, 'INFO', 'compilation…');
  m._compiling = sleep(delayMs).then(() => {
    m._compiling = null;
    m.lastAttemptAt = Date.now();
    outcome = outcome || m._broken || 'ok';
    let result;
    if (outcome === 'ok') {
      m.generation += 1;
      m.state = 'ENABLED';
      m.lastReloadAt = Date.now();
      m.lastError = null;
      m.diagnostics = Math.random() < 0.2 ? [diag('WARNING', `${pascal(m.id)}Module.java`, 12, 5, '[unchecked] unchecked cast', 'List<String> l = (List<String>) o;')] : [];
      m.rolledBack = false;
      m.runtimeErrorCount = 0;
      m.recentErrors = [];
      if (!m._cmds.length && Math.random() < 0.6) m._cmds = [m.id.split('-')[0].slice(0, 12)];
      m.commands = m._cmds.slice();
      m.listeners = Math.floor(rnd(1, 4));
      m.tasks = Math.floor(rnd(0, 3));
      log(m, 'INFO', `v${m.generation} chargée à chaud`);
      result = { ok: true, error: null };
    } else {
      const error = outcome === 'compile'
        ? `compilation failed: ${m.diagnostics.length || 1} error(s)`
        : 'enable() a lancé IllegalStateException: monde introuvable';
      m.diagnostics = outcome === 'compile' ? compileErrors(m) : [];
      const errs = m.diagnostics.filter((d) => d.kind === 'ERROR').length || 1;
      m.lastError = outcome === 'compile' ? `compilation failed: ${errs} error(s)` : error;
      if (m.generation > 0 && before !== 'DISABLED') {
        m.state = 'ENABLED';
        m.rolledBack = true;
        log(m, 'WARN', `échec, retour à v${m.generation} : ${m.lastError}`);
      } else {
        m.state = 'FAILED';
        m.rolledBack = false;
        m.commands = [];
        m.listeners = 0;
        m.tasks = 0;
        log(m, 'SEVERE', `échec : ${m.lastError}`);
      }
      result = { ok: false, error: m.lastError };
    }
    return {
      id: m.id, ok: result.ok, state: m.state, generation: m.generation, error: result.error,
      diagnostics: m.diagnostics, durationMs: Date.now() - t0, rolledBack: m.rolledBack
    };
  });
  return m._compiling;
}

function disable(m) {
  m.state = 'DISABLED';
  m.commands = [];
  m.listeners = 0;
  m.tasks = 0;
  m.msPerTick = 0;
  m.rolledBack = false;
  log(m, 'INFO', 'désactivé');
}

function runtimeError(m) {
  const where = pick(['PlayerJumpEvent', 'EntityDamageEvent', 'commande /' + (m.commands[0] || 'x'), 'tâche planifiée']);
  const ex = pick([
    'NullPointerException: Cannot invoke "org.bukkit.Location.getWorld()" because "loc" is null',
    'IllegalArgumentException: amount must be positive',
    'ModuleTimeoutError: budget de 250 ms dépassé',
    'ArrayIndexOutOfBoundsException: Index 3 out of bounds for length 3'
  ]);
  const line = `${hhmmss()} ${where}: ${ex} (${pascal(m.id)}Module.java:${Math.floor(rnd(10, 90))})`;
  m.recentErrors.push(line);
  if (m.recentErrors.length > 10) m.recentErrors.shift();
  m.runtimeErrorCount += 1;
  log(m, 'WARN', line);
}

addModule('pluie-de-cochons', {
  state: 'ENABLED', generation: 4, description: 'Fait pleuvoir des cochons autour des joueurs qui sautent',
  commands: ['cochons', 'stopcochons'], _cmds: ['cochons', 'stopcochons'], listeners: 2, tasks: 1, _baseMs: 0.42, _errRate: 0.06
});
addModule('double-saut', {
  state: 'ENABLED', generation: 2, description: 'Double saut en appuyant deux fois sur espace', rolledBack: true,
  lastError: 'compilation failed: 2 error(s)', commands: ['doublesaut'], _cmds: ['doublesaut'], listeners: 1, _broken: 'compile',
  diagnostics: [
    diag('ERROR', 'DoubleSautModule.java', 27, 21, 'cannot find symbol\n  symbol:   method setAllowFlight(boolean)\n  location: variable p of type Entity', 'p.setAllowFlight(true);'),
    diag('ERROR', 'util/Velocity.java', 9, 40, "')' expected", 'return new Vector(v.getX(), 0.8, v.getZ();')
  ]
});
addModule('arc-explosif', {
  state: 'FAILED', description: 'Les flèches explosent à l\'impact', lastError: 'compilation failed: 1 error(s)', _broken: 'compile',
  diagnostics: [diag('ERROR', 'ArcExplosifModule.java', 14, 9, 'incompatible types: Arrow cannot be converted to Projectile[]', 'Projectile[] p = event.getEntity();')]
});
addModule('meteo-folle', {
  state: 'TRIPPED', generation: 3, description: 'La météo change toutes les 10 secondes', runtimeErrorCount: 25,
  lastError: 'disjoncteur : 25 erreurs en 10 s', _errRate: 0,
  recentErrors: Array.from({ length: 10 }, (_, i) => `${hhmmss(Date.now() - (10 - i) * 900)} tâche planifiée: NullPointerException: Cannot invoke "org.bukkit.World.setStorm(boolean)" because "w" is null (MeteoFolleModule.java:31)`)
});
addModule('parkour', { state: 'DISABLED', generation: 1, description: 'Parcours chronométré au spawn' });
addModule('boutique', { description: 'Boutique de villageois personnalisée' });
addModule('sapin-geant', {
  state: 'ENABLED', generation: 7, description: 'Fait pousser un sapin géant avec /sapin', commands: ['sapin'], _cmds: ['sapin'],
  listeners: 1, tasks: 2, _baseMs: 1.6
});

// ---------------------------------------------------------------------------- conversations & AI jobs
// One conversation per module. A job is one "run" of Claude Code inside a conversation: its script is a list of
// structured steps (thinking / tool_use / tool_result / text / status) streamed into the conversation messages.
let jobSeq = 0;
let msgSeq = 0;
const jobs = []; // internal job records, creation order
const conversations = new Map(); // id -> { id, title, createdAt, updatedAt, messages: [...], _cur }

const providers = {
  openrouter: { available: !args['no-openrouter'], imageModel: 'google/gemini-2.5-flash-image' },
  meshy: { available: !!args.meshy }
};

function job(j) {
  return {
    id: j.id, moduleId: j.moduleId, prompt: j.prompt, requestedBy: j.requestedBy, state: j.state,
    startedAt: j.startedAt, endedAt: j.endedAt, progress: j.progress, result: j.result,
    sessionId: j.sessionId, costUsd: j.costUsd
  };
}

function ensureConversation(id, title, at = Date.now()) {
  let c = conversations.get(id);
  if (!c) {
    c = { id, title: short(title || id, 52), createdAt: at, updatedAt: at, messages: [], _cur: null };
    conversations.set(id, c);
  }
  return c;
}

function touch(c, msg, at = Date.now()) {
  c.updatedAt = Math.max(c.updatedAt, at);
  if (msg) msg._upd = Date.now();
}

function pushMessage(c, role, blocks, at = Date.now()) {
  const m = { id: `m${++msgSeq}`, role, at, blocks, _upd: Date.now() };
  c.messages.push(m);
  touch(c, m, at);
  return m;
}

/** Current assistant message to append blocks to (a new one if the last message is not an assistant message). */
function assistantMessage(c, at) {
  const last = c.messages[c.messages.length - 1];
  if (c._cur && last === c._cur) return c._cur;
  c._cur = pushMessage(c, 'assistant', [], at);
  return c._cur;
}

const JAVA_SNIPPET = (id) => `package modules.${id.replace(/-/g, '_')};

import dev.vibecraft.api.Module;
import dev.vibecraft.api.ModuleContext;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public final class ${pascal(id)}Module implements Module, Listener {
    @Override
    public void enable(ModuleContext ctx) {
        ctx.listen(this);
    }

    @EventHandler
    public void onJump(com.destroystokyo.paper.event.player.PlayerJumpEvent e) {
        Location loc = e.getPlayer().getLocation();
        loc.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, loc, 20);
    }
}
`;

function scriptFor(moduleId, prompt, fail, opts = {}) {
  const dir = `modules/${moduleId}`;
  const file = `${dir}/${pascal(moduleId)}Module.java`;
  const reloadCmd = `curl -s -X POST http://127.0.0.1:8765/api/modules/${moduleId}/reload`;
  const cmd = moduleId.split('-')[0].slice(0, 12);
  const wantsImage = opts.imageProvider === 'meshy' || /textur|image|mod[eè]le|3d|skin|bloc/i.test(prompt);
  const steps = [
    { think: `L'utilisateur demande : « ${short(prompt, 110)} ». Je relis d'abord CLAUDE.md pour l'API des modules (ctx.listen, ctx.command, ctx.every), puis je regarde ce qui existe déjà dans ${dir}. Il faut rester simple et rapide : un seul fichier, pas de boucle lourde dans les événements.` },
    { tool: 'Read', input: { file_path: 'CLAUDE.md' } },
    { result: '# VibeCraft — guide des modules\n\nChaque module vit dans modules/<id>/ et implémente dev.vibecraft.api.Module.\nRechargez avec curl -X POST .../api/modules/<id>/reload jusqu\'à ok:true.\n… (214 lignes)', ok: true },
    { tool: 'Glob', input: { pattern: `${dir}/**/*.java` } },
    { result: conversations.get(moduleId)?.messages.length > 2 ? file : 'No files found', ok: true },
    { text: `Je vais implémenter ça dans **${moduleId}**. Le plan :\n\n1. un écouteur sur le saut du joueur ;\n2. l'effet demandé autour de lui ;\n3. une commande \`/${cmd}\` pour l'activer ou le couper.\n\nVoici le cœur de l'idée :\n\n\`\`\`java\n@EventHandler\npublic void onJump(PlayerJumpEvent e) {\n    Location loc = e.getPlayer().getLocation();\n    loc.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, loc, 20);\n}\n\`\`\`` },
    { tool: 'Write', input: { file_path: file, content: JAVA_SNIPPET(moduleId) } },
    { result: `File created successfully at: ${file}`, ok: true }
  ];
  if (wantsImage) {
    const provider = opts.imageProvider === 'meshy' ? 'meshy' : 'openrouter';
    steps.push(
      { think: provider === 'meshy' ? 'Il faut un vrai modèle 3D : je passe par Meshy puis je le compile en modèle vanilla.' : 'Il me faut une texture 16×16 : je la génère avec OpenRouter, puis le resource pack est poussé à chaud.' },
      { tool: 'Bash', input: { command: provider === 'meshy'
        ? `curl -s -X POST http://127.0.0.1:8765/api/models/${moduleId}/generate -d "un champignon lumineux low-poly"`
        : `curl -s -X POST http://127.0.0.1:8765/api/textures/${moduleId}/generate -d "bloc de slime arc-en-ciel, pixel art 16x16"`, description: 'Générer l\'asset' } },
      { result: provider === 'meshy' ? '{"ok":true,"model":"vibecraft:item/champignon","faces":412}' : '{"ok":true,"texture":"vibecraft:block/slime_rainbow","size":"16x16"}', ok: true }
    );
  }
  steps.push(
    { tool: 'Bash', input: { command: reloadCmd, description: 'Recharger le module' }, act: 'compile' },
    { result: () => `{"ok":false,"error":"compilation failed: 1 error(s)","diagnostics":[{"kind":"ERROR","file":"${pascal(moduleId)}Module.java","line":18,"message":"cannot find symbol\\n  symbol: variable Particle"}]}`, ok: false },
    { status: `⚠ ${moduleId} : compilation échouée, version précédente conservée`, ok: false },
    { think: 'Il manque l\'import org.bukkit.Particle. Je l\'ajoute et je recharge.' },
    { tool: 'Edit', input: { file_path: file, old_string: 'import org.bukkit.Location;', new_string: 'import org.bukkit.Location;\nimport org.bukkit.Particle;' } },
    { result: `The file ${file} has been updated successfully.`, ok: true }
  );
  if (fail) {
    steps.push(
      { tool: 'Bash', input: { command: reloadCmd, description: 'Recharger le module' }, act: 'enable' },
      { result: '{"ok":false,"error":"enable() a lancé IllegalStateException: monde introuvable","rolledBack":true}', ok: false },
      { status: `✖ ${moduleId} : enable() a levé une exception`, ok: false },
      { text: 'Je n\'ai pas réussi à finir : l\'API utilisée **n\'existe pas** dans cette version de Paper. Il faudra une autre approche (par exemple passer par `ctx.every` au lieu de l\'événement).' },
      { end: 'FAILED' }
    );
  } else {
    steps.push(
      { tool: 'Bash', input: { command: reloadCmd, description: 'Recharger le module' }, act: 'ok' },
      { result: () => { const m = modules.get(moduleId); return `{"ok":true,"state":"ENABLED","generation":${m ? m.generation : 1},"durationMs":143}`; }, ok: true },
      { status: () => { const m = modules.get(moduleId); return `✔ ${moduleId} v${m ? m.generation : 1} chargé à chaud`; }, ok: true },
      { text: pick([
        `C'est en ligne ! Sautez pour voir l'effet, et tapez \`/${cmd}\` pour l'activer ou le couper.`,
        `Fonctionnalité ajoutée au module **${moduleId}**, rechargée à chaud sans redémarrer le serveur :\n\n- l'effet se déclenche à chaque saut ;\n- \`/${cmd}\` l'active ou le coupe.`,
        'Voilà ! Tout est déjà actif sur le serveur, *aucun redémarrage* nécessaire.'
      ]) },
      { end: 'SUCCEEDED' }
    );
  }
  return steps;
}

function progressLine(step, value) {
  if (step.tool) {
    const i = step.input || {};
    if (step.tool === 'Read') return `📖 ${i.file_path}`;
    if (step.tool === 'Write' || step.tool === 'Edit') return `✏ ${i.file_path}`;
    if (step.tool === 'Bash' && /reload/.test(i.command || '')) return '🔄 rechargement';
    return `🔧 ${step.tool}`;
  }
  if (step.result && step.ok === false) return '⚠ erreur';
  if (step.text) return `💬 ${short(value.replace(/\s+/g, ' '), 90)}`;
  return null;
}

function createJob(moduleId, prompt, requestedBy, opts = {}) {
  const at = opts.at || Date.now();
  const c = ensureConversation(moduleId, opts.title || prompt, at);
  pushMessage(c, 'user', [{ type: 'text', text: prompt }], at);
  c._cur = null;
  const j = {
    id: `job-${++jobSeq}`, moduleId, prompt, requestedBy, state: 'QUEUED', startedAt: 0, endedAt: 0,
    progress: [], result: null, sessionId: null, costUsd: 0, imageProvider: opts.imageProvider || 'auto', model: opts.model || null,
    _steps: scriptFor(moduleId, prompt, opts.fail ?? Math.random() < 0.2, opts), _i: 0, _next: 0, _stream: null
  };
  jobs.push(j);
  if (!modules.has(moduleId)) {
    const m = addModule(moduleId, { description: short(prompt, 60) });
    log(m, 'INFO', 'module créé par l\'IA');
  }
  if (!opts.instant) startQueued();
  return j;
}

function activeJob(moduleId) {
  return jobs.find((j) => j.moduleId === moduleId && (j.state === 'QUEUED' || j.state === 'RUNNING'));
}
function lastJob(moduleId) {
  for (let i = jobs.length - 1; i >= 0; i--) if (jobs[i].moduleId === moduleId) return jobs[i];
  return null;
}

function startQueued() {
  let running = jobs.filter((j) => j.state === 'RUNNING').length;
  for (const j of jobs) {
    if (running >= MAX_CONCURRENT) break;
    if (j.state !== 'QUEUED') continue;
    j.state = 'RUNNING';
    j.startedAt = Date.now();
    j.sessionId = j.sessionId || `sess-${Math.random().toString(16).slice(2, 10)}`;
    j.progress.push('🚀 Démarrage de Claude Code…');
    j._next = Date.now() + 700;
    running++;
  }
}

function flushStream(j) {
  const s = j._stream;
  if (!s) return;
  s.block.text = s.full;
  touch(conversations.get(j.moduleId), s.msg);
  j._stream = null;
}

function finishJob(j, state, opts = {}) {
  flushStream(j);
  j.state = state;
  j.endedAt = Date.now();
  if (!j.startedAt) j.startedAt = j.endedAt;
  if (state !== 'STOPPED' || j.progress.length > 1) j.costUsd = Math.round(rnd(0.03, 0.42) * 100) / 100;
  const c = conversations.get(j.moduleId);
  if (c) {
    const secs = Math.max(1, Math.round((j.endedAt - j.startedAt) / 1000));
    const cost = j.costUsd ? ` · ${j.costUsd.toFixed(2).replace('.', ',')} $` : '';
    const text = state === 'SUCCEEDED' ? `Tâche terminée en ${secs} s${cost}`
      : state === 'FAILED' ? `Tâche échouée après ${secs} s${cost}` : 'Tâche arrêtée';
    pushMessage(c, 'system', [{ type: 'status', text, ok: state === 'SUCCEEDED' }], opts.at || Date.now());
    c._cur = null;
  }
  if (state === 'SUCCEEDED') {
    j.progress.push('✅ Terminé');
    const m = modules.get(j.moduleId);
    if (m) m._broken = null;
  } else if (state === 'STOPPED') j.progress.push('⏹ arrêté');
  if (!opts.instant) startQueued();
}

/** Applies one step. instant = no streaming / no simulated reload (seeding history). */
function applyStep(j, step, instant, at = Date.now()) {
  const c = conversations.get(j.moduleId);
  if (!c) return;
  const value = typeof step.result === 'function' ? step.result()
    : typeof step.status === 'function' ? step.status()
      : step.think || step.text || step.result || step.status || '';
  if (step.think || step.text) {
    const msg = assistantMessage(c, at);
    const block = { type: step.think ? 'thinking' : 'text', text: instant ? value : '' };
    msg.blocks.push(block);
    touch(c, msg, at);
    if (!instant) j._stream = { msg, block, full: value, pos: 0 };
    if (step.text) j.result = value;
  } else if (step.tool) {
    const msg = assistantMessage(c, at);
    msg.blocks.push({ type: 'tool_use', name: step.tool, input: step.input });
    touch(c, msg, at);
  } else if (step.result) {
    const msg = assistantMessage(c, at);
    msg.blocks.push({ type: 'tool_result', text: value, ok: step.ok !== false });
    touch(c, msg, at);
  } else if (step.status) {
    pushMessage(c, 'system', [{ type: 'status', text: value, ok: step.ok !== false }], at);
    c._cur = null;
  }
  const line = progressLine(step, value);
  if (line) j.progress.push(line);
  if (j.progress.length > 200) j.progress.splice(0, j.progress.length - 200);
  if (!instant && step.act) {
    const m = modules.get(j.moduleId);
    if (m) {
      if (step.act === 'ok') m._broken = null;
      simReload(m, step.act, 900);
    }
  }
}

/** Streams the current thinking/text block a few words at a time. Returns true while streaming. */
function streamTick(j) {
  const s = j._stream;
  if (!s) return false;
  const rest = s.full.slice(s.pos);
  const m = /^(\s*\S+){1,4}/.exec(rest);
  const n = m ? m[0].length : rest.length;
  s.pos += Math.max(1, n);
  s.block.text = s.full.slice(0, s.pos);
  touch(conversations.get(j.moduleId), s.msg);
  if (s.pos >= s.full.length) { j._stream = null; j._next = Date.now() + rnd(500, 1100); }
  return true;
}

function advanceJobs() {
  const t = Date.now();
  for (const j of jobs) {
    if (j.state !== 'RUNNING') continue;
    if (streamTick(j)) continue;
    if (t < j._next) continue;
    const step = j._steps[j._i++];
    if (!step || step.end) { finishJob(j, step ? step.end : 'SUCCEEDED'); continue; }
    applyStep(j, step, false);
    const wait = step.tool ? rnd(700, 1600) : step.result ? rnd(500, 1000) : step.status ? rnd(700, 1200) : 300;
    j._next = t + wait;
  }
}

/** Runs a whole job instantly in the past (seeded history). stopAfter = number of steps before a STOP. */
function seedRun(moduleId, prompt, by, startedAt, opts = {}) {
  const j = createJob(moduleId, prompt, by, { ...opts, at: startedAt, instant: true });
  j.state = 'RUNNING';
  j.startedAt = startedAt + 400;
  j.sessionId = `sess-${(4096 + jobSeq).toString(16)}`;
  let at = startedAt + 1500;
  let n = 0;
  for (const step of j._steps) {
    if (opts.stopAfter != null && n >= opts.stopAfter) { j.endedAt = at; finishJob(j, 'STOPPED', { at, instant: true }); j.endedAt = at; return j; }
    if (step.end) { finishJob(j, step.end, { at, instant: true }); j.endedAt = at; return j; }
    applyStep(j, step, true, at);
    at += Math.round(rnd(1500, 6000));
    n++;
  }
  return j;
}

function conversationState(id) {
  const a = activeJob(id);
  if (a) return a.state === 'RUNNING' ? 'running' : 'queued';
  const l = lastJob(id);
  return l && l.state === 'FAILED' ? 'failed' : 'idle';
}

function msgOut(m) { return { id: m.id, role: m.role, at: m.at, blocks: m.blocks.map((b) => ({ ...b })) }; }

function conversationSummary(c) {
  const mod = modules.get(c.id);
  const a = activeJob(c.id) || lastJob(c.id);
  let lastLine = '';
  if (a && a.progress.length) lastLine = a.progress[a.progress.length - 1];
  return {
    id: c.id, title: c.title, state: conversationState(c.id), updatedAt: c.updatedAt, lastLine,
    moduleState: mod ? mod.state : null, generation: mod ? mod.generation : 0
  };
}
function conversationFull(c) {
  return { ...conversationSummary(c), messages: c.messages.map(msgOut) };
}
const conversationList = () => [...conversations.values()].sort((a, b) => b.updatedAt - a.updatedAt).map(conversationSummary);

// Seed history + one running + one queued.
(function seedJobs() {
  const t = Date.now();
  seedRun('pluie-de-cochons', 'Fais pleuvoir des cochons quand quelqu\'un saute', 'Steve', t - 1900e3, { fail: false });
  seedRun('pluie-de-cochons', 'Ajoute des particules de cœur quand un cochon touche le sol', 'dashboard', t - 1500e3, { fail: false });
  seedRun('arc-explosif', 'Les flèches explosent quand elles touchent un bloc', 'Alex', t - 1200e3, { fail: true });
  seedRun('meteo-folle', 'Change la météo toutes les 10 secondes', 'dashboard', t - 900e3, { fail: false, stopAfter: 6 });
  seedRun('sapin-geant', 'Ajoute une commande /sapin qui fait pousser un sapin géant avec une texture de guirlande', 'console', t - 600e3, { fail: false });
  createJob('double-saut', 'Corrige le double saut : il faut pouvoir sauter une deuxième fois en l\'air', 'Notch', { fail: false });
  createJob('boutique', 'Crée une boutique de villageois qui vend des diamants contre de la terre', 'Alex');
  createJob('feux-artifice', 'Lance un feu d\'artifice quand un joueur rejoint le serveur', 'dashboard');
})();


// ---------------------------------------------------------------------------- ideas
let ideaSeq = 0;
const ideas = [];
function addIdea(author, text, fields = {}) {
  const i = { id: ++ideaSeq, author, text, at: Date.now(), status: 'NEW', moduleId: null, ...fields };
  ideas.push(i);
  return i;
}
addIdea('Notch', 'Des cochons volants qui lâchent des diamants', { at: Date.now() - 400e3, status: 'ACCEPTED', moduleId: 'cochons-volants-lachent' });
addIdea('Jeb', 'Supprimer la nuit', { at: Date.now() - 320e3, status: 'DISMISSED' });
addIdea('Alex', 'Ajouter une pluie de chats arc-en-ciel quand quelqu\'un meurt', { at: Date.now() - 180e3 });
addIdea('Steve', 'Les zombies dansent la macarena à minuit', { at: Date.now() - 95e3 });
addIdea('Kiki', 'Un trampoline en blocs de slime qui propulse à 50 blocs', { at: Date.now() - 20e3 });
const IDEA_POOL = [
  ['Lucas', 'Les creepers font des confettis au lieu d\'exploser'],
  ['Emma', 'Une commande /meteo qui fait neiger des diamants'],
  ['Hugo', 'Un pet sonore quand on s\'accroupit'],
  ['Léa', 'Des poules géantes qu\'on peut chevaucher'],
  ['Nina', 'La lave devient de la limonade'],
  ['Tom', 'Un mini-jeu de chaises musicales avec des minecarts'],
  ['Inès', 'Afficher un compteur de sauts au-dessus de la tête des joueurs']
];

// ---------------------------------------------------------------------------- server stats & players
let tpsDip = 0;
const tps = [20, 20, 20];
const players = [
  { name: 'Steve', uuid: '8667ba71-b85a-4004-af54-457a9734eed7', world: 'world', x: 12.5, y: 64, z: -3.2, gameMode: 'CREATIVE', health: 20, op: true },
  { name: 'Alex', uuid: 'ec561538-f3fd-461d-aff5-086b22154bce', world: 'world', x: -40.1, y: 70, z: 18.9, gameMode: 'SURVIVAL', health: 17, op: false },
  { name: 'Kiki', uuid: '1b2e6c8e-7d9a-4a52-9a1c-3c5f2a0b6d11', world: 'world_nether', x: 3, y: 40, z: 9, gameMode: 'SURVIVAL', health: 20, op: false }
];

// ---------------------------------------------------------------------------- simulation loop
let lastAutoJob = Date.now();
let lastIdea = Date.now();
let lastSapin = Date.now();
setInterval(() => {
  const t = Date.now();
  advanceJobs();
  // TPS with an occasional dip
  if (AUTO && Math.random() < 0.01) tpsDip = 6;
  const cur = tpsDip > 0 ? rnd(16.5, 18.5) : rnd(19.85, 20.02);
  if (tpsDip > 0) tpsDip -= 0.25;
  tps[0] = tps[0] * 0.8 + cur * 0.2;
  tps[1] = tps[1] * 0.97 + cur * 0.03;
  tps[2] = tps[2] * 0.99 + cur * 0.01;
  for (const m of modules.values()) {
    if (m.state === 'ENABLED') {
      m.msPerTick = Math.max(0, m.msPerTick * 0.7 + (m._baseMs * rnd(0.6, 1.5)) * 0.3);
      if (AUTO && m._errRate && Math.random() < m._errRate / 4) runtimeError(m);
    } else if (m.state !== 'COMPILING') {
      m.msPerTick = 0;
    }
  }
  if (!AUTO) return;
  // a module reloads on its own every ~20 s (someone edits it by hand)
  if (t - lastSapin > 20e3) {
    lastSapin = t;
    const m = modules.get('sapin-geant');
    if (m && m.state === 'ENABLED') simReload(m, 'ok', 2500);
  }
  // a new audience idea every ~25 s
  if (t - lastIdea > 25e3 && ideas.filter((i) => i.status === 'NEW').length < 7) {
    lastIdea = t;
    const [author, text] = pick(IDEA_POOL);
    addIdea(author, text);
  }
  // a new AI job every ~45 s when nothing is running
  if (aiAvailable && t - lastAutoJob > 45e3 && !jobs.some((j) => j.state === 'RUNNING' || j.state === 'QUEUED')) {
    lastAutoJob = t;
    const [moduleId, prompt] = pick([
      ['pluie-de-cochons', 'Ajoute des paillettes quand les cochons touchent le sol'],
      ['sapin-geant', 'Mets des guirlandes lumineuses sur le sapin'],
      ['tnt-confettis', 'La TNT fait des confettis au lieu de casser les blocs'],
      ['vitesse-escargot', 'Tout le monde avance deux fois moins vite pendant 30 secondes']
    ]);
    if (!activeJob(moduleId)) createJob(moduleId, prompt, pick(['Steve', 'Alex', 'Kiki', 'dashboard']));
  }
}, 250);

// ---------------------------------------------------------------------------- HTTP plumbing
function send(res, status, body) {
  const data = JSON.stringify(body);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(data),
    'Cache-Control': 'no-store'
  });
  res.end(data);
}
const err = (res, status, msg) => send(res, status, { error: msg });

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on('data', (c) => { size += c.length; if (size > 1e6) { reject(new Error('body too large')); req.destroy(); } else chunks.push(c); });
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', reject);
  });
}

function statusJson() {
  return {
    server: {
      name: 'Paper', version: '26.3-159-beta (MC: 26.3) [mock]',
      online: players.length, max: 20,
      tps: tps.map((x) => Math.round(x * 100) / 100),
      uptimeMs: Date.now() - STARTED + 3_725_000
    },
    modules: [...modules.values()].map(info),
    jobs: jobList(),
    ideas: ideaList(),
    ai: { available: aiAvailable, reason: aiAvailable ? '' : 'Claude Code introuvable (ai.claude-path = auto)' }
  };
}
function jobList() {
  const active = jobs.filter((j) => j.state === 'RUNNING' || j.state === 'QUEUED');
  const done = jobs.filter((j) => !(j.state === 'RUNNING' || j.state === 'QUEUED')).sort((a, b) => b.endedAt - a.endedAt).slice(0, 20);
  return [...active, ...done].map(job);
}
const ideaList = () => ideas.slice().reverse();

const AI_REASON = 'Claude Code introuvable (ai.claude-path = auto)';
function startAi(res, moduleId, prompt, requestedBy, status = 201, opts = {}, shape = job) {
  if (!ID_RE.test(moduleId)) return err(res, 400, `identifiant de module invalide : ${moduleId}`);
  if (!prompt || !prompt.trim()) return err(res, 400, 'la demande est vide');
  if (!aiAvailable) return err(res, 503, AI_REASON);
  if (activeJob(moduleId)) return err(res, 409, `l'IA travaille déjà sur ${moduleId} : attendez la fin ou arrêtez la tâche`);
  if (opts.imageProvider && !['auto', 'openrouter', 'meshy'].includes(opts.imageProvider)) return err(res, 400, `fournisseur d'images inconnu : ${opts.imageProvider}`);
  if (opts.imageProvider && opts.imageProvider !== 'auto' && !providers[opts.imageProvider].available) {
    return err(res, 503, `${opts.imageProvider === 'meshy' ? 'Meshy AI' : 'OpenRouter'} n'est pas configuré (secrets.yml)`);
  }
  const j = createJob(moduleId, prompt.trim(), requestedBy, opts);
  send(res, status, shape(j));
  return j;
}

function providersJson() {
  return {
    openrouter: { ...providers.openrouter },
    meshy: { ...providers.meshy },
    claude: { available: aiAvailable, reason: aiAvailable ? '' : AI_REASON }
  };
}

function stopJob(id) {
  const j = activeJob(id);
  if (!j) return false;
  finishJob(j, 'STOPPED');
  return true;
}

async function handle(req, res) {
  const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
  const p = url.pathname;
  const method = req.method;
  if (VERBOSE || (method !== 'GET' && !p.startsWith('/__mock'))) console.log(`${hhmmss()} ${method} ${p}${url.search}`);

  // ---- page
  if (method === 'GET' && (p === '/' || p === '/index.html')) {
    try {
      const html = await readFile(INDEX);
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
      return res.end(html);
    } catch (e) {
      return err(res, 500, `index.html introuvable : ${e.message}`);
    }
  }

  // ---- mock controls
  if (p.startsWith('/__mock/')) {
    const q = url.searchParams;
    if (p === '/__mock/outage') { outageUntil = Date.now() + Number(q.get('seconds') || 5) * 1000; return send(res, 200, { ok: true, until: outageUntil }); }
    if (p === '/__mock/ai') { aiAvailable = q.get('on') !== '0'; return send(res, 200, { ok: true, available: aiAvailable }); }
    if (p === '/__mock/token') { TOKEN = q.get('value') || ''; return send(res, 200, { ok: true, token: TOKEN ? '(set)' : '' }); }
    if (p === '/__mock/job') {
      const id = q.get('module') || 'demo';
      return startAi(res, id, q.get('prompt') || 'Fais quelque chose de fun', 'mock');
    }
    if (p === '/__mock/idea') { return send(res, 200, addIdea(q.get('author') || 'Public', q.get('text') || 'Une idée de test')); }
    if (p === '/__mock/providers') {
      for (const k of ['openrouter', 'meshy']) if (q.has(k)) providers[k].available = q.get(k) !== '0';
      return send(res, 200, providersJson());
    }
    return err(res, 404, 'unknown mock control');
  }

  if (!p.startsWith('/api/')) return err(res, 404, 'not found');

  // ---- simulated outage: drop the connection like a dead server would
  if (Date.now() < outageUntil) { req.socket.destroy(); return undefined; }

  // ---- token
  if (TOKEN && req.headers['x-vibe-token'] !== TOKEN) return err(res, 401, 'jeton manquant ou invalide (X-Vibe-Token)');

  const body = method === 'POST' ? await readBody(req) : '';
  const seg = p.split('/').filter(Boolean).map(decodeURIComponent); // ['api', ...]

  // GET /api/status
  if (p === '/api/status' && method === 'GET') return send(res, 200, statusJson());

  // GET /api/providers
  if (p === '/api/providers' && method === 'GET') return send(res, 200, providersJson());

  // /api/conversations...
  if (seg[1] === 'conversations') {
    if (seg.length === 2 && method === 'GET') return send(res, 200, conversationList());
    const id = seg[2];
    const action = seg[3];
    if (seg.length === 4 && action === 'messages' && method === 'POST') {
      let data;
      try { data = JSON.parse(body || '{}'); } catch (e) { return err(res, 400, 'JSON invalide'); }
      const text = typeof data.text === 'string' ? data.text : '';
      startAi(res, id, text, 'dashboard', 201, {
        imageProvider: data.imageProvider || 'auto', model: typeof data.model === 'string' ? data.model : null
      }, (j) => ({ jobId: j.id }));
      return undefined;
    }
    const c = conversations.get(id);
    if (!c) return err(res, 404, `conversation inconnue : ${id}`);
    if (seg.length === 3 && method === 'GET') return send(res, 200, conversationFull(c));
    if (seg.length === 3 && method === 'DELETE') {
      stopJob(id);
      conversations.delete(id);
      return send(res, 200, { ok: true });
    }
    if (seg.length === 4 && action === 'events' && method === 'GET') {
      // messages from index `since` on, plus any older message updated in the last 5 s (streaming)
      const since = Math.max(0, Number(url.searchParams.get('since') || 0) | 0);
      const recent = Date.now() - 5000;
      const msgs = c.messages.filter((m, i) => i >= since || m._upd >= recent).map(msgOut);
      const s = conversationSummary(c);
      return send(res, 200, { messages: msgs, state: s.state, moduleState: s.moduleState, generation: s.generation });
    }
    if (seg.length === 4 && action === 'stop' && method === 'POST') return send(res, 200, { ok: stopJob(id) });
    return err(res, 404, 'route inconnue');
  }

  // /api/modules...
  if (seg[1] === 'modules') {
    if (seg.length === 2 && method === 'GET') return send(res, 200, [...modules.values()].map(info));
    const id = seg[2];
    const action = seg[3];
    if (seg.length === 4 && action === 'create' && method === 'POST') {
      if (!ID_RE.test(id)) return err(res, 400, `identifiant de module invalide : ${id}`);
      if (modules.has(id)) return send(res, 200, { ok: true, created: false });
      addModule(id, { description: body.trim() });
      simReload(modules.get(id), 'ok', 1500);
      return send(res, 200, { ok: true, created: true });
    }
    const m = modules.get(id);
    if (!m) return err(res, 404, `module inconnu : ${id}`);
    if (seg.length === 3 && method === 'GET') return send(res, 200, info(m));
    if (seg.length === 4 && method === 'POST') {
      if (action === 'reload') {
        const r = await simReload(m, m._broken || 'ok');
        return send(res, 200, r);
      }
      if (action === 'enable') {
        if (m.state === 'DISABLED' || m.state === 'FAILED' || m.state === 'TRIPPED' || m.state === 'DISCOVERED') {
          return send(res, 200, await simReload(m, m._broken || 'ok'));
        }
        return send(res, 200, { id: m.id, ok: true, state: m.state, generation: m.generation, error: null, diagnostics: m.diagnostics, durationMs: 0, rolledBack: m.rolledBack });
      }
      if (action === 'disable') {
        disable(m);
        return send(res, 200, { ok: true });
      }
    }
    if (seg.length === 4 && action === 'logs' && method === 'GET') {
      const limit = Math.max(1, Math.min(500, Number(url.searchParams.get('limit') || 200)));
      return send(res, 200, { lines: m._logs.slice(-limit) });
    }
    return err(res, 404, 'route inconnue');
  }

  // POST /api/command
  if (p === '/api/command' && method === 'POST') {
    const cmd = body.trim().replace(/^\//, '');
    if (!cmd) return err(res, 400, 'commande vide');
    return send(res, 200, { ok: true, output: [`(mock) commande exécutée : /${cmd}`] });
  }

  // GET /api/players
  if (p === '/api/players' && method === 'GET') return send(res, 200, players);

  // /api/ai...
  if (seg[1] === 'ai') {
    if (seg.length === 2 && method === 'GET') return send(res, 200, jobList());
    const id = seg[2];
    if (seg.length === 3 && method === 'POST') { startAi(res, id, body, 'dashboard'); return undefined; }
    if (seg.length === 4 && method === 'POST' && seg[3] === 'stop') {
      return send(res, 200, { ok: stopJob(id) });
    }
    if (seg.length === 4 && method === 'POST' && seg[3] === 'reset') return send(res, 200, { ok: true });
    return err(res, 404, 'route inconnue');
  }

  // /api/ideas...
  if (seg[1] === 'ideas') {
    if (seg.length === 2 && method === 'GET') return send(res, 200, ideaList());
    if (seg.length === 2 && method === 'POST') {
      if (!body.trim()) return err(res, 400, 'idée vide');
      return send(res, 200, addIdea('dashboard', body.trim()));
    }
    const n = Number(seg[2]);
    const idea = ideas.find((i) => i.id === n);
    if (!idea) return err(res, 404, `idée inconnue : ${seg[2]}`);
    if (seg.length === 4 && method === 'POST' && seg[3] === 'accept') {
      const moduleId = url.searchParams.get('module') || '';
      if (idea.status !== 'NEW') return err(res, 409, `idée #${n} déjà traitée`);
      const j = startAi(res, moduleId, idea.text, idea.author, 200);
      if (j) { idea.status = 'ACCEPTED'; idea.moduleId = moduleId; }
      return undefined;
    }
    if (seg.length === 4 && method === 'POST' && seg[3] === 'dismiss') {
      idea.status = 'DISMISSED';
      return send(res, 200, idea);
    }
    return err(res, 404, 'route inconnue');
  }

  return err(res, 404, 'route inconnue');
}

const server = http.createServer((req, res) => {
  handle(req, res).catch((e) => {
    console.error(e);
    if (!res.headersSent) err(res, 500, String(e && e.message || e));
  });
});
server.listen(PORT, HOST, () => {
  console.log(`VibeCraft mock API on http://${HOST}:${PORT}/  (token: ${TOKEN ? 'required' : 'none'}, ai: ${aiAvailable ? 'on' : 'off'}, auto: ${AUTO})`);
});
for (const sig of ['SIGINT', 'SIGTERM']) process.on(sig, () => { server.close(); process.exit(0); });
