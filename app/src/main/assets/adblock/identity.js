"use strict";

// ===========================================================================
//  identity.js -- aligne les proprietes JavaScript sur le profil d'appareil.
//
//  L'agent utilisateur lui-meme est remplace par le navigateur, en-tete HTTP
//  comprise. Restent des valeurs que Gecko n'ajuste pas : la plateforme, les
//  points tactiles, la marque. Sans elles, un site verrait un agent Windows
//  sur une plateforme Linux aarch64 avec cinq points tactiles — contradiction
//  reperable, donc signal distinctif.
// ===========================================================================

(function () {

  function apply(profile) {
    if (!profile || !profile.ua) return;

    const parts = [];

    if (profile.platform) {
      parts.push(
        'Object.defineProperty(Navigator.prototype, "platform", ' +
        '{ get: function () { return ' + JSON.stringify(profile.platform) +
        '; }, configurable: true });');
      parts.push(
        'Object.defineProperty(Navigator.prototype, "oscpu", ' +
        '{ get: function () { return ' + JSON.stringify(profile.platform) +
        '; }, configurable: true });');
    }

    if (typeof profile.touch === "number" && profile.touch >= 0) {
      parts.push(
        'Object.defineProperty(Navigator.prototype, "maxTouchPoints", ' +
        '{ get: function () { return ' + profile.touch + '; }, configurable: true });');
    }

    // appVersion doit suivre l'agent, sinon la contradiction saute aux yeux
    const appVersion = profile.ua.replace(/^Mozilla\//, "");
    parts.push(
      'Object.defineProperty(Navigator.prototype, "appVersion", ' +
      '{ get: function () { return ' + JSON.stringify(appVersion) +
      '; }, configurable: true });');

    // Marque : vide chez Firefox, "Apple Computer, Inc." chez Safari
    const vendor = /Safari\//.test(profile.ua) && !/Chrome\//.test(profile.ua)
      ? "Apple Computer, Inc."
      : (/Chrome\//.test(profile.ua) ? "Google Inc." : "");
    parts.push(
      'Object.defineProperty(Navigator.prototype, "vendor", ' +
      '{ get: function () { return ' + JSON.stringify(vendor) +
      '; }, configurable: true });');

    if (!parts.length) return;

    const code = "(function(){try{" + parts.join("") + "}catch(e){}})();";
    try {
      const el = document.createElement("script");
      el.textContent = code;
      (document.head || document.documentElement).appendChild(el);
      el.remove();
    } catch (e) {
      // Politique de securite du site : l'agent reste correct, seules les
      // proprietes secondaires gardent leur valeur d'origine.
    }
  }

  (async function () {
    try {
      const s = await browser.storage.local.get("deviceProfile");
      if (s && s.deviceProfile) apply(s.deviceProfile);
    } catch (e) { }
  })();

  browser.storage.onChanged.addListener(changes => {
    if (changes.deviceProfile) apply(changes.deviceProfile.newValue);
  });
})();
