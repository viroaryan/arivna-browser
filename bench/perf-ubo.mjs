import fs from 'fs';
import { StaticNetFilteringEngine, pslInit } from '@gorhill/ubo-core';
const s = await StaticNetFilteringEngine.create({ noPSL: true });
pslInit(fs.readFileSync('../shield-rs/data/public_suffix_list.dat', 'utf8'));
const lists = JSON.parse(fs.readFileSync('lists.json','utf8')).map(n => ({ name: n, raw: fs.readFileSync('active/'+n+'.txt','utf8') }));
global.gc && global.gc(); const m0 = process.memoryUsage();
let t = performance.now(); await s.useLists(lists); console.log('ubo build_ms', Math.round(performance.now()-t));
global.gc && global.gc(); const m1 = process.memoryUsage();
console.log('ubo heap_delta_mb', ((m1.heapUsed+m1.arrayBuffers-m0.heapUsed-m0.arrayBuffers)/1048576).toFixed(0), 'rss_delta_mb', ((m1.rss-m0.rss)/1048576).toFixed(0));
const rows = fs.readFileSync('corpus.tsv','utf8').split('\n').filter(Boolean).map(l=>l.split('\t'));
for (let i=0;i<8000;i++) s.matchRequest({url:rows[i][0],originURL:rows[i][1],type:rows[i][2]});
let best=1e9; for (let k=0;k<5;k++){ t=performance.now(); let n=0; for (const r of rows) if (s.matchRequest({url:r[0],originURL:r[1],type:r[2]})===1) n++; best=Math.min(best,performance.now()-t); }
console.log('ubo check best-of-5:', (best*1000/rows.length).toFixed(2), 'us/check');
t = performance.now(); const sel = await s.serialize(); console.log('ubo selfie serialize_ms', Math.round(performance.now()-t), 'size_mb', (Buffer.byteLength(sel)/1048576).toFixed(1));
let bl=1e9; for (let k=0;k<5;k++){ t=performance.now(); await s.deserialize(sel); bl=Math.min(bl, performance.now()-t);} console.log('ubo selfie load (RAM string, best of 5)', bl.toFixed(0), 'ms');
