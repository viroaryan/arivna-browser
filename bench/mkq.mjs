import fs from 'fs';
const names = JSON.parse(fs.readFileSync('lists.json','utf8'));
const hosts = new Set(['www.example.com','www.amazon.com','www.youtube.com','www.facebook.com','l.facebook.com','www.google.com','twitter.com','www.reddit.com','github.com','www.cnn.com','open.spotify.com','www.bing.com','www.linkedin.com','news.ycombinator.com']);
for (const n of names) for (const l of fs.readFileSync('active/'+n+'.txt','utf8').split('\n')) {
  if (!l.includes('removeparam')) continue;
  const m = l.match(/^(?:@@)?\|\|([\w.-]+)[\^\/]/); if (m) hosts.add(m[1]);
  const d = l.match(/domain=([\w.|-]+)/); if (d) d[1].split('|').filter(x=>!x.startsWith('~')).forEach(x=>hosts.add(x));
}
const params = ['utm_source=x','utm_medium=y','fbclid=abc','gclid=1','ref=tw','id=7','igshid=q','mc_eid=3','si=zz','_hsenc=1','msclkid=2','s=20','t=9','feature=share','ved=1','sca_esv=2','q=hello','page=2'];
const rows = [];
for (const h of [...hosts].slice(0, 2500)) {
  rows.push(`https://${h}/p/a?${params.join('&')}`);
  rows.push(`https://${h}/?${params.slice(0, 5).join('&')}#frag`);
  rows.push(`https://${h}/watch?v=1&${params.slice(5, 12).join('&')}`);
}
fs.writeFileSync('q.tsv', rows.join('\n')+'\n');
console.log('query corpus', rows.length);
