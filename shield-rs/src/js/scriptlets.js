// Arivna Shield scriptlets — clean-room implementations of the uBlock Origin
// scriptlet API used by filter lists (`example.com##+js(name, arg1, ...)`).
// Runs in the page's main world at document start, before page scripts.

var W = window;
var N = {
  defineProperty: Object.defineProperty,
  getOwnPropertyDescriptor: Object.getOwnPropertyDescriptor,
  JSONparse: JSON.parse,
  JSONstringify: JSON.stringify,
  setTimeout: W.setTimeout.bind(W),
  setInterval: W.setInterval.bind(W),
  clearTimeout: W.clearTimeout.bind(W),
  Proxy: W.Proxy,
  Reflect: W.Reflect,
  RegExp: W.RegExp,
  Error: W.Error,
  fetch: W.fetch,
  XHR: W.XMLHttpRequest,
  MutationObserver: W.MutationObserver,
  Response: W.Response,
  Promise: W.Promise,
  ObjectCreate: Object.create,
  ArrayIsArray: Array.isArray,
  addEventListener: EventTarget.prototype.addEventListener,
  documentQSA: Document.prototype.querySelectorAll
};
var MAGIC = String.fromCharCode(Date.now() % 26 + 97) + Math.floor(Math.random() * 982451653 + 982451653).toString(36);

var noopFunc = function () {};
function escapeRe(s) { return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'); }

/** uBO pattern semantics: '' matches all, '/re/flags' is a regex, otherwise a literal. */
function patternToRegex(p, flags) {
  if (p === undefined || p === '') return /^/;
  var m = /^\/(.+)\/([gimsu]*)$/.exec(p);
  if (m !== null) { try { return new N.RegExp(m[1], m[2] || flags || ''); } catch (e) { return /\b\B/; } }
  return new N.RegExp(escapeRe(p), flags || '');
}
function matcher(p) {
  var neg = false;
  if (typeof p === 'string' && p.charAt(0) === '!') { neg = true; p = p.slice(1); }
  var re = patternToRegex(p);
  return { test: function (s) { return re.test(s) !== neg; }, all: p === '' && !neg };
}
function onReady(fn, when) {
  var states = ['loading', 'interactive', 'complete'];
  var target = when === 'complete' ? 2 : when === 'loading' ? 0 : 1;
  if (states.indexOf(document.readyState) >= target) { fn(); return; }
  var done = false;
  var cb = function () { if (done || states.indexOf(document.readyState) < target) return; done = true; fn(); };
  N.addEventListener.call(document, 'readystatechange', cb, { capture: true });
  N.addEventListener.call(W, 'load', cb, { capture: true });
}
function observe(fn, ms) {
  var t = 0;
  var run = function () { t = 0; try { fn(); } catch (e) {} };
  run();
  var mo = new N.MutationObserver(function () { if (t === 0) t = N.setTimeout(run, 16); });
  var start = function () { mo.observe(document, { childList: true, subtree: true }); };
  if (document.documentElement) start(); else onReady(start, 'loading');
  if (ms) N.setTimeout(function () { mo.disconnect(); }, ms);
  return mo;
}
function swallowMagicErrors() {
  if (W.__arivnaErr) return;
  try { N.defineProperty(W, '__arivnaErr', { value: true }); } catch (e) {}
  var prev = W.onerror;
  W.onerror = function (msg) {
    if (typeof msg === 'string' && msg.indexOf(MAGIC) !== -1) return true;
    if (typeof prev === 'function') return prev.apply(this, arguments);
  };
  N.addEventListener.call(W, 'error', function (ev) {
    if (ev && ev.message && String(ev.message).indexOf(MAGIC) !== -1) { ev.preventDefault(); ev.stopImmediatePropagation(); }
  }, true);
}
function throwMagic() { swallowMagicErrors(); throw new ReferenceError(MAGIC); }

var ABORT = {};
function constantValue(raw, trusted) {
  if (trusted) {
    try { return N.JSONparse(raw); } catch (e) {}
  }
  switch (raw) {
    case 'undefined': return undefined;
    case 'false': return false;
    case 'true': return true;
    case 'null': return null;
    case "''": case '""': case 'emptyStr': return '';
    case 'noopFunc': return function () {};
    case 'trueFunc': return function () { return true; };
    case 'falseFunc': return function () { return false; };
    case 'throwFunc': return function () { throw ''; };
    case 'emptyObj': case '{}': return {};
    case 'emptyArr': case '[]': return [];
    case 'noopCallbackFunc': return function () { return function () {}; };
    case 'yes': case 'no': case 'on': case 'off': case 'accept': case 'reject': case 'ok': return raw;
    case 'NaN': return NaN;
    case 'Infinity': return Infinity;
    case '-Infinity': return -Infinity;
  }
  if (/^-?\d+(\.\d+)?$/.test(raw)) {
    var n = parseFloat(raw);
    if (Math.abs(n) <= 0x7FFF || trusted) return n;
  }
  if (trusted) return raw;
  return ABORT;
}

function setConstant(chain, rawValue, trusted, extra) {
  if (!chain) return;
  var cValue = constantValue(rawValue === undefined ? 'undefined' : rawValue, trusted);
  if (cValue === ABORT) return;
  if (extra === 'function') { var fv = cValue; cValue = function () { return fv; }; }
  else if (extra === 'callback') { var cv = cValue; cValue = function () { return function () { return cv; }; }; }
  else if (extra === 'resolved') { cValue = N.Promise.resolve(cValue); }
  else if (extra === 'rejected') { cValue = N.Promise.reject(cValue); }
  var thisScript = document.currentScript;
  function mustAbort(v) {
    if (v === undefined || v === null || cValue === undefined || cValue === null) return false;
    return typeof v !== typeof cValue;
  }
  function trapProp(owner, prop, configurable, handler) {
    if (handler.init(configurable ? owner[prop] : cValue) === false) return;
    var odesc = N.getOwnPropertyDescriptor(owner, prop);
    var prevGetter, prevSetter;
    if (odesc instanceof Object) {
      if (odesc.configurable === false) { try { owner[prop] = cValue; } catch (e) {} return; }
      if (odesc.get instanceof Function) prevGetter = odesc.get;
      if (odesc.set instanceof Function) prevSetter = odesc.set;
    }
    try {
      N.defineProperty(owner, prop, {
        configurable: configurable,
        get: function () { if (prevGetter !== undefined) prevGetter.call(owner); return handler.getter(); },
        set: function (a) { if (prevSetter !== undefined) prevSetter.call(owner, a); handler.setter(a); }
      });
    } catch (e) {}
  }
  function trapChain(owner, chain) {
    var pos = chain.indexOf('.');
    if (pos === -1) {
      trapProp(owner, chain, false, {
        v: undefined,
        init: function (v) { if (mustAbort(v)) return false; this.v = v; return true; },
        getter: function () { return document.currentScript === thisScript ? this.v : cValue; },
        setter: function (a) { if (mustAbort(a) === false) return; cValue = a; }
      });
      return;
    }
    var prop = chain.slice(0, pos);
    var v = owner[prop];
    var rest = chain.slice(pos + 1);
    if (v instanceof Object || (typeof v === 'object' && v !== null)) { trapChain(v, rest); return; }
    trapProp(owner, prop, true, {
      v: undefined,
      init: function (val) { this.v = val; return true; },
      getter: function () { return this.v; },
      setter: function (a) { this.v = a; if (a instanceof Object) trapChain(a, rest); }
    });
  }
  trapChain(W, chain);
}

function resolveChain(chain) {
  var owner = W, parts = chain.split('.');
  for (var i = 0; i < parts.length - 1; i++) {
    var next = owner[parts[i]];
    if (!(next instanceof Object)) return null;
    owner = next;
  }
  return { owner: owner, prop: parts[parts.length - 1] };
}

function abortOnRead(chain) {
  if (!chain) return;
  var rval;
  var makeProxy = function (owner, chain) {
    var pos = chain.indexOf('.');
    if (pos === -1) {
      var desc = N.getOwnPropertyDescriptor(owner, chain);
      if (!desc || desc.get !== undefined) { /* fine */ }
      if (desc && desc.configurable === false) return;
      try { N.defineProperty(owner, chain, { get: function () { throwMagic(); }, set: function () {} }); } catch (e) {}
      return;
    }
    var prop = chain.slice(0, pos), v = owner[prop];
    chain = chain.slice(pos + 1);
    if (v) { makeProxy(v, chain); return; }
    var desc2 = N.getOwnPropertyDescriptor(owner, prop);
    if (desc2 && desc2.set !== undefined) return;
    try {
      N.defineProperty(owner, prop, {
        get: function () { return rval; },
        set: function (a) { rval = a; if (a instanceof Object) makeProxy(a, chain); }
      });
    } catch (e) {}
  };
  makeProxy(W, chain);
}

function abortOnWrite(prop) {
  if (!prop) return;
  var r = resolveChain(prop);
  if (!r) return;
  var desc = N.getOwnPropertyDescriptor(r.owner, r.prop);
  if (desc && desc.configurable === false) return;
  var magic = r.owner[r.prop];
  try {
    N.defineProperty(r.owner, r.prop, {
      get: function () { return magic; },
      set: function () { throwMagic(); }
    });
  } catch (e) {}
}

var scriptTexts = new WeakMap();
function scriptText(elem) {
  var text = elem.textContent;
  if (text.trim() !== '') return text;
  if (scriptTexts.has(elem)) return scriptTexts.get(elem);
  var m = /^data:([^,]*),(.+)$/.exec((elem.src || '').trim());
  var out = '';
  if (m) {
    try { out = m[1].slice(-7) === ';base64' ? atob(m[2]) : decodeURIComponent(m[2]); } catch (e) {}
  }
  scriptTexts.set(elem, out);
  return out;
}

function abortCurrentScript(target, needle, context) {
  if (!target) return;
  var reNeedle = patternToRegex(needle || '');
  var reContext = patternToRegex(context || '');
  var thisScript = document.currentScript;
  var chain = target.split('.');
  var owner = W, prop;
  for (;;) {
    prop = chain.shift();
    if (chain.length === 0) break;
    if (prop in owner === false) break;
    owner = owner[prop];
    if (owner instanceof Object === false) return;
  }
  var value, desc = N.getOwnPropertyDescriptor(owner, prop);
  if (desc instanceof Object === false || desc.get === undefined) { value = owner[prop]; desc = undefined; }
  var validate = function () {
    var e = document.currentScript;
    if (e instanceof HTMLScriptElement === false) return;
    if (e === thisScript) return;
    if (context !== undefined && context !== '' && !reContext.test(e.src)) return;
    if (needle !== undefined && needle !== '' && !reNeedle.test(scriptText(e))) return;
    throwMagic();
  };
  try {
    N.defineProperty(owner, prop, {
      get: function () { validate(); return desc instanceof Object ? desc.get.call(owner) : value; },
      set: function (a) { validate(); if (desc instanceof Object && desc.set) desc.set.call(owner, a); else value = a; }
    });
  } catch (e) {}
}

function abortOnStackTrace(chain, needle) {
  if (!chain) return;
  var re = patternToRegex(needle || '');
  var r = resolveChain(chain);
  if (!r) return;
  var value = r.owner[r.prop];
  var check = function () {
    var stack = '';
    try { stack = new N.Error().stack || ''; } catch (e) {}
    if (re.test(stack)) throwMagic();
  };
  try {
    N.defineProperty(r.owner, r.prop, {
      get: function () { check(); return value; },
      set: function (a) { check(); value = a; }
    });
  } catch (e) {}
}

function fnToString(fn) {
  try { return typeof fn === 'function' ? Function.prototype.toString.call(fn) : String(fn); } catch (e) { return ''; }
}

function timerDefuser(name, needle, delay) {
  var mNeedle = matcher(needle === undefined ? '' : needle);
  var delayNeg = false, dly = -1;
  if (delay !== undefined && delay !== '') {
    if (delay.charAt(0) === '!') { delayNeg = true; delay = delay.slice(1); }
    dly = parseInt(delay, 10);
  }
  var orig = W[name];
  W[name] = new N.Proxy(orig, {
    apply: function (target, thisArg, args) {
      var a = fnToString(args[0]);
      var b = args[1];
      var hit = mNeedle.test(a);
      if (dly !== -1) { var same = b === dly || (b === undefined && dly === 0); hit = hit && (same !== delayNeg); }
      if (hit) args[0] = noopFunc;
      return N.Reflect.apply(target, thisArg, args);
    }
  });
}

function timerBooster(name, needle, delay, boost) {
  var mNeedle = matcher(needle === undefined ? '' : needle);
  var dly = delay === undefined || delay === '' ? 1000 : (delay === '*' ? -1 : parseInt(delay, 10));
  var bst = parseFloat(boost);
  if (isNaN(bst) || !isFinite(bst)) bst = 0.05;
  bst = Math.min(Math.max(bst, 0.001), 50);
  W[name] = new N.Proxy(W[name], {
    apply: function (target, thisArg, args) {
      var a = fnToString(args[0]), b = args[1];
      if ((dly === -1 || b === dly) && mNeedle.test(a)) args[1] = b * bst;
      return N.Reflect.apply(target, thisArg, args);
    }
  });
}

function addEventListenerDefuser(type, pattern) {
  var mType = matcher(type || ''), mPattern = matcher(pattern || '');
  EventTarget.prototype.addEventListener = new N.Proxy(EventTarget.prototype.addEventListener, {
    apply: function (target, thisArg, args) {
      var t = String(args[0]);
      var h = args[1] instanceof Function ? fnToString(args[1]) : (args[1] && args[1].handleEvent ? fnToString(args[1].handleEvent) : String(args[1]));
      if (mType.test(t) && mPattern.test(h)) return;
      return N.Reflect.apply(target, thisArg, args);
    }
  });
}

function noWindowOpenIf(pattern, delay, decoy) {
  var m = matcher(pattern || '');
  var fakeWindow = function () {
    var w = { closed: false, opener: W, location: { href: '' }, document: { write: noopFunc, writeln: noopFunc, close: noopFunc, open: noopFunc, body: {}, head: {} } };
    w.close = function () { w.closed = true; };
    w.focus = w.blur = w.postMessage = w.resizeTo = w.moveTo = noopFunc;
    w.window = w; w.self = w;
    return w;
  };
  W.open = new N.Proxy(W.open, {
    apply: function (target, thisArg, args) {
      var url = String(args[0] === undefined ? '' : args[0]);
      if (!m.test(url)) return N.Reflect.apply(target, thisArg, args);
      if (decoy === 'blank' || decoy === 'obj' || decoy === 'frame') {
        var el = document.createElement(decoy === 'obj' ? 'object' : 'iframe');
        el.setAttribute(decoy === 'obj' ? 'data' : 'src', url);
        el.style.setProperty('height', '1px', 'important');
        el.style.setProperty('width', '1px', 'important');
        el.style.setProperty('position', 'absolute', 'important');
        el.style.setProperty('top', '-10px', 'important');
        (document.body || document.documentElement).appendChild(el);
        N.setTimeout(function () { el.remove(); }, (parseInt(delay, 10) || 1) * 1000);
      }
      return fakeWindow();
    }
  });
}

function removeNodeText(nodeName, pattern, replacement, trusted) {
  if (!nodeName || !pattern) return;
  var reNode = patternToRegex(nodeName.charAt(0) === '/' ? nodeName : '/^' + escapeRe(nodeName) + '$/', 'i');
  if (nodeName.charAt(0) !== '/') reNode = new N.RegExp('^' + escapeRe(nodeName) + '$', 'i');
  var re = patternToRegex(pattern, 'gms');
  var handle = function (node) {
    var name = node.nodeName;
    if (!reNode.test(name)) return;
    var before = node.textContent;
    re.lastIndex = 0;
    if (!re.test(before)) return;
    re.lastIndex = 0;
    var after = replacement !== undefined ? before.replace(re, replacement) : '';
    if (node.nodeType === 1 && node.nodeName === 'SCRIPT') {
      node.textContent = after;
    } else {
      node.textContent = after;
    }
  };
  var mo = new N.MutationObserver(function (records) {
    for (var i = 0; i < records.length; i++) {
      var added = records[i].addedNodes;
      for (var j = 0; j < added.length; j++) {
        var n = added[j];
        handle(n);
        if (n.nodeType === 1 && n.childNodes.length) {
          var walker = document.createTreeWalker(n, NodeFilter.SHOW_TEXT | NodeFilter.SHOW_ELEMENT);
          var c;
          while ((c = walker.nextNode())) handle(c);
        }
      }
    }
  });
  var start = function () { mo.observe(document, { childList: true, subtree: true }); };
  if (document.documentElement) start(); else onReady(start, 'loading');
}

function parsePropsToMatch(propsToMatch, implicit) {
  var needles = new Map();
  if (propsToMatch === undefined || propsToMatch === '') return needles;
  var options = propsToMatch.split(/\s+/);
  for (var i = 0; i < options.length; i++) {
    var opt = options[i];
    var pos = opt.indexOf(':');
    var key = pos !== -1 ? opt.slice(0, pos) : implicit;
    var val = pos !== -1 ? opt.slice(pos + 1) : opt;
    if (key) needles.set(key, matcher(val));
  }
  return needles;
}
function matchObjectProps(needles, details) {
  var ok = true;
  needles.forEach(function (m, key) {
    if (!ok) return;
    var v = details[key];
    if (v === undefined) { ok = false; return; }
    if (!m.test(String(v))) ok = false;
  });
  return ok;
}
function fetchDetails(args) {
  var d = { url: '', method: 'GET' };
  var a = args[0];
  if (a instanceof Request) { d.url = a.url; d.method = a.method; } else { d.url = String(a); }
  var init = args[1];
  if (init instanceof Object) {
    for (var k in init) { try { d[k] = init[k]; } catch (e) {} }
    if (init.method) d.method = init.method;
  }
  return d;
}

function noFetchIf(propsToMatch, responseBody, responseType) {
  var needles = parsePropsToMatch(propsToMatch, 'url');
  var body = '';
  if (responseBody === 'emptyObj') body = '{}';
  else if (responseBody === 'emptyArr') body = '[]';
  else if (responseBody === 'emptyStr' || responseBody === undefined || responseBody === '') body = '';
  else if (responseBody === 'true') body = 'true';
  else if (responseBody === 'false') body = 'false';
  else body = '';
  W.fetch = new N.Proxy(W.fetch, {
    apply: function (target, thisArg, args) {
      var d = fetchDetails(args);
      if (needles.size === 0 || matchObjectProps(needles, d)) {
        var res = new N.Response(body, { status: 200, statusText: 'OK', headers: { 'Content-Type': 'text/plain' } });
        try {
          N.defineProperty(res, 'url', { value: d.url });
          if (responseType === 'opaque') N.defineProperty(res, 'type', { value: 'opaque' });
          else N.defineProperty(res, 'type', { value: 'basic' });
        } catch (e) {}
        return N.Promise.resolve(res);
      }
      return N.Reflect.apply(target, thisArg, args);
    }
  });
}

function noXhrIf(propsToMatch, directive) {
  var needles = parsePropsToMatch(propsToMatch, 'url');
  var XHR = N.XHR;
  var pending = new WeakMap();
  W.XMLHttpRequest = class extends XHR {
    open(method, url) {
      var d = { method: String(method), url: String(url) };
      if (needles.size === 0 || matchObjectProps(needles, d)) pending.set(this, d);
      else pending.delete(this);
      return super.open.apply(this, arguments);
    }
    send(body) {
      var d = pending.get(this);
      if (d === undefined) return super.send(body);
      var text = directive === 'true' ? 'true' : (directive === 'emptyObj' ? '{}' : (directive === 'emptyArr' ? '[]' : ''));
      var self = this;
      var props = { readyState: 4, response: text, responseText: text, responseURL: d.url, responseXML: null, status: 200, statusText: 'OK' };
      if (this.responseType === 'json') props.response = text ? (function () { try { return N.JSONparse(text); } catch (e) { return null; } })() : null;
      Object.keys(props).forEach(function (k) { try { N.defineProperty(self, k, { value: props[k], configurable: true }); } catch (e) {} });
      N.setTimeout(function () {
        ['readystatechange', 'load', 'loadend'].forEach(function (t) {
          try { self.dispatchEvent(new Event(t)); } catch (e) {}
        });
      }, 1);
    }
  };
}

// ---- JSON pruning -----------------------------------------------------------

function pruneObject(obj, path) {
  var parts = path.split('.');
  var found = false;
  (function walk(o, i) {
    if (o === null || typeof o !== 'object') return;
    var key = parts[i];
    var last = i === parts.length - 1;
    if (key === '[]' || key === '*') {
      var keys = N.ArrayIsArray(o) ? o.map(function (_, k) { return k; }) : Object.keys(o);
      for (var j = 0; j < keys.length; j++) {
        if (last) { delete o[keys[j]]; found = true; } else walk(o[keys[j]], i + 1);
      }
      if (last && N.ArrayIsArray(o)) o.length = 0;
      return;
    }
    if (key.slice(-2) === '[]' && key.length > 2) {
      var arr = o[key.slice(0, -2)];
      if (N.ArrayIsArray(arr)) for (var a = 0; a < arr.length; a++) walk(arr[a], i + 1);
      return;
    }
    if (!(key in o)) return;
    if (last) { delete o[key]; found = true; return; }
    walk(o[key], i + 1);
  })(obj, 0);
  return found;
}
function hasPath(obj, path) {
  var parts = path.split('.');
  var hit = false;
  (function walk(o, i) {
    if (hit || o === null || typeof o !== 'object') return;
    var key = parts[i];
    var last = i === parts.length - 1;
    if (key === '[]' || key === '*') {
      for (var k in o) { if (last) { hit = true; return; } walk(o[k], i + 1); }
      return;
    }
    var eq = key.indexOf('.[=]');
    if (!(key in o)) return;
    if (last) { hit = true; return; }
    walk(o[key], i + 1);
  })(obj, 0);
  return hit;
}
function makePruner(rawPrunePaths, rawNeedlePaths, stackNeedle) {
  var prunePaths = rawPrunePaths ? rawPrunePaths.split(/ +/) : [];
  var needlePaths = rawNeedlePaths ? rawNeedlePaths.split(/ +/) : [];
  var reStack = stackNeedle ? patternToRegex(stackNeedle) : null;
  return function (obj) {
    if (obj === null || typeof obj !== 'object' || prunePaths.length === 0) return obj;
    if (reStack !== null) {
      var st = '';
      try { st = new N.Error().stack || ''; } catch (e) {}
      if (!reStack.test(st)) return obj;
    }
    for (var i = 0; i < needlePaths.length; i++) if (!hasPath(obj, needlePaths[i])) return obj;
    for (var j = 0; j < prunePaths.length; j++) pruneObject(obj, prunePaths[j]);
    return obj;
  };
}
function jsonPrune(rawPrunePaths, rawNeedlePaths, stackNeedle) {
  var prune = makePruner(rawPrunePaths, rawNeedlePaths, stackNeedle);
  JSON.parse = new N.Proxy(JSON.parse, {
    apply: function (target, thisArg, args) { return prune(N.Reflect.apply(target, thisArg, args)); }
  });
  Response.prototype.json = new N.Proxy(Response.prototype.json, {
    apply: function (target, thisArg, args) { return N.Reflect.apply(target, thisArg, args).then(prune); }
  });
}

function rewriteFetch(propsToMatch, transformText) {
  var needles = parsePropsToMatch(propsToMatch, 'url');
  W.fetch = new N.Proxy(W.fetch, {
    apply: function (target, thisArg, args) {
      var p = N.Reflect.apply(target, thisArg, args);
      var d = fetchDetails(args);
      if (needles.size !== 0 && !matchObjectProps(needles, d)) return p;
      return p.then(function (resp) {
        return resp.clone().text().then(function (text) {
          var out = transformText(text);
          if (out === text) return resp;
          var r = new N.Response(out, { status: resp.status, statusText: resp.statusText, headers: resp.headers });
          try { N.defineProperty(r, 'url', { value: resp.url }); N.defineProperty(r, 'type', { value: resp.type }); } catch (e) {}
          return r;
        }).catch(function () { return resp; });
      });
    }
  });
}

function rewriteXhr(propsToMatch, transformText) {
  var needles = parsePropsToMatch(propsToMatch, 'url');
  var XHR = N.XHR;
  var info = new WeakMap();
  W.XMLHttpRequest = class extends XHR {
    open(method, url) {
      var d = { method: String(method), url: String(url) };
      if (needles.size === 0 || matchObjectProps(needles, d)) info.set(this, d);
      return super.open.apply(this, arguments);
    }
    get response() {
      var r = super.response;
      if (!info.has(this) || this.readyState !== 4) return r;
      var t = this.responseType;
      if (t === '' || t === 'text') return transformText(r);
      if (t === 'json' && r !== null) { try { return N.JSONparse(transformText(N.JSONstringify(r))); } catch (e) { return r; } }
      return r;
    }
    get responseText() {
      var r = super.responseText;
      if (!info.has(this) || this.readyState !== 4) return r;
      return transformText(r);
    }
  };
}

function jsonPruneText(rawPrunePaths, rawNeedlePaths) {
  var prune = makePruner(rawPrunePaths, rawNeedlePaths);
  return function (text) {
    var obj;
    try { obj = N.JSONparse(text); } catch (e) { return text; }
    if (obj === null || typeof obj !== 'object') return text;
    var before = N.JSONstringify(obj);
    prune(obj);
    var after = N.JSONstringify(obj);
    return after === before ? text : after;
  };
}

function replaceText(pattern, replacement) {
  if (!pattern) return function (t) { return t; };
  var re = patternToRegex(pattern, 'gms');
  if (replacement === undefined) replacement = '';
  return function (t) { if (typeof t !== 'string') return t; re.lastIndex = 0; return t.replace(re, replacement); };
}

function xmlPrune(selector, selectorCheck, urlPattern) {
  if (!selector) return;
  var reUrl = patternToRegex(urlPattern || '');
  var prune = function (text) {
    if (typeof text !== 'string' || text.indexOf('<') === -1) return text;
    try {
      var doc = new DOMParser().parseFromString(text, 'text/xml');
      if (doc.querySelector('parsererror')) return text;
      if (selectorCheck && !doc.querySelector(selectorCheck)) return text;
      var nodes;
      if (selector.indexOf('xpath(') === 0) {
        var xp = doc.evaluate(selector.slice(6, -1), doc, null, XPathResult.UNORDERED_NODE_SNAPSHOT_TYPE, null);
        nodes = [];
        for (var i = 0; i < xp.snapshotLength; i++) nodes.push(xp.snapshotItem(i));
      } else {
        nodes = Array.from(doc.querySelectorAll(selector));
      }
      if (nodes.length === 0) return text;
      nodes.forEach(function (n) {
        if (n.nodeType === 2) n.ownerElement.removeAttribute(n.nodeName); else if (n.parentNode) n.parentNode.removeChild(n);
      });
      return new XMLSerializer().serializeToString(doc);
    } catch (e) { return text; }
  };
  rewriteFetch('', function (t) { return reUrl.test(location.href) || true ? prune(t) : t; });
  rewriteXhr('', prune);
}

// ---- DOM helpers ------------------------------------------------------------

function removeAttr(attrs, selector, behavior) {
  if (!attrs) return;
  var list = attrs.split(/\s*\|\s*/);
  var sel = selector || list.map(function (a) { return '[' + a + ']'; }).join(',');
  var run = function () {
    var nodes;
    try { nodes = document.querySelectorAll(sel); } catch (e) { return; }
    for (var i = 0; i < nodes.length; i++) for (var j = 0; j < list.length; j++) nodes[i].removeAttribute(list[j]);
  };
  if (behavior === 'complete') onReady(run, 'complete');
  else { run(); onReady(run); }
  if (behavior !== 'asap' || true) observe(run, behavior === 'stay' ? 0 : 20000);
}

function removeClass(classes, selector, behavior) {
  if (!classes) return;
  var list = classes.split(/\s*\|\s*/);
  var sel = selector || list.map(function (c) { return '.' + CSS.escape(c); }).join(',');
  var run = function () {
    var nodes;
    try { nodes = document.querySelectorAll(sel); } catch (e) { return; }
    for (var i = 0; i < nodes.length; i++) nodes[i].classList.remove.apply(nodes[i].classList, list);
  };
  run();
  onReady(run);
  observe(run, behavior === 'stay' ? 0 : 20000);
}

function setAttr(selector, attr, value, trusted) {
  if (!selector || !attr) return;
  value = value === undefined ? '' : value;
  if (!trusted && !/^(?:''|""|true|false|\d+|\[[\w-]+\])?$/.test(value)) return;
  var copyFrom = /^\[([\w-]+)\]$/.exec(value);
  var run = function () {
    var nodes;
    try { nodes = document.querySelectorAll(selector); } catch (e) { return; }
    for (var i = 0; i < nodes.length; i++) {
      var v = copyFrom ? nodes[i].getAttribute(copyFrom[1]) : value.replace(/^(['"])\1$/, '');
      if (v !== null && nodes[i].getAttribute(attr) !== v) nodes[i].setAttribute(attr, v);
    }
  };
  run();
  onReady(run);
  observe(run, 20000);
}

function hrefSanitizer(selector, source) {
  if (!selector) return;
  source = source || 'text';
  var sanitize = function () {
    var nodes;
    try { nodes = document.querySelectorAll(selector); } catch (e) { return; }
    for (var i = 0; i < nodes.length; i++) {
      var a = nodes[i];
      if (a.localName !== 'a' || !a.hasAttribute('href')) continue;
      var v = '';
      if (source === 'text') v = a.textContent.trim();
      else if (/^\[.+\]$/.test(source)) v = a.getAttribute(source.slice(1, -1)) || '';
      else if (source.charAt(0) === '?') { try { v = new URL(a.href).searchParams.get(source.slice(1)) || ''; } catch (e) {} }
      if (/^https?:\/\/\S+$/.test(v) && a.href !== v) a.setAttribute('href', v);
    }
  };
  onReady(sanitize);
  observe(sanitize, 30000);
}

function clickElements(selectors, extraMatch, delay) {
  if (!selectors) return;
  if (extraMatch) {
    var conds = extraMatch.split(/\s*,\s*/);
    for (var i = 0; i < conds.length; i++) {
      var m = /^(!)?(cookie|localStorage):(.+)$/.exec(conds[i]);
      if (!m) continue;
      var present;
      if (m[2] === 'cookie') present = document.cookie.split(/\s*;\s*/).some(function (c) { return patternToRegex(m[3]).test(c.split('=')[0]); });
      else { try { present = localStorage.getItem(m[3]) !== null; } catch (e) { present = false; } }
      if (present === !m[1]) return;
    }
  }
  var queue = selectors.split(/\s*,\s+/).filter(function (s) { return s; });
  var wait = Math.max(parseInt(delay, 10) || 0, 1);
  var deadline = Date.now() + 10000;
  var step = function () {
    if (queue.length === 0 || Date.now() > deadline) { mo.disconnect(); return; }
    var sel = queue[0];
    var el = null;
    try { el = document.querySelector(sel); } catch (e) { queue.shift(); return step(); }
    if (el === null) return;
    queue.shift();
    N.setTimeout(function () { try { el.click(); } catch (e) {} step(); }, wait);
  };
  var t = 0;
  var mo = new N.MutationObserver(function () { if (t === 0) t = N.setTimeout(function () { t = 0; step(); }, 30); });
  onReady(function () { mo.observe(document, { childList: true, subtree: true, attributes: true }); step(); });
}

// ---- Cookies & storage ------------------------------------------------------

var SAFE_COOKIE = /^(?:accept|reject|true|false|yes|no|ok|on|off|y|n|1|0|-1|allow|deny|allowed|denied|essential|necessary|required|approved|disapproved|hide|hidden|checked|unchecked|enable|disable|enabled|disabled|granted|dismiss|dismissed|none|done|declined|accepted|rejected|\d{1,15}|)$/i;

function cookieValueTokens(v) {
  var now = Date.now();
  return v.replace('$now$', String(now)).replace('$currentDate$', new Date().toUTCString()).replace('$currentISODate$', new Date().toISOString());
}

function setCookie(name, value, pathArg, trusted, expires, reload) {
  if (!name) return;
  value = value === undefined ? '' : value;
  if (!trusted && !SAFE_COOKIE.test(value)) return;
  if (trusted) value = cookieValueTokens(value);
  var cookieName = trusted ? name : encodeURIComponent(name);
  var current = document.cookie.split(/\s*;\s*/).some(function (c) { return c === cookieName + '=' + value; });
  if (current) return;
  var parts = [cookieName + '=' + (trusted ? value : encodeURIComponent(value))];
  var path = pathArg === 'none' ? '' : (pathArg === '/' || pathArg === undefined || pathArg === '' ? '/' : pathArg);
  if (path) parts.push('path=' + path);
  if (trusted && expires) {
    var d = new Date();
    var secs = /^\d+$/.test(expires) ? parseInt(expires, 10) : expires === '1day' ? 86400 : expires === '1year' ? 31536000 : 0;
    if (secs > 0) { d.setTime(d.getTime() + secs * 1000); parts.push('expires=' + d.toUTCString()); }
  }
  try { document.cookie = parts.join('; '); } catch (e) {}
  if (reload) {
    var ok = document.cookie.split(/\s*;\s*/).some(function (c) { return c.indexOf(cookieName + '=') === 0; });
    if (ok) N.setTimeout(function () { location.reload(); }, 50);
  }
}

function removeCookie(needle) {
  var re = patternToRegex(needle || '');
  var run = function () {
    document.cookie.split(';').forEach(function (c) {
      var name = c.split('=')[0].trim();
      if (!name || !re.test(name)) return;
      var host = location.hostname;
      var domains = ['', host, '.' + host];
      var p = host.split('.');
      if (p.length > 2) domains.push('.' + p.slice(-2).join('.'));
      domains.forEach(function (d) {
        document.cookie = name + '=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/' + (d ? '; domain=' + d : '');
      });
    });
  };
  run();
  N.addEventListener.call(W, 'beforeunload', run);
  N.setTimeout(run, 1000);
}

function setStorageItem(which, key, value, trusted) {
  if (!key) return;
  var storage;
  try { storage = W[which]; } catch (e) { return; }
  if (!storage) return;
  if (value === '$remove$') { try { storage.removeItem(key); } catch (e) {} return; }
  var map = { undefined: 'undefined', false: 'false', true: 'true', null: 'null', '': '', emptyArr: '[]', emptyObj: '{}', yes: 'yes', no: 'no', on: 'on', off: 'off', accept: 'accept', reject: 'reject', accepted: 'accepted', rejected: 'rejected', allowed: 'allowed', denied: 'denied' };
  var v;
  if (Object.prototype.hasOwnProperty.call(map, value)) v = map[value];
  else if (/^\d+$/.test(value) && value.length <= 15) v = value;
  else if (trusted) v = cookieValueTokens(value);
  else return;
  try { if (storage.getItem(key) !== v) storage.setItem(key, v); } catch (e) {}
}

// ---- Misc -------------------------------------------------------------------

function noEvalIf(needle) {
  var m = matcher(needle || '');
  W.eval = new N.Proxy(W.eval, {
    apply: function (target, thisArg, args) {
      if (m.test(String(args[0]))) return;
      return N.Reflect.apply(target, thisArg, args);
    }
  });
}

function noWebrtc() {
  var rtcName = W.RTCPeerConnection ? 'RTCPeerConnection' : (W.webkitRTCPeerConnection ? 'webkitRTCPeerConnection' : '');
  if (!rtcName) return;
  var Fake = function () {};
  Fake.prototype = { close: noopFunc, createDataChannel: noopFunc, createOffer: noopFunc, setRemoteDescription: noopFunc, addEventListener: noopFunc, toString: function () { return '[object RTCPeerConnection]'; } };
  W[rtcName] = Fake;
}

function noRafIf(needle) {
  var m = matcher(needle || '');
  W.requestAnimationFrame = new N.Proxy(W.requestAnimationFrame, {
    apply: function (target, thisArg, args) {
      if (m.test(fnToString(args[0]))) args[0] = noopFunc;
      return N.Reflect.apply(target, thisArg, args);
    }
  });
}

function refreshDefuser() {
  var run = function () {
    var metas = document.querySelectorAll('meta[http-equiv="refresh" i]');
    for (var i = 0; i < metas.length; i++) metas[i].remove();
  };
  run();
  onReady(run, 'loading');
  observe(run, 5000);
}

function callNothrow(chain) {
  var r = chain && resolveChain(chain);
  if (!r || typeof r.owner[r.prop] !== 'function') return;
  r.owner[r.prop] = new N.Proxy(r.owner[r.prop], {
    apply: function (target, thisArg, args) { try { return N.Reflect.apply(target, thisArg, args); } catch (e) {} }
  });
}

function replaceArgument(chain, argpos, argraw, condition) {
  var r = chain && resolveChain(chain);
  if (!r || typeof r.owner[r.prop] !== 'function') return;
  var pos = parseInt(argpos, 10) || 0;
  var value;
  try { value = N.JSONparse(argraw); } catch (e) { value = constantValue(argraw, true); }
  var cond = condition ? patternToRegex(condition) : null;
  r.owner[r.prop] = new N.Proxy(r.owner[r.prop], {
    apply: function (target, thisArg, args) {
      var i = pos >= 0 ? pos : args.length + pos;
      if (i >= 0 && i < args.length && (cond === null || cond.test(String(args[i])))) args[i] = value;
      return N.Reflect.apply(target, thisArg, args);
    }
  });
}

function spoofCss(selector) {
  if (!selector) return;
  var props = {};
  for (var i = 1; i + 1 < arguments.length; i += 2) props[arguments[i]] = arguments[i + 1];
  var camel = function (p) { return p.replace(/-([a-z])/g, function (_, c) { return c.toUpperCase(); }); };
  W.getComputedStyle = new N.Proxy(W.getComputedStyle, {
    apply: function (target, thisArg, args) {
      var style = N.Reflect.apply(target, thisArg, args);
      var el = args[0];
      var hit = false;
      try { hit = el instanceof Element && el.matches(selector); } catch (e) {}
      if (!hit) return style;
      return new N.Proxy(style, {
        get: function (t, prop) {
          if (typeof prop === 'string') {
            for (var k in props) { if (prop === k || prop === camel(k)) return props[k]; }
            if (prop === 'getPropertyValue') return function (p) { return Object.prototype.hasOwnProperty.call(props, p) ? props[p] : t.getPropertyValue(p); };
          }
          var v = N.Reflect.get(t, prop);
          return typeof v === 'function' ? v.bind(t) : v;
        }
      });
    }
  });
}

function windowCloseIf(pattern) {
  var re = patternToRegex(pattern || '');
  if (!re.test(location.href)) return;
  try { W.close(); } catch (e) {}
}

function disableNewtabLinks() {
  N.addEventListener.call(document, 'click', function (ev) {
    var a = ev.target && ev.target.closest ? ev.target.closest('a[target]') : null;
    if (a && /^_blank$/i.test(a.target)) a.removeAttribute('target');
  }, true);
}

function defuseFab() {
  var Fab = function () {};
  var p = Fab.prototype;
  p.check = p.clearEvent = p.emitEvent = p.setOption = noopFunc;
  p.on = function (a, b) { if (!a) b(); return this; };
  p.onDetected = function () { return this; };
  p.onNotDetected = function (a) { a(); return this; };
  var fab = new Fab();
  var d = { get: function () { return Fab; }, set: noopFunc }, d2 = { get: function () { return fab; }, set: noopFunc };
  try { Object.defineProperties(W, { FuckAdBlock: d, BlockAdBlock: d, SniffAdBlock: d, fuckAdBlock: d2, blockAdBlock: d2, sniffAdBlock: d2 }); } catch (e) {}
}

function defuseBab() {
  var sigs = [['blockadblock'], ['babasbm'], [/getItem\('babn'\)/], ['getElementById', 'String.fromCharCode', 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789', 'charAt', 'DOMContentLoaded', 'AdBlock', 'addEventListener', 'doScroll', 'fromCharCode', '<<2|r>>4', 'sessionStorage', 'clientWidth', 'localStorage', 'Math', 'random']];
  var check = function (s) {
    for (var i = 0; i < sigs.length; i++) {
      var tokens = sigs[i], match = 0;
      for (var j = 0; j < tokens.length; j++) {
        var t = tokens[j];
        var pos = t instanceof RegExp ? s.search(t) : s.indexOf(t);
        if (pos !== -1) match += 1;
      }
      if (match / tokens.length >= 0.8) return true;
    }
    return false;
  };
  W.eval = new N.Proxy(W.eval, {
    apply: function (target, thisArg, args) {
      var a = String(args[0]);
      if (a.length !== 0 && check(a)) return;
      return N.Reflect.apply(target, thisArg, args);
    }
  });
}

function popadsDummy() {
  try { delete W.PopAds; delete W.popns; N.defineProperty(W, 'PopAds', { value: {} }); N.defineProperty(W, 'popns', { value: {} }); } catch (e) {}
}

// ---- Dispatcher -------------------------------------------------------------

var SCRIPTLETS = {
  'set-constant': function (a) { setConstant(a[0], a[1], false, a[3]); },
  'trusted-set-constant': function (a) { setConstant(a[0], a[1], true, a[3]); },
  'abort-on-property-read': function (a) { abortOnRead(a[0]); },
  'abort-on-property-write': function (a) { abortOnWrite(a[0]); },
  'abort-current-script': function (a) { abortCurrentScript(a[0], a[1], a[2]); },
  'abort-on-stack-trace': function (a) { abortOnStackTrace(a[0], a[1]); },
  'no-setTimeout-if': function (a) { timerDefuser('setTimeout', a[0], a[1]); },
  'no-setInterval-if': function (a) { timerDefuser('setInterval', a[0], a[1]); },
  'nano-setInterval-booster': function (a) { timerBooster('setInterval', a[0], a[1], a[2]); },
  'nano-setTimeout-booster': function (a) { timerBooster('setTimeout', a[0], a[1], a[2]); },
  'addEventListener-defuser': function (a) { addEventListenerDefuser(a[0], a[1]); },
  'no-window-open-if': function (a) { noWindowOpenIf(a[0], a[1], a[2]); },
  'remove-node-text': function (a) { removeNodeText(a[0], a[1]); },
  'replace-node-text': function (a) { removeNodeText(a[0], a[1], a[2] === undefined ? '' : a[2], true); },
  'no-fetch-if': function (a) { noFetchIf(a[0], a[1], a[2]); },
  'no-xhr-if': function (a) { noXhrIf(a[0], a[1]); },
  'trusted-prevent-xhr': function (a) { noXhrIf(a[0], a[1]); },
  'trusted-prevent-fetch': function (a) { noFetchIf(a[0], a[1], a[2]); },
  'json-prune': function (a) { jsonPrune(a[0], a[1], a[2]); },
  'json-prune-fetch-response': function (a) { rewriteFetch(argAfter(a, 2), jsonPruneText(a[0], a[1])); },
  'json-prune-xhr-response': function (a) { rewriteXhr(argAfter(a, 2), jsonPruneText(a[0], a[1])); },
  'trusted-replace-fetch-response': function (a) { rewriteFetch(a[2], replaceText(a[0], a[1])); },
  'trusted-replace-xhr-response': function (a) { rewriteXhr(a[2], replaceText(a[0], a[1])); },
  'xml-prune': function (a) { xmlPrune(a[0], a[1], a[2]); },
  'noeval-if': function (a) { noEvalIf(a[0]); },
  'noeval': function () { noEvalIf(''); },
  'remove-attr': function (a) { removeAttr(a[0], a[1], a[2]); },
  'remove-class': function (a) { removeClass(a[0], a[1], a[2]); },
  'set-attr': function (a) { setAttr(a[0], a[1], a[2], false); },
  'trusted-set-attr': function (a) { setAttr(a[0], a[1], a[2], true); },
  'href-sanitizer': function (a) { hrefSanitizer(a[0], a[1]); },
  'trusted-click-element': function (a) { clickElements(a[0], a[1], a[2]); },
  'set-cookie': function (a) { setCookie(a[0], a[1], a[2], false); },
  'set-cookie-reload': function (a) { setCookie(a[0], a[1], a[2], false, '', true); },
  'trusted-set-cookie': function (a) { setCookie(a[0], a[1], a[3], true, a[2]); },
  'trusted-set-cookie-reload': function (a) { setCookie(a[0], a[1], a[3], true, a[2], true); },
  'cookie-remover': function (a) { removeCookie(a[0]); },
  'set-local-storage-item': function (a) { setStorageItem('localStorage', a[0], a[1], false); },
  'set-session-storage-item': function (a) { setStorageItem('sessionStorage', a[0], a[1], false); },
  'trusted-set-local-storage-item': function (a) { setStorageItem('localStorage', a[0], a[1], true); },
  'trusted-set-session-storage-item': function (a) { setStorageItem('sessionStorage', a[0], a[1], true); },
  'nowebrtc': function () { noWebrtc(); },
  'no-requestAnimationFrame-if': function (a) { noRafIf(a[0]); },
  'refresh-defuser': function () { refreshDefuser(); },
  'window.name-defuser': function () { if (W === W.top) W.name = ''; },
  'window-close-if': function (a) { windowCloseIf(a[0]); },
  'disable-newtab-links': function () { disableNewtabLinks(); },
  'call-nothrow': function (a) { callNothrow(a[0]); },
  'trusted-replace-argument': function (a) { replaceArgument(a[0], a[1], a[2], a[3]); },
  'spoof-css': function (a) { spoofCss.apply(null, a); },
  'nofab': function () { defuseFab(); },
  'nobab': function () { defuseBab(); },
  'popads-dummy': function () { popadsDummy(); }
};

function argAfter(a, i) {
  // uBO's `propsToMatch` for *-response scriptlets is passed as "propsToMatch, value" pairs.
  var rest = a.slice(i);
  for (var k = 0; k + 1 < rest.length; k += 2) if (rest[k] === 'propsToMatch') return rest[k + 1];
  return rest.length === 1 ? rest[0] : '';
}

function runScriptlet(spec) {
  var fn = SCRIPTLETS[spec[0]];
  if (fn === undefined) return false;
  try { fn(spec.slice(1)); } catch (e) {}
  return true;
}
