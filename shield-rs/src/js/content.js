// Arivna Shield cosmetic runtime. Injected at document start in every frame.
// Talks to the native engine through a synchronous JavaScript bridge, then
// hides the bridge from the page.

var BRIDGE = '__arivnaShield';
var B = W[BRIDGE];
if (!B || W.__arivnaShieldRan) return;
try { N.defineProperty(W, '__arivnaShieldRan', { value: true }); } catch (e) {}
try { delete W[BRIDGE]; } catch (e) {}
// Element picker hook (see ElementPicker in the app). Only the native side acts on it, and only
// while the user is picking an element, so the page gets nothing it could use.
try { N.defineProperty(W, '__arivnaPick', { value: function (sel) { try { B.pick(String(sel)); } catch (e) {} } }); } catch (e) {}

var href = location.href;
if (!/^https?:/.test(href)) {
  try { href = W.parent.location.href; } catch (e) { href = document.referrer || ''; }
}
if (!/^https?:/.test(href)) return;

var cfg;
try { cfg = N.JSONparse(B.cosmetic(href)); } catch (e) { return; }
if (!cfg || cfg.off) return;

// 1. Scriptlets — must run before any page script.
if (cfg.js) for (var si = 0; si < cfg.js.length; si++) runScriptlet(cfg.js[si]);

// 2. Stylesheet, kept out of the DOM where possible so pages can't see or remove it.
var HIDE = '{display:none!important;}';
var sheet = null, styleEl = null;
function makeSheet() {
  if (sheet || styleEl) return;
  try {
    sheet = new CSSStyleSheet();
    document.adoptedStyleSheets = document.adoptedStyleSheets.concat([sheet]);
  } catch (e) {
    sheet = null;
    styleEl = document.createElement('style');
    (document.head || document.documentElement).appendChild(styleEl);
  }
}
// Rules are inserted one by one: a single malformed selector (e.g. an unbalanced
// bracket) would otherwise swallow every rule after it.
function addRules(rules) {
  if (!rules.length) return;
  makeSheet();
  if (sheet) {
    for (var i = 0; i < rules.length; i++) {
      try { sheet.insertRule(rules[i], sheet.cssRules.length); } catch (e) {}
    }
  } else if (styleEl) {
    var ok = [];
    for (var j = 0; j < rules.length; j++) {
      try { if (document.querySelector(rules[j].slice(0, rules[j].lastIndexOf('{'))) !== undefined) ok.push(rules[j]); } catch (e) {}
    }
    styleEl.textContent += ok.join('\n') + '\n';
  }
}
function keepSheet() {
  if (sheet && document.adoptedStyleSheets.indexOf(sheet) === -1) {
    try { document.adoptedStyleSheets = document.adoptedStyleSheets.concat([sheet]); } catch (e) {}
  } else if (styleEl && !styleEl.isConnected) {
    (document.head || document.documentElement).appendChild(styleEl);
  }
}

var initial = [];
if (cfg.hide) for (var hi = 0; hi < cfg.hide.length; hi++) initial.push(cfg.hide[hi] + HIDE);
if (cfg.style) for (var st = 0; st < cfg.style.length; st++) initial.push(cfg.style[st][0] + '{' + cfg.style[st][1] + '}');
addRules(initial);

// 3. Generic hiding, looked up lazily for the classes and ids actually present.
var seenClass = new Set(), seenId = new Set(), pendClass = [], pendId = [];
var flushTimer = 0;
function note(el) {
  if (el.nodeType !== 1) return;
  var id = el.id;
  if (id && typeof id === 'string' && !seenId.has(id)) { seenId.add(id); pendId.push(id); }
  var cl = el.classList;
  if (cl) for (var i = 0; i < cl.length; i++) { var c = cl[i]; if (!seenClass.has(c)) { seenClass.add(c); pendClass.push(c); } }
}
function scanTree(root) {
  note(root);
  if (!root.querySelectorAll) return;
  var els = root.querySelectorAll('[id],[class]');
  for (var i = 0; i < els.length; i++) note(els[i]);
}
function flushGeneric() {
  flushTimer = 0;
  if (pendClass.length === 0 && pendId.length === 0) return;
  var res;
  try { res = N.JSONparse(B.hidden(href, pendClass.join(' '), pendId.join(' '))); } catch (e) { res = []; }
  pendClass = [];
  pendId = [];
  if (res && res.length) {
    var out = [];
    for (var i = 0; i < res.length; i++) out.push(res[i] + HIDE);
    addRules(out);
  }
}

// 4. Procedural filters (:has-text, :upward, :xpath, :matches-css, :remove, ...).
var PROC_OPS = ['has-text', 'upward', 'nth-ancestor', 'xpath', 'matches-css', 'matches-css-before', 'matches-css-after',
  'min-text-length', 'remove', 'remove-attr', 'remove-class', 'matches-attr', 'matches-prop', 'matches-media',
  'others', 'watch-attr', 'shadow', 'if', 'if-not', 'has', 'not', 'style', 'matches-path'];
function balanced(s, i) {
  var depth = 0, quote = '';
  for (; i < s.length; i++) {
    var c = s.charAt(i);
    if (quote) { if (c === '\\') { i++; continue; } if (c === quote) quote = ''; continue; }
    if (c === '"' || c === "'") { quote = c; continue; }
    if (c === '\\') { i++; continue; }
    if (c === '(') depth++;
    else if (c === ')') { depth--; if (depth === 0) return i; }
  }
  return -1;
}
function containsProcedural(s) {
  for (var i = 0; i < PROC_OPS.length; i++) {
    var op = PROC_OPS[i];
    if (op === 'has' || op === 'not') continue;
    if (s.indexOf(':' + op + '(') !== -1) return true;
  }
  return false;
}
function compileProc(s) {
  s = s.trim();
  var steps = [], css = '', i = 0;
  while (i < s.length) {
    var c = s.charAt(i);
    if (c === ':') {
      var m = /^:([a-z-]+)\(/.exec(s.slice(i));
      if (m && PROC_OPS.indexOf(m[1]) !== -1) {
        var open = i + m[0].length - 1, close = balanced(s, open);
        if (close === -1) return null;
        var arg = s.slice(open + 1, close);
        var name = m[1];
        // Native :has()/:not() stay CSS unless they contain procedural parts.
        if ((name === 'has' || name === 'not') && !containsProcedural(arg)) { css += s.slice(i, close + 1); i = close + 1; continue; }
        if (css.trim() !== '' || steps.length === 0) steps.push({ css: css.trim() });
        css = '';
        steps.push({ op: name, arg: arg });
        i = close + 1;
        continue;
      }
    }
    if (c === '[' ) { var e = s.indexOf(']', i); if (e !== -1) { css += s.slice(i, e + 1); i = e + 1; continue; } }
    css += c;
    i++;
  }
  if (css.trim() !== '') steps.push({ css: css.trim() });
  var action = null;
  var last = steps[steps.length - 1];
  if (last && (last.op === 'remove' || last.op === 'remove-attr' || last.op === 'remove-class' || last.op === 'style')) {
    action = steps.pop();
  }
  for (var k = 0; k < steps.length; k++) {
    var t = steps[k];
    if (t.op === 'has' || t.op === 'if' || t.op === 'not' || t.op === 'if-not') {
      var sub = /^\s*[>+~]/.test(t.arg) ? t.arg : t.arg;
      t.sub = compileProc(sub);
      if (t.sub === null) return null;
    } else if (t.op === 'others' || t.op === 'shadow') {
      return null;
    }
  }
  return { steps: steps, action: action };
}
function textMatcher(arg) {
  var m = /^\/(.+)\/([imsu]*)$/.exec(arg);
  if (m) { try { var re = new N.RegExp(m[1], m[2]); return function (s) { return re.test(s); }; } catch (e) { return function () { return false; }; } }
  if (arg.length > 1 && (arg.charAt(0) === '"' || arg.charAt(0) === "'") && arg.charAt(arg.length - 1) === arg.charAt(0)) arg = arg.slice(1, -1);
  return function (s) { return s.indexOf(arg) !== -1; };
}
function querySteps(steps, roots) {
  var nodes = roots;
  for (var i = 0; i < steps.length; i++) {
    var t = steps[i], out = [], j, n;
    if (t.css !== undefined) {
      var sel = t.css;
      if (sel === '') continue;
      for (j = 0; j < nodes.length; j++) {
        n = nodes[j];
        var q;
        try {
          if (n === document) q = document.querySelectorAll(sel);
          else if (/^[>+~]/.test(sel)) {
            if (sel.charAt(0) === '>') q = n.querySelectorAll(':scope ' + sel);
            else {
              var p = n.parentElement;
              q = [];
              if (p) {
                var tok = 'data-arv' + MAGIC;
                n.setAttribute(tok, '');
                try { q = p.querySelectorAll(':scope > [' + tok + '] ' + sel); } finally { n.removeAttribute(tok); }
              }
            }
          } else if (i === 0 && n === document.documentElement) q = document.querySelectorAll(sel);
          else q = n.querySelectorAll(':scope ' + sel);
        } catch (e) { return []; }
        for (var k = 0; k < q.length; k++) out.push(q[k]);
      }
      nodes = out;
      continue;
    }
    switch (t.op) {
      case 'has-text': {
        var tm = t.tm || (t.tm = textMatcher(t.arg));
        for (j = 0; j < nodes.length; j++) if (tm(nodes[j].textContent || '')) out.push(nodes[j]);
        break;
      }
      case 'min-text-length': {
        var len = parseInt(t.arg, 10) || 0;
        for (j = 0; j < nodes.length; j++) if ((nodes[j].textContent || '').length >= len) out.push(nodes[j]);
        break;
      }
      case 'upward': case 'nth-ancestor': {
        var num = parseInt(t.arg, 10);
        for (j = 0; j < nodes.length; j++) {
          n = nodes[j];
          if (!isNaN(num) && String(num) === t.arg.trim()) { for (var u = 0; u < num && n; u++) n = n.parentElement; }
          else { n = n.parentElement ? n.parentElement.closest(t.arg) : null; }
          if (n && out.indexOf(n) === -1) out.push(n);
        }
        break;
      }
      case 'xpath': {
        for (j = 0; j < nodes.length; j++) {
          try {
            var ctx = nodes[j] === document ? document : nodes[j];
            var xr = document.evaluate(t.arg, ctx, null, XPathResult.ORDERED_NODE_SNAPSHOT_TYPE, null);
            for (var x = 0; x < xr.snapshotLength; x++) { var it = xr.snapshotItem(x); if (it.nodeType === 1) out.push(it); }
          } catch (e) {}
        }
        break;
      }
      case 'matches-css': case 'matches-css-before': case 'matches-css-after': {
        var pseudo = t.op === 'matches-css-before' ? '::before' : (t.op === 'matches-css-after' ? '::after' : null);
        var mc = /^\s*([\w-]+)\s*:\s*(.+)$/.exec(t.arg);
        if (!mc) break;
        var vm = textMatcher(mc[2].trim());
        var exact = !/^\//.test(mc[2].trim());
        for (j = 0; j < nodes.length; j++) {
          var cs = W.getComputedStyle(nodes[j], pseudo);
          var v = cs ? cs.getPropertyValue(mc[1]) : '';
          if (exact ? v === mc[2].trim() : vm(v)) out.push(nodes[j]);
        }
        break;
      }
      case 'matches-attr': {
        var ma = /^\s*("?)([^"=]+)\1\s*(?:=\s*("?)(.*)\3)?\s*$/.exec(t.arg);
        if (!ma) break;
        var nameM = textMatcher(ma[2]), valM = ma[4] !== undefined ? textMatcher(ma[4]) : null;
        for (j = 0; j < nodes.length; j++) {
          var attrs = nodes[j].attributes;
          for (var a = 0; a < attrs.length; a++) {
            if (nameM(attrs[a].name) && (valM === null || valM(attrs[a].value))) { out.push(nodes[j]); break; }
          }
        }
        break;
      }
      case 'matches-prop': {
        var mp = /^\s*([^=]+?)\s*(?:=\s*(.*))?$/.exec(t.arg);
        if (!mp) break;
        var chain = mp[1].split('.'), want = mp[2] !== undefined ? textMatcher(mp[2]) : null;
        for (j = 0; j < nodes.length; j++) {
          var o = nodes[j];
          for (var ci = 0; ci < chain.length && o != null; ci++) o = o[chain[ci]];
          if (o !== undefined && (want === null || want(String(o)))) out.push(nodes[j]);
        }
        break;
      }
      case 'matches-media': {
        var ok = false;
        try { ok = W.matchMedia(t.arg).matches; } catch (e) {}
        if (ok) out = nodes.slice();
        break;
      }
      case 'has': case 'if': case 'not': case 'if-not': {
        var want2 = t.op === 'has' || t.op === 'if';
        for (j = 0; j < nodes.length; j++) {
          var r = querySteps(t.sub.steps, [nodes[j]]);
          if ((r.length > 0) === want2) out.push(nodes[j]);
        }
        break;
      }
      case 'watch-attr': case 'matches-path':
        out = nodes;
        break;
      default:
        return [];
    }
    nodes = out;
  }
  return nodes;
}
var procedural = [];
if (cfg.proc) for (var pi = 0; pi < cfg.proc.length; pi++) { var c0 = compileProc(cfg.proc[pi]); if (c0) procedural.push(c0); }
var handled = new WeakSet();
function applyProcedural() {
  for (var i = 0; i < procedural.length; i++) {
    var p = procedural[i];
    var start = p.steps.length && p.steps[0].css !== undefined && p.steps[0].css !== '' ? [document] : [document.documentElement];
    if (p.steps.length && p.steps[0].css !== undefined && p.steps[0].css === '') start = [document.documentElement];
    var nodes;
    try { nodes = querySteps(p.steps, start); } catch (e) { continue; }
    for (var j = 0; j < nodes.length; j++) {
      var n = nodes[j];
      if (!n || n.nodeType !== 1) continue;
      var a = p.action;
      if (a === null) {
        if (handled.has(n)) continue;
        handled.add(n);
        n.style.setProperty('display', 'none', 'important');
      } else if (a.op === 'remove') {
        n.remove();
      } else if (a.op === 'remove-attr') {
        var names = a.arg.replace(/^["']|["']$/g, '');
        if (/^\/.+\/$/.test(names)) { var ra = textMatcher(names); Array.from(n.attributes).forEach(function (at) { if (ra(at.name)) n.removeAttribute(at.name); }); }
        else n.removeAttribute(names);
      } else if (a.op === 'remove-class') {
        n.classList.remove(a.arg.replace(/^["']|["']$/g, ''));
      } else if (a.op === 'style') {
        if (handled.has(n)) continue;
        handled.add(n);
        var decls = a.arg.split(';');
        for (var d = 0; d < decls.length; d++) {
          var kv = decls[d].split(':');
          if (kv.length < 2) continue;
          var prop = kv[0].trim(), val = kv.slice(1).join(':').replace(/!important/i, '').trim();
          if (prop) n.style.setProperty(prop, val, 'important');
        }
      }
    }
  }
}

// 5. Wire up observers.
var procTimer = 0;
function scheduleProcedural() { if (procedural.length && procTimer === 0) procTimer = N.setTimeout(function () { procTimer = 0; applyProcedural(); }, 60); }
var generic = !cfg.ghide;
var mo = new N.MutationObserver(function (records) {
  if (generic) {
    for (var i = 0; i < records.length; i++) {
      var r = records[i];
      if (r.type === 'attributes') { note(r.target); continue; }
      var added = r.addedNodes;
      for (var j = 0; j < added.length; j++) if (added[j].nodeType === 1) scanTree(added[j]);
    }
    if ((pendClass.length || pendId.length) && flushTimer === 0) {
      // Flush synchronously for the first paint, then batch.
      if (document.readyState === 'loading') flushGeneric(); else flushTimer = N.setTimeout(flushGeneric, 30);
    }
  }
  scheduleProcedural();
  keepSheet();
});
function startObserving() {
  if (!document.documentElement) return;
  mo.observe(document.documentElement, generic ? { childList: true, subtree: true, attributes: true, attributeFilter: ['class', 'id'] } : { childList: true, subtree: true });
  if (generic) { scanTree(document.documentElement); flushGeneric(); }
  scheduleProcedural();
}
if (document.documentElement) startObserving();
else N.addEventListener.call(document, 'readystatechange', startObserving, { once: true });
onReady(function () { if (generic) { scanTree(document.documentElement); flushGeneric(); } applyProcedural(); keepSheet(); });
onReady(function () { applyProcedural(); }, 'complete');
