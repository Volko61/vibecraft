#!/usr/bin/env node
// Minimal zero-dependency RCON client.
// Usage: node tests/tools/rcon.mjs [--dir <serverDir>] [--host 127.0.0.1] [--port N] [--password P] "command" ["command2" ...]
// Port/password default to the values in <serverDir>/server.properties (default serverDir: ./server).
import net from 'node:net';
import fs from 'node:fs';
import path from 'node:path';

const args = process.argv.slice(2);
const opts = { dir: 'server', host: '127.0.0.1', port: null, password: null };
const commands = [];
for (let i = 0; i < args.length; i++) {
  const a = args[i];
  if (a === '--dir') opts.dir = args[++i];
  else if (a === '--host') opts.host = args[++i];
  else if (a === '--port') opts.port = Number(args[++i]);
  else if (a === '--password') opts.password = args[++i];
  else commands.push(a);
}

if (opts.port == null || opts.password == null) {
  const props = fs.readFileSync(path.join(opts.dir, 'server.properties'), 'utf8');
  const get = (k) => (props.match(new RegExp('^' + k.replace('.', '\\.') + '=(.*)$', 'm')) || [])[1];
  opts.port ??= Number(get('rcon.port'));
  opts.password ??= get('rcon.password');
}
if (!commands.length) {
  console.error('usage: rcon.mjs [--dir serverDir] "command" ...');
  process.exit(2);
}

function packet(id, type, body) {
  const payload = Buffer.from(body, 'utf8');
  const buf = Buffer.alloc(14 + payload.length);
  buf.writeInt32LE(10 + payload.length, 0);
  buf.writeInt32LE(id, 4);
  buf.writeInt32LE(type, 8);
  payload.copy(buf, 12);
  return buf;
}

const sock = net.connect(opts.port, opts.host);
sock.setTimeout(15000, () => { console.error('rcon: timeout'); process.exit(1); });
sock.on('error', (e) => { console.error('rcon: ' + e.message); process.exit(1); });

let pending = Buffer.alloc(0);
let step = 0;
let nextId = 1;
sock.on('connect', () => sock.write(packet(nextId++, 3, opts.password)));
sock.on('data', (chunk) => {
  pending = Buffer.concat([pending, chunk]);
  while (pending.length >= 4) {
    const len = pending.readInt32LE(0);
    if (pending.length < 4 + len) break;
    const id = pending.readInt32LE(4);
    const body = pending.subarray(12, 4 + len - 2).toString('utf8');
    pending = pending.subarray(4 + len);
    if (step === 0) {
      if (id === -1) { console.error('rcon: auth failed'); process.exit(1); }
      step = 1;
      sendNext();
    } else {
      if (body) process.stdout.write(body.replace(/§./g, '') + (body.endsWith('\n') ? '' : '\n'));
      sendNext();
    }
  }
});

function sendNext() {
  const cmd = commands.shift();
  if (cmd === undefined) { sock.end(); return; }
  sock.write(packet(nextId++, 2, cmd.replace(/^\//, '')));
}
