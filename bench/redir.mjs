import fs from 'fs';
import { StaticNetFilteringEngine, pslInit } from '@gorhill/ubo-core';
const s = await StaticNetFilteringEngine.create({ noPSL: true });
pslInit(fs.readFileSync('../shield-rs/data/public_suffix_list.dat', 'utf8'));
const names = JSON.parse(fs.readFileSync('lists.json','utf8'));
await s.useLists(names.map(n => ({ name: n, raw: fs.readFileSync('active/'+n+'.txt','utf8') })));
const a = fs.readFileSync('arivna-out.tsv','utf8').split('\n').filter(Boolean).map(l=>l.split('\t'));
let n=0, agree=0, noneBoth=0, diffs=[], arivnaOnly=0, uboOnly=0;
for (const r of a) {
  if (r[3] !== '1') continue;
  const d = { url: r[0], originURL: r[1], type: r[2] };
  s.matchRequest(d);
  const m = s.matchAndFetchModifiers(d, 'redirect-rule');
  let u = '';
  if (m && m.length) { const f = m[0]; u = ((f.refs && f.refs.value) || '').replace(/:\d+$/, ''); }
  const x = r[5] || '';
  n++;
  if (!x && !u) noneBoth++;
  else if (x === u) agree++;
  else { if (x && !u) arivnaOnly++; else if (u && !x) uboOnly++; if (diffs.length < 15) diffs.push([r[0].slice(0,70), r[2], 'arivna='+x, 'ubo='+u]); }
}
console.log({ blockedChecked: n, noRedirectBoth: noneBoth, sameRedirect: agree, arivnaOnly, uboOnly });
diffs.forEach(d => console.log(d.join(' | ')));
