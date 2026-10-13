const api = (c) => fetch('http://127.0.0.1:8820/api/command', { method: 'POST', body: c }).then(r => r.json());
export default async (vb, h) => {
  const bot = vb.bot;
  const oinks = () => Object.values(bot.entities).filter(e => e.name === 'pig' && e.position.distanceTo(bot.entity.position) < 30);
  const displays = () => Object.values(bot.entities).filter(e => e.name === 'item_display');
  const t0 = Date.now();
  vb.say('OINK!');
  await vb.waitForMessage(/OINK/, 5000);
  await h.sleep(2000);
  const a = { pigs: oinks().length, displays: displays().length, withPassenger: oinks().filter(p => p.passengers?.length).length,
              c1: (await api('execute if entity @e[type=minecraft:item_display]')).output, c2: (await api('execute if entity @e[type=minecraft:pig,name=Oink]')).output };
  await h.sleep(9000);
  const b = { c1: (await api('execute if entity @e[type=minecraft:item_display]')).output, c2: (await api('execute if entity @e[type=minecraft:pig,name=Oink]')).output, ms: Date.now() - t0 };
  return { a, b };
};
