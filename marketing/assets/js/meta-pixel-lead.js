// Fires the Meta Pixel Lead event on any click of a Calendly link.
document.addEventListener('click', function (e) {
  var a = e.target.closest('a[href*="calendly.com"]');
  if (a && window.fbq) fbq('track', 'Lead');
});
