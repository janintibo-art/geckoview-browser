"use strict";

// ===========================================================================
//  history.js -- historique de navigation.
//
//  Deux niveaux distincts, volontairement separes :
//    - adresse et titre, actif par defaut, comme dans tout navigateur ;
//    - texte de la page, desactive par defaut, car indexer ce qu'on lit
//      est puissant mais sensible.
//
//  La navigation privee n'est jamais enregistree.
// ===========================================================================

(function () {

  if (window.top !== window.self) return;

  // Le mode prive doit rester sans trace, quelle que soit la configuration
  try {
    if (browser.extension && browser.extension.inIncognitoContext) return;
  } catch (e) { }

  const url = location.href;
  if (!/^https?:/i.test(url)) return;

  const MAX_TEXT = 8000;

  async function record() {
    let cfg = { enabled: true, fullText: false, exclude: [] };
    try {
      const s = await browser.storage.local.get("histCfg");
      if (s && s.histCfg) cfg = Object.assign(cfg, s.histCfg);
    } catch (e) { }

    if (!cfg.enabled) return;

    const host = location.hostname.replace(/^www\./, "");
    const excluded = (cfg.exclude || []).some(h => {
      h = String(h).trim().toLowerCase();
      return h && (host === h || host.endsWith("." + h));
    });
    if (excluded) return;

    const entry = {
      url: url,
      host: host,
      title: (document.title || host).slice(0, 200),
      at: Date.now(),
      text: ""
    };

    if (cfg.fullText) {
      try {
        const t = GB.mainText(document);
        if (t && t.length > 40) entry.text = t.slice(0, MAX_TEXT);
      } catch (e) { }
    }

    try {
      await browser.runtime.sendMessage({ type: "histAdd", entry: entry });
    } catch (e) { }
  }

  // Le titre arrive parfois apres le chargement : on laisse la page se poser.
  setTimeout(record, 2500);
})();
