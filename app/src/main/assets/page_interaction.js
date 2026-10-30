/*
 * AnimeGF page-interaction script. Injected at document start (or as early as the
 * WebViewClient allows) and safe to run more than once.
 *
 *  1. Long-press targets. On every touch it reports what is under the finger: a real
 *     image, a real link, or an editable field. The app uses that to decide whether a
 *     long-press opens its image / link menu. Links are judged by their own href and
 *     attributes ("#", javascript: and popup / toggle anchors are never reported), so a link
 *     or large image that sits inside a clickable card is still reported. Anything inside an
 *     element the site uses for its own long-press menu (context-menu-trigger) is left to the site.
 *  2. Text selection. It reports the current selection (state + on-screen rectangle) to
 *     the app's own selection toolbar and exposes the helpers that toolbar's actions use.
 *
 * Java side: the AgfPage JavaScript interface (see PageInteraction.java).
 */
(function () {
  'use strict';

  if (window.top !== window || window.__agfPage) return;

  var MIN_IMAGE_PX = 24;          // smaller images are icons / emoji, not something to save
  var MIN_CONTROL_IMAGE_PX = 64;  // inside a button, smaller images are icons / avatars on a trigger
  var SETTLE_MS = 180;     // quiet time before a selection is considered "final"

  var TEXT_INPUT = /^(text|search|url|tel|email|password|number)$/;

  // The site's own long-press menu (chat rows are links marked like this). It handles long-press there, so
  // the app's menu stays out of the way - but only when the trigger IS the link, or a link wrapping the image.
  // A card that merely sits inside such a wrapper keeps its own link and image menus.
  var SITE_MENU_SELECTOR = '[data-slot="context-menu-trigger"]';

  // Elements whose job is to trigger UI. A small image inside one is an icon, not a picture to save.
  var CONTROL_SELECTOR = [
    'button', 'summary', 'select', 'textarea', 'input',
    '[role="button"]', '[role="menuitem"]', '[role="menuitemcheckbox"]', '[role="menuitemradio"]',
    '[role="tab"]', '[role="switch"]', '[role="checkbox"]', '[role="radio"]', '[role="option"]',
    '[role="combobox"]', '[role="slider"]',
    '[aria-haspopup]:not([aria-haspopup="false"])', '[data-toggle]', '[data-bs-toggle]'
  ].join(',');

  // Attributes that mark an <a> as a toggle / modal trigger rather than a destination.
  var TOGGLE_ATTRS = ['aria-expanded', 'aria-controls', 'data-toggle', 'data-bs-toggle'];

  // CSS properties copied to a hidden mirror element to locate a selection inside <input>/<textarea>.
  var MIRROR_PROPS = [
    'direction', 'boxSizing', 'width', 'height', 'overflowX', 'overflowY',
    'borderTopWidth', 'borderRightWidth', 'borderBottomWidth', 'borderLeftWidth',
    'paddingTop', 'paddingRight', 'paddingBottom', 'paddingLeft',
    'fontStyle', 'fontVariant', 'fontWeight', 'fontStretch', 'fontSize', 'lineHeight',
    'fontFamily', 'textAlign', 'textTransform', 'textIndent', 'letterSpacing', 'wordSpacing', 'tabSize'
  ];

  // ── Bridge ──────────────────────────────────────────────────────────────────

  function send(method, payload) {
    try {
      var bridge = window.AgfPage;
      if (!bridge) return;
      if (payload === undefined) bridge[method](); else bridge[method](payload);
    } catch (e) { /* bridge not available in this context */ }
  }

  // ── Element helpers ─────────────────────────────────────────────────────────

  function isTextControl(el) {
    if (!el || el.nodeType !== 1) return false;
    if (el.tagName === 'TEXTAREA') return true;
    return el.tagName === 'INPUT' && TEXT_INPUT.test(el.type || '');
  }

  function isEditable(el) {
    if (isTextControl(el)) return !el.readOnly && !el.disabled;
    return !!el.isContentEditable;
  }

  // ── 1. Long-press targets ───────────────────────────────────────────────────

  function sameDocument(url) {
    return url.origin + url.pathname + url.search === location.origin + location.pathname + location.search;
  }

  /** Absolute URL of a link that really goes somewhere, otherwise null. */
  function linkTarget(a) {
    if (a.matches(SITE_MENU_SELECTOR)) return null;
    var role = a.getAttribute('role');
    if (role && role !== 'link') return null;

    var popup = a.getAttribute('aria-haspopup');
    if (popup !== null && popup !== 'false') return null;
    for (var i = 0; i < TOGGLE_ATTRS.length; i++) {
      if (a.getAttribute(TOGGLE_ATTRS[i]) !== null) return null;
    }

    var raw = (a.getAttribute('href') || '').trim();
    if (!raw || raw.charAt(0) === '#') return null;

    var url;
    try { url = new URL(raw, document.baseURI); } catch (e) { return null; }

    if (url.protocol === 'mailto:' || url.protocol === 'tel:') return url.href;
    if (url.protocol !== 'http:' && url.protocol !== 'https:') return null;   // javascript:, blob:, data: ...
    if (url.hash && sameDocument(url)) return null;                            // page#section
    return url.href;
  }

  /** Source URL of an image that can be saved, otherwise null. */
  function imageTarget(img) {
    var src = img.currentSrc || img.src || '';
    if (!/^https?:/i.test(src)) return null;
    var site = img.closest(SITE_MENU_SELECTOR);
    if (site && site.tagName === 'A') return null;
    var box = img.getBoundingClientRect();
    var smallest = Math.min(box.width, box.height);
    if (smallest < MIN_IMAGE_PX) return null;
    if (smallest < MIN_CONTROL_IMAGE_PX && img.closest(CONTROL_SELECTOR)) return null;
    return src;
  }

  /** {i: image URL|null, l: link URL|null, e: 1 in an editable field, s: 1 when inside the site's own long-press menu trigger}. */
  function classify(node) {
    var out = { i: null, l: null, e: 0, s: 0 };
    var el = node && node.nodeType === 1 ? node : node && node.parentElement;
    if (!el) return out;

    if (isEditable(el)) { out.e = 1; return out; }

    var img = el.closest('img');
    if (img) out.i = imageTarget(img);

    var anchor = el.closest('a[href]');
    if (anchor) out.l = linkTarget(anchor);
    if ((out.i || out.l) && el.closest(SITE_MENU_SELECTOR)) out.s = 1;
    return out;
  }

  document.addEventListener('touchstart', function (e) {
    var info = { i: null, l: null, e: 0 };
    if (e.touches && e.touches.length === 1) {
      info = classify(e.composedPath ? e.composedPath()[0] : e.target);
    }
    send('onTouchTarget', JSON.stringify(info));
  }, { capture: true, passive: true });

  // ── 2. Text selection ───────────────────────────────────────────────────────

  /** Convert a layout-viewport CSS rect to pixels relative to the WebView's top-left corner. */
  function toViewPx(rect) {
    var vv = window.visualViewport;
    var k = (window.devicePixelRatio || 1) * (vv ? vv.scale : 1);
    var ox = vv ? vv.offsetLeft : 0;
    var oy = vv ? vv.offsetTop : 0;
    return {
      l: (rect.left - ox) * k, t: (rect.top - oy) * k,
      r: (rect.right - ox) * k, b: (rect.bottom - oy) * k
    };
  }

  function rangeRect(range) {
    var box = range.getBoundingClientRect();
    if (box.width || box.height) return box;
    var rects = range.getClientRects();
    return rects.length ? rects[0] : null;
  }

  /**
   * Rectangle of the selected text inside an <input>/<textarea>. The browser does not expose
   * it, so the text is laid out in an invisible copy of the control and measured there.
   * Falls back to the control's own box.
   */
  function controlRect(el, start, end) {
    var box = el.getBoundingClientRect();
    if (start === null || end === null) return box;

    var mirror = document.createElement('div');
    try {
      var computed = window.getComputedStyle(el);
      var style = mirror.style;
      for (var i = 0; i < MIRROR_PROPS.length; i++) style[MIRROR_PROPS[i]] = computed[MIRROR_PROPS[i]];
      style.position = 'fixed';
      style.left = box.left + 'px';
      style.top = box.top + 'px';
      style.visibility = 'hidden';
      style.pointerEvents = 'none';
      style.overflow = 'hidden';
      style.whiteSpace = el.tagName === 'TEXTAREA' ? 'pre-wrap' : 'pre';
      style.wordWrap = 'break-word';

      var value = el.value;
      var mark = document.createElement('span');
      mark.textContent = value.substring(start, end) || '\u200b';
      mirror.appendChild(document.createTextNode(value.substring(0, start)));
      mirror.appendChild(mark);
      mirror.appendChild(document.createTextNode(value.substring(end) || '.'));
      document.documentElement.appendChild(mirror);

      var rects = mark.getClientRects();
      if (!rects.length) return box;
      var first = rects[0];
      var last = rects[rects.length - 1];
      var left = Math.min(first.left, last.left) - el.scrollLeft;
      var right = Math.max(first.right, last.right) - el.scrollLeft;
      return {
        left: Math.max(left, box.left),
        right: Math.min(right, box.right),
        top: Math.max(first.top - el.scrollTop, box.top),
        bottom: Math.min(last.bottom - el.scrollTop, box.bottom)
      };
    } catch (e) {
      return box;
    } finally {
      if (mirror.parentNode) mirror.parentNode.removeChild(mirror);
    }
  }

  /**
   * Current selection as {s: has selected text, e: editable, p: password field, z: empty field,
   * l/t/r/b: rectangle in WebView pixels}, or null when there is nothing the toolbar should act on.
   */
  function snapshot() {
    var active = document.activeElement;
    var sel = window.getSelection ? window.getSelection() : null;
    var hasText = 0, editable = 0, secret = 0, empty = 0, rect = null;

    if (isTextControl(active)) {
      var start = null, end = null;
      try { start = active.selectionStart; end = active.selectionEnd; } catch (e) { /* type without selection API */ }
      editable = !active.readOnly && !active.disabled ? 1 : 0;
      hasText = start !== null ? (end > start ? 1 : 0) : (sel && sel.toString().length ? 1 : 0);
      if (!hasText && !editable) return null;
      secret = active.type === 'password' ? 1 : 0;
      empty = active.value.length === 0 ? 1 : 0;
      rect = controlRect(active, start, end);
    } else if (sel && sel.rangeCount) {
      var inEditable = !!(active && active.isContentEditable && active.contains(sel.anchorNode));
      hasText = sel.isCollapsed ? 0 : 1;
      if (!hasText && !inEditable) return null;
      editable = inEditable ? 1 : 0;
      empty = inEditable && !(active.textContent || '').trim() ? 1 : 0;
      rect = rangeRect(sel.getRangeAt(0)) || (inEditable ? active.getBoundingClientRect() : null);
    }

    if (!rect) return null;
    var px = toViewPx(rect);
    return { s: hasText, e: editable, p: secret, z: empty, l: px.l, t: px.t, r: px.r, b: px.b };
  }

  var lastJson = '';
  var busy = false;
  var timer = 0;

  function push(force) {
    timer = 0;
    var snap = null;
    try { snap = snapshot(); } catch (e) { snap = null; }
    var json = snap ? JSON.stringify(snap) : '';
    var wasBusy = busy;
    busy = false;
    if (!force && !wasBusy && json === lastJson) return;
    lastJson = json;
    send('onSelection', json);
  }

  function schedule() {
    if (timer) clearTimeout(timer);
    timer = setTimeout(function () { push(false); }, SETTLE_MS);
  }

  /** Tell the app the selection is moving, so it can hide the toolbar right away. */
  function signalBusy() {
    if (busy) return;
    busy = true;
    send('onSelectionBusy');
  }

  function onSelectionActivity() {
    signalBusy();
    schedule();
  }

  /** Scrolling / zooming / resizing only matters while a selection (or caret toolbar) is on screen. */
  function onViewportMoved() {
    if (!lastJson) return;
    onSelectionActivity();
  }

  document.addEventListener('selectionchange', onSelectionActivity, true);
  document.addEventListener('focusout', schedule, true);
  window.addEventListener('scroll', onViewportMoved, { capture: true, passive: true });
  window.addEventListener('resize', onViewportMoved, { passive: true });
  if (window.visualViewport) {
    window.visualViewport.addEventListener('scroll', onViewportMoved, { passive: true });
    window.visualViewport.addEventListener('resize', onViewportMoved, { passive: true });
  }

  // ── Actions used by the toolbar ─────────────────────────────────────────────

  function selectedText() {
    var active = document.activeElement;
    if (isTextControl(active)) {
      try {
        if (active.selectionStart !== null) return active.value.substring(active.selectionStart, active.selectionEnd);
      } catch (e) { /* fall through to the page selection */ }
    }
    var sel = window.getSelection ? window.getSelection() : null;
    return sel ? sel.toString() : '';
  }

  window.__agfPage = {
    /** Re-send the current state even if nothing changed. */
    report: function () { push(true); },

    text: selectedText,

    /** Remove the selected text from an editable field and return it. */
    cut: function () {
      var text = selectedText();
      if (text) document.execCommand('delete');
      return text;
    },

    /** Insert text at the caret / over the selection, the way typing would. */
    paste: function (text) {
      var active = document.activeElement;
      var done = false;
      try { done = document.execCommand('insertText', false, text); } catch (e) { done = false; }
      if (!done && isTextControl(active) && active.selectionStart !== null) {
        active.setRangeText(text, active.selectionStart, active.selectionEnd, 'end');
        active.dispatchEvent(new Event('input', { bubbles: true }));
      }
    },

    selectAll: function () {
      var active = document.activeElement;
      if (isTextControl(active)) active.select(); else document.execCommand('selectAll');
    },

    /** Close a menu the site opened itself (it opens on the same long-press), by pressing Escape. */
    closeSiteMenu: function () {
      var opts = { key: 'Escape', code: 'Escape', keyCode: 27, which: 27, bubbles: true, cancelable: true };
      (document.activeElement || document.body).dispatchEvent(new KeyboardEvent('keydown', opts));
    },

    /** Drop the selection (keeps the caret in editable content). */
    collapse: function () {
      var active = document.activeElement;
      if (isTextControl(active)) {
        try { active.setSelectionRange(active.selectionEnd, active.selectionEnd); } catch (e) { /* no selection API */ }
        return;
      }
      var sel = window.getSelection ? window.getSelection() : null;
      if (!sel) return;
      if (active && active.isContentEditable) sel.collapseToEnd(); else sel.removeAllRanges();
    }
  };
})();
