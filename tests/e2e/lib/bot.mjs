// VibeBot: a mineflayer bot that joins the server, never reconnects on its own, and records
// everything the e2e runner needs (disconnects, server messages, world-age samples for TPS).
import mineflayer from 'mineflayer';

const now = () => Date.now();
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// Unwrap prismarine-nbt "typed" values ({type:'compound', value:{text:{type:'string', value:'..'}}}).
function simplifyNbt(v) {
  if (Array.isArray(v)) return v.map(simplifyNbt);
  if (v && typeof v === 'object') {
    if (typeof v.type === 'string' && 'value' in v && Object.keys(v).length <= 2) {
      if (v.type === 'compound') return simplifyNbt(v.value);
      if (v.type === 'list') return simplifyNbt(v.value?.value ?? []);
      return simplifyNbt(v.value);
    }
    const o = {};
    for (const [k, x] of Object.entries(v)) o[k] = simplifyNbt(x);
    return o;
  }
  return v;
}

function fixNbtLongs(v) {
  if (Array.isArray(v)) return v.map(fixNbtLongs);
  if (v && typeof v === 'object') {
    const keys = Object.keys(v);
    if (keys.length === 1 && keys[0] === '' && Array.isArray(v['']) && v[''].length === 2 && v[''].every(Number.isInteger)) {
      const [hi, lo] = v[''];
      return { text: ((BigInt(hi) << 32n) + BigInt(lo >>> 0)).toString() };
    }
    const o = {};
    for (const [k, x] of Object.entries(v)) o[k] = fixNbtLongs(x);
    return o;
  }
  return v;
}

function reasonToText(reason) {
  if (reason == null) return '';
  if (typeof reason === 'string') {
    try { return reasonToText(JSON.parse(reason)); } catch { return reason; }
  }
  if (typeof reason === 'object') {
    reason = simplifyNbt(reason);
    if (typeof reason === 'string') return reason;
    // Text component (possibly NBT-simplified): flatten text/translate/extra.
    let out = '';
    if (typeof reason.text === 'string') out += reason.text;
    if (reason.translate) out += reason.translate + (reason.with ? ' ' + JSON.stringify(reason.with) : '');
    if (Array.isArray(reason.extra)) out += reason.extra.map(reasonToText).join('');
    if (out) return out;
    try { return JSON.stringify(reason); } catch { return String(reason); }
  }
  return String(reason);
}

export class VibeBot {
  /**
   * @param {object} o
   * @param {string} [o.host]      default 127.0.0.1
   * @param {number} [o.port]      default 25565
   * @param {string} [o.username]  default VibeBot
   * @param {string|false} [o.version] protocol version spoken by the bot (default '26.1': newest mineflayer
   *                               supports; the 26.3 server needs ViaVersion+ViaBackwards)
   * @param {(line:string)=>void} [o.log]
   * @param {boolean} [o.tickEndShim=true] send serverbound tick_end like a vanilla client (see _installTickEndShim).
   *                               Only disable it to reproduce the "invalid_player_movement" kick (tools/kick-repro.mjs).
   */
  constructor(o = {}) {
    this.host = o.host ?? '127.0.0.1';
    this.port = o.port ?? 25565;
    this.username = o.username ?? 'VibeBot';
    this.version = o.version ?? '26.1';
    this.log = o.log ?? (() => {});
    this.tickEndShim = o.tickEndShim ?? true;
    /** root-level command names of the last declare_commands packet (the client's command tree / tab completion) */
    this.commandTree = null;
    /** {at, count} of every declare_commands packet received */
    this.commandTreeUpdates = [];
    this.bot = null;
    this.connected = false;
    this.spawnedAt = 0;
    this.quitting = false;
    /** @type {{type:string, at:number, reason?:string, loggedIn?:boolean}[]} */
    this.events = [];
    /** @type {{at:number, text:string, position:string}[]} */
    this.messages = [];
    /** world-age samples from update_time packets: {at, age} */
    this.timeSamples = [];
    this.maxMessages = 20000;
    this._waiters = new Set();
  }

  /**
   * Unexpected disconnects (kicks / connection ends that we did not ask for). One VibeBot = one connection,
   * so a kick ('kicked' then 'end') counts once, with the kick reason.
   */
  get disconnects() {
    const out = [];
    for (const e of this.events) {
      if (e.intentional) continue;
      if (e.type === 'kicked') out.push(e);
      else if (e.type === 'end' && !out.some((k) => k.type === 'kicked')) out.push(e);
    }
    return out;
  }

  get errors() {
    return this.events.filter((e) => e.type === 'error');
  }

  isConnected() {
    return this.connected;
  }

  /** Connect and resolve once the bot has spawned. Rejects on kick/end/error before spawn or on timeout. */
  connect({ timeoutMs = 30000 } = {}) {
    if (this.bot) throw new Error('VibeBot.connect() called twice (no auto-reconnect by design)');
    this.quitting = false;
    const bot = mineflayer.createBot({
      host: this.host,
      port: this.port,
      username: this.username,
      auth: 'offline',
      version: this.version || false,
      hideErrors: true,
      logErrors: false,
      checkTimeoutInterval: 60_000,
      respawn: true,
    });
    this.bot = bot;
    if (this.tickEndShim) this._installTickEndShim(bot);
    bot._client.on('declare_commands', (packet) => {
      try {
        const nodes = packet.nodes || [];
        const root = nodes[packet.rootIndex];
        const names = new Set((root?.children || []).map((i) => nodes[i]?.extraNodeData?.name).filter((n) => typeof n === 'string'));
        this.commandTree = names;
        this.commandTreeUpdates.push({ at: now(), count: names.size });
        if (this.commandTreeUpdates.length > 2000) this.commandTreeUpdates.splice(0, 500);
        for (const w of [...this._waiters]) w.onCommandTree?.(names);
      } catch (e) {
        this._event('error', { reason: 'cannot parse declare_commands: ' + e.message });
      }
    });
    // NBT longs inside chat components arrive as {"": [hi, lo]} and are silently dropped by prismarine-chat
    // (e.g. "/time query day" -> "Timeline minecraft:day is at  tick(s)"): turn them into plain numbers first.
    bot._client.prependListener('systemChat', (data) => {
      if (typeof data?.formattedMessage === 'string' && data.formattedMessage.includes('"":[')) {
        try { data.formattedMessage = JSON.stringify(fixNbtLongs(JSON.parse(data.formattedMessage))); } catch { /* keep */ }
      }
    });

    bot.on('messagestr', (text, position) => {
      const m = { at: now(), text, position: position || 'system' };
      this.messages.push(m);
      if (this.messages.length > this.maxMessages) this.messages.splice(0, this.messages.length - this.maxMessages);
      for (const w of [...this._waiters]) w.onMessage(m);
    });
    bot.on('time', () => {
      const age = Number(bot.time?.bigAge ?? bot.time?.age ?? NaN);
      if (Number.isFinite(age)) {
        this.timeSamples.push({ at: now(), age });
        if (this.timeSamples.length > 20000) this.timeSamples.splice(0, 5000);
      }
    });
    bot.on('death', () => this._event('death'));
    bot.on('kicked', (reason, loggedIn) => {
      this._event('kicked', { reason: reasonToText(reason), loggedIn, intentional: this.quitting });
    });
    bot.on('end', (reason) => {
      this.connected = false;
      this._event('end', { reason: reasonToText(reason), intentional: this.quitting });
      for (const w of [...this._waiters]) w.onDisconnect(this.events[this.events.length - 1]);
    });
    bot.on('error', (err) => this._event('error', { reason: String(err?.stack || err) }));

    return new Promise((resolve, reject) => {
      const t = setTimeout(() => { cleanup(); reject(new Error(`bot did not spawn within ${timeoutMs} ms`)); }, timeoutMs);
      const onSpawn = () => {
        cleanup();
        this.connected = true;
        this.spawnedAt = now();
        this._event('spawn');
        resolve(this);
      };
      const onFail = (what) => (reason) => {
        cleanup();
        reject(new Error(`bot ${what} before spawn: ${reasonToText(reason?.message ?? reason)}`));
      };
      const kicked = onFail('kicked'); const ended = onFail('disconnected'); const errored = onFail('error');
      const cleanup = () => {
        clearTimeout(t);
        bot.off('spawn', onSpawn); bot.off('kicked', kicked); bot.off('end', ended); bot.off('error', errored);
      };
      bot.once('spawn', onSpawn);
      bot.once('kicked', kicked);
      bot.once('end', ended);
      bot.once('error', errored);
    });
  }

  /**
   * Since 1.21.2 the server accepts at most ONE position-carrying move packet per client tick, client ticks
   * being delimited by the serverbound `tick_end` packet (ServerGamePacketListenerImpl#receivedPositionThisTick,
   * reset in handleClientTickEnd; a 2nd position in the same "tick" => kick "invalid_player_movement").
   * mineflayer 4.39 never sends `tick_end`, so on 26.x it is kicked ~1 s after spawning. This shim sends a
   * `tick_end` every 50 ms like a vanilla client, and also right before a position packet if one was already
   * sent since the last `tick_end` (e.g. teleport confirm + physics tick in the same 50 ms).
   */
  _installTickEndShim(bot) {
    let hasTickEnd = false; // decided on login, from the protocol definition of the negotiated version
    const client = bot._client;
    const rawWrite = client.write.bind(client);
    const POS = new Set(['position', 'position_look']);
    let posSinceTickEnd = false;
    const sendTickEnd = () => {
      if (client.state !== 'play') return;
      try { rawWrite('tick_end', {}); } catch { /* ignore */ }
      posSinceTickEnd = false;
    };
    const enable = () => {
      if (this._tickEndTimer) return;
      this._tickEndTimer = setInterval(sendTickEnd, 50);
      this._tickEndTimer.unref?.();
    };
    client.write = (name, params) => {
      if (hasTickEnd && POS.has(name)) {
        if (posSinceTickEnd) sendTickEnd();
        posSinceTickEnd = true;
      }
      return rawWrite(name, params);
    };
    const check = () => {
      try {
        hasTickEnd = !!bot.registry?.protocol?.play?.toServer?.types?.packet_tick_end;
      } catch { hasTickEnd = false; }
      if (hasTickEnd) enable();
    };
    bot.once('login', check);
    bot.once('end', () => { clearInterval(this._tickEndTimer); this._tickEndTimer = null; });
  }

  _event(type, extra = {}) {
    const e = { type, at: now(), ...extra };
    this.events.push(e);
    this.log(`[bot] ${type}${extra.reason ? ': ' + extra.reason.split('\n')[0] : ''}`);
    return e;
  }

  _ensure() {
    if (!this.connected) {
      const last = this.disconnects.at(-1);
      throw new Error('bot is not connected' + (last ? ` (${last.type} at ${new Date(last.at).toISOString()}: ${last.reason})` : ''));
    }
  }

  /** Send raw chat (no leading slash) or a command (leading slash). */
  say(text) {
    this._ensure();
    this.bot.chat(text);
  }

  /**
   * Send a command and collect the server messages received in a window.
   * @param {string} text command with or without leading '/'
   * @param {object} [o]
   * @param {number} [o.windowMs=1500]  max time to wait
   * @param {RegExp} [o.until]          stop early `settleMs` after the first message matching this
   * @param {number} [o.settleMs=500]   extra time after `until` matched (to catch duplicates)
   * @param {string[]} [o.positions]    message positions to keep (default system+chat; action bar excluded)
   * @returns {Promise<{lines:string[], text:string, ms:number, matched:boolean, firstMatchMs:number|null}>}
   */
  async cmd(text, { windowMs = 1500, until = null, settleMs = 500, positions = ['system', 'chat'] } = {}) {
    this._ensure();
    const cmdline = text.startsWith('/') ? text : '/' + text;
    const start = now();
    const startIdx = this.messages.length;
    let matched = false; let firstMatchMs = null;
    await new Promise((resolve) => {
      let settleTimer = null;
      const finish = () => { clearTimeout(windowTimer); clearTimeout(settleTimer); this._waiters.delete(w); resolve(); };
      const windowTimer = setTimeout(finish, windowMs);
      const w = {
        onMessage: (m) => {
          if (until && !matched && positions.includes(m.position) && until.test(m.text)) {
            matched = true; firstMatchMs = now() - start;
            clearTimeout(windowTimer);
            settleTimer = setTimeout(finish, settleMs);
          }
        },
        onDisconnect: () => finish(),
      };
      this._waiters.add(w);
      this.bot.chat(cmdline);
    });
    const lines = this.messages.slice(startIdx).filter((m) => positions.includes(m.position)).map((m) => m.text);
    return { lines, text: lines.join('\n'), ms: now() - start, matched, firstMatchMs };
  }

  /**
   * Wait for a server message matching `regex`.
   * @param {RegExp} regex
   * @param {number} [timeoutMs=10000]
   * @param {object} [o]
   * @param {number} [o.since] also accept messages already received at/after this epoch ms (default: only new ones)
   * @param {string[]} [o.positions] default: any position (system, chat, game_info)
   */
  waitForMessage(regex, timeoutMs = 10000, { since = null, positions = null } = {}) {
    const ok = (m) => (!positions || positions.includes(m.position)) && regex.test(m.text);
    if (since != null) {
      const hit = this.messages.find((m) => m.at >= since && ok(m));
      if (hit) return Promise.resolve(hit);
    }
    return new Promise((resolve, reject) => {
      if (!this.connected) { reject(new Error('bot is not connected')); return; }
      const w = {
        onMessage: (m) => { if (ok(m)) { done(); resolve(m); } },
        onDisconnect: (e) => { done(); reject(new Error(`bot disconnected while waiting for ${regex}: ${e?.reason}`)); },
      };
      const t = setTimeout(() => { done(); reject(new Error(`no message matching ${regex} within ${timeoutMs} ms`)); }, timeoutMs);
      const done = () => { clearTimeout(t); this._waiters.delete(w); };
      this._waiters.add(w);
    });
  }

  /**
   * Wait until the client's command tree (declare_commands, i.e. what tab completion offers) satisfies
   * predicate(names:Set<string>). Resolves with the names; rejects on timeout / disconnect.
   */
  waitForCommandTree(predicate, timeoutMs = 5000) {
    if (this.commandTree && predicate(this.commandTree)) return Promise.resolve(this.commandTree);
    return new Promise((resolve, reject) => {
      if (!this.connected) { reject(new Error('bot is not connected')); return; }
      const w = {
        onMessage: () => {},
        onCommandTree: (names) => { if (predicate(names)) { done(); resolve(names); } },
        onDisconnect: (e) => { done(); reject(new Error(`bot disconnected while waiting for the command tree: ${e?.reason}`)); },
      };
      const t = setTimeout(() => { done(); reject(new Error(`command tree condition not met within ${timeoutMs} ms (${this.commandTree ? this.commandTree.size + ' commands' : 'no declare_commands received'})`)); }, timeoutMs);
      const done = () => { clearTimeout(t); this._waiters.delete(w); };
      this._waiters.add(w);
    });
  }

  /**
   * Ask the server for tab-completion suggestions of `text` (e.g. "/vibe re"), like the client does while typing.
   * (mineflayer's own bot.tabComplete() writes the pre-1.13 packet layout; 26.x needs a transactionId.)
   * @returns {Promise<string[]>} suggested words
   */
  tabComplete(text, timeoutMs = 4000) {
    this._ensure();
    this._tabId = (this._tabId ?? 4200) + 1;
    const id = this._tabId;
    const client = this.bot._client;
    return new Promise((resolve, reject) => {
      const onPacket = (p) => { if (p.transactionId === id) { cleanup(); resolve((p.matches || []).map((m) => m.match)); } };
      const cleanup = () => { clearTimeout(t); client.off('tab_complete', onPacket); };
      const t = setTimeout(() => { cleanup(); reject(new Error(`no tab_complete answer for "${text}" within ${timeoutMs} ms`)); }, timeoutMs);
      client.on('tab_complete', onPacket);
      client.write('tab_complete', { transactionId: id, text });
    });
  }

  /** Messages received since `since` (epoch ms) matching an optional regex. */
  messagesSince(since, regex = null) {
    return this.messages.filter((m) => m.at >= since && (!regex || regex.test(m.text)));
  }

  /**
   * Server ticks per second as seen by the client, from world-age deltas of update_time packets.
   * @returns {{tps:number|null, samples:number, maxGapMs:number}} over [from, to]
   */
  tickStats(from = 0, to = Infinity) {
    const s = this.timeSamples.filter((x) => x.at >= from && x.at <= to);
    if (s.length < 2) return { tps: null, samples: s.length, maxGapMs: 0 };
    let maxGapMs = 0;
    for (let i = 1; i < s.length; i++) maxGapMs = Math.max(maxGapMs, s[i].at - s[i - 1].at);
    const dt = (s.at(-1).at - s[0].at) / 1000;
    const dAge = s.at(-1).age - s[0].age;
    return { tps: dt > 0 ? +(dAge / dt).toFixed(2) : null, samples: s.length, maxGapMs };
  }

  /** The `n` longest intervals without any world-time packet in [from, to]: [{from, ms}] (ISO time of the gap start). */
  timeGaps(from = 0, to = Infinity, n = 3) {
    const s = this.timeSamples.filter((x) => x.at >= from && x.at <= to);
    const gaps = [];
    for (let i = 1; i < s.length; i++) gaps.push({ from: new Date(s[i - 1].at).toISOString(), ms: s[i].at - s[i - 1].at });
    return gaps.sort((a, b) => b.ms - a.ms).slice(0, n);
  }

  /** Minimum client-observed TPS over sliding windows of `windowMs` (null if not enough samples). */
  minWindowTps(windowMs = 10000, from = 0, to = Infinity) {
    const s = this.timeSamples.filter((x) => x.at >= from && x.at <= to);
    let min = null;
    for (let i = 0, j = 0; i < s.length; i++) {
      while (j < s.length && s[j].at - s[i].at < windowMs) j++;
      if (j >= s.length) break;
      const tps = (s[j].age - s[i].age) / ((s[j].at - s[i].at) / 1000);
      if (min == null || tps < min) min = tps;
    }
    return min == null ? null : +min.toFixed(2);
  }

  position() {
    const p = this.bot?.entity?.position;
    return p ? { x: +p.x.toFixed(2), y: +p.y.toFixed(2), z: +p.z.toFixed(2) } : null;
  }

  /** Disconnect on purpose (not counted as a failure). */
  async quit(reason = 'e2e done') {
    if (!this.bot) return;
    this.quitting = true;
    if (this.connected) {
      const ended = new Promise((r) => this.bot.once('end', r));
      this.bot.quit(reason);
      await Promise.race([ended, sleep(3000)]);
    }
    this.connected = false;
  }
}

export { reasonToText, sleep };
