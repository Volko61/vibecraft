export default async (vb, h) => {
  const p = vb.waitForMessage(/termine le parkour/, 8000);
  await h.sleep(200);
  const r = await fetch('http://127.0.0.1:8820/api/command', { method: 'POST', body: 'tp Testeur 30.5 -42 -7.5' }).then(r => r.json());
  const m = await p;
  await h.sleep(500);
  const top = await fetch('http://127.0.0.1:8820/api/command', { method: 'POST', body: 'parkour top' }).then(r => r.json());
  // fall test: start again then tp below
  await fetch('http://127.0.0.1:8820/api/command', { method: 'POST', body: 'parkour Testeur' });
  await h.sleep(500);
  const pf = vb.waitForMessage(/Chute/, 8000);
  await fetch('http://127.0.0.1:8820/api/command', { method: 'POST', body: 'tp Testeur 5 -55 -7' });
  const f = await pf.then(x => x.text).catch(e => String(e));
  await h.sleep(500);
  return { tp: r, finish: m.text, top: top.output, fall: f, posAfterFall: vb.bot.entity.position };
};
