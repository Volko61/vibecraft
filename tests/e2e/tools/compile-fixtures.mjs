#!/usr/bin/env node
// Compile-checks every e2e fixture (and every derived variant) exactly like the engine would:
// javac --release 25 -proc:none against server/libraries + server/versions + the VibeCraft API.
//
// Usage: node tools/compile-fixtures.mjs [--root G:/Aywen/Minecraft] [--keep]
// Expectations:
//   - every fixture compiles (as-is and rendered for its module id)
//   - COMPILE_ERROR / SYNTAX_ERROR variants FAIL to compile, with the error on the marked line
//   - every FORBIDDEN variant compiles (it must be rejected later, at load time, by the engine's policy)
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { fixtureNames, renderFixture, COMPILE_ERROR, SYNTAX_ERROR, FORBIDDEN, FORBIDDEN_INDIRECT, forbiddenInjection, forbiddenHelperFile, MODULE_CLASS_VARIANTS } from '../lib/fixtures.mjs';
import { packageFor } from '../lib/workspace.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const args = process.argv.slice(2);
const opt = (k, d) => { const i = args.indexOf(k); return i >= 0 ? args[i + 1] : d; };
const ROOT = path.resolve(opt('--root', path.join(here, '..', '..', '..')));
const KEEP = args.includes('--keep');
const exe = process.platform === 'win32' ? '.exe' : '';
const JAVAC = path.join(ROOT, 'runtime', 'jdk-25', 'bin', 'javac' + exe);

function jarsUnder(dir) {
  const out = [];
  const walk = (d) => {
    let es = [];
    try { es = fs.readdirSync(d, { withFileTypes: true }); } catch { return; }
    for (const e of es) {
      const p = path.join(d, e.name);
      if (e.isDirectory()) walk(p); else if (e.name.endsWith('.jar')) out.push(p);
    }
  };
  walk(dir);
  return out;
}

const cp = [...jarsUnder(path.join(ROOT, 'server', 'libraries')), ...jarsUnder(path.join(ROOT, 'server', 'versions'))];
const apiClasses = path.join(ROOT, 'vibecraft-plugin', 'out', 'classes');
const apiSources = path.join(ROOT, 'vibecraft-plugin', 'src', 'main', 'java', 'dev', 'vibecraft', 'api');
let extraSources = [];
if (fs.existsSync(path.join(apiClasses, 'dev', 'vibecraft', 'api', 'VibeModule.class'))) cp.push(apiClasses);
else extraSources = fs.readdirSync(apiSources).filter((f) => f.endsWith('.java')).map((f) => path.join(apiSources, f));

if (!fs.existsSync(JAVAC)) { console.error('javac not found: ' + JAVAC); process.exit(2); }
if (!cp.length) { console.error('no jars under server/libraries: start the Paper server once first'); process.exit(2); }

const tmpRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'vibe-fixtures-'));
const cpFile = path.join(tmpRoot, 'cp.txt');
fs.writeFileSync(cpFile, '-cp\n"' + cp.join(path.delimiter).replace(/\\/g, '/') + '"\n');

function compile(label, files) {
  const dir = path.join(tmpRoot, label.replace(/[^\w.-]+/g, '_'));
  const src = path.join(dir, 'src'); const out = path.join(dir, 'classes');
  fs.mkdirSync(out, { recursive: true });
  const javaFiles = [];
  for (const [rel, content] of Object.entries(files)) {
    const p = path.join(src, rel);
    fs.mkdirSync(path.dirname(p), { recursive: true });
    fs.writeFileSync(p, content);
    if (rel.endsWith('.java')) javaFiles.push(p);
  }
  const r = spawnSync(JAVAC, ['--release', '25', '-proc:none', '-g', '-parameters', '-encoding', 'UTF-8', '-Xlint:none',
    '-d', out, '@' + cpFile, ...javaFiles, ...extraSources], { encoding: 'utf8' });
  return { ok: r.status === 0, output: (r.stdout || '') + (r.stderr || '') };
}

const results = [];
const check = (label, files, expectOk, extraCheck) => {
  const t0 = Date.now();
  const r = compile(label, files);
  let pass = r.ok === expectOk;
  let why = pass ? '' : (expectOk ? 'expected to compile but failed' : 'expected a compile error but it compiled');
  if (pass && extraCheck) { const e = extraCheck(r); if (e) { pass = false; why = e; } }
  results.push({ label, pass, ms: Date.now() - t0, why, output: pass ? '' : r.output.trim().split('\n').slice(0, 12).join('\n') });
  console.log(`${pass ? 'PASS' : 'FAIL'}  ${label.padEnd(48)} ${String(Date.now() - t0).padStart(5)} ms ${why}`);
  if (!pass && r.output) console.log(r.output.trim().split('\n').slice(0, 12).map((l) => '      ' + l).join('\n'));
};

for (const name of fixtureNames()) {
  check(`fixture ${name} (as-is)`, renderFixture(name, {}), true);
  check(`fixture ${name} (rendered for e2e-x-${name})`, renderFixture(name, { id: `e2e-x-${name}`, vars: { V: 'run.v1', P: `e2ex${name}`, MODE: 'loops', TARGET: `e2e-x-${name}`, BOT: 'VibeBot' } }), true);
}
for (const mode of ['ok', 'loop', 'loopcatch', 'throw', 'recurse']) {
  check(`enable mode=${mode}`, renderFixture('enable', { id: 'e2e-enable', vars: { V: 'r.v1', P: 'e2eenable', MODE: mode } }), true);
}
for (const [label, variant] of [['COMPILE_ERROR', COMPILE_ERROR], ['SYNTAX_ERROR', SYNTAX_ERROR]]) {
  const files = renderFixture('hot', { id: 'e2e-hot', vars: { V: 'r.v2', P: 'e2ehot' }, inject: variant.inject });
  const markerLine = files[variant.file].split('\n').findIndex((l) => l.includes(variant.marker)) + 1;
  check(`hot + ${label} (error on line ${markerLine})`, files, false, (r) => {
    const m = r.output.match(new RegExp(variant.file.replace('.', '\\.') + ':(\\d+): error'));
    if (!m) return 'no "File.java:LINE: error" in javac output';
    const line = Number(m[1]);
    return Math.abs(line - markerLine) <= 1 ? '' : `first error on line ${line}, expected ~${markerLine}`;
  });
}
for (const mode of ['full', 'probe']) {
  check(`ctxres mode=${mode}`, renderFixture('ctxres', { id: 'e2e-ctx', vars: { V: 'r.v1', P: 'e2ectx', MODE: mode, TARGET: 'e2e-ctx', BOT: 'VibeBot' } }), true);
}
for (const v of MODULE_CLASS_VARIANTS) {
  // all of them compile: the problem must be detected at LOAD time
  check(`module-class variant: ${v.name}`, v.apply(renderFixture('hot', { id: 'e2e-cls', vars: { V: 'r.v2', P: 'e2ecls' } }), packageFor('e2e-cls')), true);
}
{
  const h = forbiddenHelperFile(packageFor('e2e-forbid'));
  check('forbidden call in a helper class file', { ...renderFixture('hot', { id: 'e2e-forbid', vars: { V: 'r.v2', P: 'e2eforbid' } }), [h.file]: h.content }, true);
}
for (const [label, snippet] of Object.entries({ ...FORBIDDEN, ...FORBIDDEN_INDIRECT })) {
  check(`forbidden ${label}`, renderFixture('hot', { id: 'e2e-forbid', vars: { V: 'r.v2', P: 'e2eforbid' }, inject: { CMD: forbiddenInjection(snippet) } }), true);
}

if (!KEEP) fs.rmSync(tmpRoot, { recursive: true, force: true });
else console.log('kept ' + tmpRoot);
const failed = results.filter((r) => !r.pass);
console.log(`\n${results.length - failed.length}/${results.length} compile checks passed` + (failed.length ? `; FAILED: ${failed.map((f) => f.label).join(', ')}` : ''));
process.exit(failed.length ? 1 : 0);
