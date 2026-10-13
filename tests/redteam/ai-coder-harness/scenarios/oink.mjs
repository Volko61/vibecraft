export default async (vb, h) => {
  const before = Object.values(vb.bot.entities).filter(e => e.name === 'pig').length;
  const t = Date.now();
  vb.say('oink oink les amis');
  const m = await vb.waitForMessage(/OINK/, 5000);
  await h.sleep(2500);
  const pigs = Object.values(vb.bot.entities).filter(e => e.name === 'pig');
  const me = vb.bot.entity.position;
  const above = pigs.filter(p => p.position.y > me.y + 2).length;
  return { before, after: pigs.length, above, msg: m.text, ms: Date.now() - t };
};
