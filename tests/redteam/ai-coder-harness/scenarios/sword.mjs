export default async (vb, h) => {
  const bot = vb.bot;
  const bolts = [];
  const onSpawn = (e) => { if (e.name === 'lightning_bolt') bolts.push({ at: Date.now(), pos: e.position.clone() }); };
  bot.on('entitySpawn', onSpawn);
  await h.sleep(500);
  const sword = bot.inventory.items().find(i => i.name === 'golden_sword');
  if (!sword) return { error: 'no sword', items: bot.inventory.items().map(i => i.name) };
  await bot.equip(sword, 'hand');
  const me = bot.entity.position;
  const target = bot.blockAt(me.offset(3, -1, 0).floored());
  await bot.lookAt(target.position.offset(0.5, 1, 0.5), true);
  bot.dig(target, true).catch(() => {});
  await h.sleep(600);
  try { bot.stopDigging(); } catch {}
  await h.sleep(800);
  const blockStill = bot.blockAt(target.position)?.name;
  const r1 = { bolts: bolts.map(b => b.pos), target: target.position, blockStill };
  // now hit a pig: find nearest pig
  const pig = bot.nearestEntity(e => e.name === 'pig');
  let r2 = null;
  if (pig) {
    const n0 = bolts.length;
    const hp0 = pig.health ?? pig.metadata?.[9];
    // walk close if needed: teleport is not possible for non-op bot; just attack if within 4 blocks
    const d = pig.position.distanceTo(bot.entity.position);
    if (d < 3.5) { await bot.lookAt(pig.position.offset(0, 0.5, 0), true); bot.attack(pig); await h.sleep(1000); }
    r2 = { pigDist: d, newBolts: bolts.length - n0, pigValid: pig.isValid };
  }
  bot.off('entitySpawn', onSpawn);
  return { r1, r2 };
};
