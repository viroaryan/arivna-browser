// Runs the real uBlock Origin static network engine over the same corpus.
import fs from 'fs';
import { StaticNetFilteringEngine, pslInit } from '@gorhill/ubo-core';
const dir = '../app/src/main/assets/filters/';
const names = JSON.parse(fs.readFileSync('lists.json', 'utf8'));
const lists = names.map(n => ({ name: n, raw: fs.readFileSync(dir + n + '.txt', 'utf8') }));
const m0 = process.memoryUsage().rss;
let t = performance.now();
const snfe = await StaticNetFilteringEngine.create({ noPSL: true });
pslInit(fs.readFileSync('../shield-rs/data/public_suffix_list.dat', 'utf8'));
await snfe.useLists(lists);
console.log('ubo build_ms', Math.round(performance.now() - t), 'rss_delta_mb', ((process.memoryUsage().rss - m0) / 1048576).toFixed(0));
const rows = fs.readFileSync('mini.tsv', 'utf8').split('\n').filter(Boolean).map(l => l.split('\t'));
const res = new Array(rows.length);
// warm up so the JIT is fair to uBO
for (let i = 0; i < Math.min(5000, rows.length); i++) snfe.matchRequest({ url: rows[i][0], originURL: rows[i][1], type: rows[i][2] });
t = performance.now();
for (let i = 0; i < rows.length; i++) {
  const [u, o, ty] = rows[i];
  const r = snfe.matchRequest({ url: u, originURL: o, type: ty });
  // 1 = block, 2 = allow (exception), 0 = no match
  res[i] = r === 1 ? [1, snfe.toLogData()?.raw || ''] : [0, r === 2 ? '@@' + (snfe.toLogData()?.raw || '') : ''];
}
const el = performance.now() - t;
console.log('ubo check_total_ms', el.toFixed(1), 'per_check_us', (el * 1000 / rows.length).toFixed(2), 'n', rows.length);
fs.writeFileSync('mini-u.tsv', rows.map((r, i) => `${r[0]}\t${r[1]}\t${r[2]}\t${res[i][0]}\t${res[i][1]}`).join('\n') + '\n');
