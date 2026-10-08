#!/usr/bin/env node
// VibeCraft dashboard mock server: zero dependencies, follows docs/ARCHITECTURE.md section 5.
// Serves src/main/resources/web/index.html (re-read on every request) and a fake, evolving JSON API:
// modules compiling / reloading / rolling back / tripping, AI jobs streaming progress lines,
// audience ideas arriving.
//
// Usage: node vibecraft-plugin/dev/mock-api.mjs [--port 8768] [--host 127.0.0.1]
//          [--token secret]   require the X-Vibe-Token header (401 otherwise)
//          [--no-ai]          ai.available=false (POST /api/ai/{id} -> 503)
//          [--static]         no automatic demo activity (only reacts to requests)
//          [--verbose]        log every request
// Test helpers (outside /api, never need the token):
//   GET /__mock/outage?seconds=5     /api/* requests are dropped (simulates a dead server)
//   GET /__mock/ai?on=0|1            toggle AI availability
//   GET /__mock/token?value=xyz      change the required token at runtime (empty = none)
//   GET /__mock/job?module=x&prompt=y  start a fake AI job

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

// ---------------------------------------------------------------------------- AI jobs
let jobSeq = 0;
const jobs = []; // internal job records, creation order

function job(j) {
  return {
    id: j.id, moduleId: j.moduleId, prompt: j.prompt, requestedBy: j.requestedBy, state: j.state,
    startedAt: j.startedAt, endedAt: j.endedAt, progress: j.progress, result: j.result,
    sessionId: j.sessionId, costUsd: j.costUsd
  };
}

function scriptFor(moduleId, prompt, fail) {
  const file = `modules/${moduleId}/${pascal(moduleId)}Module.java`;
  const steps = [
    { line: '🚀 Démarrage de Claude Code…', wait: 900 },
    { line: '📖 CLAUDE.md', wait: 1300 },
    { line: `💬 Je vais implémenter : ${short(prompt, 80)}`, wait: 1600 },
    { line: `📖 ${file}`, wait: 1100 },
    { line: `✏ ${file}`, wait: 2300 },
    { line: '🔄 rechargement', wait: 700, act: 'compile' },
    { line: '⚠ erreur de compilation', wait: 1900 },
    { line: '💬 Il manque un import, je corrige.', wait: 1300 },
    { line: `✏ ${file}`, wait: 1700 }
  ];
  if (fail) {
    steps.push({ line: '🔄 rechargement', wait: 700, act: 'enable' });
    steps.push({ line: '⚠ enable() a levé une exception, version précédente conservée', wait: 1500 });
    steps.push({ end: 'FAILED', result: 'Je n\'ai pas réussi à finir : l\'API utilisée n\'existe pas dans cette version de Paper.' });
  } else {
    steps.push({ line: '🔄 rechargement', wait: 700, act: 'ok' });
    steps.push({ line: `💬 Le module ${moduleId} est chargé, je vérifie son statut.`, wait: 1400 });
    steps.push({ end: 'SUCCEEDED', result: pick([
      `C'est en ligne ! Tapez /${moduleId.split('-')[0]} pour essayer.`,
      `Fonctionnalité ajoutée au module ${moduleId}, rechargée à chaud sans redémarrer le serveur.`,
      'Voilà ! Sautez pour voir le résultat, tout est déjà actif sur le serveur.'
    ]) });
  }
  return steps;
}

function createJob(moduleId, prompt, requestedBy) {
  const j = {
    id: `job-${++jobSeq}`, moduleId, prompt, requestedBy, state: 'QUEUED', startedAt: 0, endedAt: 0,
    progress: [], result: null, sessionId: null, costUsd: 0,
    _steps: scriptFor(moduleId, prompt, Math.random() < 0.2), _i: 0, _next: 0
  };
  jobs.push(j);
  if (!modules.has(moduleId)) {
    const m = addModule(moduleId, { description: short(prompt, 60) });
    log(m, 'INFO', 'module créé par l\'IA');
  }
  startQueued();
  return j;
}

function activeJob(moduleId) {
  return jobs.find((j) => j.moduleId === moduleId && (j.state === 'QUEUED' || j.state === 'RUNNING'));
}

function startQueued() {
  let running = jobs.filter((j) => j.state === 'RUNNING').length;
  for (const j of jobs) {
    if (running >= MAX_CONCURRENT) break;
    if (j.state !== 'QUEUED') continue;
    j.state = 'RUNNING';
    j.startedAt = Date.now();
    j.sessionId = j.sessionId || `sess-${Math.random().toString(16).slice(2, 10)}`;
    j._next = Date.now() + 600;
    running++;
  }
}

function finishJob(j, state, result) {
  j.state = state;
  j.endedAt = Date.now();
  j.result = result;
  if (state !== 'STOPPED' || j.startedAt) j.costUsd = Math.round(rnd(0.03, 0.42) * 100) / 100;
  if (!j.startedAt) j.startedAt = j.endedAt;
  startQueued();
}

function advanceJobs() {
  const t = Date.now();
  for (const j of jobs) {
    if (j.state !== 'RUNNING' || t < j._next) continue;
    const step = j._steps[j._i++];
    if (!step) { finishJob(j, 'SUCCEEDED', 'Terminé.'); continue; }
    if (step.end) {
      if (step.end === 'SUCCEEDED') j.progress.push('✅ Terminé');
      finishJob(j, step.end, step.result);
      if (step.end === 'SUCCEEDED') {
        const m = modules.get(j.moduleId);
        if (m) m._broken = null;
      }
      continue;
    }
    j.progress.push(step.line);
    if (j.progress.length > 200) j.progress.splice(0, j.progress.length - 200);
    const m = modules.get(j.moduleId);
    if (m && step.act) {
      if (step.act === 'ok') m._broken = null;
      simReload(m, step.act === 'ok' ? 'ok' : step.act, 900);
    }
    j._next = t + step.wait * rnd(0.8, 1.25);
  }
}

// Seed a few finished jobs + one running + one queued.
(function seedJobs() {
  const t = Date.now();
  const done = [
    ['pluie-de-cochons', 'Fais pleuvoir des cochons quand quelqu\'un saute', 'Steve', 'SUCCEEDED', 'C\'est en ligne ! Sautez et regardez le ciel : des cochons tombent autour de vous.'],
    ['arc-explosif', 'Les flèches explosent quand elles touchent un bloc', 'Alex', 'FAILED', 'Je n\'ai pas réussi : le type de projectile est mal géré, il faut une autre approche.'],
    ['meteo-folle', 'Change la météo toutes les 10 secondes', 'dashboard', 'STOPPED', null],
    ['sapin-geant', 'Ajoute une commande /sapin qui fait pousser un sapin géant', 'console', 'SUCCEEDED', 'Tapez /sapin pour faire pousser un sapin de 30 blocs devant vous.']
  ];
  done.forEach(([moduleId, prompt, by, state, result], i) => {
    const startedAt = t - (done.length - i) * 300e3;
    const j = {
      id: `job-${++jobSeq}`, moduleId, prompt, requestedBy: by, state, startedAt, endedAt: startedAt + Math.round(rnd(40e3, 160e3)),
      progress: ['🚀 Démarrage de Claude Code…', '📖 CLAUDE.md', `💬 Je vais implémenter : ${prompt}`, `✏ modules/${moduleId}/${pascal(moduleId)}Module.java`, '🔄 rechargement', state === 'SUCCEEDED' ? '✅ Terminé' : '⚠ erreur de compilation'],
      result, sessionId: `sess-${(1000 + i).toString(16)}`, costUsd: state === 'STOPPED' ? 0 : Math.round(rnd(0.05, 0.4) * 100) / 100,
      _steps: [], _i: 0, _next: 0
    };
    jobs.push(j);
  });
  createJob('double-saut', 'Corrige le double saut : il faut pouvoir sauter une deuxième fois en l\'air', 'Notch');
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

function startAi(res, moduleId, prompt, requestedBy, status = 201) {
  if (!ID_RE.test(moduleId)) return err(res, 400, `identifiant de module invalide : ${moduleId}`);
  if (!prompt || !prompt.trim()) return err(res, 400, 'la demande est vide');
  if (!aiAvailable) return err(res, 503, 'Claude Code introuvable (ai.claude-path = auto)');
  if (activeJob(moduleId)) return err(res, 409, `une tâche IA est déjà en cours pour ${moduleId}`);
  const j = createJob(moduleId, prompt.trim(), requestedBy);
  send(res, status, job(j));
  return j;
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
      const j = activeJob(id);
      if (!j) return send(res, 200, { ok: false });
      j.progress.push('⏹ arrêté');
      finishJob(j, 'STOPPED', null);
      return send(res, 200, { ok: true });
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
