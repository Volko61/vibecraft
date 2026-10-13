const api = (c) => fetch('http://127.0.0.1:8820/api/command', { method: 'POST', body: c }).then(r => r.json());
export default async (vb, h) => {
  const bot = vb.bot;
  const count = (n) => bot.inventory.items().filter(i => i.name === n).reduce((a, i) => a + i.count, 0);
  const em0 = count('emerald'), ga0 = count('golden_apple');
  const winP = new Promise((res) => bot.once('windowOpen', res));
  const out = await api('shop Testeur');
  const win = await Promise.race([winP, h.sleep(5000).then(() => null)]);
  if (!win) return { err: 'no window', out };
  const title = JSON.stringify(win.title).slice(0, 200);
  const slot10 = win.slots[10]?.name, slot22 = win.slots[22]?.name;
  const since = Date.now();
  await bot.clickWindow(10, 0, 0);
  await h.sleep(800);
  await bot.clickWindow(15, 0, 0);
  await h.sleep(800);
  // try to steal an item with shift-click
  await bot.clickWindow(13, 0, 1).catch(e => 0);
  await h.sleep(800);
  const msgs = vb.messagesSince(since).map(m => m.text);
  const cursor = bot.inventory.selectedItem ? null : null;
  bot.closeWindow(win);
  await h.sleep(500);
  return { out: out.output, title, slot10, slot22, msgs, em: [em0, count('emerald')], apples: [ga0, count('golden_apple')], beef: count('cooked_beef') };
};
