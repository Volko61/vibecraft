export default async (vb, h) => {
  const bot = vb.bot;
  const bars = [];
  const on = (m) => bars.push(m.toString());
  bot.on('actionBar', on);
  const raw = [];
  const onRaw = (p) => raw.push(JSON.stringify(p).slice(0, 200));
  bot._client.on('action_bar', onRaw);
  bot._client.on('set_action_bar_text', onRaw);
  await h.sleep(1200);
  bot.off('actionBar', on);
  bot._client.off('action_bar', onRaw); bot._client.off('set_action_bar_text', onRaw);
  return { bars: bars.slice(-3), n: bars.length, raw: raw.slice(-2), rawN: raw.length };
};
