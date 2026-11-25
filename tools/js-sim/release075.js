// 0.7.5 (jsdom): the empty-page trace stays quiet in the mini player and fullscreen; inner scrolls are named.
const fs = require('fs');
const { JSDOM } = require('jsdom');
const [extras, cfgTrace] = [2, 3].map(i => fs.readFileSync(process.argv[i], 'utf8'));
let failures = 0;
const check = (n, c) => { console.log((c ? 'PASS ' : 'FAIL ') + n); if (!c) failures++; };
const dom = new JSDOM('<html class="ss-mini"><head></head><body><ytm-app><div id="shelf" style="overflow:auto">x</div></ytm-app></body></html>', { url: 'https://m.youtube.com/watch?v=aaaaaaaaaaa', runScripts: 'outside-only', pretendToBeVisual: true });
const w = dom.window, d = w.document, posted = [];
w.streamShellSponsor = { postMessage: m => posted.push(m) };
d.elementFromPoint = () => d.querySelector('ytm-app'); // everything but the player is black in the mini player
w.eval(cfgTrace); w.eval(extras);
const lines = () => posted.filter(m => m.startsWith('diag|')).map(m => m.slice(5));
d.getElementById('shelf').dispatchEvent(new w.Event('scroll', { bubbles: false }));
setTimeout(() => {
  check('mini player: no "page empty"', !lines().some(l => l.startsWith('page empty')));
  check('inner scroll named', lines().some(l => /^inner scroll in \d+ ms$/.test(l)) && !lines().some(l => /^scroll 0 px/.test(l)));
  d.documentElement.classList.remove('ss-mini');
  setTimeout(() => {
    check('normal page: empty page still found', lines().some(l => l.startsWith('page empty for 2 s')));
    if (failures) { console.log(failures + ' failed'); process.exit(1); }
    process.exit(0);
  }, 2700);
}, 2700);
