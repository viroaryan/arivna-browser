import fs from 'fs';
const load = f => fs.readFileSync(f, 'utf8').split('\n').filter(Boolean).map(l => l.split('\t'));
const a = load('arivna-out.tsv'), u = load('ubo-out.tsv');
let both = 0, onlyA = [], onlyU = [], allowBoth = 0;
for (let i = 0; i < a.length; i++) {
  const x = a[i][3] === '1', y = u[i][3] === '1';
  if (x && y) both++; else if (x) onlyA.push(i); else if (y) onlyU.push(i); else allowBoth++;
}
const n = a.length;
console.log(`total ${n}\nagree-block ${both}\nagree-allow ${allowBoth}\nonly-arivna-blocks ${onlyA.length}\nonly-ubo-blocks ${onlyU.length}\nagreement ${((both + allowBoth) / n * 100).toFixed(2)}%`);
const show = (idx, label) => { console.log('\n== ' + label); for (const i of idx.slice(0, 25)) console.log(`${a[i][2].padEnd(14)} ${a[i][0].slice(0, 90)}\n    from ${a[i][1]}\n    arivna: ${a[i][4]}   ubo: ${u[i][4]}`); };
show(onlyA, 'blocked by Arivna only');
show(onlyU, 'blocked by uBO only');
fs.writeFileSync('mismatch.json', JSON.stringify({ onlyA: onlyA.map(i => a[i].concat(u[i][4])), onlyU: onlyU.map(i => a[i].concat(u[i][4])) }, null, 1));
