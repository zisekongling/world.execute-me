/* ============================================================================
   android/tools/web-overlay/perf-head.js

   Runs in <head>, BEFORE every engine script. It has exactly one job: decide
   what kind of host this is and write that decision onto the document, while
   there is still time for the engine to read it.

   60_app.js reads `window.matchMedia('(prefers-reduced-motion: reduce)')` ONCE,
   at parse time, and caps the backing store at DPR 2 inside resize(). Both of
   those are read before any other script runs, so both have to be arranged
   here — patching them later would be a change nothing would ever consult.

   URL parameters this file honours (the native app appends them):

     ?perf=low     emulate prefers-reduced-motion and clamp DPR to 1
     ?dpr=1.5      clamp DPR to an explicit value (0.5 – 3)

   Nothing in here is allowed to throw: a viewer whose film failed to start
   because of a *host* file would be the worst possible trade. Every step is
   individually guarded, and the whole thing sits inside one try/catch.
   ========================================================================== */
(function () {
  'use strict';
  try {
    var root = document.documentElement;
    var q = window.location.search || '';

    root.setAttribute('data-da-host', 'android');

    var touch = ('ontouchstart' in window) ||
      (navigator.maxTouchPoints > 0) ||
      (navigator.msMaxTouchPoints > 0);
    if (touch) root.className += ' da-touch';

    function hasFlag(name) {
      return new RegExp('[?&]' + name + '(?:=|&|$)').test(q);
    }
    function flagValue(name) {
      var m = new RegExp('[?&]' + name + '=([^&]+)').exec(q);
      return m ? decodeURIComponent(m[1]) : null;
    }

    var perfLow = hasFlag('perf') && flagValue('perf') === 'low';

    /* ---- prefers-reduced-motion -------------------------------------------
       The film already knows how to tone itself down; the media query is the
       only switch it exposes, so a phone that cannot afford the full
       compositor asks for it through the same door every other viewer uses. */
    if (perfLow) {
      root.className += ' da-perf-low';
      var realMatchMedia = window.matchMedia;
      if (typeof realMatchMedia === 'function') {
        window.matchMedia = function (query) {
          var mql = realMatchMedia.call(window, query);
          if (/prefers-reduced-motion/.test(String(query))) {
            try {
              Object.defineProperty(mql, 'matches', {
                get: function () { return true; },
                configurable: true
              });
            } catch (e) { /* a frozen MediaQueryList is still usable */ }
          }
          return mql;
        };
      }
    }

    /* ---- device pixel ratio ------------------------------------------------
       The film is composed on a 1600x900 virtual stage and scaled. On a phone
       that reports DPR 3 the backing store is 3x, so every one of the
       compositor's full-screen draws costs nine times the fill rate — for
       detail smaller than the eye can resolve at arm's length. Clamping is the
       single largest performance lever available from outside the engine. */
    var dpr = null;
    if (perfLow) {
      dpr = 1;
    } else {
      var asked = parseFloat(flagValue('dpr') || '');
      if (isFinite(asked) && asked >= 0.5 && asked <= 3) dpr = asked;
    }
    if (dpr !== null) {
      try {
        Object.defineProperty(window, 'devicePixelRatio', {
          get: function () { return dpr; },
          configurable: true
        });
        root.setAttribute('data-da-dpr', String(dpr));
      } catch (e) { /* the engine's own 2x cap still applies */ }
    }
  } catch (e) {
    /* deliberately silent: this file must never be the reason the film is dark */
  }
})();
