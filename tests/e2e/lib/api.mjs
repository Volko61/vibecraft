// Minimal client for the VibeCraft HTTP API (ARCHITECTURE.md section 5). Uses global fetch (Node >= 20).

export class ApiError extends Error {
  constructor(method, path, status, body) {
    const msg = body && typeof body === 'object' && body.error ? body.error : (typeof body === 'string' ? body.slice(0, 300) : JSON.stringify(body));
    super(`${method} ${path} -> HTTP ${status}: ${msg}`);
    this.status = status;
    this.body = body;
  }
}

export class VibeApi {
  /**
   * @param {object} o
   * @param {string} [o.base]      default http://127.0.0.1:8765
   * @param {string} [o.token]     X-Vibe-Token (if api.token is set in the plugin config)
   * @param {number} [o.timeoutMs] default per-request timeout (default 15000)
   */
  constructor({ base = 'http://127.0.0.1:8765', token = '', timeoutMs = 15000 } = {}) {
    this.base = base.replace(/\/+$/, '');
    this.token = token;
    this.timeoutMs = timeoutMs;
    /** last N calls, for the report: {method, path, status, ms} */
    this.calls = [];
  }

  /**
   * Raw request. Returns {status, body, ms, contentType}; never throws on HTTP status (throws on network errors / timeouts).
   * @param {string} method
   * @param {string} path      e.g. /api/status
   * @param {string|null} [body]  plain-text body (UTF-8)
   */
  async raw(method, path, body = null, { timeoutMs, accept = 'application/json' } = {}) {
    const headers = { Accept: accept };
    if (this.token) headers['X-Vibe-Token'] = this.token;
    if (body != null) headers['Content-Type'] = 'text/plain; charset=utf-8';
    const t0 = Date.now();
    let res;
    try {
      res = await fetch(this.base + path, {
        method,
        headers,
        body: body == null ? undefined : body,
        signal: AbortSignal.timeout(timeoutMs ?? this.timeoutMs),
      });
    } catch (e) {
      const ms = Date.now() - t0;
      this._record(method, path, 'ERR', ms);
      const why = e?.name === 'TimeoutError' ? `timeout after ${ms} ms` : (e?.cause?.code || e?.message || String(e));
      const err = new Error(`${method} ${path} failed: ${why}`);
      err.cause = e;
      throw err;
    }
    const text = await res.text();
    const ms = Date.now() - t0;
    this._record(method, path, res.status, ms);
    let parsed = text;
    if ((res.headers.get('content-type') || '').includes('json') || /^\s*[[{]/.test(text)) {
      try { parsed = JSON.parse(text); } catch { /* keep text */ }
    }
    return { status: res.status, body: parsed, ms, contentType: res.headers.get('content-type') || '' };
  }

  _record(method, path, status, ms) {
    this.calls.push({ at: Date.now(), method, path, status, ms });
    if (this.calls.length > 2000) this.calls.splice(0, 500);
  }

  /** Request expecting a 2xx; throws ApiError otherwise. */
  async json(method, path, body = null, opts = {}) {
    const r = await this.raw(method, path, body, opts);
    if (r.status < 200 || r.status >= 300) throw new ApiError(method, path, r.status, r.body);
    return r.body;
  }

  // ------------------------------------------------------------------ endpoints
  status() { return this.json('GET', '/api/status'); }
  modules() { return this.json('GET', '/api/modules'); }

  /** ModuleInfo, or null if the module is unknown (404). */
  async module(id) {
    const r = await this.raw('GET', `/api/modules/${encodeURIComponent(id)}`);
    if (r.status === 404) return null;
    if (r.status < 200 || r.status >= 300) throw new ApiError('GET', `/api/modules/${id}`, r.status, r.body);
    return r.body;
  }

  /** ReloadResult (the server waits up to 120 s). */
  reload(id) { return this.json('POST', `/api/modules/${encodeURIComponent(id)}/reload`, null, { timeoutMs: 130000 }); }
  enable(id) { return this.json('POST', `/api/modules/${encodeURIComponent(id)}/enable`, null, { timeoutMs: 130000 }); }
  disable(id) { return this.json('POST', `/api/modules/${encodeURIComponent(id)}/disable`, null, { timeoutMs: 30000 }); }
  create(id, description = '') { return this.json('POST', `/api/modules/${encodeURIComponent(id)}/create`, description); }
  async logs(id, limit = 200) {
    const r = await this.json('GET', `/api/modules/${encodeURIComponent(id)}/logs?limit=${limit}`);
    return r?.lines ?? [];
  }

  /** Run a console command; returns {ok, output:[lines]}. */
  command(line) { return this.json('POST', '/api/command', line, { timeoutMs: 20000 }); }

  players() { return this.json('GET', '/api/players'); }
  aiJobs() { return this.json('GET', '/api/ai'); }
  ideas() { return this.json('GET', '/api/ideas'); }

  /** True if GET /api/status answers 2xx within `timeoutMs`. */
  async reachable(timeoutMs = 3000) {
    try {
      const r = await this.raw('GET', '/api/status', null, { timeoutMs });
      return r.status >= 200 && r.status < 300;
    } catch {
      return false;
    }
  }
}
