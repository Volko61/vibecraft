const api = (c) => fetch('http://127.0.0.1:8820/api/command', { method: 'POST', body: c }).then(r => r.json());
export default async (vb, h) => {
  const bot = vb.bot;
  const winP = new Promise((res) => bot.once('windowOpen', res));
  await api('shop Testeur');
  const win = await Promise.race([winP, h.sleep(5000).then(() => null)]);
  if (!win) return { err: 'no window' };
  let closed = false;
  bot.once('windowClose', () => { closed = true; });
  const rl = await fetch('http://127.0.0.1:8820/api/modules/shop/reload', { method: 'POST' }).then(r => r.json());
  await h.sleep(1000);
  return { reload: rl.ok, gen: rl.generation, closedByServer: closed, currentWindow: bot.currentWindow?.id ?? null };
};
