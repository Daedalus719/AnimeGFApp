/*
 * AnimeGF "desktop mode" page script. Injected at document start (before any site script).
 * Java prepends:  window.__AGF_DESKTOP_CFG__ = { mode, width, chromeMajor, chromeFull };
 *
 * mode 1 (IDENTITY): keep the mobile layout, but present the device as a desktop browser:
 *   platform / userAgentData, hover+pointer media features, width media queries,
 *   window/screen/documentElement widths.
 * mode 2 (VIEWPORT): force a real wide layout viewport (width=<cfg.width>) with free
 *   pinch-zoom. The meta tag is applied before first layout and re-asserted whenever the
 *   site rewrites it, which is what prevents flashing and "jumping back" while zoomed.
 */
(function () {
  'use strict';

  var cfg = window.__AGF_DESKTOP_CFG__;
  if (!cfg || !cfg.mode) return;
  if (window.__agfDesktopApplied__) return;
  try {
    Object.defineProperty(window, '__agfDesktopApplied__', { value: true });
  } catch (e) {
    window.__agfDesktopApplied__ = true;
  }

  var IDENTITY = 1, VIEWPORT = 2;
  var W = Math.max(600, cfg.width | 0 || 1024);

  function define(obj, prop, getter) {
    try {
      Object.defineProperty(obj, prop, { get: getter, configurable: true, enumerable: true });
    } catch (e) { /* property is locked down; skip */ }
  }

  // ── Shared: look like a desktop Chrome ───────────────────────────────────────
  define(navigator, 'platform', function () { return 'Win32'; });

  try {
    var major = String(cfg.chromeMajor || '124');
    var full = String(cfg.chromeFull || (major + '.0.0.0'));
    var brands = [
      { brand: 'Chromium', version: major },
      { brand: 'Google Chrome', version: major },
      { brand: 'Not.A/Brand', version: '99' }
    ];
    var uaData = {
      brands: brands,
      mobile: false,
      platform: 'Windows',
      getHighEntropyValues: function () {
        return Promise.resolve({
          brands: brands, mobile: false, platform: 'Windows', platformVersion: '10.0.0',
          architecture: 'x86', bitness: '64', model: '', uaFullVersion: full,
          fullVersionList: [
            { brand: 'Chromium', version: full },
            { brand: 'Google Chrome', version: full },
            { brand: 'Not.A/Brand', version: '99.0.0.0' }
          ]
        });
      },
      toJSON: function () { return { brands: brands, mobile: false, platform: 'Windows' }; }
    };
    define(navigator, 'userAgentData', function () { return uaData; });
  } catch (e) { /* ignore */ }

  // ── matchMedia: width / hover / pointer / orientation answered as a desktop ───
  var nativeMM = typeof window.matchMedia === 'function' ? window.matchMedia.bind(window) : null;

  function toPx(num, unit) {
    var n = parseFloat(num);
    return unit === 'px' ? n : n * 16; // em / rem
  }

  function cmp(op, a, b) {
    switch (op) {
      case '<=': return a <= b;
      case '>=': return a >= b;
      case '<':  return a < b;
      case '>':  return a > b;
      default:   return a === b;
    }
  }

  function flip(op) {
    return op === '<' ? '>' : op === '>' ? '<' : op === '<=' ? '>=' : op === '>=' ? '<=' : '=';
  }

  /** true/false when we can answer the single media feature, null to defer to the browser. */
  function evalFeature(f) {
    var m;
    if ((m = /^(any-)?hover\s*:\s*(\w+)$/.exec(f))) return m[2] === 'hover';
    if ((m = /^(any-)?pointer\s*:\s*(\w+)$/.exec(f))) return m[2] === 'fine';
    if (/^(any-)?(hover|pointer)$/.test(f)) return true;
    if ((m = /^orientation\s*:\s*(\w+)$/.exec(f))) return m[1] === 'landscape';

    if ((m = /^(min-|max-)?(device-)?width\s*:\s*([\d.]+)(px|em|rem)$/.exec(f))) {
      var px = toPx(m[3], m[4]);
      return m[1] === 'min-' ? W >= px : m[1] === 'max-' ? W <= px : W === px;
    }
    // range syntax: (width >= 48rem)  (48rem <= width)  (40rem <= width < 64rem)
    if ((m = /^(device-)?width\s*(<=|>=|<|>|=)\s*([\d.]+)(px|em|rem)$/.exec(f))) {
      return cmp(m[2], W, toPx(m[3], m[4]));
    }
    if ((m = /^([\d.]+)(px|em|rem)\s*(<=|>=|<|>|=)\s*(device-)?width$/.exec(f))) {
      return cmp(flip(m[3]), W, toPx(m[1], m[2]));
    }
    if ((m = /^([\d.]+)(px|em|rem)\s*(<=|<)\s*(device-)?width\s*(<=|<)\s*([\d.]+)(px|em|rem)$/.exec(f))) {
      return cmp(flip(m[3]), W, toPx(m[1], m[2])) && cmp(m[5], W, toPx(m[6], m[7]));
    }
    return null;
  }

  /** true/false if the query involved a spoofed feature, null if the browser should answer. */
  function evalQuery(q) {
    var touched = false;
    var anyMatch = false;
    var groups = q.toLowerCase().split(',');
    for (var g = 0; g < groups.length; g++) {
      var part = groups[g].trim();
      var neg = false;
      if (/^not\s+/.test(part)) { neg = true; part = part.replace(/^not\s+/, ''); }
      part = part.replace(/^only\s+/, '');
      var terms = part.split(/\s+and\s+/);
      var ok = true;
      for (var i = 0; i < terms.length; i++) {
        var t = terms[i].trim();
        if (t === 'screen' || t === 'all' || t === '') continue;
        if (t === 'print' || t === 'speech') { ok = false; continue; }
        var inner = /^\((.*)\)$/.exec(t);
        if (!inner) return null;
        var feature = inner[1].trim();
        var r = evalFeature(feature);
        if (r === null) {
          r = nativeMM('(' + feature + ')').matches;
        } else {
          touched = true;
        }
        if (!r) ok = false;
      }
      if (neg) ok = !ok;
      if (ok) anyMatch = true;
    }
    return touched ? anyMatch : null;
  }

  function noop() {}

  function FakeMediaQueryList(media, matches) {
    this.media = media;
    this.matches = matches;
    this.onchange = null;
  }
  FakeMediaQueryList.prototype.addListener = noop;
  FakeMediaQueryList.prototype.removeListener = noop;
  FakeMediaQueryList.prototype.addEventListener = noop;
  FakeMediaQueryList.prototype.removeEventListener = noop;
  FakeMediaQueryList.prototype.dispatchEvent = function () { return false; };

  if (nativeMM) {
    window.matchMedia = function (query) {
      try {
        var q = String(query);
        var r = evalQuery(q);
        if (r !== null) return new FakeMediaQueryList(q, r);
      } catch (e) { /* fall through to the real thing */ }
      return nativeMM(query);
    };
  }

  // ── IDENTITY: keep the mobile layout, report desktop-sized windows ───────────
  if (cfg.mode === IDENTITY) {
    define(window, 'innerWidth', function () { return W; });
    define(window, 'outerWidth', function () { return W; });
    try {
      define(window.screen, 'width', function () { return W; });
      define(window.screen, 'availWidth', function () { return W; });
    } catch (e) { /* ignore */ }
    try {
      // Keep documentElement.clientWidth consistent with innerWidth. Scroll-lock helpers
      // compute (innerWidth - clientWidth) and would otherwise add a huge side margin.
      var desc = Object.getOwnPropertyDescriptor(Element.prototype, 'clientWidth');
      if (desc && desc.get) {
        Object.defineProperty(Element.prototype, 'clientWidth', {
          configurable: true,
          enumerable: true,
          get: function () {
            return this === document.documentElement ? W : desc.get.call(this);
          }
        });
      }
    } catch (e) { /* ignore */ }
    return;
  }

  // ── VIEWPORT: real wide layout viewport + stable pinch-zoom ──────────────────
  if (cfg.mode === VIEWPORT) {
    var CONTENT = 'width=' + W + ', maximum-scale=5, user-scalable=yes';
    var headObserver = null;

    var ensure = function () {
      var root = document.documentElement;
      if (!root) return;
      var metas = document.querySelectorAll('meta[name="viewport" i]');
      if (!metas.length) {
        var meta = document.createElement('meta');
        meta.setAttribute('name', 'viewport');
        meta.setAttribute('content', CONTENT);
        (document.head || root).insertBefore(meta, (document.head || root).firstChild);
        return;
      }
      for (var i = 0; i < metas.length; i++) {
        if (metas[i].getAttribute('content') !== CONTENT) {
          metas[i].setAttribute('content', CONTENT);
        }
      }
    };

    var watchHead = function () {
      if (headObserver || !document.head) return;
      headObserver = new MutationObserver(ensure);
      headObserver.observe(document.head, {
        childList: true, subtree: true, attributes: true, attributeFilter: ['content', 'name']
      });
    };

    ensure();
    watchHead();
    // <head> doesn't exist yet at document start; wait for it, then keep watching it.
    new MutationObserver(function () {
      ensure();
      watchHead();
    }).observe(document, { childList: true, subtree: false });
    new MutationObserver(function () {
      ensure();
      watchHead();
    }).observe(document.documentElement, { childList: true, subtree: false });
  }
})();
