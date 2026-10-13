// Fake OpenRouter API for tests (no real key, no network): mimics the response shapes of
//   POST /api/v1/chat/completions   (text; or images in message.images when "modalities" contains "image")
//   POST /api/v1/images             ({data:[{b64_json, media_type}], usage:{cost}})
// Every request must carry "Authorization: Bearer <key>" with the expected key, else 401 like the real API.
// Usage (standalone): node fake-openrouter.mjs [port] [key]     or import { startFakeOpenRouter }.
import http from 'node:http';
import zlib from 'node:zlib';

/** Minimal RGBA PNG encoder. */
export function encodePng(w, h, rgbaAt) {
  const raw = Buffer.alloc((w * 4 + 1) * h);
  for (let y = 0; y < h; y++) {
    raw[y * (w * 4 + 1)] = 0;
    for (let x = 0; x < w; x++) {
      const [r, g, b, a] = rgbaAt(x, y);
      const o = y * (w * 4 + 1) + 1 + x * 4;
      raw[o] = r; raw[o + 1] = g; raw[o + 2] = b; raw[o + 3] = a;
    }
  }
  const chunk = (type, data) => {
    const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
    const td = Buffer.concat([Buffer.from(type, 'ascii'), data]);
    const crc = Buffer.alloc(4); crc.writeUInt32BE(zlib.crc32(td) >>> 0);
    return Buffer.concat([len, td, crc]);
  };
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4);
  ihdr[8] = 8; ihdr[9] = 6; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0;
  return Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(raw)), chunk('IEND', Buffer.alloc(0))]);
}

/** 512x512 "AI picture": magenta background, a red/orange gem with a dark outline in the middle third. */
export function fakeArtwork() {
  return encodePng(512, 512, (x, y) => {
    const dx = x - 256, dy = y - 270;
    const d = Math.abs(dx) + Math.abs(dy) * 1.3; // diamond
    if (d < 120) return d < 60 ? [255, 170, 40, 255] : [220, 40, 30, 255];
    if (d < 132) return [40, 10, 10, 255];
    // slightly noisy magenta like a real model output
    const n = ((x * 7 + y * 13) % 9) - 4;
    return [250 + Math.min(5, n), 4 + Math.abs(n), 250 + Math.min(5, -n), 255];
  });
}

export function startFakeOpenRouter({ port = 8852, key = 'sk-or-v1-FAKE-TEST-KEY-0123456789', noImagesApi = false, log = () => {} } = {}) {
  const stats = { chat: 0, images: 0, chatImages: 0, unauthorized: 0, bodies: [], noImagesApi };
  const art = fakeArtwork();
  const server = http.createServer((req, res) => {
    let body = '';
    req.on('data', (c) => { body += c; });
    req.on('end', () => {
      const send = (status, obj) => {
        res.writeHead(status, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify(obj));
      };
      if (req.headers.authorization !== `Bearer ${key}`) {
        stats.unauthorized++;
        return send(401, { error: { message: 'No auth credentials found', code: 401 } });
      }
      let json = {};
      try { json = JSON.parse(body || '{}'); } catch { return send(400, { error: { message: 'bad json' } }); }
      stats.bodies.push({ path: req.url, json });
      log(`${req.method} ${req.url} model=${json.model}`);
      setTimeout(() => {
        if (req.url === '/api/v1/images') {
          if (stats.noImagesApi) return send(404, { error: { message: 'Not Found' } });
          stats.images++;
          return send(200, { created: Date.now(), data: [{ b64_json: art.toString('base64'), media_type: 'image/png' }],
            usage: { prompt_tokens: 0, completion_tokens: 1290, total_tokens: 1290, cost: 0.039 } });
        }
        if (req.url === '/api/v1/chat/completions') {
          if (Array.isArray(json.modalities) && json.modalities.includes('image')) {
            stats.chatImages++;
            return send(200, { id: 'gen-fake', model: json.model, choices: [{ index: 0, finish_reason: 'stop', message: {
              role: 'assistant', content: 'Voici ton image.',
              images: [{ type: 'image_url', image_url: { url: 'data:image/png;base64,' + art.toString('base64') } }] } }],
            usage: { cost: 0.04 } });
          }
          stats.chat++;
          const last = (json.messages || []).filter((m) => m.role === 'user').pop()?.content || '';
          return send(200, { id: 'gen-fake', model: json.model, object: 'chat.completion', choices: [{ index: 0, finish_reason: 'stop',
            message: { role: 'assistant', content: `Ah, ${String(last).slice(0, 40)} ? Par ma barbe, quelle question !` } }],
            usage: { prompt_tokens: 20, completion_tokens: 12, total_tokens: 32, cost: 0.00001 } });
        }
        send(404, { error: { message: 'unknown endpoint ' + req.url } });
      }, 150);
    });
  });
  return new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(port, '127.0.0.1', () => resolve({ server, stats, key, close: () => new Promise((r) => server.close(r)) }));
  });
}

if (import.meta.url === `file://${process.argv[1].replace(/\\/g, '/')}` || process.argv[1]?.endsWith('fake-openrouter.mjs')) {
  const port = Number(process.argv[2] || 8852);
  const key = process.argv[3] || 'sk-or-v1-FAKE-TEST-KEY-0123456789';
  startFakeOpenRouter({ port, key, log: (l) => console.log(l) }).then(() => console.log(`fake OpenRouter on http://127.0.0.1:${port}/api/v1`));
}
