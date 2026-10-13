// Client for bot-daemon.mjs: node botctl.mjs --dir <ipcDir> '<json request>'
// e.g. node botctl.mjs --dir /tmp/bot '{"op":"status"}'
import fs from 'node:fs';
import path from 'node:path';

const args = process.argv.slice(2);
let dir = null; let body = null; let timeoutMs = 30000;
for (let i = 0; i < args.length; i++) {
  if (args[i] === '--dir') dir = args[++i];
  else if (args[i] === '--timeout') timeoutMs = Number(args[++i]);
  else body = args[i];
}
const id = Date.now() + '-' + Math.floor(Math.random() * 1e6);
fs.writeFileSync(path.join(dir, 'req-' + id + '.json.tmp'), body);
fs.renameSync(path.join(dir, 'req-' + id + '.json.tmp'), path.join(dir, 'req-' + id + '.json'));
const res = path.join(dir, 'res-' + id + '.json');
const t0 = Date.now();
while (Date.now() - t0 < timeoutMs) {
  if (fs.existsSync(res)) {
    const out = fs.readFileSync(res, 'utf8');
    fs.unlinkSync(res);
    console.log(out);
    process.exit(0);
  }
  await new Promise((r) => setTimeout(r, 50));
}
console.error('timeout');
process.exit(1);
