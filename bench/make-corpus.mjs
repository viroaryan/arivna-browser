// Builds a deterministic request corpus from the filter lists themselves plus clean traffic.
// Output: corpus.tsv with  url \t origin \t type   (types use uBO names)
import fs from 'fs';
const dir = '../app/src/main/assets/filters/';
const names = JSON.parse(fs.readFileSync('lists.json', 'utf8'));
let seed = 12345;
const rnd = () => { seed ^= seed << 13; seed >>>= 0; seed ^= seed >>> 17; seed ^= seed << 5; seed >>>= 0; return seed / 4294967296; };
const pick = a => a[Math.floor(rnd() * a.length)];
const types = ['script', 'image', 'stylesheet', 'xmlhttprequest', 'sub_frame', 'font', 'media', 'ping', 'other'];
const origins = ['https://www.example.com/', 'https://news.site.org/article/1', 'https://www.youtube.com/watch?v=x', 'https://www.reddit.com/', 'https://hdhub4u.kg/', 'https://www.google.com/search?q=a', 'https://github.com/', 'https://www.cnn.com/'];
const out = new Set();
const add = (u, o, t) => out.add(`${u}\t${o}\t${t}`);
for (const n of names) {
  const lines = fs.readFileSync(dir + n + '.txt', 'utf8').split('\n');
  for (const raw of lines) {
    if (rnd() > 0.4) continue;
    let l = raw.trim();
    if (!l || l[0] === '!' || l[0] === '[' || l.includes('##') || l.includes('#@#') || l.includes('#?#') || l.includes('#$#') || l.includes('#%#')) continue;
    l = l.replace(/^@@/, '');
    const dollar = l.indexOf('$');
    let pat = dollar >= 0 ? l.slice(0, dollar) : l;
    if (/^\d+\.\d+\.\d+\.\d+\s+/.test(pat)) pat = '||' + pat.split(/\s+/)[1] + '^';
    if (pat.startsWith('/') && pat.endsWith('/')) continue;
    let u = pat.replace(/^\|\|/, 'https://').replace(/^\|/, '').replace(/\|$/, '').replace(/\^/g, '/').replace(/\*/g, 'x1');
    if (!/^https?:/.test(u)) { if (/^[\w.-]+\.[a-z]{2,}$/.test(u)) u = 'https://' + u + '/'; else u = 'https://cdn.test-host.net/' + u.replace(/^\/+/, ''); }
    if (!/^https?:\/\/[\w.-]+(:\d+)?([\/?#]|$)/.test(u)) continue;
    add(u, pick(origins), pick(types));
    if (rnd() < 0.3) add(u.replace(/^(https:\/\/)/, '$1sub.'), pick(origins), pick(types));
  }
}
const clean = ['https://www.example.com/', 'https://cdn.jsdelivr.net/npm/vue@3/dist/vue.global.js', 'https://code.jquery.com/jquery-3.7.1.min.js', 'https://fonts.googleapis.com/css2?family=Inter', 'https://fonts.gstatic.com/s/inter/v12/a.woff2', 'https://i.ytimg.com/vi/x/hqdefault.jpg', 'https://www.youtube.com/s/player/abc/base.js', 'https://github.githubassets.com/assets/app.js', 'https://upload.wikimedia.org/a.png', 'https://static.cnn.com/a.css', 'https://www.google.com/search?q=rust', 'https://api.github.com/repos/a/b', 'https://cdnjs.cloudflare.com/ajax/libs/x/1/x.js', 'https://news.site.org/static/main.js', 'https://images.unsplash.com/photo-1?w=400', 'https://www.reddit.com/r/rust/', 'https://stackoverflow.com/questions/1', 'https://developer.mozilla.org/en-US/docs/Web'];
for (const u of clean) for (const t of types) for (const o of origins.slice(0, 4)) add(u, o, t);
// URLs with tracking-style query strings and ad-ish paths
const hosts = ['www.example.com', 'cdn.site.org', 'static.news.net', 'media.shop.io'];
const paths = ['/ads/banner.gif', '/adframe.html', '/track/pixel.png', '/analytics.js', '/js/ads.js', '/img/sponsor.jpg', '/api/v1/collect', '/beacon?id=1', '/pagead/show_ads.js', '/static/app.js', '/main.css', '/logo.svg'];
for (const h of hosts) for (const p of paths) for (const t of types) add(`https://${h}${p}`, pick(origins), t);
fs.writeFileSync('corpus.tsv', [...out].join('\n') + '\n');
console.log('corpus size', out.size);
