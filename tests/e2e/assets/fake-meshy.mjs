// Fake Meshy AI API for tests (no key, no network), shapes from https://docs.meshy.ai:
//   POST /openapi/v1/text-to-image  {ai_model, prompt, ...}            -> {result: id}
//   GET  /openapi/v1/text-to-image/:id                                 -> {id, status, progress, image_urls}
//   POST /openapi/v2/text-to-3d     {mode: preview|refine, ...}         -> {result: id}
//   GET  /openapi/v2/text-to-3d/:id                                    -> {id, status, progress, model_urls{obj,mtl}, texture_urls[{base_color}]}
// Tasks go PENDING -> IN_PROGRESS -> SUCCEEDED over a few polls. Files are served under /files/ without auth (CDN).
// The model is a dense UV sphere (4800 triangles); its texture is red on the upper half, blue on the lower half.
import http from 'node:http';
import { encodePng } from './fake-openrouter.mjs';

function sphereObj(lon = 60, lat = 40) {
  const v = []; const vt = []; const f = [];
  for (let j = 0; j <= lat; j++) {
    const th = Math.PI * j / lat; // 0 = top
    for (let i = 0; i <= lon; i++) {
      const ph = 2 * Math.PI * i / lon;
      v.push([Math.sin(th) * Math.cos(ph), 1 + Math.cos(th), Math.sin(th) * Math.sin(ph)]);
      vt.push([i / lon, 1 - j / lat]);
    }
  }
  const id = (i, j) => j * (lon + 1) + i + 1;
  for (let j = 0; j < lat; j++) {
    for (let i = 0; i < lon; i++) {
      const a = id(i, j), b = id(i + 1, j), c = id(i + 1, j + 1), d = id(i, j + 1);
      // counter-clockwise seen from outside
      f.push([a, d, c]); f.push([a, c, b]);
    }
  }
  return ['# fake meshy sphere', 'mtllib model.mtl', ...v.map((p) => `v ${p.map((x) => x.toFixed(5)).join(' ')}`),
    ...vt.map((p) => `vt ${p[0].toFixed(5)} ${p[1].toFixed(5)}`), 'usemtl material_0',
    ...f.map((t) => `f ${t.map((k) => `${k}/${k}`).join(' ')}`)].join('\n') + '\n';
}

export function startFakeMeshy({ port = 8873, key = 'msy_FAKE_TEST_KEY_0123456789abcdef', polls = 2, log = () => {} } = {}) {
  const base = `http://127.0.0.1:${port}`;
  const stats = { images: 0, previews: 0, refines: 0, gets: 0, unauthorized: 0, downloads: 0, bodies: [] };
  const tasks = new Map();
  let seq = 0;
  const obj = sphereObj();
  const tex = encodePng(64, 64, (x, y) => (y < 32 ? [220, 30, 30, 255] : [30, 60, 220, 255]));
  const img = encodePng(256, 256, (x, y) => ((x - 128) ** 2 + (y - 128) ** 2 < 70 ** 2 ? [40, 200, 60, 255] : [255, 0, 255, 255]));
  const server = http.createServer((req, res) => {
    let body = '';
    req.on('data', (c) => { body += c; });
    req.on('end', () => {
      const send = (status, o, type = 'application/json') => {
        res.writeHead(status, { 'Content-Type': type });
        res.end(type === 'application/json' ? JSON.stringify(o) : o);
      };
      const url = req.url.split('?')[0];
      if (url.startsWith('/files/')) {
        stats.downloads++;
        if (url === '/files/model.obj') return send(200, obj, 'text/plain');
        if (url === '/files/model.mtl') return send(200, 'newmtl material_0\nKd 1 1 1\nmap_Kd texture_0.png\n', 'text/plain');
        if (url === '/files/tex.png') return send(200, tex, 'image/png');
        if (url.startsWith('/files/img-')) return send(200, img, 'image/png');
        return send(404, { message: 'no such file' });
      }
      if (req.headers.authorization !== `Bearer ${key}`) {
        stats.unauthorized++;
        return send(401, { message: 'Invalid API key' });
      }
      let json = {};
      try { json = JSON.parse(body || '{}'); } catch { return send(400, { message: 'bad json' }); }
      log(`${req.method} ${url}`);
      if (req.method === 'POST' && (url === '/openapi/v1/text-to-image' || url === '/openapi/v2/text-to-3d')) {
        stats.bodies.push({ path: url, json });
        const kind = url.includes('image') ? 'image' : json.mode === 'refine' ? 'refine' : 'preview';
        if (kind === 'image') { stats.images++; if (!json.ai_model || !json.prompt) return send(400, { message: 'ai_model and prompt required' }); }
        if (kind === 'preview') { stats.previews++; if (!json.prompt) return send(400, { message: 'prompt required' }); }
        if (kind === 'refine') { stats.refines++; if (!tasks.has(json.preview_task_id)) return send(400, { message: 'unknown preview task' }); }
        const id = `0199-fake-${kind}-${++seq}`;
        tasks.set(id, { id, kind, polls: 0, json });
        return send(202, { result: id });
      }
      const m = /^\/openapi\/(v1\/text-to-image|v2\/text-to-3d)\/([^/]+)$/.exec(url);
      if (req.method === 'GET' && m) {
        stats.gets++;
        const t = tasks.get(m[2]);
        if (!t) return send(404, { message: 'Task not found' });
        t.polls++;
        const status = t.polls === 1 ? 'PENDING' : t.polls <= polls ? 'IN_PROGRESS' : 'SUCCEEDED';
        const o = { id: t.id, type: t.kind === 'image' ? 'text-to-image' : `text-to-3d-${t.kind}`, status,
          progress: status === 'SUCCEEDED' ? 100 : status === 'PENDING' ? 0 : 50, created_at: Date.now(), task_error: null };
        if (status === 'SUCCEEDED') {
          if (t.kind === 'image') o.image_urls = [`${base}/files/img-${t.id}.png`];
          else {
            o.model_urls = { obj: `${base}/files/model.obj`, mtl: `${base}/files/model.mtl` };
            o.thumbnail_url = `${base}/files/img-thumb.png`;
            if (t.kind === 'refine') o.texture_urls = [{ base_color: `${base}/files/tex.png` }];
          }
        }
        return send(200, o);
      }
      send(404, { message: 'unknown endpoint ' + url });
    });
  });
  return new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(port, '127.0.0.1', () => resolve({ server, stats, key, close: () => new Promise((r) => server.close(r)) }));
  });
}
