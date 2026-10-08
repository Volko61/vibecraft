// Minimal Minecraft "server list ping" (status) client, zero dependency.
// ping('127.0.0.1', 25581, { timeoutMs }) -> { latencyMs, status } or throws.
import net from 'node:net';

function varint(n) {
  const out = [];
  n >>>= 0;
  do {
    let b = n & 0x7f;
    n >>>= 7;
    if (n !== 0) b |= 0x80;
    out.push(b);
  } while (n !== 0);
  return Buffer.from(out);
}

function readVarint(buf, off) {
  let num = 0, shift = 0, pos = off;
  for (;;) {
    if (pos >= buf.length) return null;
    const b = buf[pos++];
    num |= (b & 0x7f) << shift;
    if ((b & 0x80) === 0) return { value: num, size: pos - off };
    shift += 7;
    if (shift > 35) throw new Error('varint too big');
  }
}

function packet(id, payload) {
  const body = Buffer.concat([varint(id), payload]);
  return Buffer.concat([varint(body.length), body]);
}

export function ping(host, port, { timeoutMs = 1500, protocol = 775 } = {}) {
  return new Promise((resolve, reject) => {
    const t0 = Date.now();
    const sock = net.connect(port, host);
    let buf = Buffer.alloc(0);
    let done = false;
    const finish = (err, val) => {
      if (done) return;
      done = true;
      sock.destroy();
      clearTimeout(timer);
      err ? reject(err) : resolve(val);
    };
    const timer = setTimeout(() => finish(new Error('timeout')), timeoutMs);
    sock.on('error', (e) => finish(e));
    sock.on('close', () => finish(new Error('closed')));
    sock.on('connect', () => {
      const hostBuf = Buffer.from(host, 'utf8');
      const portBuf = Buffer.alloc(2);
      portBuf.writeUInt16BE(port);
      const hs = Buffer.concat([varint(protocol), varint(hostBuf.length), hostBuf, portBuf, varint(1)]);
      sock.write(packet(0x00, hs));
      sock.write(packet(0x00, Buffer.alloc(0)));
    });
    sock.on('data', (chunk) => {
      buf = Buffer.concat([buf, chunk]);
      const len = readVarint(buf, 0);
      if (!len || buf.length < len.size + len.value) return;
      const body = buf.subarray(len.size, len.size + len.value);
      const id = readVarint(body, 0);
      const strLen = readVarint(body, id.size);
      const json = body.subarray(id.size + strLen.size, id.size + strLen.size + strLen.value).toString('utf8');
      try {
        finish(null, { latencyMs: Date.now() - t0, status: JSON.parse(json) });
      } catch (e) {
        finish(e);
      }
    });
  });
}

// CLI: node mcping.mjs host port [--watch ms]
if (process.argv[1] && process.argv[1].endsWith('mcping.mjs')) {
  const [host = '127.0.0.1', port = '25565', flag, every] = process.argv.slice(2);
  const once = async () => {
    try {
      const r = await ping(host, Number(port));
      console.log(new Date().toISOString(), 'OK', r.latencyMs + 'ms', JSON.stringify(r.status.version), r.status.players ? `${r.status.players.online}/${r.status.players.max}` : '');
    } catch (e) {
      console.log(new Date().toISOString(), 'FAIL', e.code || e.message);
    }
  };
  if (flag === '--watch') {
    setInterval(once, Number(every || 500));
  } else {
    await once();
  }
}
