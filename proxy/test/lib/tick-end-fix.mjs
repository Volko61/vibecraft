// Minecraft 26.x servers accept at most ONE position-bearing move packet per client tick and only
// reset that flag when the client sends "tick_end" (ServerboundClientTickEndPacket). Vanilla clients
// send it every tick; mineflayer (<= 4.39, protocol 26.1) never does, so the 2nd position packet gets
// the bot kicked with "multiplayer.disconnect.invalid_player_movement" ~1 s after joining.
// Workaround: emit tick_end right after every movement packet the bot writes.
const MOVE = new Set(['position', 'position_look', 'look', 'flying']);
export function applyTickEndFix(bot) {
  const client = bot._client;
  const write = client.write.bind(client);
  client.write = (name, params) => {
    const r = write(name, params);
    if (MOVE.has(name) && client.state === 'play') {
      try { write('tick_end', {}); } catch { /* older protocol without tick_end */ }
    }
    return r;
  };
}
