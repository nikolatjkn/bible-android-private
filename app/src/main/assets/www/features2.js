/* Study tools: verse of the day, search + word count, compare translations, memorize (spaced repetition), prayer journal, reading plan. */
(function () {
  const K = 'bs_feat2';
  const def = { cards: [], prayers: [], plan: null, votd: '' };
  let D; try { D = Object.assign({}, def, JSON.parse(localStorage.getItem(K) || '{}')); } catch (e) { D = Object.assign({}, def); }
  const save = () => { try { localStorage.setItem(K, JSON.stringify(D)); } catch (e) {} };
  const DAY = 864e5, today = () => Math.floor(Date.now() / DAY);
  const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  const BD = () => { try { return BIBLE_DATA; } catch (e) { return {}; } };
  const kjv = () => (BD().kjv || {}).books || {};
  const bookList = () => Object.keys(kjv());
  const vtxt = (ver, b, c, v) => { try { return BD()[ver].books[b].chapters[c][v - 1].replace(/^\d+\s*/, ''); } catch (e) { return ''; } };
  const parseRef = (s) => { const m = /^(.+?)\s+(\d+):(\d+)$/.exec((s || '').trim()); if (!m) return null; const b = bookList().find((x) => x.toLowerCase() === m[1].toLowerCase()); return b ? { b, c: m[2], v: +m[3] } : null; };
  const POOL = ['John 3:16', 'Psalms 23:1', 'Philippians 4:13', 'Romans 8:28', 'Jeremiah 29:11', 'Isaiah 41:10', 'Proverbs 3:5', 'Matthew 11:28', 'Joshua 1:9', 'Psalms 46:1', 'Romans 12:2', 'Isaiah 40:31', 'John 14:6', 'Psalms 119:105', '2 Timothy 1:7', 'Matthew 6:33', 'Hebrews 11:1', 'Galatians 5:22', 'Psalms 27:1', 'Romans 5:8'];
  const st = document.createElement('style');
  st.textContent = '#bs2-btn{position:fixed;right:14px;bottom:140px;z-index:99999;width:44px;height:44px;border-radius:50%;border:0;background:#e0483e;color:#fff;font-size:20px;box-shadow:0 2px 8px #0006}#bs2-ov{position:fixed;inset:0;z-index:100000;background:#000b;display:none}#bs2-box{position:absolute;inset:24px 12px;background:#1c1f26;color:#fff;border-radius:14px;padding:14px;overflow:auto;font:15px/1.5 sans-serif}#bs2-box button{margin:3px 3px 3px 0;padding:8px 11px;border:0;border-radius:8px;background:#333a47;color:#fff}#bs2-box button.p{background:#4f8cff}#bs2-box input,#bs2-box textarea{width:100%;box-sizing:border-box;padding:9px;border-radius:8px;border:1px solid #444;background:#10131a;color:#fff;margin:4px 0}#bs2-box mark{background:#ffd479;color:#000}#bs2-box .it{border-bottom:1px solid #333;padding:8px 0}#bs2-box small{color:#9aa}';
  document.head.appendChild(st);
  let ov, box;
  const open = (h) => { box.innerHTML = '<button id="bs2-x" style="float:right">✕</button>' + h; ov.style.display = 'block'; box.querySelector('#bs2-x').onclick = () => { ov.style.display = 'none'; }; };
  const $ = (s) => box.querySelector(s);
  const on = (s, f) => { const e = $(s); if (e) e.onclick = f; };

  function home() {
    const due = D.cards.filter((c) => c.due <= today()).length;
    open('<h3>Study tools</h3><button class="p" id="a1">Verse of the day</button><button id="a2">Search &amp; word count</button><button id="a3">Compare translations</button><button id="a4">Memorize (' + due + ' due)</button><button id="a5">Prayer journal</button><button id="a6">Reading plan</button>');
    on('#a1', votd); on('#a2', search); on('#a3', () => compare('')); on('#a4', memo); on('#a5', prayer); on('#a6', plan);
  }
  function votd() {
    const r = parseRef(POOL[today() % POOL.length]); if (!r) return open('<p>Bible data not loaded yet.</p>');
    const vs = Object.keys(BD()).filter((k) => vtxt(k, r.b, r.c, r.v));
    open('<h3>Verse of the day</h3><b>' + esc(r.b + ' ' + r.c + ':' + r.v) + '</b>' + vs.slice(0, 3).map((k) => '<div class="it"><small>' + esc((BD()[k].abbrev || k)) + '</small><br>' + esc(vtxt(k, r.b, r.c, r.v)) + '</div>').join('') + '<button id="m">Memorize this</button><button id="h">Back</button>');
    on('#m', () => addCard(r.b + ' ' + r.c + ':' + r.v)); on('#h', home);
  }
  function search() {
    open('<h3>Search</h3><input id="q" placeholder="Word or phrase (KJV)"><button id="o">All</button><button id="ot">Old T.</button><button id="nt">New T.</button><div id="res"></div><button id="h">Back</button>');
    on('#h', home);
    const run = (f) => {
      const q = $('#q').value.trim(); if (q.length < 2) return;
      const re = new RegExp('\\b' + q.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'gi'); const bl = bookList(); let tot = 0, hits = []; const per = {};
      bl.forEach((b, i) => { if (f === 'ot' && i >= 39) return; if (f === 'nt' && i < 39) return;
        const ch = kjv()[b].chapters; Object.keys(ch).forEach((c) => ch[c].forEach((t, vi) => { const m = t.match(re); if (m) { tot += m.length; per[b] = (per[b] || 0) + m.length; hits.push([b, c, vi + 1, t.replace(/^\d+\s*/, '')]); } })); });
      const top = Object.entries(per).sort((a, b) => b[1] - a[1]).slice(0, 5).map((x) => x[0] + ' ' + x[1]).join(', ');
      $('#res').innerHTML = '<p><b>' + tot + '</b> times in <b>' + hits.length + '</b> verses.<br><small>Most in: ' + esc(top) + '</small></p>' + hits.slice(0, 40).map((h, i) => '<div class="it"><b>' + esc(h[0] + ' ' + h[1] + ':' + h[2]) + '</b><br>' + esc(h[3]).replace(new RegExp('\\b' + esc(q), 'gi'), (x) => '<mark>' + x + '</mark>') + '<br><button data-c="' + esc(h[0] + ' ' + h[1] + ':' + h[2]) + '">Compare</button><button data-m="' + esc(h[0] + ' ' + h[1] + ':' + h[2]) + '">Memorize</button></div>').join('');
      box.querySelectorAll('[data-c]').forEach((b) => b.onclick = () => compare(b.dataset.c)); box.querySelectorAll('[data-m]').forEach((b) => b.onclick = () => addCard(b.dataset.m));
    };
    on('#o', () => run('all')); on('#ot', () => run('ot')); on('#nt', () => run('nt'));
  }
  function compare(ref) {
    open('<h3>Compare translations</h3><input id="r" placeholder="e.g. John 3:16" value="' + esc(ref) + '"><button class="p" id="go">Show</button><div id="out"></div><button id="h">Back</button>'); on('#h', home);
    const go = () => { const r = parseRef($('#r').value); if (!r) { $('#out').innerHTML = '<p>Use format: Book 3:16 (English book name).</p>'; return; }
      $('#out').innerHTML = Object.keys(BD()).map((k) => { const t = vtxt(k, r.b, r.c, r.v); return t ? '<div class="it"><small>' + esc(BD()[k].name || k) + '</small><br>' + esc(t) + '</div>' : ''; }).join('') || '<p>Not found.</p>'; };
    on('#go', go); if (ref) go();
  }
  function addCard(ref) {
    if (!parseRef(ref)) return;
    if (!D.cards.some((c) => c.ref === ref)) D.cards.push({ ref, box: 0, due: today() });
    save(); memo();
  }
  function memo() {
    const c = D.cards.filter((x) => x.due <= today())[0];
    if (!c) return open('<h3>Memorize</h3><p>' + (D.cards.length ? 'All done for today! 🎉' : 'No verses yet. Add one from Search or Verse of the day.') + '</p><small>' + D.cards.length + ' verses saved</small><br><button id="h">Back</button>'), on('#h', home);
    const r = parseRef(c.ref), full = vtxt('kjv', r.b, r.c, r.v), w = full.split(' ');
    const masked = w.map((x, i) => (c.box >= 3 || i % 3 !== 0) ? '_'.repeat(Math.min(x.length, 8)) : x).join(' ');
    open('<h3>' + esc(c.ref) + '</h3><p id="t">' + esc(masked) + '</p><button id="rv">Reveal</button><button class="p" id="ok">Got it</button><button id="no">Missed</button><br><small>Level ' + c.box + ' · ' + D.cards.filter((x) => x.due <= today()).length + ' due</small>');
    on('#rv', () => { $('#t').textContent = full; });
    on('#ok', () => { c.box = Math.min(c.box + 1, 5); c.due = today() + Math.pow(2, c.box - 1); save(); memo(); });
    on('#no', () => { c.box = 0; c.due = today() + 1; save(); memo(); });
  }
  function prayer() {
    const list = D.prayers.map((p, i) => '<div class="it">' + (p.ans ? '✅ ' : '🙏 ') + esc(p.t) + '<br><small>' + p.d + (p.ans ? ' → answered ' + p.ans : '') + '</small>' + (p.ans ? '' : '<br><button data-a="' + i + '">Mark answered</button>') + '<button data-x="' + i + '">Delete</button></div>').join('');
    open('<h3>Prayer journal</h3><textarea id="pt" rows="3" placeholder="Prayer request"></textarea><button class="p" id="ad">Add</button>' + list + '<br><button id="h">Back</button>'); on('#h', home);
    on('#ad', () => { const t = $('#pt').value.trim(); if (t) { D.prayers.unshift({ t, d: new Date().toLocaleDateString(), ans: '' }); save(); prayer(); } });
    box.querySelectorAll('[data-a]').forEach((b) => b.onclick = () => { D.prayers[+b.dataset.a].ans = new Date().toLocaleDateString(); save(); prayer(); });
    box.querySelectorAll('[data-x]').forEach((b) => b.onclick = () => { D.prayers.splice(+b.dataset.x, 1); save(); prayer(); });
  }
  function plan() {
    const chs = []; bookList().forEach((b) => Object.keys(kjv()[b].chapters).forEach((c) => chs.push(b + ' ' + c)));
    if (!chs.length) return open('<p>Bible data not loaded yet.</p>');
    if (!D.plan) { D.plan = { start: today(), done: {} }; save(); }
    const per = Math.ceil(chs.length / 365), d = Math.max(0, today() - D.plan.start), mine = chs.slice(d * per, d * per + per);
    let streak = 0, i = d; if (!D.plan.done[i]) i--; while (i >= 0 && D.plan.done[i]) { streak++; i--; }
    const missed = []; for (let k = 0; k < d; k++) if (!D.plan.done[k]) missed.push(k);
    open('<h3>1-year reading plan</h3><p>Day ' + (d + 1) + ' of 365 · 🔥 ' + streak + '-day streak</p><p><b>Today:</b> ' + esc(mine.join(', ') || 'Plan complete!') + '</p><button class="p" id="dn">' + (D.plan.done[d] ? 'Done ✓ (undo)' : 'Mark today read') + '</button>' + (missed.length ? '<p><small>' + missed.length + ' catch-up days behind. First: Day ' + (missed[0] + 1) + ' — ' + esc(chs.slice(missed[0] * per, missed[0] * per + per).join(', ')) + '</small></p><button id="cu">Mark that catch-up day read</button>' : '') + '<br><button id="rs">Restart plan</button><button id="h">Back</button>');
    on('#dn', () => { D.plan.done[d] = !D.plan.done[d]; save(); plan(); }); on('#h', home);
    on('#cu', () => { D.plan.done[missed[0]] = true; save(); plan(); }); on('#rs', () => { D.plan = null; save(); plan(); });
  }
  function build() {
    const b = document.createElement('button'); b.id = 'bs2-btn'; b.textContent = '✦'; ov = document.createElement('div'); ov.id = 'bs2-ov'; box = document.createElement('div'); box.id = 'bs2-box'; ov.appendChild(box);
    document.body.append(b, ov); b.onclick = home;
    if (D.votd !== String(today())) setTimeout(() => { if (Object.keys(kjv()).length) { D.votd = String(today()); save(); votd(); } }, 3500);
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', build); else build();
})();
