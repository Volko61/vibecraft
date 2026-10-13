export default async (vb, h) => {
  const bot = vb.bot;
  const Vec3 = bot.entity.position.constructor;
  const seen = [];
  const onSpawn = (e) => { if (['firework_rocket', 'tnt'].includes(e.name)) seen.push({ n: e.name, t: Date.now() }); };
  bot.on('entitySpawn', onSpawn);
  let explosion = 0;
  const onExp = () => explosion++;
  bot._client.on('explosion', onExp);
  await h.sleep(800);
  const tnt = bot.inventory.items().find(i => i.name === 'tnt');
  if (!tnt) return { err: 'no tnt' };
  await bot.equip(tnt, 'hand');
  const ref = bot.blockAt(bot.entity.position.offset(2, -1, 0).floored());
  const hp0 = bot.health;
  const t0 = Date.now();
  try { await bot.placeBlock(ref, new Vec3(0, 1, 0)); } catch (e) { seen.push({ placeErr: String(e) }); }
  await h.sleep(4500);
  bot.off('entitySpawn', onSpawn); bot._client.off('explosion', onExp);
  const ground = bot.blockAt(ref.position)?.name;
  return { seen: seen.map(s => s.n ? s.n + '@' + (s.t - t0) : s), explosionPackets: explosion, hp0, hp1: bot.health, groundAfter: ground };
};
