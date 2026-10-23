# helpers for the REDTEAM-LEAKS sandbox: source this file
export LK_ROOT=/g/Aywen/Minecraft
export LK_SB=$LK_ROOT/.sandbox-leaks
export LK_MOD=$LK_SB/workspace/modules
export LK_API=http://127.0.0.1:8810
export LK_BOTDIR="${LK_BOTDIR:-C:/Users/Volko/AppData/Local/Temp/claude/G--Aywen-Minecraft/4fcc243e-e251-4c04-b565-d5fde4563553/scratchpad/botipc}"
reload() { curl -s -X POST $LK_API/api/modules/$1/reload; echo; }
modinfo() { curl -s $LK_API/api/modules/$1; echo; }
mc() { curl -s -X POST $LK_API/api/command --data "$*"; echo; }
probe() { curl -s -X POST $LK_API/api/command --data "lkprobe" | node -e "let s='';process.stdin.on('data',d=>s+=d).on('end',()=>{const j=JSON.parse(s);const p=JSON.parse(j.output[0]);delete p.threads;console.log(JSON.stringify(p,null,0))})"; }
bot() { node $LK_ROOT/tests/redteam/leaks-tools/botctl.mjs --dir "$LK_BOTDIR" "$1"; }
logs() { curl -s "$LK_API/api/modules/$1/logs?limit=${2:-30}"; echo; }
