// Per-scenario test context: logging, assertions, cleanup registry, deploy/command helpers.
import { renderFixture } from './fixtures.mjs';
import { waitForModule, waitForGone, waitForAttempt, summarizeInfo, summarizeResult, sleep } from './workspace.mjs';

export class AssertionError extends Error {}
/** Thrown by t.skip(): the scenario does not apply to this server (reported as SKIP, does not fail the run). */
export class SkipError extends Error {}

export const UNKNOWN_CMD = /Unknown or incomplete command|Unknown command|Commande inconnue/i;

export const escapeRe = (s) => String(s).replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

const short = (v, n = 700) => {
  let s;
  try { s = typeof v === 'string' ? v : JSON.stringify(v); } catch { s = String(v); }
  return s && s.length > n ? s.slice(0, n) + '…' : s;
};

export class TestContext {
  /**
   * @param {object} o
   * @param {string} o.name scenario name
   * @param {import('./api.mjs').VibeApi} o.api
   * @param {() => import('./bot.mjs').VibeBot} o.getBot  current bot (it may be replaced after a disconnect)
   * @param {import('./workspace.mjs').Workspace} o.ws
   * @param {object} o.cfg  runner config
   * @param {string} o.run  unique run id (used in version markers)
   * @param {(line:string)=>void} o.log
   */
  constructor({ name, api, getBot, ws, cfg, run, log }) {
    this.name = name;
    this.api = api;
    this._getBot = getBot;
    this.ws = ws;
    this.cfg = cfg;
    this.run = run;
    this._log = log;
    this.steps = [];
    this.warnings = [];
    this.softFailures = [];
    this.data = {};
    this.cleanups = [];
    this.aborted = false;
    this.startedAt = Date.now();
  }

  get bot() { return this._getBot(); }

  // ------------------------------------------------------------ reporting
  step(msg) {
    if (this.aborted) throw new AssertionError('scenario aborted (timeout)');
    const at = Date.now() - this.startedAt;
    this.steps.push({ t: at, msg });
    this._log(`    [${String(at).padStart(6)} ms] ${msg}`);
  }

  warn(msg) {
    this.warnings.push(msg);
    this._log(`    WARN ${msg}`);
  }

  record(key, value) { this.data[key] = value; }

  /** Stop here: this scenario does not apply to the server under test (status SKIP). */
  skip(reason) { throw new SkipError(reason); }

  // ------------------------------------------------------------ assertions
  assert(cond, msg, details) {
    if (!cond) throw new AssertionError(msg + (details !== undefined ? ' | ' + short(details) : ''));
  }

  eq(actual, expected, msg, details) {
    if (actual !== expected) {
      throw new AssertionError(`${msg}: expected ${short(expected, 200)}, got ${short(actual, 200)}` + (details !== undefined ? ' | ' + short(details) : ''));
    }
  }

  /**
   * Non-fatal assertion: the scenario keeps going (to report every broken aspect in one run) but FAILS at
   * the end if any of these failed.
   */
  expect(cond, msg, details) {
    if (!cond) {
      const m = msg + (details !== undefined ? ' | ' + short(details, 300) : '');
      this.softFailures.push(m);
      this._log(`    FAILED CHECK ${m}`);
    }
    return !!cond;
  }

  /** Soft check: records a warning instead of failing. */
  check(cond, msg) {
    if (!cond) this.warn(msg);
    return !!cond;
  }

  // ------------------------------------------------------------ naming
  id(suffix) { return this.cfg.prefix + suffix; }
  /** Version marker unique to this run: "<run>.v<n>". */
  ver(n) { return `${this.run}.v${n}`; }
  fixture(name, opts) { return renderFixture(name, opts); }

  // ------------------------------------------------------------ cleanup
  onCleanup(fn, label = 'cleanup') { this.cleanups.push({ fn, label }); }

  /** Make sure a test module does not exist yet, and remove it (sources + data) when the scenario ends. */
  async useModule(id) {
    if (this.ws.exists(id) || (await this.api.module(id))) {
      this.step(`leftover ${id} found: removing it first`);
      await this.removeModule(id);
    }
    this.ws.removeModule(id, { data: true });
    if (!this.cfg.keep) this.onCleanup(() => this.removeModule(id), `remove ${id}`);
    return id;
  }

  async removeModule(id, { timeoutMs = 20000 } = {}) {
    this.ws.removeModule(id, { data: false });
    await waitForGone(this.api, id, timeoutMs);
    // The engine must not resurrect the directory (e.g. by writing .vibe/ after the removal).
    await sleep(300);
    if (this.ws.exists(id)) {
      this.warn(`${id}: directory re-appeared after removal (engine wrote into it after REMOVED?) - deleting again`);
      this.ws.removeModule(id, { data: false });
      await waitForGone(this.api, id, timeoutMs);
    }
    this.ws.removeModule(id, { data: true });
  }

  // ------------------------------------------------------------ modules
  async info(id) { return this.api.module(id); }

  waitInfo(id, predicate, opts) { return waitForModule(this.api, id, predicate, opts); }

  /**
   * Write module files then make the engine load them, like an AI agent would.
   * via='api' (default): POST /api/modules/{id}/reload right after writing (falls back to waiting for the
   * watcher if the module is not discovered yet). via='watcher': only wait for the file watcher.
   * Returns a ReloadResult-like object {ok, state, generation, error, diagnostics, rolledBack, durationMs, via, info}.
   */
  async deploy(id, files, { via = 'api', timeoutMs = 60000, clean = true } = {}) {
    const since = Date.now() - 50;
    const before = await this.api.module(id);
    this.ws.writeModule(id, files, { clean });
    const t0 = Date.now();
    let result = null; let how = via;
    if (via === 'api') {
      const looksUnknown = (msg) => /unknown|not found|no such|does not exist|introuvable|inconnu/i.test(msg || '');
      try {
        result = await this.api.reload(id);
      } catch (e) {
        if (before || e.status !== 404) throw e;
        result = { ok: false, error: e.message, http404: true };
      }
      if (!before && result && !result.ok && (result.http404 || looksUnknown(result.error))) {
        this.warn(`POST reload right after creating ${id} answered "${result.error}" (not discovered yet); AI agents doing write+curl would hit this. Falling back to the watcher.`);
        result = null; how = 'watcher-fallback';
      }
    }
    let info;
    if (!result) {
      info = await waitForAttempt(this.api, id, since, timeoutMs);
      result = {
        id, ok: info.state === 'ENABLED' && !info.rolledBack && !info.lastError, state: info.state, generation: info.generation,
        error: info.lastError, diagnostics: info.diagnostics || [], rolledBack: info.rolledBack, durationMs: Date.now() - t0,
      };
    } else {
      info = await this.api.module(id);
    }
    const out = { ...result, via: how, info, wallMs: Date.now() - t0 };
    this.step(`deploy ${id} via ${how}: ${short(summarizeResult(out), 400)}`);
    return out;
  }

  /** deploy() + assert the new code is running. */
  async deployOk(id, files, opts) {
    const r = await this.deploy(id, files, opts);
    this.assert(r.ok === true && r.state === 'ENABLED', `${id} should load`, summarizeResult(r));
    return r;
  }

  // ------------------------------------------------------------ bot
  /** Run a command as the bot; logs the lines received. */
  async cmd(text, opts = {}) {
    const r = await this.bot.cmd(text, opts);
    this.step(`bot ${text} -> ${r.lines.length ? short(r.lines.join(' ⏎ ').replace(/\n/g, ' ⏎ '), 300) : '(no message)'} [${r.ms} ms]`);
    return r;
  }

  /** Run a command and assert exactly `count` received lines match `regex`. */
  async expectCmd(text, regex, count = 1, { windowMs = 6000, settleMs = 600 } = {}) {
    // stop early on the expected line, or on an "unknown command" reply (no point waiting the whole window)
    const until = new RegExp(`(?:${regex.source})|${UNKNOWN_CMD.source}`, regex.flags);
    const r = await this.cmd(text, count > 0 ? { until, windowMs, settleMs } : { windowMs: Math.min(windowMs, 1500) });
    const n = r.lines.filter((l) => regex.test(l)).length;
    this.eq(n, count, `"${text}": lines matching ${regex}`, r.lines);
    return r;
  }

  /** Lines among r.lines matching regex. */
  matching(r, regex) { return r.lines.filter((l) => regex.test(l)); }

  /** Max gap between two world-time packets seen by the bot in [from, to] (≈ 1000 ms when the server ticks normally). */
  stallMs(from, to = Date.now()) {
    const s = this.bot.timeSamples;
    if (!s.length) return 0;
    // start at the last sample before `from` (a stall that began just before `from` counts too)
    let start = s.findIndex((x) => x.at >= from);
    if (start === -1) start = s.length;
    start = Math.max(0, start - 1);
    let max = 0; let last = s[start];
    for (let k = start + 1; k < s.length && s[k].at <= to; k++) {
      max = Math.max(max, s[k].at - s[k - 1].at);
      last = s[k];
    }
    // the interval still open at `to` (e.g. measured right after a freeze, before the next packet)
    max = Math.max(max, Math.min(to, Date.now()) - last.at);
    return max;
  }

  infoSummary(i) { return summarizeInfo(i); }
}

export { sleep, short };
