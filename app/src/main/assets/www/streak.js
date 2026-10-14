/* Reading streak + daily verse reminders (installed Android app only).
   - Tells the native side when you have really read today (widget + notifications use it).
   - Sends the next 30 days of verses so the widget/notification work without opening the app.
   - Floating streak pill (bottom-left) opens the streak screen: week view, stats, reminder settings. */
(function () {
  const A = window.AndroidStreak;
  if (!A) return; // web / iPhone version: nothing to do

  const SVG = {"flame": "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\"><path d=\"M12,2 C13,6 19,9 19,15 C19,19 16,22 12,22 C8,22 5,19 5,15 C5,12 7,10 8,8 C9,10 10,10.5 10.5,10 C10,7 10.5,4.5 12,2Z\" fill=\"#FF8A00\"/><path d=\"M12,22 C9.5,22 8,20.5 8,18.5 C8,16.5 10,15 11,13 C12,15 14,16 16,18.5 C16,20.5 14.5,22 12,22Z\" fill=\"#FFD23F\"/></svg>", "flame_off": "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\"><path d=\"M12,2 C13,6 19,9 19,15 C19,19 16,22 12,22 C8,22 5,19 5,15 C5,12 7,10 8,8 C9,10 10,10.5 10.5,10 C10,7 10.5,4.5 12,2Z\" fill=\"#6D7590\"/><path d=\"M12,22 C9.5,22 8,20.5 8,18.5 C8,16.5 10,15 11,13 C12,15 14,16 16,18.5 C16,20.5 14.5,22 12,22Z\" fill=\"#9AA2BA\"/></svg>"};
  // ---- Jesus photos replace the old owl mascot. Mood: 0 happy, 1 worried, 2 sad, 3 crying ----
  const PH = {
    boy:  [[1, 2, 4], [3, 13], [12, 9, 10], [16, 11, 18]],
    girl: [[7], [5], [17, 15, 10], [19, 8, 6]]
  };
  const GKEY = 'bs_gender';
  const getGender = () => { try { const g = localStorage.getItem(GKEY); return g === 'boy' || g === 'girl' ? g : ''; } catch (e) { return ''; } };
  const setGender = (g) => { try { localStorage.setItem(GKEY, g); } catch (e) {} try { A.setGender(g); } catch (e) {} };
  try { if (getGender()) A.setGender(getGender()); } catch (e) {}
  const photoUrl = (g, mood, day) => {
    const list = PH[g || 'boy'][Math.max(0, Math.min(3, mood))];
    return 'jesus/j' + String(list[Math.abs(day) % list.length]).padStart(2, '0') + '.jpg';
  };
  const DAY = 864e5;
  const localDay = (ms) => Math.floor((ms - new Date(ms).getTimezoneOffset() * 60000) / DAY);
  const CFG_KEY = 'bs_streak_cfg';
  let cfg = {};
  try { cfg = JSON.parse(localStorage.getItem(CFG_KEY) || '{}'); } catch (e) { cfg = {}; }
  const saveCfg = () => { try { localStorage.setItem(CFG_KEY, JSON.stringify(cfg)); } catch (e) {} };

  const POOL = ['John 3:16', 'Psalms 23:1', 'Philippians 4:13', 'Romans 8:28', 'Jeremiah 29:11', 'Isaiah 41:10',
    'Proverbs 3:5', 'Matthew 11:28', 'Joshua 1:9', 'Psalms 46:1', 'Romans 12:2', 'Isaiah 40:31', 'John 14:6',
    'Psalms 119:105', '2 Timothy 1:7', 'Matthew 6:33', 'Hebrews 11:1', 'Galatians 5:22', 'Psalms 27:1', 'Romans 5:8',
    'Psalms 121:1', 'Proverbs 16:3', 'Matthew 5:16', '1 Corinthians 13:4', 'Ephesians 2:8', 'Psalms 37:4',
    'Isaiah 26:3', 'John 15:5', 'Lamentations 3:22', 'Psalms 34:8'];

  const BD = () => { try { return BIBLE_DATA; } catch (e) { return {}; } };
  const books = (ver) => ((BD()[ver] || {}).books) || {};
  const findBook = (ver, name) => Object.keys(books(ver)).find((x) => x.toLowerCase() === name.toLowerCase());
  const verseText = (ver, ref) => {
    const m = /^(.+?)\s+(\d+):(\d+)$/.exec(ref); if (!m) return '';
    try {
      const b = findBook(ver, m[1]); if (!b) return '';
      return String(books(ver)[b].chapters[m[2]][m[3] - 1] || '').replace(/^\d+\s*/, '').trim();
    } catch (e) { return ''; }
  };
  const activeVer = () => {
    if (cfg.ver && BD()[cfg.ver]) return cfg.ver;
    try { const v = JSON.parse(localStorage.getItem('bible_active_version')); if (v && BD()[v]) return v; } catch (e) {}
    return 'kjv';
  };

  function syncVerses() {
    const ver = activeVer(), t0 = localDay(Date.now()), out = [];
    for (let i = 0; i < 30; i++) {
      const day = t0 + i, ref = POOL[day % POOL.length];
      const text = verseText(ver, ref) || verseText('kjv', ref);
      if (text) out.push({ d: day, ref: ref, text: text.length > 400 ? text.slice(0, 397) + '...' : text });
    }
    if (out.length) { try { A.setVerses(JSON.stringify(out)); } catch (e) {} return true; }
    return false;
  }
  let tries = 0;
  (function trySync() { if (!syncVerses() && ++tries < 8) setTimeout(trySync, 3000); })();
  document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'visible') { syncVerses(); refreshPill(); } });

  // ---- "I read today" detection: a chapter is open and the app has been in view for ~25s ----
  let visibleSecs = 0, markedDay = -1;
  setInterval(() => {
    if (document.visibilityState !== 'visible') return;
    visibleSecs += 5;
    const today = localDay(Date.now());
    if (visibleSecs < 25 || markedDay === today) return;
    try {
      const h = JSON.parse(localStorage.getItem('bible_history') || '[]');
      if (h[0] && h[0].timestamp && localDay(h[0].timestamp) === today) {
        A.markRead(); markedDay = today; refreshPill();
      }
    } catch (e) {}
  }, 5000);

  // ---- styles ----
  const ACC = ['#4ADE80', '#FFB020', '#8E9BFF', '#FF6B81'];
  const st = document.createElement('style');
  st.textContent = `
  #bsk-btn{position:fixed;left:14px;bottom:140px;z-index:99998;display:flex;align-items:center;gap:6px;padding:6px 14px 6px 9px;border-radius:999px;border:1px solid rgba(255,255,255,.22);background:rgba(16,20,40,.58);-webkit-backdrop-filter:blur(14px);backdrop-filter:blur(14px);color:#fff;font:800 16px system-ui,sans-serif;box-shadow:0 4px 18px rgba(0,0,0,.45),0 0 0 0 rgba(255,138,0,.5)}
  #bsk-btn svg{width:22px;height:22px}
  #bsk-btn.on{box-shadow:0 4px 18px rgba(0,0,0,.45),0 0 16px rgba(255,138,0,.45)}
  #bsk-btn.on svg{animation:bskflick 1.6s ease-in-out infinite}
  #bsk-ov{display:none;position:fixed;inset:0;z-index:100000;background:rgba(4,6,18,.6);-webkit-backdrop-filter:blur(10px);backdrop-filter:blur(10px)}
  #bsk-box{--acc:#4ADE80;position:absolute;left:0;right:0;bottom:0;max-height:94%;overflow:auto;border-radius:30px 30px 0 0;padding:20px 18px 28px;color:#fff;font:15px system-ui,sans-serif;background:linear-gradient(180deg,#27315c,#0e1226 70%);background:radial-gradient(120% 60% at 80% 0%,color-mix(in srgb,var(--acc) 28%,transparent),transparent 60%),linear-gradient(180deg,#27315c,#0e1226 70%);border-top:1px solid rgba(255,255,255,.18);box-shadow:0 -10px 40px rgba(0,0,0,.5);animation:bskup .32s cubic-bezier(.2,.9,.3,1)}
  #bsk-box .x{position:absolute;right:14px;top:14px;width:34px;height:34px;border-radius:50%;border:0;background:rgba(255,255,255,.12);color:#fff;font-size:16px}
  .bsk-hero{display:flex;align-items:center;justify-content:space-between;gap:8px;margin-top:6px}
  .bsk-num{display:flex;align-items:center;gap:6px}
  .bsk-num svg{width:50px;height:50px}.bsk-num.on svg{animation:bskflick 1.6s ease-in-out infinite;filter:drop-shadow(0 0 10px rgba(255,138,0,.7))}
  .bsk-num b{font-size:64px;line-height:1;font-weight:900;letter-spacing:-2px;background:linear-gradient(180deg,#fff,var(--acc));-webkit-background-clip:text;background-clip:text;color:transparent}
  .bsk-lbl{font-weight:800;font-size:15px;opacity:.9;margin-top:2px}.bsk-sub{opacity:.7;font-size:13px;margin-top:4px;max-width:190px}
  .bsk-owl{position:relative;width:124px;height:156px;flex:none}
  .bsk-owl:before{content:"";position:absolute;inset:-8px;border-radius:34px;background:radial-gradient(circle,rgba(74,222,128,.45),transparent 68%);background:radial-gradient(circle,color-mix(in srgb,var(--acc) 55%,transparent),transparent 68%)}
  .bsk-owl img{position:relative;width:100%;height:100%;object-fit:cover;object-position:50% 40%;border-radius:26px;border:2px solid rgba(255,255,255,.35);background:#fff;box-shadow:0 8px 18px rgba(0,0,0,.45);animation:bskbob 3.2s ease-in-out infinite}
  .bsk-sw{display:flex;justify-content:center;gap:8px;margin-top:12px}.bsk-sw button{padding:7px 16px;border-radius:999px;border:1px solid rgba(255,255,255,.22);background:rgba(255,255,255,.08);color:#fff;font:700 13px system-ui,sans-serif}.bsk-sw button.on{background:linear-gradient(90deg,#2bb7ff,#4f7cff);border-color:transparent}
  #bsk-who{display:none;position:fixed;inset:0;z-index:100001;background:radial-gradient(120% 70% at 50% 0%,#3a4a8a,#0e1226 70%);color:#fff;font:16px system-ui,sans-serif;text-align:center;overflow:auto}
  #bsk-who .in{min-height:100%;display:flex;flex-direction:column;align-items:center;justify-content:center;padding:28px 22px}
  #bsk-who h1{font-size:28px;margin:18px 0 6px;font-weight:900}#bsk-who p{opacity:.75;margin:0 0 22px;max-width:300px}
  #bsk-who .pic{width:200px;height:250px;border-radius:30px;object-fit:cover;border:3px solid rgba(255,255,255,.4);background:#fff;box-shadow:0 14px 34px rgba(0,0,0,.55)}
  #bsk-who .row{display:flex;gap:14px;width:100%;max-width:340px}
  #bsk-who .row button{flex:1;padding:20px 8px;border-radius:22px;border:1px solid rgba(255,255,255,.25);color:#fff;font:900 20px system-ui,sans-serif;background:rgba(255,255,255,.1)}
  #bsk-who .row button:active{transform:scale(.96)}
  #bsk-who .row button.b{background:linear-gradient(180deg,#3aa0ff,#2563eb)}#bsk-who .row button.g{background:linear-gradient(180deg,#ff7eb6,#e0457b)}
  .bsk-card{margin-top:16px;padding:14px;border-radius:20px;background:rgba(255,255,255,.07);border:1px solid rgba(255,255,255,.12)}
  .bsk-week{display:flex;justify-content:space-between}
  .bsk-d{display:flex;flex-direction:column;align-items:center;gap:6px;font-size:11px;font-weight:800;opacity:.9}
  .bsk-c{width:36px;height:36px;border-radius:50%;display:flex;align-items:center;justify-content:center;background:rgba(255,255,255,.08);border:1.5px solid rgba(255,255,255,.14)}
  .bsk-c.done{background:linear-gradient(180deg,#58e68f,#1fb860);border-color:#1fa860;box-shadow:0 0 14px rgba(74,222,128,.55)}
  .bsk-c.today{border:2px solid #fff;animation:bskpulse 1.8s infinite}
  .bsk-c.fut{background:transparent;border-style:dashed;opacity:.5}
  .bsk-c svg{width:20px;height:20px}
  .bsk-stats{display:flex;gap:10px;margin-top:12px}
  .bsk-stat{flex:1;padding:12px 8px;border-radius:16px;text-align:center;background:rgba(255,255,255,.07);border:1px solid rgba(255,255,255,.12)}
  .bsk-stat b{display:block;font-size:22px;font-weight:900}.bsk-stat span{font-size:11px;opacity:.65;font-weight:700;text-transform:uppercase;letter-spacing:.5px}
  .bsk-row{display:flex;align-items:center;justify-content:space-between;gap:10px;padding:9px 0}
  .bsk-row+.bsk-row{border-top:1px solid rgba(255,255,255,.08)}
  .bsk-row small{display:block;opacity:.6;font-size:12px}
  .bsk-row input[type=time],.bsk-row select{background:rgba(255,255,255,.1);color:#fff;border:1px solid rgba(255,255,255,.2);border-radius:12px;padding:8px 10px;font-size:15px;color-scheme:dark}
  .bsk-row select option{color:#000}
  .sw{position:relative;width:50px;height:30px;flex:none}.sw input{opacity:0;position:absolute;inset:0;width:100%;height:100%;margin:0;z-index:2}
  .sw i{position:absolute;inset:0;border-radius:99px;background:rgba(255,255,255,.18);transition:.2s}.sw i:after{content:"";position:absolute;left:3px;top:3px;width:24px;height:24px;border-radius:50%;background:#fff;transition:.2s;box-shadow:0 2px 6px rgba(0,0,0,.4)}
  .sw input:checked+i{background:linear-gradient(90deg,#3ddc84,#1fb860)}.sw input:checked+i:after{transform:translateX(20px)}
  .bsk-btn2{display:block;width:100%;margin-top:12px;padding:14px;border:0;border-radius:16px;font:800 15px system-ui,sans-serif;color:#fff;background:linear-gradient(90deg,#2bb7ff,#4f7cff);box-shadow:0 6px 18px rgba(60,120,255,.4)}
  .bsk-btn2.g{background:rgba(255,255,255,.1);box-shadow:none;border:1px solid rgba(255,255,255,.18)}
  #bsk-msg{margin-top:12px;text-align:center;font-size:12px;opacity:.65}
  @keyframes bskup{from{transform:translateY(60px);opacity:0}to{transform:none;opacity:1}}
  @keyframes bskbob{0%,100%{transform:translateY(0)}50%{transform:translateY(-6px)}}
  @keyframes bskflick{0%,100%{transform:scale(1) rotate(-2deg)}50%{transform:scale(1.12) rotate(2deg)}}
  @keyframes bskpulse{0%{box-shadow:0 0 0 0 rgba(255,255,255,.55)}70%{box-shadow:0 0 0 9px rgba(255,255,255,0)}100%{box-shadow:0 0 0 0 rgba(255,255,255,0)}}`;
  document.head.appendChild(st);

  const ov = document.createElement('div'); ov.id = 'bsk-ov';
  const box = document.createElement('div'); box.id = 'bsk-box';
  ov.appendChild(box); document.body.appendChild(ov);
  ov.addEventListener('click', (e) => { if (e.target === ov) ov.style.display = 'none'; });
  const btn = document.createElement('button'); btn.id = 'bsk-btn';
  document.body.appendChild(btn);

  const cfgNow = () => { try { return JSON.parse(A.getConfig()); } catch (e) { return {}; } };
  function refreshPill() {
    const c = cfgNow(), alive = (c.streak || 0) > 0;
    btn.className = alive ? 'on' : '';
    btn.innerHTML = (alive ? SVG.flame : SVG.flame_off) + '<span>' + (c.streak || 0) + '</span>';
  }
  refreshPill(); setInterval(refreshPill, 30000);

  const pad = (n) => String(n).padStart(2, '0');
  const NAMES = { kjv: 'KJV', tamil: 'Tamil', thngv: 'Tamil (THNGV)', myanmar: 'Myanmar', myanmar_judson: 'Myanmar (Judson)' };
  const LET = ['M', 'T', 'W', 'T', 'F', 'S', 'S'];
  const CHECK = '<svg viewBox="0 0 24 24"><path d="M5.5 12.5l4.2 4.2L18.5 7.5" fill="none" stroke="#fff" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"/></svg>';

  function render() {
    const c = cfgNow(), moodIdx = Math.max(0, Math.min(3, c.mood || 0));
    const days = c.days || [], today = c.today || localDay(Date.now());
    const idx = (new Date().getDay() + 6) % 7;
    let wk = '', weekDone = 0;
    for (let i = 0; i < 7; i++) {
      const d = today - idx + i, done = days.indexOf(d) >= 0;
      if (done) weekDone++;
      const cls = done ? 'done' : d === today ? 'today' : d > today ? 'fut' : '';
      wk += '<div class="bsk-d"><div class="bsk-c ' + cls + '">' + (done ? CHECK : '') + '</div>' + LET[i] + '</div>';
    }
    const alive = !!c.streak || (c.readToday);
    const vers = Object.keys(BD()), cur = activeVer();
    box.style.setProperty('--acc', ACC[moodIdx]);
    box.innerHTML =
      '<button class="x" id="bsk-x">\u2715</button>' +
      '<div class="bsk-hero"><div><div class="bsk-num ' + (c.streak ? 'on' : '') + '">' + (c.streak ? SVG.flame : SVG.flame_off) + '<b>' + (c.num != null ? c.num : 0) + '</b></div>' +
      '<div class="bsk-lbl">' + (c.label || 'day streak') + '</div><div class="bsk-sub">' + (c.sub || '') + '</div></div>' +
      '<div class="bsk-owl"><img alt="" src="' + photoUrl(getGender(), moodIdx, today) + '"></div></div>' +
      '<div class="bsk-card"><div class="bsk-week">' + wk + '</div></div>' +
      '<div class="bsk-sw"><button data-g="boy" class="' + (getGender() !== 'girl' ? 'on' : '') + '">Boy</button><button data-g="girl" class="' + (getGender() === 'girl' ? 'on' : '') + '">Girl</button></div>' +
      '<div class="bsk-stats"><div class="bsk-stat"><b>' + (c.best || 0) + '</b><span>Best</span></div>' +
      '<div class="bsk-stat"><b>' + (c.total || 0) + '</b><span>Days read</span></div>' +
      '<div class="bsk-stat"><b>' + weekDone + '/7</b><span>This week</span></div></div>' +
      '<div class="bsk-card" style="padding:6px 14px">' +
      '<div class="bsk-row"><div>Morning verse<small>Verse of the day notification</small></div><label class="sw"><input type="checkbox" id="bsk-m" ' + (c.morning ? 'checked' : '') + '><i></i></label></div>' +
      '<div class="bsk-row"><div>Morning time</div><input type="time" id="bsk-mt" value="' + pad(c.mh || 0) + ':' + pad(c.mm || 0) + '"></div>' +
      '<div class="bsk-row"><div>Evening nudge<small>Only if you have not read yet</small></div><label class="sw"><input type="checkbox" id="bsk-e" ' + (c.evening ? 'checked' : '') + '><i></i></label></div>' +
      '<div class="bsk-row"><div>Evening time</div><input type="time" id="bsk-et" value="' + pad(c.eh || 0) + ':' + pad(c.em || 0) + '"></div>' +
      '<div class="bsk-row"><div>Verse language</div><select id="bsk-v">' +
      vers.map((k) => '<option value="' + k + '"' + (k === cur ? ' selected' : '') + '>' + (NAMES[k] || k.toUpperCase()) + '</option>').join('') + '</select></div></div>' +
      '<button class="bsk-btn2" id="bsk-w">Add widget to home screen</button>' +
      '<button class="bsk-btn2 g" id="bsk-t">Send test notification</button>' +
      '<div id="bsk-msg">Reading counts when a chapter stays open for about 25 seconds.</div>';
    const q = (s) => box.querySelector(s);
    const push = () => {
      const mt = (q('#bsk-mt').value || '07:00').split(':'), et = (q('#bsk-et').value || '20:00').split(':');
      try { A.configure(JSON.stringify({ morning: q('#bsk-m').checked, mh: +mt[0], mm: +mt[1], evening: q('#bsk-e').checked, eh: +et[0], em: +et[1] })); } catch (e) {}
    };
    ['#bsk-m', '#bsk-mt', '#bsk-e', '#bsk-et'].forEach((s) => { q(s).onchange = push; });
    q('#bsk-v').onchange = () => { cfg.ver = q('#bsk-v').value; saveCfg(); syncVerses(); };
    box.querySelectorAll('.bsk-sw button').forEach((b) => { b.onclick = () => { setGender(b.dataset.g); render(); }; });
    q('#bsk-x').onclick = () => { ov.style.display = 'none'; };
    q('#bsk-t').onclick = () => { try { A.testNotification(); q('#bsk-msg').textContent = 'Sent. Check your notifications (allow notifications if nothing shows).'; } catch (e) {} };
    q('#bsk-w').onclick = () => {
      let ok = false; try { ok = A.requestPinWidget(); } catch (e) {}
      q('#bsk-msg').textContent = ok ? 'Confirm adding the widget on the popup.' : 'Your launcher cannot add it from here. Long-press the home screen, choose Widgets, find Bible Study.';
    };
  }
  btn.onclick = () => { render(); ov.style.display = 'block'; };

  // ---- first launch after install: ask boy or girl ----
  function askWho() {
    if (getGender()) return;
    const w = document.createElement('div'); w.id = 'bsk-who';
    w.innerHTML = '<div class="in"><img class="pic" alt="" src="jesus/j01.jpg"><h1>Welcome</h1><p>Are you a boy or a girl? Jesus will walk with you through your reading streak.</p>' +
      '<div class="row"><button class="b" data-g="boy">Boy</button><button class="g" data-g="girl">Girl</button></div></div>';
    document.body.appendChild(w); w.style.display = 'block';
    w.querySelectorAll('button').forEach((b) => { b.onclick = () => { setGender(b.dataset.g); w.remove(); }; });
  }
  askWho();
})();
