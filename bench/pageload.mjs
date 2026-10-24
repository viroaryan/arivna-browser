// Usage: node pageload.mjs <port> <label> <runs> url1 url2 ...
// Loads each URL with the HTTP cache disabled and prints load timings from the page itself.
const [, , port, label, runsArg, ...urls] = process.argv;
const runs = Number(runsArg);
async function target() {
  const list = await (await fetch(`http://localhost:${port}/json`)).json();
  return list.find(t => t.type === 'page').webSocketDebuggerUrl;
}
function connect(url) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(url);
    let id = 0; const pend = new Map(); const waiters = [];
    ws.onmessage = e => { const m = JSON.parse(e.data); if (m.id && pend.has(m.id)) { pend.get(m.id)(m); pend.delete(m.id); } else if (m.method) waiters.forEach(w => w(m)); };
    ws.onopen = () => resolve({
      send: (method, params = {}) => new Promise(r => { const i = ++id; pend.set(i, r); ws.send(JSON.stringify({ id: i, method, params })); }),
      once: (name, ms) => new Promise(r => { const t = setTimeout(() => r(null), ms); waiters.push(function w(m) { if (m.method === name) { clearTimeout(t); waiters.splice(waiters.indexOf(w), 1); r(m); } }); }),
      close: () => ws.close(),
    });
    ws.onerror = reject;
  });
}
const results = {};
for (let r = 0; r < runs; r++) {
  for (const u of urls) {
    const c = await connect(await target());
    await c.send('Page.enable'); await c.send('Network.enable'); await c.send('Network.setCacheDisabled', { cacheDisabled: true });
    await c.send('Page.navigate', { url: 'about:blank' }); await c.once('Page.loadEventFired', 3000);
    const loaded = c.once('Page.loadEventFired', 30000);
    const t0 = Date.now();
    await c.send('Page.navigate', { url: u });
    const ev = await loaded; const wall = Date.now() - t0;
    await new Promise(r => setTimeout(r, 1500));
    const v = await c.send('Runtime.evaluate', { returnByValue: true, expression: `(function(){var n=performance.getEntriesByType('navigation')[0]||{};var fcp=(performance.getEntriesByType('paint').find(function(p){return p.name==='first-contentful-paint'})||{}).startTime;var res=performance.getEntriesByType('resource');return {dcl:Math.round(n.domContentLoadedEventEnd||0),load:Math.round(n.loadEventEnd||0),fcp:Math.round(fcp||0),reqs:res.length+1,kb:Math.round(res.reduce(function(a,e){return a+(e.encodedBodySize||0)},0)/1024)}})()` });
    const m = v.result && v.result.result && v.result.result.value || {};
    (results[u] ||= []).push({ wall: ev ? wall : null, ...m });
    c.close();
  }
}
console.log(`== ${label}`);
for (const u of urls) {
  const a = results[u]; const med = k => { const x = a.map(o => o[k]).filter(v => v != null).sort((p, q) => p - q); return x.length ? x[Math.floor(x.length / 2)] : 'n/a'; };
  console.log(u.replace(/^https?:\/\//, '').padEnd(28), `wall ${String(med('wall')).padStart(5)}ms  fcp ${String(med('fcp')).padStart(5)}  load ${String(med('load')).padStart(5)}  reqs ${String(med('reqs')).padStart(3)}  ${String(med('kb')).padStart(5)}KB`);
}
