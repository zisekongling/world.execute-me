/* ============================================================================
   android/tools/preview-check.mjs

   Renders the *packaged* film — the one that lives in the APK's assets, with
   the mobile overlay already injected — in a real browser at a phone-shaped
   viewport, and writes PNGs.

   WHY THIS EXISTS
   ---------------
   There is no emulator and no device attached to this machine, so nothing else
   in this repository can answer the only question that matters about the
   Android layer: does the film still draw once `perf-head.js`, `mobile.css`
   and `mobile.js` are stapled onto it?

   The offline verifiers cannot answer it. `_tools/verify.js` reads sources;
   `_tools/render-png.js` drives the plate code through a software rasteriser
   with a stub DOM. Neither one runs the overlay, and the overlay is the only
   new code that touches the page.

   So this drives Chrome (the same Blink engine the default path uses, inside
   the WebView) over the DevTools protocol: emulated touch + Android UA, so the
   overlay installs itself exactly as it would on a handset; a click to begin;
   then seeks and screenshots.

   Usage:
     node tools/preview-check.mjs                # a few key moments
     node tools/preview-check.mjs 12 82 200      # exact seconds
   Output: build-preview/*.png plus a one-line verdict per frame.
   ==========================================================================*/
'use strict';

import http from 'node:http';
import fs from 'node:fs';
import fsp from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ANDROID = path.join(HERE, '..');
const WEB = path.join(ANDROID, 'app', 'src', 'main', 'assets', 'web');
const OUT = path.join(ANDROID, 'build-preview');

const CHROME_CANDIDATES = [
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe',
];

/* The moments worth looking at: an early plate, a mid-film plate, the densest
   stretch, and a late one — a spread across all six plate families. */
const DEFAULT_TIMES = [8, 42, 82, 137, 199];

/* ---------------------------------------------------------------------------
   1. The static server. A deliberately small subset of AssetServer.kt, but it
      speaks Range because <audio> scrubbing does, and a preview that cannot
      seek is not a preview of this film.
   ------------------------------------------------------------------------ */
const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.mp3': 'audio/mpeg',
  '.png': 'image/png',
};

function startServer(root) {
  return new Promise((resolve) => {
    const server = http.createServer((req, res) => {
      let rel;
      try {
        rel = decodeURIComponent(new URL(req.url, 'http://x').pathname);
      } catch {
        res.writeHead(400).end('bad');
        return;
      }
      if (rel.endsWith('/')) rel += 'index.html';
      const full = path.resolve(root, rel.replace(/^\/+/, ''));
      if (full !== root && !full.startsWith(root + path.sep)) {
        res.writeHead(403).end('forbidden');
        return;
      }
      let body;
      try {
        body = fs.readFileSync(full);
      } catch {
        console.log('  404 ' + rel); /* the film asks for nothing it did not ship */
        res.writeHead(404).end('not found');
        return;
      }
      const type = MIME[path.extname(full).toLowerCase()] || 'application/octet-stream';
      const range = req.headers.range;
      const m = range && /^bytes=(\d*)-(\d*)$/.exec(range.trim());
      if (m) {
        const total = body.length;
        let start = m[1] === '' ? Math.max(0, total - Number(m[2])) : Number(m[1]);
        let end = m[1] === '' || m[2] === '' ? total - 1 : Math.min(Number(m[2]), total - 1);
        if (start > end || start >= total) {
          res.writeHead(416, { 'Content-Range': `bytes */${total}` }).end();
          return;
        }
        res.writeHead(206, {
          'Content-Type': type,
          'Content-Length': end - start + 1,
          'Accept-Ranges': 'bytes',
          'Content-Range': `bytes ${start}-${end}/${total}`,
        });
        res.end(body.subarray(start, end + 1));
        return;
      }
      res.writeHead(200, {
        'Content-Type': type,
        'Content-Length': body.length,
        'Accept-Ranges': 'bytes',
      });
      res.end(body);
    });
    server.listen(0, '127.0.0.1', () => resolve({ server, port: server.address().port }));
  });
}

/* ---------------------------------------------------------------------------
   2. A very small CDP client. Node's global WebSocket is enough; no puppeteer,
      because adding a dependency to a repository that has none would be a
      heavier price than these sixty lines.
   ------------------------------------------------------------------------ */
class Cdp {
  constructor(ws) {
    this.ws = ws;
    this.id = 0;
    this.pending = new Map();
    this.listeners = [];
    ws.addEventListener('message', (ev) => {
      const msg = JSON.parse(typeof ev.data === 'string' ? ev.data : ev.data.toString());
      if (msg.id !== undefined) {
        const p = this.pending.get(msg.id);
        if (!p) return;
        this.pending.delete(msg.id);
        if (msg.error) p.reject(new Error(msg.error.message));
        else p.resolve(msg.result);
      } else {
        for (const fn of this.listeners) fn(msg);
      }
    });
  }

  static connect(url) {
    return new Promise((resolve, reject) => {
      const ws = new WebSocket(url);
      ws.addEventListener('open', () => resolve(new Cdp(ws)));
      ws.addEventListener('error', (e) => reject(new Error('websocket: ' + (e.message || 'error'))));
    });
  }

  send(method, params = {}) {
    const id = ++this.id;
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject });
      this.ws.send(JSON.stringify({ id, method, params }));
      setTimeout(() => {
        if (this.pending.delete(id)) reject(new Error(method + ' timed out'));
      }, 30000);
    });
  }

  on(fn) {
    this.listeners.push(fn);
  }

  async eval(expression) {
    const r = await this.send('Runtime.evaluate', {
      expression,
      returnByValue: true,
      awaitPromise: true,
    });
    if (r.exceptionDetails) {
      throw new Error(
        'page threw: ' + (r.exceptionDetails.exception?.description || r.exceptionDetails.text),
      );
    }
    return r.result.value;
  }
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/* ---------------------------------------------------------------------------
   3. Squeeze the canvas down to a luminance grid, so a frame can be judged
      without a human: an all-black grid means the overlay broke the render.
   ------------------------------------------------------------------------ */
const CANVAS_STATS = `(() => {
  const c = document.getElementById('view');
  if (!c) return { error: 'no canvas' };
  const g = document.createElement('canvas');
  g.width = 48; g.height = 27;
  const x = g.getContext('2d');
  x.drawImage(c, 0, 0, 48, 27);
  const d = x.getImageData(0, 0, 48, 27).data;
  let sum = 0, max = 0, lit = 0, rs = 0, gs = 0, bs = 0;
  for (let i = 0; i < d.length; i += 4) {
    const l = 0.2126 * d[i] + 0.7152 * d[i+1] + 0.0722 * d[i+2];
    sum += l; if (l > max) max = l; if (l > 12) lit++;
    rs += d[i]; gs += d[i+1]; bs += d[i+2];
  }
  const n = d.length / 4;
  return {
    mean: +(sum / n).toFixed(2),
    max: Math.round(max),
    litPct: +(100 * lit / n).toFixed(1),
    rgb: [Math.round(rs/n), Math.round(gs/n), Math.round(bs/n)],
  };
})()`;

/* ---------------------------------------------------------------------------
   4. Drive it.
   ------------------------------------------------------------------------ */
async function main() {
  const times = process.argv.slice(2).map(Number).filter((n) => Number.isFinite(n));
  const shots = times.length ? times : DEFAULT_TIMES;

  const entry = path.join(WEB, 'index.html');
  if (!fs.existsSync(entry)) {
    console.error(`缺少 ${entry}\n先运行：node tools/build-web-assets.mjs`);
    process.exit(1);
  }
  const chrome = CHROME_CANDIDATES.find((p) => fs.existsSync(p));
  if (!chrome) {
    console.error('找不到 Chrome 或 Edge，无法预览。');
    process.exit(1);
  }

  await fsp.mkdir(OUT, { recursive: true });
  const { server, port } = await startServer(WEB);

  /* A fresh profile every run: a stale service worker or a cached page would
     make this tool lie to us. */
  const profile = await fsp.mkdtemp(path.join(os.tmpdir(), 'da-preview-'));
  const dbgPort = 9333 + (process.pid % 200);
  const args = [
    '--headless=new',
    `--remote-debugging-port=${dbgPort}`,
    `--user-data-dir=${profile}`,
    '--no-first-run',
    '--no-default-browser-check',
    '--disable-extensions',
    '--disable-background-networking',
    '--autoplay-policy=no-user-gesture-required',
    '--mute-audio',
    '--enable-unsafe-swiftshader',
    '--window-size=844,390',
    'about:blank',
  ];
  const proc = spawn(chrome, args, { stdio: 'ignore', windowsHide: true });
  proc.on('error', (e) => console.error('浏览器启动失败：' + e.message));

  const base = `http://127.0.0.1:${dbgPort}`;
  let version = null;
  for (let i = 0; i < 60 && !version; i++) {
    await sleep(250);
    try {
      version = await (await fetch(`${base}/json/version`)).json();
    } catch {
      /* not up yet */
    }
  }
  if (!version) {
    console.error('无法连接浏览器的调试端口。');
    proc.kill();
    server.close();
    process.exit(1);
  }
  console.log(`浏览器：${version.Browser}`);

  const target = await (
    await fetch(`${base}/json/new?${encodeURIComponent('about:blank')}`, { method: 'PUT' })
  ).json();
  const cdp = await Cdp.connect(target.webSocketDebuggerUrl);

  const problems = [];
  cdp.on((msg) => {
    if (msg.method === 'Runtime.exceptionThrown') {
      const d = msg.params.exceptionDetails;
      problems.push('异常: ' + (d.exception?.description || d.text));
    }
    if (msg.method === 'Runtime.consoleAPICalled' && msg.params.type === 'error') {
      problems.push('console.error: ' + msg.params.args.map((a) => a.value ?? a.description).join(' '));
    }
    if (msg.method === 'Log.entryAdded' && msg.params.entry.level === 'error') {
      /* The film ships no favicon and asks for none; the browser asks anyway.
         Counting that as a page error would train us to ignore this section. */
      const text = msg.params.entry.text;
      if (!/favicon\.ico/.test(text) && !/404/.test(text)) problems.push('日志: ' + text);
    }
  });

  await cdp.send('Runtime.enable');
  await cdp.send('Log.enable');
  await cdp.send('Page.enable');
  /* 844x390 = a 6.1" handset lying on its side, which is how the film is
     meant to be seen. */
  await cdp.send('Emulation.setDeviceMetricsOverride', {
    width: 844,
    height: 390,
    deviceScaleFactor: 2,
    mobile: true,
    screenOrientation: { type: 'landscapePrimary', angle: 90 },
  });
  await cdp.send('Emulation.setTouchEmulationEnabled', { enabled: true, maxTouchPoints: 5 });
  await cdp.send('Emulation.setEmitTouchEventsForMouse', { enabled: true, configuration: 'mobile' });
  await cdp.send('Emulation.setUserAgentOverride', {
    userAgent:
      'Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) ' +
      'Chrome/122.0.0.0 Mobile Safari/537.36',
    platform: 'Linux armv8l',
  });

  const url = `http://127.0.0.1:${port}/index.html`;
  console.log(`页面：${url}`);
  await cdp.send('Page.navigate', { url });
  await sleep(2500);

  const ready = await cdp.eval(`(() => {
    const app = window.EM && window.EM.__app;
    return {
      engine: !!app,
      htmlClass: document.documentElement.className,
      topbar: !!document.querySelector('#hud-top .da-topbar'),
      rotateIsDisplayed: (() => {
        const r = document.querySelector('.da-rotate');
        return r ? getComputedStyle(r).display !== 'none' : null;
      })(),
      audioOK: app ? app.now().audioOK : null,
      duration: window.EM ? window.EM.AUDIO_END : null,
      shellDisplay: getComputedStyle(document.getElementById('shell')).display,
    };
  })()`);
  console.log('装载状态：' + JSON.stringify(ready));

  /* The film waits for a gesture, and on a phone that gesture belongs to the
     overlay: `mobile.js` intercepts `#stage` clicks during the capture phase
     and stops propagation so the page's own "start once" handler never runs,
     then debounces 300 ms and toggles play/pause itself (that is the whole
     point — the page has no way back to pause).

     So a tap is dispatched and NOTHING else is called: if the film ends up
     playing, the overlay's own transport is what started it. An earlier
     version of this file called `EM.__app.play()` first and then tapped, which
     toggled the film straight back off — a harness bug that looked exactly
     like a broken player. */
  await cdp.eval(`(() => {
    const s = document.getElementById('stage');
    s.dispatchEvent(new MouseEvent('click', { bubbles: true, clientX: 420 }));
    return true;
  })()`);
  await sleep(900); /* 300 ms debounce + a few frames */
  const playing = await cdp.eval(`(() => {
    const m = document.getElementById('audio');
    return {
      state: document.getElementById('state').textContent,
      running: !!(window.EM && EM.__app && EM.__app.now().running),
      paused: m ? m.paused : null,
    };
  })()`);
  console.log('轻点画面后：' + JSON.stringify(playing));
  await sleep(1200);

  const rows = [];
  for (const t of shots) {
    await cdp.eval(`(window.EM && EM.__app) ? EM.__app.seek(${t}) : null`);
    await sleep(420); /* two or three animation frames: the plates ease in */
    const now = await cdp.eval(`(() => {
      const a = window.EM && EM.__app;
      if (!a) return null;
      const n = a.now();
      const m = document.getElementById('audio');
      return { t: +n.t.toFixed(2), running: n.running, offset: n.offset,
               dpr: n.dpr, u: n.u ? +n.u.toFixed(3) : null, audioOK: n.audioOK,
               state: document.getElementById('state').textContent,
               paused: m ? m.paused : null, readyState: m ? m.readyState : null };
    })()`);
    const stats = await cdp.eval(CANVAS_STATS);
    const shot = await cdp.send('Page.captureScreenshot', { format: 'png' });
    const name = `t${String(t).padStart(3, '0')}.png`;
    await fsp.writeFile(path.join(OUT, name), Buffer.from(shot.data, 'base64'));
    rows.push({ t, file: name, now, stats });
    const verdict =
      stats.mean < 1.5 && stats.max < 40 ? '!! 画面几乎全黑' : 'ok';
    console.log(
      `  t=${String(t).padStart(3)}s  ${name}  均值 ${String(stats.mean).padStart(6)}  ` +
        `峰值 ${String(stats.max).padStart(3)}  点亮 ${String(stats.litPct).padStart(5)}%  ` +
        `rgb ${stats.rgb.join(',')}  引擎t=${now?.t ?? '-'}  ` +
        `${now?.state ?? '-'}  running=${now?.running}  audio.paused=${now?.paused}  ${verdict}`,
    );
  }

  /* ---- the subtitle switch ------------------------------------------------
     This is the one part of the overlay that MODIFIES the film rather than
     adding to it, so it gets a pass of its own. Three states on one button;
     sampled four times so the wrap back to `en` is covered too. The film keeps
     playing, so the playhead is parked on the cue again before each sample —
     the cue it lands on lasts 1.4 s and the sample takes less than that, but
     only just, and a check that trips over its own timing teaches nothing. */
  const LYR_T = 29.75;
  const readLyrics = `(() => {
    const el = document.getElementById('lyric-line');
    const p = document.getElementById('panel');
    const b = document.getElementById('da-lyr-btn');
    const bar = document.querySelector('.da-topbar');
    const host = document.getElementById('toast-host');
    const t = document.querySelector('#toast-host .toast');
    let toast = null;
    if (t && bar) {
      const tr = t.getBoundingClientRect(), br = bar.getBoundingClientRect();
      toast = { text: t.textContent, inStrip: bar.contains(t),
                inBand: tr.top >= br.top - 2 && tr.bottom <= br.bottom + 2,
                coversArt: tr.top > br.bottom + 8 };
    }
    return {
      mode: b ? b.getAttribute('data-lyr') : null,
      label: b ? b.textContent : null,
      line: el ? el.textContent : null,
      panelOpacity: p ? +getComputedStyle(p).opacity : null,
      htmlClass: document.documentElement.className,
      hostInStrip: !!(bar && host && bar.contains(host)),
      toast: toast,
    };
  })()`;
  const lyricStates = [];
  for (let i = 0; i < 4; i++) {
    if (i) {
      await cdp.eval(`(() => { const b = document.getElementById('da-lyr-btn');
        if (b) b.dispatchEvent(new MouseEvent('click', { bubbles: true }));
        return true; })()`);
    }
    await cdp.eval(`(window.EM && EM.__app) ? EM.__app.seek(${LYR_T}) : null`);
    await sleep(360);
    const st = await cdp.eval(readLyrics);
    lyricStates.push(st);
    /* one frame of the translated subtitle, for the record */
    if (st.mode === 'zh') {
      const shot = await cdp.send('Page.captureScreenshot', { format: 'png' });
      await fsp.writeFile(path.join(OUT, 'lyrics-zh.png'), Buffer.from(shot.data, 'base64'));
    }
  }
  for (const s of lyricStates) {
    console.log(`  歌词 ${String(s.mode).padEnd(3)} ${String(s.label).padEnd(6)} ` +
      `panel=${String(s.panelOpacity).padEnd(4)}  ${JSON.stringify(s.line)}` +
      (s.toast ? `  toast=${JSON.stringify(s.toast.text)} 条带内=${s.toast.inBand}` : ''));
  }
  /* the last button in the strip against the readout beside it: at 844 px the
     strip has to hold eight controls without pushing into the state badge */
  const barFit = await cdp.eval(`(() => {
    const bar = document.querySelector('.da-topbar');
    const right = document.querySelector('#hud-top .right');
    if (!bar) return null;
    const last = bar.lastElementChild;
    return { barScroll: bar.scrollWidth, barClient: bar.clientWidth,
             btns: bar.children.length,
             lastRight: last ? Math.round(last.getBoundingClientRect().right) : null,
             rightLeft: right ? Math.round(right.getBoundingClientRect().left) : null,
             vw: window.innerWidth };
  })()`);
  console.log('  顶栏：' + JSON.stringify(barFit));

  /* did the three states actually do what they claim? */
  const EN_LINE = "If I'm a set of points";
  const ZH_LINE = '如果我是一组点';
  const lyricsOK =
    lyricStates.length === 4 &&
    lyricStates[0].mode === 'en' && lyricStates[0].line === EN_LINE &&
    lyricStates[1].mode === 'zh' && lyricStates[1].line === ZH_LINE &&
    lyricStates[2].mode === 'off' && lyricStates[2].panelOpacity === 0 &&
    lyricStates[3].mode === 'en' && lyricStates[3].line === EN_LINE &&
    /* the toast host must live in the strip, and a toast must stay in its band
       rather than floating down over the picture */
    lyricStates.every((s) => s.hostInStrip) &&
    lyricStates[1].toast !== null &&
    lyricStates[1].toast.inBand && !lyricStates[1].toast.coversArt;

  /* ---- the flash -----------------------------------------------------------
     The engine writes its English line from its own rAF callback, and ours may
     run before it in the same frame — which paints one frame of English on top
     of the line the viewer is reading. Writing into the element the way the
     engine does, then draining only the microtask queue, is the honest test:
     a paint cannot happen until the current task and its microtasks are done,
     so if the translated line is back after one microtask, no frame was ever
     painted in English. */
  await cdp.eval(`(() => { const b = document.getElementById('da-lyr-btn');
    if (b && b.getAttribute('data-lyr') !== 'zh') {
      b.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    }
    return true; })()`);
  await cdp.eval(`(window.EM && EM.__app) ? EM.__app.seek(${LYR_T}) : null`);
  await sleep(360);
  const noFlash = await cdp.eval(`(async () => {
    const el = document.getElementById('lyric-line');
    if (!el) return null;
    el.textContent = 'ENGINE WROTE ENGLISH';   /* what a cue change does */
    await Promise.resolve(); await Promise.resolve();
    return { afterMicrotasks: el.textContent };
  })()`);
  const noFlashOK = !!(noFlash && noFlash.afterMicrotasks === ZH_LINE);
  console.log('  换行瞬间        : ' + (noFlashOK
    ? '无英文闪帧（微任务内已回到 ' + JSON.stringify(noFlash.afterMicrotasks) + '）'
    : '!! 微任务排空后仍是英文 ' + JSON.stringify(noFlash)));

  /* ---- hiding the chrome must not take the words with it -------------------
     Both the film's own `body.hide-ui` (src/style.css:48-50) and this overlay's
     five-second auto-hide had `#panel` in the group they fade. The subtitle is
     content, not chrome: hiding the transport should leave the line you are
     reading on screen. Driven through the real button, like a thumb would. */
  const pressHide = `(() => {
    const b = [...document.querySelectorAll('.da-topbar .btn')]
      .find(x => x.textContent === '隐藏');
    if (!b) return false;
    b.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    return true;
  })()`;
  const pressed = await cdp.eval(pressHide);
  await sleep(520);   /* clear the opacity transition */
  const whileHidden = await cdp.eval(`(() => {
    const op = (id) => { const e = document.getElementById(id);
      return e ? +getComputedStyle(e).opacity : null; };
    return { hideUi: document.body.classList.contains('hide-ui'),
             panel: op('panel'), ctrl: op('ctrl'), hud: op('hud-top'),
             line: document.getElementById('lyric-line').textContent };
  })()`);
  await cdp.eval(pressHide);   /* put the chrome back for the portrait shot */
  await sleep(420);
  console.log('  隐藏界面后：' + JSON.stringify(whileHidden));

  /* and one frame of it, so the claim can be looked at rather than trusted */
  await cdp.eval(pressHide);
  await sleep(520);
  const hshot = await cdp.send('Page.captureScreenshot', { format: 'png' });
  await fsp.writeFile(path.join(OUT, 'chrome-hidden.png'), Buffer.from(hshot.data, 'base64'));
  await cdp.eval(pressHide);
  await sleep(420);
  /* the chrome really is gone, and the line really is still there */
  const hideOK = pressed && whileHidden.hideUi === true &&
    whileHidden.panel === 1 && whileHidden.ctrl === 0 && whileHidden.hud === 0 &&
    typeof whileHidden.line === 'string' && whileHidden.line.length > 0;
  /* the strip must not run into the readout it sits beside */
  const barOK = !barFit || barFit.lastRight === null || barFit.rightLeft === null ||
    barFit.lastRight <= barFit.rightLeft;

  /* A phone held the wrong way round. The app locks its own window, but a
     browser we only handed a URL to cannot be, so the film has to ask — and
     that is worth one screenshot of its own. */
  await cdp.send('Emulation.setDeviceMetricsOverride', {
    width: 390,
    height: 844,
    deviceScaleFactor: 2,
    mobile: true,
    screenOrientation: { type: 'portraitPrimary', angle: 0 },
  });
  await sleep(500);
  const portrait = await cdp.eval(`(() => {
    const r = document.querySelector('.da-rotate');
    return {
      exists: !!r,
      displayed: r ? getComputedStyle(r).display !== 'none' : false,
      text: r ? r.textContent.replace(/\\s+/g, ' ').trim().slice(0, 80) : null,
    };
  })()`);
  const pshot = await cdp.send('Page.captureScreenshot', { format: 'png' });
  await fsp.writeFile(path.join(OUT, 'portrait.png'), Buffer.from(pshot.data, 'base64'));
  console.log(
    `\n竖屏：提示层 ${portrait.displayed ? '显示' : '隐藏'}（存在=${portrait.exists}）— "${portrait.text}"`,
  );

  console.log('\n--- 汇总 ---');
  console.log('  引擎存在        : ' + ready.engine);
  console.log('  html.className  : ' + (ready.htmlClass || '(空)'));
  console.log('  叠加层顶栏      : ' + (ready.topbar ? '已插入' : '未插入'));
  console.log('  竖屏提示层      : ' + (ready.rotateIsDisplayed === null ? '不存在' : ready.rotateIsDisplayed ? '显示' : '隐藏'));
  console.log('  #shell display  : ' + ready.shellDisplay + '   （手机层应为 block）');
  console.log('  时长            : ' + ready.duration + ' s');
  console.log('  黑屏帧          : ' + rows.filter((r) => r.stats.mean < 1.5 && r.stats.max < 40).length + '/' + rows.length);
  console.log('  页面错误        : ' + (problems.length ? '\n    ' + problems.join('\n    ') : '无'));
  console.log('  轻点后播放      : ' + (playing.running && playing.paused === false ? '是' : '否 ' + JSON.stringify(playing)));
  console.log('  歌词三档        : ' + (lyricsOK
    ? '原文 → 译文 → 关闭 → 原文 均正确'
    : '!! 有档位不对\n    ' + JSON.stringify(lyricStates)));
  console.log('  换行无英文闪帧  : ' + (noFlashOK ? '是' : '!! ' + JSON.stringify(noFlash)));
  console.log('  顶栏不溢出      : ' + (barOK ? '是' : '!! 最后一个按钮撞到了右侧读数 ' + JSON.stringify(barFit)));
  console.log('  隐藏界面留字幕  : ' + (hideOK
    ? '是（面板 ' + whileHidden.panel + '，控制条 ' + whileHidden.ctrl + '，顶栏 ' + whileHidden.hud + '）'
    : '!! 字幕跟着控制条一起消失了\n    ' + JSON.stringify(whileHidden)));
  console.log('  截图目录        : ' + OUT);

  cdp.ws.close();
  proc.kill();
  server.close();
  await fsp.rm(profile, { recursive: true, force: true }).catch(() => {});

  const fatal = !ready.engine || problems.some((p) => p.includes('异常')) ||
    !lyricsOK || !noFlashOK || !barOK || !hideOK ||
    rows.filter((r) => r.stats.mean < 1.5 && r.stats.max < 40).length > rows.length / 2;
  process.exit(fatal ? 1 : 0);
}

main().catch((e) => {
  console.error('预览失败：' + (e && e.stack ? e.stack : e));
  process.exit(1);
});
