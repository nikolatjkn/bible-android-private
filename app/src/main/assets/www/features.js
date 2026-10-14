/* Extra features: reading comfort, share-as-image, backup reminder. Self-contained, touches no app internals. */
(function () {
  const LS = 'bs_feat_v1';
  const load = () => { try { return Object.assign({ size: 100, line: 1.7, theme: 'default', awake: false, lastBackup: Date.now() }, JSON.parse(localStorage.getItem(LS) || '{}')); } catch (e) { return { size: 100, line: 1.7, theme: 'default', awake: false, lastBackup: Date.now() }; } };
  let S = load();
  const save = () => { try { localStorage.setItem(LS, JSON.stringify(S)); } catch (e) {} };
  const st = document.createElement('style'); document.head.appendChild(st);
  const THEMES = { default: '', sepia: 'body,#root{background:#f4ecd8!important;color:#3b2f1e!important}', night: 'body,#root{background:#000!important;color:#d8d8d8!important}' };
  let wake = null;
  async function setAwake() {
    try { if (S.awake && navigator.wakeLock) wake = await navigator.wakeLock.request('screen'); else if (wake) { await wake.release(); wake = null; } } catch (e) {}
  }
  function apply() {
    st.textContent = '.verse-text{font-size:' + S.size + '%!important;line-height:' + S.line + '!important}' + (THEMES[S.theme] || '') +
      '#bsf-btn{position:fixed;right:14px;bottom:84px;z-index:99999;width:44px;height:44px;border-radius:50%;border:0;background:#4f8cff;color:#fff;font-size:20px;box-shadow:0 2px 8px #0006}' +
      '#bsf-panel{position:fixed;right:14px;bottom:136px;z-index:99999;width:250px;padding:14px;border-radius:14px;background:#1c1f26;color:#fff;font:14px sans-serif;box-shadow:0 4px 18px #0008;display:none}' +
      '#bsf-panel label{display:block;margin:10px 0 4px}#bsf-panel input[type=range]{width:100%}' +
      '#bsf-panel button{margin:4px 4px 0 0;padding:7px 10px;border:0;border-radius:8px;background:#333a47;color:#fff}' +
      '#bsf-toast{position:fixed;left:12px;right:12px;bottom:20px;z-index:99999;padding:12px;border-radius:10px;background:#1c1f26;color:#fff;font:14px sans-serif}';
  }
  function wrap(ctx, text, w) {
    const words = text.split(/\s+/), lines = []; let cur = '';
    for (const wd of words) { const t = cur ? cur + ' ' + wd : wd; if (ctx.measureText(t).width > w && cur) { lines.push(cur); cur = wd; } else cur = t; }
    if (cur) lines.push(cur); return lines;
  }
  async function shareImage() {
    let txt = (window.getSelection() || '').toString().trim();
    if (!txt) txt = (prompt('Select verse text first, or paste it here:') || '').trim();
    if (!txt) return;
    const c = document.createElement('canvas'); c.width = c.height = 1080; const x = c.getContext('2d');
    const g = x.createLinearGradient(0, 0, 1080, 1080); g.addColorStop(0, '#1d3557'); g.addColorStop(1, '#0b132b');
    x.fillStyle = g; x.fillRect(0, 0, 1080, 1080); x.fillStyle = '#fff'; x.textAlign = 'center';
    let fs = 56; x.font = fs + 'px sans-serif'; let lines = wrap(x, txt, 880);
    while (lines.length * fs * 1.45 > 800 && fs > 26) { fs -= 4; x.font = fs + 'px sans-serif'; lines = wrap(x, txt, 880); }
    const top = 540 - (lines.length * fs * 1.45) / 2;
    lines.forEach((l, i) => x.fillText(l, 540, top + i * fs * 1.45));
    x.fillStyle = '#ffd479'; x.font = '30px sans-serif'; x.fillText('Bible Study', 540, 1020);
    c.toBlob(async (b) => {
      const f = new File([b], 'verse.png', { type: 'image/png' });
      try { if (navigator.canShare && navigator.canShare({ files: [f] })) { await navigator.share({ files: [f] }); return; } } catch (e) {}
      const a = document.createElement('a'); a.href = URL.createObjectURL(b); a.download = 'verse.png'; a.click();
    });
  }
  function toast(msg) { const d = document.createElement('div'); d.id = 'bsf-toast'; d.textContent = msg; d.onclick = () => d.remove(); document.body.appendChild(d); setTimeout(() => d.remove(), 9000); }
  function build() {
    const btn = document.createElement('button'); btn.id = 'bsf-btn'; btn.textContent = 'Aa';
    const p = document.createElement('div'); p.id = 'bsf-panel';
    p.innerHTML = '<b>Reading tools</b><label>Text size <span id="bsf-sv"></span></label><input id="bsf-s" type="range" min="80" max="200" step="5">' +
      '<label>Line spacing</label><input id="bsf-l" type="range" min="1.3" max="2.4" step="0.1">' +
      '<label>Theme</label><button data-t="default">Default</button><button data-t="sepia">Sepia</button><button data-t="night">Night</button>' +
      '<label><input id="bsf-a" type="checkbox"> Keep screen awake</label><button id="bsf-share">Share selected verse as image</button><button id="bsf-bk">I backed up</button>';
    document.body.append(btn, p);
    const $ = (i) => p.querySelector('#' + i);
    const sync = () => { $('bsf-s').value = S.size; $('bsf-l').value = S.line; $('bsf-a').checked = S.awake; $('bsf-sv').textContent = S.size + '%'; };
    btn.onclick = () => { p.style.display = p.style.display === 'block' ? 'none' : 'block'; sync(); };
    $('bsf-s').oninput = (e) => { S.size = +e.target.value; save(); apply(); sync(); };
    $('bsf-l').oninput = (e) => { S.line = +e.target.value; save(); apply(); };
    p.querySelectorAll('[data-t]').forEach((b) => b.onclick = () => { S.theme = b.dataset.t; save(); apply(); });
    $('bsf-a').onchange = (e) => { S.awake = e.target.checked; save(); setAwake(); };
    $('bsf-share').onclick = shareImage;
    $('bsf-bk').onclick = () => { S.lastBackup = Date.now(); save(); toast('Backup reminder reset.'); };
    document.addEventListener('visibilitychange', () => { if (!document.hidden) setAwake(); });
    setAwake();
    if (Date.now() - S.lastBackup > 14 * 864e5 && (localStorage.getItem('bible_notes') || localStorage.getItem('bible_highlights')))
      setTimeout(() => toast('Reminder: back up your notes and highlights (Settings). Tap to dismiss.'), 4000);
  }
  apply();
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', build); else build();
})();
