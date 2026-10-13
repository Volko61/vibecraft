export default async (vb, h) => {
  const bot = vb.bot;
  const r = await vb.cmd('/naive', { windowMs: 1500 });
  const sword = bot.inventory.items().find(i => i.name === 'diamond_sword');
  if (sword) await bot.equip(sword, 'hand');
  const bolts = []; const on = (e) => { if (e.name === 'lightning_bolt') bolts.push(1); };
  bot.on('entitySpawn', on);
  bot.activateItem(); await h.sleep(300); bot.deactivateItem();
  await h.sleep(1200);
  bot.off('entitySpawn', on);
  return { lines: r.lines, swordName: sword?.customName ?? sword?.displayName, bolts: bolts.length };
};
