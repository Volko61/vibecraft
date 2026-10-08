// Helpers to write / delete module source directories and to trigger / await (re)loads.
import fs from 'node:fs';
import path from 'node:path';

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** Java package recommended for a module id (ARCHITECTURE.md section 3). */
export function packageFor(id) {
  return 'vibe.' + id.replace(/-/g, '_');
}

export class Workspace {
  /**
   * @param {object} o
   * @param {string} o.modulesDir  workspace/modules
   * @param {string} [o.dataDir]   workspace/data (default: <modulesDir>/../data)
   * @param {string} [o.prefix]    only ids with this prefix may be written/deleted (default "e2e-")
   */
  constructor({ modulesDir, dataDir, prefix = 'e2e-' }) {
    this.modulesDir = path.resolve(modulesDir);
    this.dataDir = path.resolve(dataDir ?? path.join(this.modulesDir, '..', 'data'));
    this.prefix = prefix;
  }

  _guard(id) {
    if (!id.startsWith(this.prefix)) throw new Error(`refusing to touch module "${id}": test modules must start with "${this.prefix}"`);
    if (!/^[a-z0-9][a-z0-9_-]{0,39}$/.test(id)) throw new Error(`invalid module id "${id}"`);
  }

  dir(id) { return path.join(this.modulesDir, id); }
  dataOf(id) { return path.join(this.dataDir, id); }
  exists(id) { return fs.existsSync(this.dir(id)); }

  /**
   * Write module files (relative path -> content). With clean=true (default), other source files
   * (*.java, config.yml, module.yml) already in the directory are deleted; `.vibe/` is never touched.
   * mtimes are forced to strictly increase so the (path,size,mtime) fingerprint always changes.
   */
  writeModule(id, files, { clean = true } = {}) {
    this._guard(id);
    const dir = this.dir(id);
    fs.mkdirSync(dir, { recursive: true });
    if (clean) {
      for (const f of this.sourceFiles(id)) {
        if (!(f in files)) fs.rmSync(path.join(dir, f), { force: true });
      }
    }
    for (const [rel, content] of Object.entries(files)) {
      const p = path.join(dir, rel);
      let prevMtime = 0;
      try { prevMtime = fs.statSync(p).mtimeMs; } catch { /* new file */ }
      fs.mkdirSync(path.dirname(p), { recursive: true });
      fs.writeFileSync(p, content, 'utf8');
      const mtime = Math.max(Date.now(), prevMtime + 10);
      fs.utimesSync(p, new Date(), new Date(mtime));
    }
    return dir;
  }

  /** Write a single file of a module (no cleaning). */
  writeFile(id, rel, content) {
    return this.writeModule(id, { [rel]: content }, { clean: false });
  }

  /** Delete one file of a module (relative path). */
  removeFile(id, rel) {
    this._guard(id);
    fs.rmSync(path.join(this.dir(id), rel), { force: true });
  }

  /** Source files of a module (relative, '/'-separated), excluding .vibe/. */
  sourceFiles(id) {
    const dir = this.dir(id);
    const out = [];
    const walk = (d, rel) => {
      let entries = [];
      try { entries = fs.readdirSync(d, { withFileTypes: true }); } catch { return; }
      for (const e of entries) {
        const r = rel ? rel + '/' + e.name : e.name;
        if (e.isDirectory()) { if (e.name !== '.vibe') walk(path.join(d, e.name), r); }
        else if (/\.java$/.test(e.name) || (!rel && (e.name === 'config.yml' || e.name === 'module.yml'))) out.push(r);
      }
    };
    walk(dir, '');
    return out;
  }

  /** Delete a module directory (and optionally its data directory). Retries on Windows file locks. */
  removeModule(id, { data = true } = {}) {
    this._guard(id);
    const rm = (p) => {
      for (let i = 0; i < 10; i++) {
        try { fs.rmSync(p, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 }); return; } catch (e) {
          if (i === 9) throw e;
        }
      }
    };
    rm(this.dir(id));
    if (data) rm(this.dataOf(id));
  }

  /** Read a host-written file in modules/<id>/.vibe/ (status.json, build.log, errors.log, disabled); null if absent. */
  readVibe(id, name) {
    try { return fs.readFileSync(path.join(this.dir(id), '.vibe', name), 'utf8'); } catch { return null; }
  }

  vibeExists(id, name) {
    return fs.existsSync(path.join(this.dir(id), '.vibe', name));
  }

  readData(id, rel = 'store.yml') {
    try { return fs.readFileSync(path.join(this.dataOf(id), rel), 'utf8'); } catch { return null; }
  }

  /** Test module ids currently on disk. */
  listTestModules() {
    let entries = [];
    try { entries = fs.readdirSync(this.modulesDir, { withFileTypes: true }); } catch { return []; }
    return entries.filter((e) => e.isDirectory() && e.name.startsWith(this.prefix)).map((e) => e.name);
  }

  listTestData() {
    let entries = [];
    try { entries = fs.readdirSync(this.dataDir, { withFileTypes: true }); } catch { return []; }
    return entries.filter((e) => e.isDirectory() && e.name.startsWith(this.prefix)).map((e) => e.name);
  }
}

// ---------------------------------------------------------------- module state helpers (HTTP API)

/**
 * Poll GET /api/modules/{id} until predicate(info) is true. Returns the info.
 * `info` is null while the module is unknown (404).
 */
export async function waitForModule(api, id, predicate, { timeoutMs = 20000, intervalMs = 150, what = 'condition' } = {}) {
  const t0 = Date.now();
  let last = null; let lastErr = null;
  while (Date.now() - t0 < timeoutMs) {
    try {
      last = await api.module(id);
      lastErr = null;
      if (predicate(last)) return last;
    } catch (e) {
      lastErr = e;
    }
    await sleep(intervalMs);
  }
  const snap = last ? JSON.stringify(summarizeInfo(last)) : 'null (404)';
  throw new Error(`timeout after ${timeoutMs} ms waiting for ${id}: ${what}; last info=${snap}${lastErr ? '; last error=' + lastErr.message : ''}`);
}

/** Wait until the module is unknown to the API (404). */
export function waitForGone(api, id, timeoutMs = 15000) {
  return waitForModule(api, id, (i) => i === null, { timeoutMs, what: 'module removed (404)' });
}

const BUSY = new Set(['COMPILING', 'DISCOVERED']);

/** Wait for a load attempt that started at/after `since` (epoch ms) to finish. */
export function waitForAttempt(api, id, since, timeoutMs = 30000) {
  return waitForModule(api, id, (i) => i && i.lastAttemptAt >= since && !BUSY.has(i.state),
    { timeoutMs, what: `a finished load attempt after ${new Date(since).toISOString()}` });
}

/** Compact ModuleInfo for logs / reports. */
export function summarizeInfo(i) {
  if (!i) return null;
  return {
    state: i.state, generation: i.generation, rolledBack: i.rolledBack, lastError: i.lastError,
    commands: i.commands, listeners: i.listeners, tasks: i.tasks, runtimeErrorCount: i.runtimeErrorCount,
    diagnostics: (i.diagnostics || []).slice(0, 5).map((d) => `${d.kind} ${d.file}:${d.line} ${d.message}`),
    recentErrors: (i.recentErrors || []).slice(-3),
  };
}

export function summarizeResult(r) {
  if (!r) return null;
  return {
    ok: r.ok, state: r.state, generation: r.generation, error: r.error, rolledBack: r.rolledBack, durationMs: r.durationMs,
    diagnostics: (r.diagnostics || []).slice(0, 5).map((d) => `${d.kind} ${d.file}:${d.line} ${d.message}`),
  };
}

export { sleep };
