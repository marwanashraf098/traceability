// "Watch the 60-second demo" lightbox. The overlay is built on first open and
// the video's src is only set while it is open, so nothing downloads on load.
(() => {
  const SRC = 'assets/demo-60.mp4';
  const triggers = document.querySelectorAll('[data-demo-video]');
  if (!triggers.length) return;

  let box, video, closeBtn, opener = null, scrollY = 0;

  const build = () => {
    box = document.createElement('div');
    box.className = 'dlb';
    box.hidden = true;
    box.setAttribute('role', 'dialog');
    box.setAttribute('aria-modal', 'true');
    box.setAttribute('aria-label', 'Traced 60-second demo');
    box.innerHTML =
      '<div class="dlb-stage">' +
        '<button type="button" class="dlb-close" aria-label="Close video">' +
          '<svg viewBox="0 0 12 12" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"><path d="M3 3l6 6M9 3l-6 6"/></svg>' +
        '</button>' +
        '<video controls playsinline preload="none" poster="assets/demo-60-poster.jpg"></video>' +
      '</div>';
    document.body.appendChild(box);
    video = box.querySelector('video');
    closeBtn = box.querySelector('.dlb-close');

    closeBtn.addEventListener('click', close);
    // backdrop = anywhere outside the video and the close button
    box.addEventListener('click', e => { if (e.target === box || e.target.classList.contains('dlb-stage')) close(); });
    box.addEventListener('keydown', e => {
      if (e.key === 'Escape') { e.preventDefault(); close(); return; }
      if (e.key !== 'Tab') return;
      const f = [closeBtn, video];
      const i = f.indexOf(document.activeElement);
      if (e.shiftKey && i <= 0) { e.preventDefault(); f[f.length - 1].focus(); }
      else if (!e.shiftKey && (i === -1 || i === f.length - 1)) { e.preventDefault(); f[0].focus(); }
    });
    // a click on the video's own controls can move focus out of the dialog; pull it back
    box.addEventListener('focusout', e => { if (!box.hidden && e.relatedTarget && !box.contains(e.relatedTarget)) closeBtn.focus(); });
  };

  const lock = () => {
    scrollY = window.scrollY;
    const sbw = window.innerWidth - document.documentElement.clientWidth;
    document.documentElement.style.overflow = 'hidden';
    if (sbw > 0) document.body.style.paddingRight = sbw + 'px';
  };
  const unlock = () => {
    document.documentElement.style.overflow = '';
    document.body.style.paddingRight = '';
    window.scrollTo(0, scrollY);
  };

  function open(e) {
    e.preventDefault();
    if (!box) build();
    if (!box.hidden) return;
    opener = e.currentTarget;
    lock();
    video.src = SRC;
    box.hidden = false;
    requestAnimationFrame(() => box.classList.add('on'));
    closeBtn.focus();
  }

  function close() {
    if (!box || box.hidden) return;
    video.pause();
    video.removeAttribute('src');
    video.load(); // resets to the poster and aborts any in-flight download
    box.classList.remove('on');
    box.hidden = true;
    unlock();
    if (opener) opener.focus({ preventScroll: true });
    opener = null;
  }

  triggers.forEach(t => t.addEventListener('click', open));
})();
