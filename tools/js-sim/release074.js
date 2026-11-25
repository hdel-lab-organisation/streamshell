// 0.7.4 in a real DOM (jsdom): player-tab links, the bar's in-page switch, the empty-page trace, ads left alone in a
// silent tab.
const fs = require('fs');
const { JSDOM, VirtualConsole } = require('jsdom');
const [links, bar, extras, cfgTrace, ads] = [2, 3, 4, 5, 6].map(i => fs.readFileSync(process.argv[i], 'utf8'));
let failures = 0;
const check = (n, c) => { console.log((c ? 'PASS ' : 'FAIL ') + n); if (!c) failures++; };

// 1. Player tab: channel / search / list links go to the app, videos and other sites stay.
{
  const dom = new JSDOM(`<html><body><a id="ch" href="/@SomeChannel">c</a><a id="ch2" href="/channel/UCabc">c</a><a id="res" href="/results?search_query=x">r</a>
<a id="vid" href="/watch?v=aaaaaaaaaaa">v</a><a id="sh" href="/shorts/aaaaaaaaaaa">s</a><a id="ext" href="https://example.com/@x">e</a></body></html>`,
  { url: 'https://m.youtube.com/watch?v=bbbbbbbbbbb', runScripts: 'outside-only', virtualConsole: new VirtualConsole() }); // links not taken try to navigate: jsdom cannot
  const w = dom.window, d = w.document, posted = [];
  w.streamShellPage = { postMessage: m => posted.push(m) };
  w.eval(links);
  const click = id => { const e = new w.MouseEvent('click', { bubbles: true, cancelable: true }); d.getElementById(id).dispatchEvent(e); return e.defaultPrevented; };
  check('channel link goes to the app', click('ch') && posted[0] === 'https://m.youtube.com/@SomeChannel');
  check('channel id link goes to the app', click('ch2') && posted[1] === 'https://m.youtube.com/channel/UCabc');
  check('search goes to the app', click('res') && posted.length === 3);
  check('video link stays in the player', !click('vid') && posted.length === 3);
  check('Shorts link stays', !click('sh') && posted.length === 3);
  check('another site stays', !click('ext') && posted.length === 3);
  w.eval('window.__ssPlayerLinksOn=false;');
  check('switched off: nothing taken', !click('ch') && posted.length === 3);
}

// 2. Bar: in-page switch pushes the address and fires popstate.
{
  const dom = new JSDOM('<html><body></body></html>', { url: 'https://m.youtube.com/', runScripts: 'outside-only' });
  const w = dom.window;
  let pop = 0; w.addEventListener('popstate', () => pop++);
  const r = w.eval(bar);
  check('bar: in-page switch', r === 'ok' && w.location.pathname === '/feed/subscriptions' && pop === 1);
}

// 3. Trace: a page that stays empty 2 s and more is logged with its length.
{
  const dom = new JSDOM('<html><head></head><body><ytm-app><div id="c">x</div></ytm-app></body></html>', { url: 'https://m.youtube.com/@ch', runScripts: 'outside-only', pretendToBeVisual: true });
  const w = dom.window, d = w.document, posted = [];
  w.streamShellSponsor = { postMessage: m => posted.push(m) };
  let empty = true;
  d.elementFromPoint = () => (empty ? d.querySelector('ytm-app') : d.getElementById('c'));
  w.eval(cfgTrace); w.eval(extras);
  const lines = () => posted.filter(m => m.startsWith('diag|')).map(m => m.slice(5));
  setTimeout(() => {
    check('empty page: "still" line after 2 s', lines().some(l => l === 'page empty for 2 s on /@ch (still)'));
    empty = false;
    setTimeout(() => {
      check('empty page: length when content appears', lines().some(l => /^page empty \d{4} ms on \/@ch$/.test(l)));
      const n = lines().filter(l => l.startsWith('page empty')).length;
      setTimeout(() => {
        check('content: nothing more logged', lines().filter(l => l.startsWith('page empty')).length === n);
        part4();
      }, 1200);
    }, 800);
  }, 2800);
}

// 4. Ads: a silent tab (closed mini player) does not press Skip.
function part4() {
  const dom = new JSDOM(`<html><head></head><body><div class="html5-video-player ad-showing" id="movie_player"><video></video>
<button class="ytp-skip-ad-button" id="skip">Skip</button></div></body></html>`, { url: 'https://m.youtube.com/watch?v=aaaaaaaaaaa', runScripts: 'outside-only', pretendToBeVisual: true });
  const w = dom.window, d = w.document;
  let clicks = 0; d.getElementById('skip').addEventListener('click', () => clicks++);
  d.getElementById('skip').getClientRects = () => [{}];
  d.getElementById('skip').getBoundingClientRect = () => ({ left: 0, top: 0, width: 80, height: 30 });
  w.__ssSilent = true;
  w.eval(ads);
  setTimeout(() => {
    check('silent tab: Skip not pressed', clicks === 0);
    w.__ssSilent = false;
    setTimeout(() => {
      check('playing tab: Skip pressed', clicks >= 1);
      if (failures) { console.log(failures + ' failed'); process.exit(1); }
      process.exit(0);
    }, 1500);
  }, 1500);
}
