/* ============================================================================
   android/tools/web-overlay/mobile.js

   The touch adaptation for world.execute(me);

   This file is a *host*, not a part of the film. It watches and it adds
   controls; it never touches the clock, the palette, the world or a plate. The
   engine's entire public surface — `EM.__app` — is four verbs and one reader
   (seek / play / pause / setOffset / now), and that is all this file uses.

   It is written defensively on purpose:

     * everything is inside one try/catch, so a device this file does not
       understand gets the ordinary desktop interface rather than a black page;
     * it bails out silently if the engine is missing, so it stays harmless in
       the offline verifier, in a screenshot tool, or in a browser that blocked
       a script;
     * it only installs itself when the device reports touch, so opening the
       same page on a laptop changes nothing at all.

   What it adds on a phone:

     轻点画面        play / pause   (there is no space bar on a phone)
     双击左/右三分之一  −5s / +5s
     顶栏           −5s / +5s / 同步偏移 ±10ms / 隐藏界面 / 退出
     横屏全屏         for a browser we only handed a URL to, which we cannot
                    lock from the native side
     屏幕常亮        a wake lock while it plays
     记忆            volume and sync offset survive a reload
   ========================================================================== */
(function () {
  'use strict';

  try {
    if (!(('ontouchstart' in window) ||
          (navigator.maxTouchPoints > 0) ||
          (navigator.msMaxTouchPoints > 0))) {
      return;                                /* a desktop browser keeps the desktop UI */
    }
    if (document.readyState === 'loading') {
      document.addEventListener('DOMContentLoaded', boot);
    } else {
      boot();
    }
  } catch (e) {
    /* silent by design — see the header */
  }

  /* ------------------------------------------------------------------------ */
  function boot() {
    var root = document.documentElement;
    root.classList.add('da-mobile');

    var app = window.EM && window.EM.__app;
    if (!app) return;                        /* no engine, nothing to adapt */

    var body = document.body;
    var host = detectHost();
    if (host) root.classList.add('da-native');

    var $ = function (id) { return document.getElementById(id); };
    var OFFSET_KEY = 'da.offset';
    var VOLUME_KEY = 'da.volume';

    /* ---- toast ----------------------------------------------------------
       The engine has one and keeps it private. Rather than reach into it, the
       same host element and the same stylesheet class are reused, so the two
       kinds of message look identical. */
    function toast(msg) {
      var box = $('toast-host');
      if (!box) return;
      var d = document.createElement('div');
      d.className = 'toast';
      d.textContent = msg;
      box.appendChild(d);
      setTimeout(function () { if (d.parentNode) d.parentNode.removeChild(d); }, 1700);
    }

    /* ---- the native host, if there is one -------------------------------
       Present only inside the app's own WebView. In every other browser
       `AndroidHost` does not exist and this is null, which is exactly how the
       page tells an in-app session from a handed-off one. */
    function detectHost() {
      try {
        if (typeof window.AndroidHost !== 'undefined' && window.AndroidHost) return window.AndroidHost;
      } catch (e) { /* an interface that throws on access is not a host */ }
      return null;
    }

    if (host) {
      try { if (host.onReady) host.onReady(); } catch (e) { /* ignore */ }
      /* The renderer's diagnostics are the only window into a phone's WebView
         from a desktop. Forward them so the app can show a log. */
      try {
        var realError = console.error;
        console.error = function () {
          try {
            var parts = [];
            for (var i = 0; i < arguments.length; i++) {
              var a = arguments[i];
              parts.push(a && a.message ? a.message : String(a));
            }
            if (host.onLog) host.onLog(parts.join(' ').slice(0, 400));
          } catch (e) { /* never let logging break logging */ }
          return realError.apply(console, arguments);
        };
      } catch (e) { /* ignore */ }
      window.addEventListener('error', function (ev) {
        try { if (host.onLog) host.onLog('window.onerror: ' + (ev.message || '') + ' @' + (ev.lineno || 0)); }
        catch (e) { /* ignore */ }
      });
    }

    /* ---- the transport helpers ------------------------------------------ */
    function nowRaw() {
      var s = app.now();
      return (s && typeof s.rawT === 'number') ? s.rawT : 0;
    }

    function seekBy(delta) {
      var to = Math.max(0, Math.min(EM.AUDIO_END - 0.05, nowRaw() + delta));
      app.seek(to);
      toast((delta > 0 ? '+' : '') + delta + 's  →  ' + EM.fmtTime(to));
      wake();
    }

    function toggle() {
      var s = app.now();
      if (s.running) app.pause(); else app.play();
      wake();
      poke();
    }

    function isPlaying() {
      var s = app.now();
      return !!(s && s.running);
    }

    /* ---- subtitles -------------------------------------------------------
       The film prints the sung line into #lyric-line and nothing else draws it
       — every plate paints its own diagram, and the words on the canvas belong
       to the picture, not to the subtitle. So there is exactly one element to
       work with, and no second copy to keep in step.

       That element is shared with the engine, which writes into it whenever the
       cue index changes. Rather than add a parallel element and restate the
       film's typography, this file reuses theirs: whatever the engine last put
       there is remembered as the English line, and in Chinese mode it is
       replaced. The class the engine sets (`instrumental`) is left alone, so a
       translation of a stage direction still comes out in the film's monospace
       cyan.

       Three states, cycled from one button:

         en   the film's own English. The default, and a complete no-op.
         zh   the fan translation, or the film's own line where the translator
              left the cue alone (the (instrumental ...) and (outro) directions).
         off  the panel goes away entirely.

       The alternative — joining the two sheets by row number — would have been
       silently wrong: the translation splits three cues in two. Timestamps are
       the interface, which is also why the build refuses to emit the table when
       the two sheets no longer agree on the times. */
    var LYR_KEY = 'da.lyrics';
    var LYR_MODES = ['en', 'zh', 'off'];
    var LYR_BTN = { en: '歌词·原', zh: '歌词·译', off: '歌词·关' };
    var LYR_NAME = { en: '原文', zh: '中文翻译', off: '已关闭' };

    var ZH = (window.DA_LYRICS_ZH && window.DA_LYRICS_ZH.lines) || [];
    var lyricEl = $('lyric-line');
    var lyrMode = null;
    var lyrEn = '';          /* the text the ENGINE last wrote */
    var lyrWrote = null;     /* the text THIS file last wrote, so the engine's
                                next write can be told from our own */
    var lyrRaf = 0;

    /* the last translation at or before `t`, or null in a stretch the
       translation does not cover */
    function zhAt(t) {
      var lo = 0, hi = ZH.length - 1, best = -1;
      while (lo <= hi) {
        var mid = (lo + hi) >> 1;
        if (ZH[mid][0] <= t + 1e-6) { best = mid; lo = mid + 1; } else { hi = mid - 1; }
      }
      return best < 0 ? null : ZH[best][1];
    }

    /* the only thing in this file that writes to #lyric-line */
    function lyrPaint() {
      if (!lyricEl) return;
      var cur = lyricEl.textContent;
      /* anything other than what we wrote came from the engine */
      if (cur !== lyrWrote) lyrEn = cur;
      var want = lyrEn;
      if (lyrMode === 'zh') {
        var z = zhAt(app.now().t);
        if (z !== null) want = z;   /* untranslated: leave the film's own line */
      }
      if (cur !== want) { lyricEl.textContent = want; lyrWrote = want; }
    }

    function lyrStep() {
      if (lyrMode !== 'zh' || !lyricEl) { lyrRaf = 0; return; }
      lyrPaint();
      lyrRaf = requestAnimationFrame(lyrStep);
    }

    /* ---- why a frame is not enough ---------------------------------------
       The engine writes its English line from its own rAF callback. Ours may
       run BEFORE it within the same frame — and then the browser paints the
       English the engine just wrote, one frame of the wrong language, right on
       top of the line the viewer is already reading. That is the flash.

       So the correction is not scheduled on a frame at all. A MutationObserver
       fires as a microtask the instant the engine touches the element, which is
       still before the browser paints, so the English never reaches the screen
       while Chinese mode is on. Ordering between the two rAF callbacks stops
       mattering.

       The rAF loop stays, for the job the observer cannot do: the translation
       splits three cues in two, so the Chinese line changes at moments the
       engine has no reason to write at all. */
    var lyrObs = null;

    function lyrWatch(on) {
      if (!lyricEl || typeof MutationObserver !== 'function') return;
      if (on && !lyrObs) {
        lyrObs = new MutationObserver(lyrPaint);
        lyrObs.observe(lyricEl, { childList: true, characterData: true, subtree: true });
      } else if (!on && lyrObs) {
        lyrObs.disconnect();
        lyrObs = null;
      }
    }

    function applyLyrics() {
      var root = document.documentElement;
      root.classList.toggle('da-lyr-zh', lyrMode === 'zh');
      root.classList.toggle('da-lyr-off', lyrMode === 'off');
      if (lyricEl) {
        var cur = lyricEl.textContent;
        /* in `en` and `off` this file has never written to the element, so
           whatever is in it IS the film's own current line */
        if (cur !== lyrWrote) lyrEn = cur;
        if (lyrMode === 'zh') {
          /* watch first: the next thing the engine writes must be caught before
             it can be painted. Then fix the line that is on screen right now,
             synchronously, rather than waiting for a frame that the engine may
             already have written English into. */
          lyrWatch(true);
          lyrPaint();
          if (!lyrRaf) lyrRaf = requestAnimationFrame(lyrStep);
        } else {
          lyrWatch(false);
          if (lyrRaf) { cancelAnimationFrame(lyrRaf); lyrRaf = 0; }
          if (lyrWrote !== null && cur !== lyrEn) lyricEl.textContent = lyrEn;
          lyrWrote = null;
        }
      }
    }

    function setLyrics(mode, quiet) {
      if (LYR_MODES.indexOf(mode) < 0) mode = 'en';
      if (mode === lyrMode) return;
      lyrMode = mode;
      save(LYR_KEY, mode);
      var b = $('da-lyr-btn');
      if (b) {
        b.textContent = LYR_BTN[mode];
        b.setAttribute('data-lyr', mode);
        b.setAttribute('aria-label', '歌词：' + LYR_NAME[mode] + '（点击切换）');
      }
      applyLyrics();
      if (!quiet) { toast('歌词：' + LYR_NAME[mode]); poke(); }
    }

    function cycleLyrics() {
      setLyrics(LYR_MODES[(LYR_MODES.indexOf(lyrMode) + 1) % LYR_MODES.length]);
    }

    /* ---- the injected top strip -----------------------------------------
       −5s / +5s / sync ±10ms / hide / exit. The keyboard could do all five;
       a phone has no keyboard, and a seek that requires precision-dragging a
       3.5-minute waveform is not a seek, it is a lottery. */
    var offRead = document.createElement('span');
    offRead.className = 'da-off mono';
    offRead.textContent = '±0 ms';

    function mkBtn(label, title, fn) {
      var b = document.createElement('button');
      b.type = 'button';
      b.className = 'btn da-btn';
      b.textContent = label;
      if (title) b.title = title;
      b.setAttribute('aria-label', title || label);
      b.addEventListener('click', function (e) {
        e.stopPropagation();
        e.preventDefault();
        fn();
      });
      return b;
    }

    function readOffset() {
      var ms = Math.round((app.now().offset || 0) * 1000);
      offRead.textContent = (ms > 0 ? '+' : ms < 0 ? '−' : '±') + Math.abs(ms) + ' ms';
      offRead.classList.toggle('nonzero', ms !== 0);
      offRead.classList.toggle('hot', Math.abs(ms) >= 100);
    }

    function nudgeOffset(deltaMs) {
      var ms = Math.round((app.now().offset || 0) * 1000) + deltaMs;
      ms = Math.max(-2000, Math.min(2000, ms));
      app.setOffset(ms / 1000);
      save(OFFSET_KEY, String(ms));
      readOffset();
      toast('同步偏移 ' + (ms > 0 ? '+' : '') + ms + ' ms');
    }

    var hud = $('hud-top');
    if (hud) {
      var bar = document.createElement('div');
      bar.className = 'da-topbar';
      bar.appendChild(mkBtn('−5s', '后退 5 秒', function () { seekBy(-5); }));
      bar.appendChild(mkBtn('+5s', '前进 5 秒', function () { seekBy(5); }));
      bar.appendChild(mkBtn('同步−', '画面提前 10 毫秒', function () { nudgeOffset(-10); }));
      bar.appendChild(mkBtn('同步+', '画面延后 10 毫秒', function () { nudgeOffset(10); }));
      bar.appendChild(offRead);
      var lyrBtn = mkBtn(LYR_BTN.en, '歌词：原文（点击切换）', cycleLyrics);
      lyrBtn.id = 'da-lyr-btn';
      lyrBtn.setAttribute('data-lyr', 'en');
      bar.appendChild(lyrBtn);
      bar.appendChild(mkBtn('隐藏', '隐藏界面（轻点画面恢复）', function () {
        body.classList.toggle('hide-ui');
        poke();
      }));
      bar.appendChild(mkBtn(host ? '退出' : '横屏全屏',
        host ? '退出播放' : '全屏并锁定横屏',
        function () {
          if (host && host.exit) { try { host.exit(); return; } catch (e) { /* fall through */ } }
          fullscreenLandscape();
        }));
      hud.appendChild(bar);
      /* The film puts its toasts at the bottom centre of the stage — on a
         desktop window that is just above the transport, but a phone held
         sideways is ~390 px tall, so it lands on the artwork, and centred is
         where the subtitle is too. The strip has unused room at its left end
         (the brand is hidden there), which is exactly where a status line
         belongs. Both this file's toast() and the film's own append to this one
         host, so moving the host moves both. */
      var toastHost = $('toast-host');
      if (toastHost) bar.insertBefore(toastHost, bar.firstChild);
      offRead.addEventListener('click', function (e) {
        e.stopPropagation();
        app.setOffset(0);
        save(OFFSET_KEY, '0');
        readOffset();
        toast('同步偏移归零');
      });
      setLyrics(load(LYR_KEY) || 'en', true);
    }

    /* ---- portrait guard --------------------------------------------------
       The native player is locked to landscape by the manifest, so this only
       ever appears in a browser we handed a URL to. Chrome for Android will
       honour an orientation lock, but only from inside fullscreen — hence the
       button, and hence the attempt on the first tap. */
    var rot = document.createElement('div');
    rot.className = 'da-rotate';
    rot.innerHTML =
      '<div class="da-rot-ico"></div>' +
      '<div class="da-rot-txt">请横屏观看</div>' +
      '<div class="da-rot-sub">这部片子是宽银幕构图，竖屏会把画面压成一条。<br>' +
      '点下面的按钮可以全屏并锁定横屏。</div>';
    var rotBtn = mkBtn('横屏全屏播放', '全屏并锁定横屏', function () { fullscreenLandscape(); });
    rot.appendChild(rotBtn);
    body.appendChild(rot);

    function fullscreenLandscape() {
      var el = document.documentElement;
      var req = el.requestFullscreen || el.webkitRequestFullscreen;
      var done = function () {
        try {
          if (screen.orientation && screen.orientation.lock) {
            var p = screen.orientation.lock('landscape');
            if (p && p.catch) p.catch(function () { /* unsupported, or not fullscreen */ });
          }
        } catch (e) { /* ignore */ }
        poke();
      };
      try {
        var p2 = req ? req.call(el) : null;
        if (p2 && p2.then) p2.then(done, done); else done();
      } catch (e) { done(); }
    }

    /* ---- taps on the picture --------------------------------------------
       The page keeps its own click handler on #stage, and it only ever does
       one thing: start playback the first time. On a phone that leaves no way
       back to pause. So taps are intercepted one level up, on `document`
       during the capture phase, which fires before the stage handler, and the
       propagation is stopped there.

       Everything inside #ctrl and #hud-top is left completely alone, so the
       film's own transport keeps working exactly as it does on a desktop. */
    var lastTap = 0, tapTimer = 0, swallow = false;

    function stageTap(e) {
      if (body.classList.contains('hide-ui')) {
        body.classList.remove('hide-ui');
        poke();
        return;
      }
      if (swallow) { swallow = false; return; }
      var now = Date.now();
      if (now - lastTap < 300) {
        lastTap = 0;
        if (tapTimer) { clearTimeout(tapTimer); tapTimer = 0; }
        var w = window.innerWidth || 1;
        var x = (typeof e.clientX === 'number' && e.clientX) ? e.clientX : w / 2;
        if (x < w * 0.34) { seekBy(-5); return; }
        if (x > w * 0.66) { seekBy(5); return; }
        toggle();
        return;
      }
      lastTap = now;
      tapTimer = setTimeout(function () { tapTimer = 0; lastTap = 0; toggle(); }, 300);
    }

    document.addEventListener('click', function (e) {
      var t = e.target;
      if (!t || !t.closest) return;
      if (t.closest('#ctrl') || t.closest('#hud-top')) return;
      if (!t.closest('#stage')) return;
      e.stopPropagation();
      e.preventDefault();
      stageTap(e);
    }, true);

    /* ---- auto-hiding chrome ---------------------------------------------
       The film is the point. Five seconds after the last touch the controls
       get out of the way, but only while it is playing — hiding the transport
       of a paused film just hides the reason it is paused. */
    var hideTimer = 0;
    function poke() {
      if (hideTimer) { clearTimeout(hideTimer); hideTimer = 0; }
      body.classList.remove('da-ui-hidden');
      hideTimer = setTimeout(function () {
        hideTimer = 0;
        if (isPlaying() && !body.classList.contains('hide-ui')) {
          body.classList.add('da-ui-hidden');
        }
      }, 5000);
    }

    function onTouchStart() {
      if (body.classList.contains('da-ui-hidden')) {
        swallow = true;
        setTimeout(function () { swallow = false; }, 400);
      }
      poke();
    }

    document.addEventListener('touchstart', onTouchStart, { passive: true, capture: true });
    document.addEventListener('mousedown', onTouchStart, { passive: true, capture: true });
    document.addEventListener('keydown', function () { poke(); }, true);

    /* ---- wake lock -------------------------------------------------------
       A 3.5 minute film during which nothing is touched is exactly the case
       the screen timeout was designed for. */
    var wakeSentinel = null;
    function wake() {
      try {
        if (!navigator.wakeLock || wakeSentinel) return;
        var p = navigator.wakeLock.request('screen');
        if (p && p.then) {
          p.then(function (s) {
            wakeSentinel = s;
            s.addEventListener('release', function () { wakeSentinel = null; });
          }).catch(function () { /* denied; the native app has its own flag */ });
        }
      } catch (e) { /* ignore */ }
    }
    document.addEventListener('visibilitychange', function () {
      if (!document.hidden) { wake(); poke(); }
    });

    /* ---- remember what the viewer tuned ---------------------------------- */
    function save(key, value) {
      try { localStorage.setItem(key, value); } catch (e) { /* private mode */ }
    }
    function load(key) {
      try { return localStorage.getItem(key); } catch (e) { return null; }
    }

    var vol = $('vol');
    if (vol) {
      var v = load(VOLUME_KEY);
      if (v !== null) {
        var vn = parseFloat(v);
        if (isFinite(vn) && vn >= 0 && vn <= 1) app.setVolume(vn);
      }
      vol.addEventListener('input', function () { save(VOLUME_KEY, String(app.now().v || vol.value)); });
      vol.addEventListener('change', function () { save(VOLUME_KEY, String(vol.value)); });
    }

    var savedOffset = load(OFFSET_KEY);
    if (savedOffset !== null) {
      var on = parseInt(savedOffset, 10);
      if (isFinite(on)) app.setOffset(Math.max(-2000, Math.min(2000, on)) / 1000, true);
    }
    readOffset();

    /* ---- the opening hint, in touch ------------------------------ */
    var hintTxt = document.querySelector('#start-hint .txt');
    var hintSub = document.querySelector('#start-hint .sub');
    if (hintTxt) hintTxt.textContent = '轻点开始';
    if (hintSub) {
      hintSub.textContent = '轻点画面 播放 / 暂停　·　双击左 / 右两侧 ±5 秒' +
        '　·　顶栏可微调同步偏移　·　手机请横屏观看';
    }

    /* A first touch is also the moment to ask for fullscreen + landscape in a
       browser we do not own. It is a request, not a lock: if the browser says
       no, the film still plays inside the page. */
    if (!host) {
      var tried = false;
      document.addEventListener('touchstart', function () {
        if (tried) return;
        tried = true;
        if (window.innerHeight > window.innerWidth) fullscreenLandscape();
      }, { passive: true, capture: true, once: true });
    }

    poke();
    setInterval(readOffset, 500);

    /* A small handle for a curious viewer with a JavaScript console, and for
       the native side to drive if it ever needs to. */
    window.DA = {
      toast: toast,
      seekBy: seekBy,
      toggle: toggle,
      nudgeOffset: nudgeOffset,
      fullscreen: fullscreenLandscape,
      poke: poke,
      isHost: !!host
    };
  }
})();
