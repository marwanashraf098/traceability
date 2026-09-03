function toggleLang() {
  const html = document.documentElement;
  const isRtl = html.getAttribute('dir') === 'rtl';
  html.setAttribute('dir', isRtl ? 'ltr' : 'rtl');
  document.querySelector('.lang-toggle').textContent = isRtl ? 'العربية' : 'English';
}

document.getElementById('lang-toggle').addEventListener('click', toggleLang);
