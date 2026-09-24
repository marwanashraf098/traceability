(() => {
  const strip = document.querySelector('.ts .strip');
  const lane = document.getElementById('tlane');
  if (!strip || !lane) return;

  const reduced = matchMedia('(prefers-reduced-motion: reduce)').matches;
  const DURATION = 58; // seconds to cross one loopWidth — matches the old CSS "roll 58s" speed

  let loopWidth = 0;
  const measure = () => {
    const cards = [...lane.children];
    const half = cards.length / 2;
    loopWidth = (half >= 1 && cards[half]) ? cards[half].offsetLeft - cards[0].offsetLeft : 0;
  };
  measure();
  addEventListener('resize', measure, { passive: true });

  // small positive start so an immediate right-swipe (scrollLeft decreasing from 0)
  // still has room to be caught and wrapped before it clamps at the native 0 floor
  let pos = 1;
  strip.scrollLeft = pos;

  let paused = false;
  let lastScrollAt = 0;
  let touchEndAt = 0;

  strip.addEventListener('scroll', () => {
    lastScrollAt = performance.now();
    if (loopWidth <= 0) return;
    if (strip.scrollLeft >= loopWidth) strip.scrollLeft -= loopWidth;
    else if (strip.scrollLeft <= 0) strip.scrollLeft += loopWidth;
  }, { passive: true });

  const pause = () => { paused = true; };
  const noteTouchEnd = () => { touchEndAt = performance.now(); };
  strip.addEventListener('touchstart', pause, { passive: true });
  strip.addEventListener('pointerdown', pause, { passive: true });
  strip.addEventListener('wheel', pause, { passive: true });
  strip.addEventListener('touchend', noteTouchEnd, { passive: true });
  strip.addEventListener('touchcancel', noteTouchEnd, { passive: true });

  if (reduced) return; // swipe only, no auto-scroll

  let visible = false;
  new IntersectionObserver(es => es.forEach(e => { visible = e.isIntersecting; }), { threshold: 0.01 }).observe(strip);

  let last = null;
  const step = (t) => {
    if (last === null) last = t;
    const dt = (t - last) / 1000;
    last = t;
    const now = performance.now();

    if (paused) {
      // resume 2s after the last touchend, and only once momentum has settled
      // (no scroll events for ~150ms) — then sync pos from the live scrollLeft
      // so resuming never causes a jump.
      if (touchEndAt && now - touchEndAt >= 2000 && now - lastScrollAt >= 150) {
        paused = false;
        pos = strip.scrollLeft;
      }
    } else if (visible && !document.hidden && loopWidth > 0) {
      pos += (loopWidth / DURATION) * dt;
      if (pos >= loopWidth) pos -= loopWidth;
      strip.scrollLeft = pos;
    }
    requestAnimationFrame(step);
  };
  requestAnimationFrame(step);
})();
