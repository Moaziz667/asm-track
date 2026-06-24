// Activate PatternFly 5's dark theme so every component token flips to dark; asm.css then
// recolors those dark defaults to the ASM "Control Tower" navy palette. Set as early as possible
// (and re-assert after hydration) to avoid a light flash.
(function () {
  var apply = function () {
    document.documentElement.classList.add('pf-v5-theme-dark');
  };
  apply();
  document.addEventListener('DOMContentLoaded', apply);
  // The account console is a SPA; re-assert once after it mounts.
  setTimeout(apply, 300);
})();
