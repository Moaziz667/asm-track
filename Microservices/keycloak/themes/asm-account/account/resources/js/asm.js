// Read the asm-theme cookie (set by the admin app's dark mode toggle) and apply
// the correct PatternFly 5 theme class. Falls back to system preference, then
// to dark (matching the admin app default).
(function () {
  function getTheme() {
    // 1. Check asm-theme cookie (set by admin app)
    var m = document.cookie.split('; ').find(function (c) { return c.startsWith('asm-theme='); });
    if (m) return m.split('=')[1];
    // 2. Check system preference
    if (window.matchMedia && window.matchMedia('(prefers-color-scheme: light)').matches) return 'light';
    // 3. Default = dark
    return 'dark';
  }

  var isDark = getTheme() !== 'light';

  function apply() {
    if (isDark) {
      document.documentElement.classList.add('pf-v5-theme-dark');
      document.documentElement.removeAttribute('data-theme');
    } else {
      document.documentElement.classList.remove('pf-v5-theme-dark');
      document.documentElement.setAttribute('data-theme', 'light');
    }
  }

  apply();
  document.addEventListener('DOMContentLoaded', apply);
  // The account console is a SPA; re-assert once after it mounts.
  setTimeout(apply, 300);

  // Listen for cookie changes (cross-tab sync via storage event won't fire for cookies,
  // but the admin app sets a cookie that we re-read on any user interaction).
  document.addEventListener('click', function () {
    var newTheme = getTheme();
    var newIsDark = newTheme !== 'light';
    if (newIsDark !== isDark) {
      isDark = newIsDark;
      apply();
    }
  });
})();
