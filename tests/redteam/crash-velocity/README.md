# crash-velocity — extreme finite velocity freezes the server in vanilla collision (CRITICAL)

## What it does
`/vel huge` sets every online player's velocity to `(1e9,1e9,1e9)` (finite, so Paper accepts it).

## Repro
Deploy to `modules/velocity/`, reload, connect a player, run `/vel huge`.

## Expected
A fun shove; server keeps ticking.

## Observed
Next tick, vanilla `Entity.collide` -> `Level.getHardCollidingEntities` scans a ~1e9-block bounding
box on the MAIN thread and never finishes. Paper watchdog dumps repeatedly
("not responded for 10/15/.../45 seconds"), stuck in
`net.minecraft.world.entity.Entity.collide`. No ticks for 45+ s. The guard never applies: the work
is in the server's own entity tick, not a guarded module call.

## Held variants
`NaN`/`Infinity` velocity -> Paper throws `IllegalArgumentException: x not finite` (guard catches).
Teleport to 3e7 blocks -> only a brief mspt spike, recovers.

## Suggested fix
Redirect `Entity.setVelocity` (as other tracked Bukkit calls are redirected) and clamp magnitude to
a sane max (e.g. <= 10 blocks/tick); reject non-finite. More generally, cap per-entity movement the
module can induce.

## After the fix (re-verified 2026-10-14 on a private sandbox, MC 25640 / API 8840)

`/vel huge` -> velocity scaled down to 10 blocks/tick (`Entity.setVelocity` / Fireball vectors redirected); TPS 19.9, no watchdog dump, the bot just took fall damage.
Also clamped: projectiles launched with a huge speed (`launchProjectile(Class, Vector)`, `World.spawnArrow(..., speed, ...)`):
their velocity is scaled to 10 blocks/tick right after the call (probe module: `arrow speeds 10.0 / 10.0` for 1e9).
