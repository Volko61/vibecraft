#!/usr/bin/env node
// Repro: the client's command tree (tab completion / command highlighting) ends up STALE after several modules
// are added / removed at the same time, although the server dispatcher is correct.
// Each round: create N modules (one command each) at once -> wait until all are ENABLED -> wait 1.5 s -> does the
// bot's last declare_commands contain all N? Then delete them all at once -> wait until unloaded -> wait 1.5 s ->
// does the last declare_commands still list any of them? Also counts the declare_commands packets per phase.
//
//   node tests/redteam/e2emain-stale-command-tree/repro.mjs [--rounds 10] [--n 7] [--api http://127.0.0.1:8765] [--mc-port 25565] [--rcon-dir server]
// Module ids rt-tree-1..N in workspace/modules (deleted at the end).
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { VibeBot, sleep } from '../../e2e/lib/bot.mjs';
import { VibeApi } from '../../e2e/lib/api.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, '..', '..', '..');
const argv = process.argv.slice(2);
const opt = (n, d) => { const i = argv.indexOf(n); return i >= 0 ? argv[i + 1] : d; };
const ROUNDS = Number(opt('--rounds', '10'));
const N = Number(opt('--n', '7'));
const api = new VibeApi({ base: opt('--api', 'http://127.0.0.1:8765') });
const MODULES = path.resolve(opt('--modules-dir', path.join(ROOT, 'workspace', 'modules')));
const DATA = path.join(MODULES, '..', 'data');
const ids = Array.from({ length: N }, (_, i) => `rt-tree-${i + 1}`);
const cmdOf = (id) => id.replace(/-/g, '');
const src = (id) => `package vibe.${id.replace(/-/g, '_')};

import dev.vibecraft.api.ModuleContext;
import dev.vibecraft.api.VibeModule;

public class TreeModule implements VibeModule {
    @Override
    public void enable(ModuleContext ctx) {
        ctx.command("${cmdOf(id)}", (sender, args) -> sender.sendMessage("TREE ${id}"));
    }
}
`;

const bot = new VibeBot({ port: Number(opt('--mc-port', '25565')) });
// whitelist/op are refused by the API since the api-command-no-denylist fix: use RCON (--rcon-dir <serverDir>)
const rconDir = opt('--rcon-dir', null);
for (const line of ['whitelist add VibeBot', 'op VibeBot']) {
  try { await api.command(line); } catch (e) {
    if (rconDir) { const { rcon } = await import('../../e2e/lib/rcon.mjs'); await rcon(rconDir, line); }
  }
}
await bot.connect();
const trees = []; // {at, names}
bot.bot._client.on('declare_commands', () => trees.push({ at: Date.now(), names: new Set(bot.commandTree) }));
const results = [];
try {
  for (let r = 1; r <= ROUNDS; r++) {
    // ---- add N at once
    let t = Date.now();
    await Promise.all(ids.map(async (id) => {
      await fs.promises.mkdir(path.join(MODULES, id), { recursive: true });
      await fs.promises.writeFile(path.join(MODULES, id, 'TreeModule.java'), src(id));
    }));
    for (let i = 0; i < 100; i++) {
      const list = await api.modules();
      if (ids.every((id) => list.some((m) => m.id === id && m.state === 'ENABLED'))) break;
      await sleep(100);
    }
    await sleep(1500);
    const addPackets = trees.filter((x) => x.at >= t).length;
    const missing = ids.filter((id) => !bot.commandTree.has(cmdOf(id)));
    // the server itself knows them?
    const srv = await bot.cmd('/' + cmdOf(ids[0]), { until: /TREE|Unknown/, windowMs: 2000 });
    // ---- delete N at once
    t = Date.now();
    await Promise.all(ids.map((id) => fs.promises.rm(path.join(MODULES, id), { recursive: true, force: true })));
    for (let i = 0; i < 100; i++) {
      const list = await api.modules();
      if (!list.some((m) => ids.includes(m.id))) break;
      await sleep(100);
    }
    await sleep(1500);
    const delPackets = trees.filter((x) => x.at >= t).length;
    const leftover = ids.filter((id) => bot.commandTree.has(cmdOf(id)) || bot.commandTree.has('vibe:' + cmdOf(id)));
    const srv2 = await bot.cmd('/' + cmdOf(ids[0]), { until: /TREE|Unknown/, windowMs: 2000 });
    const row = { round: r, addPackets, missingAfterAdd: missing, serverHasCmd: /TREE/.test(srv.text), delPackets, staleAfterDelete: leftover, serverStillHasCmd: /TREE/.test(srv2.text) };
    results.push(row);
    console.log(JSON.stringify(row));
  }
} finally {
  for (const id of ids) { fs.rmSync(path.join(MODULES, id), { recursive: true, force: true }); fs.rmSync(path.join(DATA, id), { recursive: true, force: true }); }
  await bot.quit();
}
const stale = results.filter((x) => x.missingAfterAdd.length || x.staleAfterDelete.length);
console.log(`\n${stale.length}/${results.length} rounds ended with a stale client command tree (server dispatcher correct in all: ${results.every((x) => x.serverHasCmd && !x.serverStillHasCmd)})`);
process.exit(stale.length ? 1 : 0);
