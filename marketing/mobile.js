/* Traced: phone behaviour (<900px). Runs only when html.m is set. */
(() => {
  if (!document.documentElement.classList.contains('m')) return;
  const $ = (s, r = document) => r.querySelector(s), $$ = (s, r = document) => [...r.querySelectorAll(s)];

  /* nav: menu button + panel from the same link list */
  const nav = $('#nav'), links = $('#links'), right = $('.right');
  const btn = document.createElement('button'); btn.className = 'mbtn'; btn.type = 'button'; btn.setAttribute('aria-expanded', 'false');
  btn.innerHTML = 'Menu <svg viewBox="0 0 12 12" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"><path d="M2 4.5 6 8.5l4-4"/></svg>';
  right.prepend(btn);
  const setOpen = o => { nav.classList.toggle('open', o); btn.setAttribute('aria-expanded', String(o)); };
  btn.addEventListener('click', () => setOpen(!nav.classList.contains('open')));
  links.addEventListener('click', e => { if (e.target.closest('a')) setOpen(false); });
  addEventListener('scroll', () => { if (nav.classList.contains('open')) setOpen(false); }, { passive: true });
  document.addEventListener('click', e => { if (!nav.contains(e.target)) setOpen(false); });
  new MutationObserver(() => nav.dataset.theme = nav.classList.contains('is-light') ? 'light' : 'dark').observe(nav, { attributes: true, attributeFilter: ['class'] });

  /* features: each card gets its own screen (clone of the matching demo); tail gets a screen under the list */
  const lap = $('#fbLap'), demos = $$('#fbLap .fb-demo'), cards = $$('.fb-card');
  cards.forEach((c, i) => {
    const d = demos[i]; if (!d) return;
    const win = document.createElement('div'); win.className = 'fb-win';
    const clone = d.cloneNode(true); clone.classList.add('on'); clone.style.opacity = '1'; clone.style.visibility = 'visible'; clone.style.position = 'relative'; clone.style.inset = 'auto'; clone.style.width = '100%'; clone.style.height = '100%';
    win.appendChild(clone); c.appendChild(win);
  });
  const tail = $('#fbTail'), tailPane = $('#fbTailPane'), list = $('#fbTailList');
  if (tail && tailPane) {
    const box = document.createElement('div'); box.className = 'fb-mshot';
    const clone = tailPane.cloneNode(true); clone.id = ''; clone.style.opacity = '1'; clone.style.visibility = 'visible'; clone.classList.add('on');
    box.appendChild(clone); const h3 = tail.querySelector('h3'); (h3 || tail.firstElementChild).after(box);
    const shots = $$('.fb-tshot', box), items = $$('li', list);
    const show = k => shots.forEach((s, i) => s.classList.toggle('on', i === k));
    items.forEach((li, k) => li.addEventListener('click', () => show(k)));
    show(0);
  }
  if (lap) lap.style.display = 'none';

  /* use cases: the dial turns with the active item; tapping an item rotates it */
  const items = $$('.ucd .item'), dial = $('#dial'), pops = $$('.ucd .pop'), stops = $$('.ucd .stop');
  const N = items.length || 3;
  const set = i => {
    items.forEach((it, k) => it.classList.toggle('on', k === i));
    stops.forEach((s, k) => s.classList.toggle('on', k === i));
    pops.forEach((p, k) => { p.classList.toggle('on', k === i); p.style.setProperty('--po', k === i ? 1 : .35); p.style.setProperty('--ps', k === i ? 1.08 : .9); });
    place(i);
  };
  const place = (i) => { const wrap = $('.ucd .dialwrap'); if (!wrap) return; const r = Math.min(wrap.clientWidth, 440) * .38; pops.forEach((p, k) => { const a = ((k - i) / N) * Math.PI * 2 - Math.PI / 2; p.style.setProperty('--px', (Math.cos(a) * r).toFixed(1)); p.style.setProperty('--py', (Math.sin(a) * r).toFixed(1)); }); };
  addEventListener('resize', () => place(cur));
  const uc = $('#use-cases'); let cur = 0, timer = 0;
  items.forEach((it, k) => it.addEventListener('click', () => set(k)));
  set(0);
  /* auto-advance while on screen */
  if (uc) new IntersectionObserver(es => es.forEach(e => { clearInterval(timer); if (e.isIntersecting) timer = setInterval(() => { cur = (cur + 1) % N; set(cur); }, 4200); })).observe(uc);
  items.forEach((it, k) => it.addEventListener('click', () => { cur = k; }));

  /* why: the three beats as a static list under the headline */
  const why = $('#why'), copy = $('.wcopy');
  if (why && copy && window.WHY_BEATS) {
    const wrap = document.createElement('div'); wrap.className = 'mbeats';
    WHY_BEATS.forEach(b => { const el = document.createElement('div'); el.className = 'mbeat'; const card = (b.cards || []).find(x => x && x.html); el.innerHTML = '<b>' + b.w + '</b><p>' + b.sub + '</p>' + (card ? '<div class="pc on">' + card.html + '</div>' : ''); wrap.appendChild(el); });
    const w = $('#word'); if (w) { w.innerHTML = ''; }
    const h2 = $('.wcopy h2'); if (h2) { const l2 = $('.l2', h2), l3 = $('.l3', h2); if (l2) l2.textContent = 'Proof at every hand-off.'; }
    copy.appendChild(wrap);
  }
})();
