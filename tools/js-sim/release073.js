// 0.7.3 in a real DOM (jsdom): the live chat frame and page, and the fullscreen settle check.
const fs = require('fs');
const { JSDOM } = require('jsdom');
const [live, settle] = [2, 3].map(i => fs.readFileSync(process.argv[i], 'utf8'));
let failures = 0;
const check = (n, c) => { console.log((c ? 'PASS ' : 'FAIL ') + n); if (!c) failures++; };

// 1. Inside the chat frame: the list keeps its swipes, a touch is told to the page (once per 1.5 s, only to YouTube).
function frame(embed) {
  const dom = new JSDOM('<html><head></head><body><div id="item-scroller">msgs</div></body></html>', { url: 'https://www.youtube.com/live_chat?v=dQw4w9WgXcQ&embed_domain=' + embed, runScripts: 'outside-only', pretendToBeVisual: true });
  const w = dom.window;
  const posted = [];
  w.__fake = { top: {}, parent: { postMessage: (m, o) => posted.push([m, o]) } };
  const r = w.eval('(function(window){return ' + live + '})(window.__fake)');
  return { w, posted, r };
}
const f = frame('m.youtube.com');
check('frame: script stops as frame', f.r === 'frame');
check('frame: chat list keeps its swipes (overscroll contain)', f.w.getComputedStyle(f.w.document.getElementById('item-scroller')).overscrollBehavior === 'contain' ||
  /overscroll-behavior:contain/.test(f.w.document.querySelector('style').textContent));
f.w.document.dispatchEvent(new f.w.Event('touchstart'));
f.w.document.dispatchEvent(new f.w.Event('touchstart'));
check('frame: touch told to the page once', f.posted.length === 1 && f.posted[0][0] === 'ss-chat-touch' && f.posted[0][1] === 'https://m.youtube.com');
const bad = frame('evil.example');
bad.w.document.dispatchEvent(new bad.w.Event('touchstart'));
check('frame: never told to another site', bad.posted.length === 0);

// 2. The page: a touch on the chat moves the page so the card is on screen below the player.
const html = `<html><head></head><body><ytm-watch><div class="player-container" id="pc"><div id="movie_player" class="html5-video-player"><video></video></div></div>
<div class="slim-video-metadata" id="meta">description</div></ytm-watch><div><ytm-video-with-context-renderer>rec</ytm-video-with-context-renderer></div></body></html>`;
const dom = new JSDOM(html, { url: 'https://m.youtube.com/watch?v=dQw4w9WgXcQ', runScripts: 'outside-only', pretendToBeVisual: true });
const w = dom.window, d = w.document;
d.getElementById('movie_player').getVideoData = () => ({ isLive: true });
const diags = [];
w.streamShellSponsor = { postMessage: m => diags.push(m) };
const scrolls = [];
w.scrollBy = o => scrolls.push(o);
Object.defineProperty(w, 'innerHeight', { value: 800, configurable: true });
w.eval(live);
const box = d.getElementById('streamshell-live-chat');
check('page: chat card made', !!box);
d.getElementById('pc').getBoundingClientRect = () => ({ top: 0, bottom: 230, height: 230 });
box.getBoundingClientRect = () => ({ top: 600, bottom: 1064, height: 464 });
w.dispatchEvent(new w.MessageEvent('message', { data: 'ss-chat-touch', origin: 'https://evil.example' }));
check('page: message from another site ignored', scrolls.length === 0);
w.dispatchEvent(new w.MessageEvent('message', { data: 'ss-chat-touch', origin: 'https://www.youtube.com' }));
check('page: moves up so the card is on screen (below the player)', scrolls.length === 1 && scrolls[0].top === 264 + 8 && scrolls[0].behavior === 'smooth');
check('page: logged', diags.some(m => /^diag\|live chat: touch/.test(m)));
box.getBoundingClientRect = () => ({ top: 240, bottom: 704, height: 464 });
w.dispatchEvent(new w.MessageEvent('message', { data: 'ss-chat-touch', origin: 'https://www.youtube.com' }));
check('page: card already on screen: nothing moves', scrolls.length === 1);

// 3. Fullscreen settle: picture moved up by a leftover translate.
function fs3(offset, full = true) {
  const dm = new JSDOM('<html><body><div id="fs"><div id="wrap" style="transform: translateY(' + offset + 'px)"><video id="v"></video></div></div></body></html>', { url: 'https://m.youtube.com/watch?v=dQw4w9WgXcQ', runScripts: 'outside-only', pretendToBeVisual: true });
  const ww = dm.window, dd = ww.document;
  const fsEl = dd.getElementById('fs'), wrap = dd.getElementById('wrap');
  Object.defineProperty(dd, 'fullscreenElement', { value: full ? fsEl : null });
  fsEl.getBoundingClientRect = () => ({ top: 0, height: 400 });
  dd.getElementById('v').getBoundingClientRect = () => { const m = /translateY\((-?\d+)px\)/.exec(wrap.style.transform || ''); return { top: 50 + (m ? +m[1] : 0), height: 300 }; };
  return { r: ww.eval(settle), wrap };
}
check('settle: in place = ok', fs3(0).r === 'ok');
const moved = fs3(-36);
check('settle: moved picture put back and reported', moved.r === 'moved|-36|0' && moved.wrap.style.transform === '');
check('settle: not fullscreen = none', fs3(-36, false).r === 'none');

if (failures) { console.log(failures + " failed"); process.exit(1); }
process.exit(0);
