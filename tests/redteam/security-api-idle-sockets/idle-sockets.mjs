import net from 'node:net';
const H='127.0.0.1', P=8830;
// 1) body limit: 70 KB idea body
const big='a'.repeat(70000);
let r=await fetch(`http://${H}:${P}/api/ideas`,{method:'POST',body:big});
console.log('70KB body ->', r.status, (await r.text()).slice(0,90));
// 2) idle connections (slowloris-lite, bounded): 30 sockets sending partial headers
const socks=[];
for(let i=0;i<30;i++){const s=net.connect(P,H,()=>s.write('GET /api/status HTTP/1.1\r\nHost: x\r\n'));s.on('error',()=>{});socks.push(s);}
await new Promise(r=>setTimeout(r,1500));
const t0=Date.now();
try{const c=new AbortController();setTimeout(()=>c.abort(),8000);r=await fetch(`http://${H}:${P}/api/status`,{signal:c.signal});console.log('status with 30 idle sockets ->',r.status,`${Date.now()-t0} ms`);}
catch(e){console.log('status with 30 idle sockets -> FAILED/timeout after',Date.now()-t0,'ms');}
socks.forEach(s=>s.destroy());
