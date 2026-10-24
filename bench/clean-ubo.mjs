import fs from 'fs';
import { StaticNetFilteringEngine, pslInit } from '@gorhill/ubo-core';
const s = await StaticNetFilteringEngine.create({ noPSL: true });
pslInit(fs.readFileSync('../shield-rs/data/public_suffix_list.dat', 'utf8'));
await s.useLists(JSON.parse(fs.readFileSync('lists.json','utf8')).map(n => ({ name: n, raw: fs.readFileSync('active/'+n+'.txt','utf8') })));
const urls = fs.readFileSync('q.tsv','utf8').split('\n').filter(Boolean);
const t = performance.now(); const out = [];
for (const u of urls) { const r = s.filterQuery({ url: u, originURL: '', type: 'main_frame' }); out.push(u + '\t' + (r && r.redirectURL ? r.redirectURL : u)); }
console.log('ubo filterQuery', ((performance.now()-t)*1000/urls.length).toFixed(2), 'us/url');
fs.writeFileSync('q-ubo.tsv', out.join('\n')+'\n');
const A = fs.readFileSync('q-arivna.tsv','utf8').split('\n').filter(Boolean), U = out;
let same=0, cleanedBoth=0, diff=[]; for (let i=0;i<A.length;i++){ if (A[i]===U[i]) { same++; if (A[i].split('\t')[0]!==A[i].split('\t')[1]) cleanedBoth++; } else diff.push(i); }
console.log({ total: A.length, identical: same, ofWhichCleaned: cleanedBoth, differ: diff.length });
for (const i of diff.slice(0,10)) console.log('\n', A[i].split('\t')[0], '\n  arivna', A[i].split('\t')[1], '\n  ubo   ', U[i].split('\t')[1]);
