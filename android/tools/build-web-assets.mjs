#!/usr/bin/env node
/* ============================================================================
   android/tools/build-web-assets.mjs

   Copies the film into the Android app and injects the phone layer.

   The original project is NEVER written to. This script only reads from the
   repository root and writes into android/app/src/main/assets/web/. Run it
   with `npm run web` from android/, or `node tools/build-web-assets.mjs`.

   What it does, in order:

     1. wipes the destination and copies index.html, assets/, src/ and the MP3;
     2. adds a second copy of the MP3 named `audio.mp3` and rewrites the film's
        CLIP_CANDIDATES list to try it first. The original file is called
        "Mili - world.execute (me) ;.mp3" — two spaces and a semicolon, which
        survives a zip, an APK and a filesystem about equally badly;
     3. copies the overlay into web/mobile/ and references it from the copy of
        index.html — perf-head.js in the head (it must run before the engine
        reads prefers-reduced-motion), mobile.css after the film's stylesheet
        (so it can override), lyrics-zh.js then mobile.js after the last engine
        script;
     4. compiles the Chinese subtitle sheet into mobile/lyrics-zh.js, pairing it
        to the film's cue table by timestamp and failing the build if the two
        sheets have drifted apart;
     5. writes a manifest of SHA-256 hashes so the app can tell whether the
        files already unpacked on a device are still current;
     6. re-reads everything it wrote and fails loudly if any anchor was missed.

   Step 6 matters more than it looks. Every patch below is a string insertion
   into a file this repository does not own. If the film is ever edited and an
   anchor moves, this script must stop the build — not produce an APK whose
   controls silently never appear.
   ========================================================================== */

import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ANDROID = path.resolve(HERE, '..');
const ROOT = path.resolve(ANDROID, '..');
const DEST = path.join(ANDROID, 'app', 'src', 'main', 'assets', 'web');

const ok = (m) => console.log('  \u2713 ' + m);
const step = (m) => console.log('\n\u25b8 ' + m);
const fail = (m) => { console.error('\n  \u2717 ' + m + '\n'); process.exit(1); };

/* ---- 1. source inventory ------------------------------------------------- */

function findFilm() {
  const src = path.join(ROOT, 'index.html');
  if (!fs.existsSync(src)) fail('找不到 ' + src + ' —— 请在仓库根目录运行本脚本');
  return src;
}

/** The MP3, whatever it happens to be called this week. */
function findAudio() {
  const named = fs.readdirSync(ROOT).filter((f) => /\.mp3$/i.test(f));
  if (!named.length) fail('仓库根目录下找不到任何 .mp3');
  /* Prefer the canonically-named original over any incidental file. */
  const want = named.find((f) => /world\.execute/i.test(f)) || named[0];
  if (named.length > 1) {
    console.log('  找到多个 mp3，使用: ' + want + '  （忽略 ' +
      named.filter((f) => f !== want).join(', ') + '）');
  }
  return path.join(ROOT, want);
}

function copyDir(from, to, filter) {
  fs.mkdirSync(to, { recursive: true });
  for (const e of fs.readdirSync(from, { withFileTypes: true })) {
    const s = path.join(from, e.name);
    const d = path.join(to, e.name);
    if (e.isDirectory()) copyDir(s, d, filter);
    else if (!filter || filter(e.name)) fs.copyFileSync(s, d);
  }
}

/* ---- 2. the actual build ------------------------------------------------- */

fs.rmSync(DEST, { recursive: true, force: true });
fs.mkdirSync(DEST, { recursive: true });

const filmSrc = findFilm();
const audioSrc = findAudio();
console.log('world.execute(me); \u2192 android assets');
console.log('  源: ' + ROOT);
console.log('  目标: ' + DEST);
console.log('  音频: ' + path.basename(audioSrc) +
            '  (' + (fs.statSync(audioSrc).size / 1048576).toFixed(1) + ' MB)');

step('复制影片');
copyDir(path.join(ROOT, 'assets'), path.join(DEST, 'assets'), (n) => n.endsWith('.js'));
copyDir(path.join(ROOT, 'src'), path.join(DEST, 'src'), (n) => n.endsWith('.js'));
copyDir(path.join(ROOT, 'src'), path.join(DEST, 'src'), (n) => n.endsWith('.css'));
fs.copyFileSync(filmSrc, path.join(DEST, 'index.html'));
fs.copyFileSync(audioSrc, path.join(DEST, 'audio.mp3'));
ok('index.html + assets/ + src/ + audio.mp3');

step('复制手机叠加层');
/* Only what the page can actually load. tools/web-overlay/ also holds the
   Chinese subtitle source, which is compiled into lyrics-zh.js below and has no
   business riding along in the APK as a second copy of itself. */
copyDir(path.join(HERE, 'web-overlay'), path.join(DEST, 'mobile'),
  (n) => n.endsWith('.js') || n.endsWith('.css'));
ok('mobile/perf-head.js, mobile/mobile.css, mobile/mobile.js');

/* ---- 2b. the Chinese subtitle track --------------------------------------
   tools/web-overlay/lyrics.zh.txt is a fan translation of the film's own
   lyric sheet, supplied line for line and timestamp for timestamp. The overlay
   cannot read it directly: the film loads over file:// on a phone and a fetch
   there is a CORS refusal, so the table is compiled into a script.

   THE TIMESTAMPS ARE THE INTERFACE. Nothing joins these two files by index or
   by text; a line is paired with a cue because the two disagree about when it
   is sung by less than a millisecond. That is what makes the check below worth
   running on every build: if either sheet is ever re-timed, the pairing is
   silently wrong rather than loudly broken, and the viewer just sees the wrong
   translation under the right words.                                */

step('编译中文歌词表');

const zhSrc = path.join(HERE, 'web-overlay', 'lyrics.zh.txt');
const enSrc = path.join(ROOT, 'assets', 'lrc-embed.txt');

if (!fs.existsSync(zhSrc)) fail('找不到 ' + zhSrc);
if (!fs.existsSync(enSrc)) fail('找不到 ' + enSrc);

/** `[mm:ss.mmm]text` -> {t, text}, in seconds. Every other line is skipped. */
function parseLRC(text) {
  const out = [];
  for (const raw of text.split(/\r?\n/)) {
    const m = /^\[(\d+):(\d+(?:\.\d+)?)\](.*)$/.exec(raw.trim());
    if (!m) continue;
    out.push({ t: (+m[1]) * 60 + parseFloat(m[2]), text: m[3].trim() });
  }
  return out;
}

const enLines = parseLRC(fs.readFileSync(enSrc, 'utf8'));
const zhLines = parseLRC(fs.readFileSync(zhSrc, 'utf8'));
const credit = (/^\[by:(.*)\]$/m.exec(fs.readFileSync(zhSrc, 'utf8')) || [, ''])[1].trim();

if (enLines.length < 100) fail('英文歌词只解析出 ' + enLines.length + ' 行，文件不对');
if (zhLines.length < enLines.length * 0.5) {
  fail('中文歌词 ' + zhLines.length + ' 行，英文 ' + enLines.length + ' 行 —— 差得太多，多半拿错了文件');
}

/* The cue table the film actually renders from is generated from lrc-embed.txt
   by _build/gen-timeline.js. If that generator ever changed the times, the
   sheet on disk and the film would disagree and this script would happily
   compile a table timed to the wrong thing. So the two are compared. */
const tlPath = path.join(DEST, 'assets', 'timeline.js');
if (!fs.existsSync(tlPath)) fail('复制后找不到 assets/timeline.js');
const tlTimes = [];
for (const m of fs.readFileSync(tlPath, 'utf8').matchAll(/^\s*\[(\d+(?:\.\d+)?),\s*"/gm)) {
  tlTimes.push(parseFloat(m[1]));
}
if (tlTimes.length !== enLines.length) {
  fail('assets/timeline.js 有 ' + tlTimes.length + ' 个 cue，lrc-embed.txt 有 ' +
       enLines.length + ' 行 —— 影片的 cue 表不是从这份歌词生成的，先查清楚再继续');
}
let drift = 0;
for (let i = 0; i < tlTimes.length; i++) drift = Math.max(drift, Math.abs(tlTimes[i] - enLines[i].t));
if (drift > 0.0005) {
  fail('assets/timeline.js 与 lrc-embed.txt 的时间轴最大偏差 ' + drift.toFixed(4) +
       's —— 两份文件不是同一版，中文表会挂在错误的时刻上');
}
ok('cue 表与 lrc-embed.txt 一致（' + enLines.length + ' 个 cue，最大偏差 ' + drift.toFixed(4) + 's）');

/* Pairing report. Not fatal when it is only the film's own stage directions
   that went untranslated — those are reported and left to fall through to the
   film's English. */
const zhByT = new Map(zhLines.map((l) => [l.t.toFixed(3), l.text]));
const enTimes = new Set(enLines.map((l) => l.t.toFixed(3)));
const enOnly = enLines.filter((l) => !zhByT.has(l.t.toFixed(3)));
const zhOnly = zhLines.filter((l) => !enTimes.has(l.t.toFixed(3)));
const exact = enLines.length - enOnly.length;

if (exact < enLines.length * 0.75) {
  fail('只有 ' + exact + '/' + enLines.length +
       ' 行按时间戳配对成功 —— 中文歌词的时间轴和影片对不上');
}
ok('按时间戳配对 ' + exact + '/' + enLines.length + ' 行');
for (const l of enOnly) {
  console.log('      · 英 ' + l.t.toFixed(3) + 's  "' + l.text + '"  ' +
              (l.text.charAt(0) === '(' ? '（影片自己的说明行，中文表未覆盖，将回落英文）'
                                        : '（中文表没有这一行，将回落英文）'));
}
for (const l of zhOnly) {
  console.log('      · 中 ' + l.t.toFixed(3) + 's  "' + l.text +
              '"  （与相邻英文行同属一个 cue，中文分得更细）');
}

if (!credit) console.log('  注意: 中文表没有 [by:...] 署名行');
else ok('署名: ' + credit);

/* The generated script is loaded by the phone layer. Plain assignment, one
   file, no module syntax — it has to work under file://. */
const zhJs =
  '/* Generated by android/tools/build-web-assets.mjs from\n' +
  '   android/tools/web-overlay/lyrics.zh.txt (+ a cross-check against the\n' +
  '   film\'s assets/lrc-embed.txt). Do not edit: your change will be\n' +
  '   overwritten on the next build. Edit the .txt instead.\n\n' +
  '   Translation credit: ' + (credit || '(none given)') + '\n' +
  '   [' + exact + '/' + enLines.length + ' of the film\'s cues have a line here at\n' +
  '   the same timestamp; the rest are the film\'s own (instrumental ...) and\n' +
  '   (outro) stage directions, which fall through to the English.]        */\n' +
  'window.DA_LYRICS_ZH = {\n' +
  '  credit: ' + JSON.stringify(credit) + ',\n' +
  '  lines: [\n' +
  zhLines.map((l) => '    [' + l.t.toFixed(3) + ', ' + JSON.stringify(l.text) + ']').join(',\n') + '\n' +
  '  ]\n' +
  '};\n';
fs.writeFileSync(path.join(DEST, 'mobile', 'lyrics-zh.js'), zhJs, 'utf8');
ok('mobile/lyrics-zh.js（' + zhLines.length + ' 行，' +
   (Buffer.byteLength(zhJs, 'utf8') / 1024).toFixed(1) + ' KB）');

/* ---- 3. patch the engine's clip list ------------------------------------- */

step('改写 60_app.js 的音频候选表');

const appPath = path.join(DEST, 'src', '60_app.js');
if (!fs.existsSync(appPath)) fail('复制后找不到 src/60_app.js');
let app = fs.readFileSync(appPath, 'utf8');

if (app.includes("'audio.mp3'") && /CLIP_CANDIDATES\s*=\s*\[\s*'audio\.mp3'/.test(app)) {
  ok('已经包含 audio.mp3，跳过');
} else {
  const re = /(CLIP_CANDIDATES\s*=\s*\[)/;
  if (!re.test(app)) {
    fail('在 src/60_app.js 里找不到 CLIP_CANDIDATES = [ 这个锚点。\n' +
         '    影片的音频加载改动过，需要先看一眼再更新本脚本。');
  }
  app = app.replace(re, "$1\n  'audio.mp3',          /* 由 build-web-assets.mjs 前置：APK 里不存在带空格和分号的原始文件名 */");
  fs.writeFileSync(appPath, app);
  if (!/CLIP_CANDIDATES\s*=\s*\[\s*\n\s*'audio\.mp3'/.test(app)) {
    fail('改写 60_app.js 后校验失败');
  }
  ok("CLIP_CANDIDATES 现在以 'audio.mp3' 开头");
}

/* ---- 4. inject the overlay into index.html ------------------------------- */

step('注入 index.html');

const idxPath = path.join(DEST, 'index.html');
let idx = fs.readFileSync(idxPath, 'utf8');

/** Insert `snippet` once, immediately after the first line containing `anchor`. */
function afterLine(html, anchor, snippet, label) {
  if (html.includes(snippet.trim())) { ok(label + ' 已存在，跳过'); return html; }
  const lines = html.split('\n');
  const i = lines.findIndex((l) => l.includes(anchor));
  if (i === -1) fail('在 index.html 里找不到锚点: ' + anchor + '  （' + label + '）');
  lines.splice(i + 1, 0, snippet);
  ok(label + ' \u2192 第 ' + (i + 2) + ' 行');
  return lines.join('\n');
}

idx = afterLine(idx, '<meta name="viewport"',
  '<script src="mobile/perf-head.js"></script>',
  'perf-head.js');

idx = afterLine(idx, 'href="src/style.css"',
  '<link rel="stylesheet" href="mobile/mobile.css">',
  'mobile.css');

if (idx.includes('mobile/mobile.js')) {
  ok('mobile.js 已存在，跳过');
} else {
  const lines = idx.split('\n');
  const i = lines.findIndex((l) => l.includes('src/60_app.js'));
  if (i === -1) fail('在 index.html 里找不到 <script src="src/60_app.js">');
  /* the subtitle table must be defined before the phone layer reads it */
  lines.splice(i + 1, 0,
    '<script src="mobile/lyrics-zh.js"></script>',
    '<script src="mobile/mobile.js"></script>');
  ok('lyrics-zh.js + mobile.js \u2192 第 ' + (i + 2) + ' 行起（引擎之后）');
  idx = lines.join('\n');
}

fs.writeFileSync(idxPath, idx);

/* ---- 5. verify the artefact, not the intention --------------------------- */

step('校验产物');

const checks = [
  ['perf-head.js 在 head 内、style.css 之前',
    () => idx.indexOf('mobile/perf-head.js') < idx.indexOf('href="src/style.css"') &&
          idx.indexOf('mobile/perf-head.js') > 0],
  ['mobile.css 在 style.css 之后（否则覆盖不了）',
    () => idx.indexOf('mobile/mobile.css') > idx.indexOf('href="src/style.css"')],
  ['mobile.css 在 </head> 之前',
    () => idx.indexOf('mobile/mobile.css') < idx.indexOf('</head>')],
  ['mobile.js 在 60_app.js 之后',
    () => idx.indexOf('mobile/mobile.js') > idx.indexOf('src/60_app.js')],
  ['mobile.js 在 </body> 之前',
    () => idx.indexOf('mobile/mobile.js') < idx.indexOf('</body>')],
  ['perf-head.js 在 head 内、body 之前',
    () => idx.indexOf('mobile/perf-head.js') < idx.indexOf('<body>')],
  ['lyrics-zh.js 在 mobile.js 之前（否则手机层读不到表）',
    () => idx.indexOf('mobile/lyrics-zh.js') > 0 &&
          idx.indexOf('mobile/lyrics-zh.js') < idx.indexOf('mobile/mobile.js')],
];
let bad = 0;
for (const [label, fn] of checks) {
  let pass = false;
  try { pass = !!fn(); } catch (e) { pass = false; }
  if (pass) ok(label); else { bad++; console.error('  \u2717 ' + label); }
}
if (bad) fail(bad + ' 项注入校验未通过');

/* Every file the page asks for must exist. Indexed against the copy, because
   that is the tree whose relative paths actually have to resolve. */
const referenced = [];
for (const m of idx.matchAll(/<script[^>]+src="([^"]+)"/g)) referenced.push(m[1]);
for (const m of idx.matchAll(/<link[^>]+href="([^"]+)"/g)) referenced.push(m[1]);
for (const rel of referenced) {
  if (/^(https?:)?\/\//.test(rel)) continue;
  const f = path.join(DEST, rel.split('?')[0]);
  if (!fs.existsSync(f)) fail('index.html 引用了 ' + rel + '，但产物里没有它');
}
ok(referenced.length + ' 个被引用的脚本/样式全部存在');

/* The overlay itself is plain classic script, like the engine. A stray `import`
   or `export` would be a syntax error under file:// and would take the page
   down with it. */
for (const rel of ['mobile/perf-head.js', 'mobile/lyrics-zh.js', 'mobile/mobile.js']) {
  const t = fs.readFileSync(path.join(DEST, rel), 'utf8');
  if (/^\s*(import|export)\s/m.test(t)) fail(rel + ' 用了 import/export，file:// 下会挂');
  try { new Function(t); } catch (e) { fail(rel + ' 语法错误: ' + e.message); }
}
ok('叠加层脚本语法检查通过（classic script）');

/* Loading it for real is the only way to know the subtitle table survived
   compilation: `new Function` proves it parses, not that it defines anything. */
const lyrSandbox = { window: {} };
new Function('window', fs.readFileSync(path.join(DEST, 'mobile', 'lyrics-zh.js'), 'utf8'))(lyrSandbox.window);
const emitted = lyrSandbox.window.DA_LYRICS_ZH;
if (!emitted || !Array.isArray(emitted.lines)) fail('lyrics-zh.js 求值后没有 DA_LYRICS_ZH.lines');
if (emitted.lines.length !== zhLines.length) {
  fail('lyrics-zh.js 里有 ' + emitted.lines.length + ' 行，编译前是 ' + zhLines.length + ' 行');
}
for (let i = 1; i < emitted.lines.length; i++) {
  if (!(emitted.lines[i][0] > emitted.lines[i - 1][0])) {
    fail('lyrics-zh.js 第 ' + i + ' 行的时间戳没有递增 —— 二分查找会返回错的行');
  }
}
ok('lyrics-zh.js 求值通过（' + emitted.lines.length + ' 行，时间戳严格递增）');

/* The patched engine must still be valid JavaScript -- a broken insertion here
   would be a blank page on every phone. */
try { new Function(fs.readFileSync(appPath, 'utf8')); }
catch (e) { fail('改写后的 60_app.js 语法错误: ' + e.message); }
ok('改写后的 60_app.js 语法检查通过');

/* ---- 6. manifest --------------------------------------------------------- */

step('写入清单');

function hash(p) {
  return crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
}

const files = [];
(function walk(dir, rel) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const s = path.join(dir, e.name);
    const r = rel ? rel + '/' + e.name : e.name;
    if (e.isDirectory()) walk(s, r);
    else files.push(r);
  }
})(DEST, '');

files.sort();
const manifest = {
  generator: 'android/tools/build-web-assets.mjs',
  film: path.basename(filmSrc),
  audio: 'audio.mp3',
  totalBytes: 0,
  files: {},
};
for (const f of files) {
  const p = path.join(DEST, f);
  manifest.files[f] = hash(p);
  manifest.totalBytes += fs.statSync(p).size;
}
manifest.id = crypto.createHash('sha256')
  .update(files.map((f) => f + ':' + manifest.files[f]).join('\n')).digest('hex').slice(0, 16);

fs.writeFileSync(path.join(DEST, 'build-manifest.json'),
  JSON.stringify(manifest, null, 2) + '\n');

for (const f of files) {
  const s = fs.statSync(path.join(DEST, f)).size;
  console.log('    ' + (s / 1024).toFixed(0).padStart(7) + ' KB  ' + f);
}
console.log('    ' + (fs.statSync(path.join(DEST, 'build-manifest.json')).size / 1024).toFixed(0)
  .padStart(7) + ' KB  build-manifest.json');

console.log('\n完成。资产 id = ' + manifest.id + '，共 ' +
            files.length + ' 个文件，' +
            (manifest.totalBytes / 1048576).toFixed(1) + ' MB。\n');
