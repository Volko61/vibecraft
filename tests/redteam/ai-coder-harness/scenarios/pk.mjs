export default async (vb, h) => {
  const bot = vb.bot;
  const since = Date.now();
  await h.sleep(1500);
  const bars = vb.messagesSince(since).filter(m => m.position === 'game_info').map(m => m.text).slice(-2);
  const gold = bot.findBlock({ matching: (b) => b.name === 'gold_block', maxDistance: 64 });
  const wools = bot.findBlocks({ matching: (b) => b.name.endsWith('_wool'), maxDistance: 64, count: 50 }).length;
  return { pos: bot.entity.position, bars, gold: gold?.position, wools };
};
