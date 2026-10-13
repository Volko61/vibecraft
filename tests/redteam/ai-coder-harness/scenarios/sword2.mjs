export default async (vb, h) => {
  const bot = vb.bot;
  const bolts = [];
  const onSpawn = (e) => { if (e.name === 'lightning_bolt') bolts.push(e.position.clone()); };
  bot.on('entitySpawn', onSpawn);
  await h.sleep(1000);
  const z = bot.nearestEntity(e => e.name === 'cow');
  if (!z) return 'no zombie';
  await bot.lookAt(z.position.offset(0, 1, 0), true);
  await h.sleep(500);
  bot.attack(z);
  await h.sleep(1500);
  bot.off('entitySpawn', onSpawn);
  return { dist: z.position.distanceTo(bot.entity.position), bolts, zombieValid: z.isValid, zpos: z.position };
};
