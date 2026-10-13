// Reload a module N times through the HTTP API and sample class-loader / heap / metaspace / thread
// counts with jcmd every K reloads (GC.class_histogram forces a full GC first).
// Usage: node soak.mjs --api http://127.0.0.1:8810 --pid <java pid> --module leaks-soak --count 300 --every 50
//        [--jdk G:/Aywen/Minecraft/runtime/jdk-25/bin] [--probe lkprobe] [--out file.json]
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';

const a = process.argv.slice(2);
const o = { api: 'http://127.0.0.1:8810', pid: null, module: 'leaks-soak', count: 300, every: 50, jdk: 'G:/Aywen/Minecraft/runtime/jdk-25/bin', out: null, pkg: null };
for (let i = 0; i < a.length; i++) {
  const k = a[i].replace(/^--/, ''); o[k] = a[++i];
}
o.count = Number(o.count); o.every = Number(o.every);
const pkg = o.pkg || 'vibe.' + o.module.replace(/-/g, '_') + '.';

const jcmd = (...args) => execFileSync(o.jdk + '/jcmd', [String(o.pid), ...args], { encoding: 'utf8', maxBuffer: 256 * 1024 * 1024 });

function measure(label) {
  const t0 = Date.now();
  const hist = jcmd('GC.class_histogram'); // full GC first
  const gcMs = Date.now() - t0;
  let mcl = 0; let ctx = 0; let gens = 0; let modRows = 0; let modInstances = 0; const otherVibe = {};
  for (const line of hist.split('\n')) {
    const m = line.match(/^\s*\d+:\s+(\d+)\s+(\d+)\s+(\S+)/);
    if (!m) continue;
    const [, inst, , name] = m;
    if (name === 'dev.vibecraft.core.load.ModuleClassLoader') mcl += +inst;
    else if (name === 'dev.vibecraft.core.ctx.ModuleContextImpl') ctx += +inst;
    else if (name === 'dev.vibecraft.core.guard.Gen') gens += +inst;
    else if (name.startsWith(pkg)) { modRows++; modInstances += +inst; }
    else if (name.startsWith('vibe.')) { const p = name.split('.').slice(0, 2).join('.'); otherVibe[p] = (otherVibe[p] || 0) + 1; }
  }
  const heap = jcmd('GC.heap_info');
  const used = (heap.match(/used (\d+)K/) || [])[1];
  const ms = jcmd('VM.metaspace');
  const metaUsed = ((ms.match(/Both:[^\n]*?(\d+(?:\.\d+)?)\s*([KMG]B) used/) || ms.match(/Non-class space:[^\n]*/) || [])[0]) || '';
  const both = ms.split('\n').find((l) => /^\s*Both:/.test(l)) || '';
  const loaders = (ms.match(/Number of CLDs[^\n]*/) || [''])[0];
  const tlines = jcmd('Thread.print').split('\n').filter((l) => l.startsWith('"'));
  const threads = tlines.length;
  const tnames = {};
  for (const l of tlines) {
    const n = ((l.match(/^"([^"]*)"/) || [])[1] || '').replace(/[0-9]+/g, '#');
    if (!/^(GC Thread|G#|C#|VM |Reference|Finalizer|Signal|Service|Attach|Common-Cleaner|Notification|Monitor)/.test(n)) tnames[n] = (tnames[n] || 0) + 1;
  }
  const cls = jcmd('VM.classloader_stats').split('\n').filter((l) => l.includes('ModuleClassLoader'));
  const mclCLDs = cls.length;
  const mclClasses = cls.reduce((s, l) => s + Number(l.trim().split(/\s+/)[3] || 0), 0);
  const r = { label, at: new Date().toISOString(), moduleClassLoaders: mcl, contexts: ctx, gens, moduleClassRows: modRows, moduleInstances: modInstances, otherVibeClassRows: otherVibe, heapUsedMB: Math.round(Number(used) / 1024), metaspaceBoth: both.trim(), threads, threadNames: tnames, moduleLoaderCLDs: mclCLDs, moduleLoaderClasses: mclClasses, fullGcMs: gcMs };
  console.log(JSON.stringify(r));
  return r;
}

async function reload(id) {
  const t0 = Date.now();
  const res = await fetch(`${o.api}/api/modules/${id}/reload`, { method: 'POST', signal: AbortSignal.timeout(130000) });
  const j = await res.json();
  return { ok: j.ok, gen: j.generation, ms: Date.now() - t0, error: j.error };
}

const samples = [measure('before')];
const failures = [];
const durations = [];
for (let i = 1; i <= o.count; i++) {
  const r = await reload(o.module);
  durations.push(r.ms);
  if (!r.ok) failures.push({ i, ...r });
  if (i % o.every === 0) samples.push(measure('after ' + i + ' reloads (gen ' + r.gen + ')'));
}
durations.sort((x, y) => x - y);
const summary = {
  module: o.module, reloads: o.count, failures,
  reloadMs: { p50: durations[Math.floor(durations.length / 2)], p95: durations[Math.floor(durations.length * 0.95)], max: durations.at(-1) },
  samples,
};
if (o.out) fs.writeFileSync(o.out, JSON.stringify(summary, null, 2));
console.log(JSON.stringify({ failures: failures.length, reloadMs: summary.reloadMs }));
