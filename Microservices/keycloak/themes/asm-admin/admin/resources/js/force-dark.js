// Force the Keycloak admin console (PatternFly) into dark mode. The stock console has no dark
// toggle and renders light; PatternFly's dark theme is activated by a class on <html>. We set it
// on load and re-apply it if the SPA ever re-renders the root element. Class names cover the
// PatternFly versions Keycloak 26.x may ship (v5/v6).
(function () {
  var CLASSES = ["pf-v5-theme-dark", "pf-v6-theme-dark", "pf-theme-dark"];
  function apply() {
    var el = document.documentElement;
    for (var i = 0; i < CLASSES.length; i++) {
      if (!el.classList.contains(CLASSES[i])) el.classList.add(CLASSES[i]);
    }
    el.style.colorScheme = "dark";
  }
  apply();
  // Keep the class present across SPA re-renders (guard against a render that resets <html> class).
  try {
    new MutationObserver(function () {
      var el = document.documentElement;
      if (!el.classList.contains("pf-v5-theme-dark")) apply();
    }).observe(document.documentElement, { attributes: true, attributeFilter: ["class"] });
  } catch (e) { /* MutationObserver unsupported — the initial apply() still holds */ }
})();
