// Persistent test player for the coder sandbox: connects once, then runs scenario files dropped in ./queue.
// Each queue/<name>.mjs exports default async (vb, h) => result; the result (or error) goes to queue/<name>.out.json.
import { VibeBot } from '../../e2e/lib/bot.mjs';
import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const dir = path.join(path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1')), 'queue');
fs.mkdirSync(dir, { recursive: true });
const log = (s) => console.log(new Date().toISOString().slice(11, 23), s);
const vb = new VibeBot({ port: Number(process.env.MC_PORT || 25620), username: process.argv[2] || 'Testeur', log });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
await vb.connect({ timeoutMs: 60000 });
log('spawned at ' + JSON.stringify(vb.bot.entity.position));
vb.bot.on('messagestr', (t, pos) => { if (pos !== 'game_info') log('[msg ' + pos + '] ' + t); });
const helpers = { sleep, log };
let busy = false;
setInterval(async () => {
  if (busy) return;
  const files = fs.readdirSync(dir).filter((f) => f.endsWith('.mjs'));
  if (!files.length) return;
  busy = true;
  for (const f of files) {
    const full = path.join(dir, f);
    const out = full.replace(/\.mjs$/, '.out.json');
    let res;
    try {
      const mod = await import(pathToFileURL(full).href + '?t=' + Date.now());
      res = { ok: true, result: await mod.default(vb, helpers) };
    } catch (e) {
      res = { ok: false, error: String(e?.stack || e) };
    }
    try { fs.renameSync(full, full + '.done'); } catch {}
    fs.writeFileSync(out, JSON.stringify(res, null, 2));
    log('ran ' + f + ' -> ' + (res.ok ? 'ok' : 'ERR'));
  }
  busy = false;
}, 300);
process.on('SIGINT', () => { vb.quitting = true; vb.bot?.quit(); process.exit(0); });
vb.bot.on('end', () => { log('ENDED'); setTimeout(() => process.exit(1), 500); });
