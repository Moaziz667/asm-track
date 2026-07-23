// Read the asm-theme cookie (set by the admin app's dark mode toggle) and apply
// the correct PatternFly 5 theme class. Falls back to system preference, then
// to dark (matching the admin app default).
(function () {
  var CLASSES = ["pf-v5-theme-dark", "pf-v6-theme-dark", "pf-theme-dark"];

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
    var el = document.documentElement;
    if (isDark) {
      for (var i = 0; i < CLASSES.length; i++) {
        if (!el.classList.contains(CLASSES[i])) el.classList.add(CLASSES[i]);
      }
      el.removeAttribute('data-theme');
      el.style.colorScheme = 'dark';
    } else {
      for (var i = 0; i < CLASSES.length; i++) {
        el.classList.remove(CLASSES[i]);
      }
      el.setAttribute('data-theme', 'light');
      el.style.colorScheme = 'light';
    }
  }

  apply();
  document.addEventListener('DOMContentLoaded', apply);
  // The account console is a SPA; re-assert once after it mounts.
  setTimeout(apply, 300);

  // Guard against SPA re-renders that reset <html> class (MutationObserver)
  try {
    new MutationObserver(function () {
      var el = document.documentElement;
      var hasDark = false;
      for (var i = 0; i < CLASSES.length; i++) {
        if (el.classList.contains(CLASSES[i])) { hasDark = true; break; }
      }
      if (isDark && !hasDark) apply();
      if (!isDark && hasDark) apply();
    }).observe(document.documentElement, { attributes: true, attributeFilter: ['class'] });
  } catch (e) { /* MutationObserver unsupported — the initial apply() still holds */ }

  // Listen for cookie changes on click (cross-tab sync via storage event won't fire for cookies)
  document.addEventListener('click', function () {
    var newTheme = getTheme();
    var newIsDark = newTheme !== 'light';
    if (newIsDark !== isDark) {
      isDark = newIsDark;
      apply();
    }
  });
})();
