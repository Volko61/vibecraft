export default async (vb, h) => {
  const bot = vb.bot;
  const bars = [];
  const onBar = (p) => bars.push({ action: p.action, title: p.title ? JSON.stringify(p.title).match(/"value":"([^"]*s)"/g)?.join('|') : undefined, health: p.health });
  bot._client.on('boss_bar', onBar);
  const r0 = bot.isRaining;
  const res = await vb.cmd('/meteo pluie 3', { windowMs: 1500 });
  await h.sleep(3000);
  const r1 = bot.isRaining;
  const res2 = await vb.cmd('/meteo orage 2', { windowMs: 1000 });
  await h.sleep(2500);
  const t2 = bot.thunderState;
  const res3 = await vb.cmd('/meteo soleil 1', { windowMs: 500 });
  await h.sleep(2000);
  bot._client.off('boss_bar', onBar);
  return { r0, r1, thunder: t2, rainAfterSun: bot.isRaining, lines: [res.lines, res2.lines, res3.lines], bars: bars.slice(0, 12), nBars: bars.length };
};
