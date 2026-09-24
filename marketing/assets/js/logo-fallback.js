// CSP-safe replacement for the drop's inline onerror= handlers on the
// integration logos: if the image fails, swap it for a <b> with the name.
(() => {
  const swap = img => img.replaceWith(Object.assign(document.createElement('b'), { textContent: img.dataset.fallback }));
  document.querySelectorAll('img[data-fallback]').forEach(img => {
    if (img.complete && img.naturalWidth === 0 && img.getAttribute('src')) swap(img);
    else img.addEventListener('error', () => swap(img), { once: true });
  });
})();
