// Copies fbclid and utm_* from this page's URL onto app.tracedtech.com/signup links at
// click time, so ad attribution reaches the signup page. Delegated, so it also covers
// the pricing-plan buttons rendered later by desktop-main.js / mobile-main.js.
(function () {
  var KEYS = ['fbclid', 'utm_source', 'utm_medium', 'utm_campaign', 'utm_term', 'utm_content'];
  function carry(e) {
    var a = e.target.closest && e.target.closest('a[href^="https://app.tracedtech.com/signup"]');
    if (!a) return;
    var here = new URLSearchParams(location.search);
    var url = new URL(a.href);
    KEYS.forEach(function (k) {
      var v = here.get(k);
      if (v && !url.searchParams.has(k)) url.searchParams.set(k, v);
    });
    a.href = url.toString();
  }
  document.addEventListener('click', carry);
  document.addEventListener('auxclick', carry);
})();
