"use strict";

// ===========================================================================
//  translate.js -- traduction de la selection courante.
//
//  Le texte part vers une instance Lingva (choisie par background.js), jamais
//  vers Google directement, sans cookie ni cle. La langue cible est celle du
//  menu Traduire (cle trLang). La traduction de la page entiere ne passe pas
//  par ici : elle est faite localement par le moteur de Gecko, cote Java.
// ===========================================================================

(function () {

  if (window.top !== window.self) return;

  let panel = null;

  function close() {
    if (panel) { panel.remove(); panel = null; }
  }

  function open(original) {
    close();
    panel = document.createElement("div");
    panel.style.cssText =
      "position:fixed;left:0;right:0;bottom:0;z-index:2147483647;" +
      "background:#14161a;border-top:1px solid #2b303a;padding:12px 14px 16px;" +
      "color:#e8eaee;font:13px/1.55 -apple-system,Roboto,sans-serif;" +
      "max-height:55vh;overflow:auto";
    panel.innerHTML =
      '<div style="color:#99a0ad;font-size:11px;word-break:break-word;' +
      'margin-bottom:7px">' + esc(original.slice(0, 220)) +
      (original.length > 220 ? "\u2026" : "") + "</div>" +
      '<div id="gbt-out" style="word-break:break-word">Traduction en cours\u2026</div>' +
      '<div id="gbt-note" style="color:#99a0ad;font-size:11px;margin-top:7px"></div>' +
      '<div style="display:flex;gap:6px;margin-top:11px">' +
      '<button data-a="copy" style="flex:1;padding:9px;border:1px solid #3d5c34;' +
      'border-radius:7px;background:#1c1f26;color:#8fce7c;font-size:12px">Copier</button>' +
      '<button data-a="close" style="flex:1;padding:9px;border:1px solid #2b303a;' +
      'border-radius:7px;background:transparent;color:#99a0ad;font-size:12px">Fermer</button>' +
      "</div>";

    panel.addEventListener("click", e => {
      const a = e.target.getAttribute && e.target.getAttribute("data-a");
      if (a === "close") close();
      if (a === "copy") {
        const txt = (panel.querySelector("#gbt-out") || {}).textContent || "";
        try { navigator.clipboard.writeText(txt); } catch (e2) { }
        e.target.textContent = "Copie";
      }
    });
    document.documentElement.appendChild(panel);
    return panel;
  }

  function esc(s) {
    return String(s).replace(/&/g, "&amp;").replace(/</g, "&lt;")
                    .replace(/>/g, "&gt;");
  }

  async function run() {
    const sel = String(window.getSelection() || "").replace(/\s+/g, " ").trim();
    if (!sel) {
      alert("Selectionnez d'abord le texte a traduire\n(appui long sur un mot, " +
            "puis etendez la selection).");
      return;
    }

    let to = "fr";
    try {
      const s = await browser.storage.local.get("trLang");
      if (s && s.trLang) to = s.trLang;
    } catch (e) { }

    const p = open(sel);
    let r = null;
    try {
      r = await browser.runtime.sendMessage(
        { type: "translateText", text: sel, to: to });
    } catch (e) { }

    if (!p || p !== panel) return;   // ferme entre-temps
    const out = p.querySelector("#gbt-out");
    const note = p.querySelector("#gbt-note");
    if (!r || r.error || !r.translation) {
      out.textContent = "Traduction impossible : aucune instance joignable. " +
                        "Reessayez dans un instant.";
      return;
    }
    out.textContent = r.translation;
    note.textContent = (sel.length > 1500 ? "Texte tronque a 1500 caracteres \u00b7 " : "") +
                       "vers \u00ab " + to + " \u00bb \u00b7 via " + r.instance;
  }

  browser.storage.onChanged.addListener(changes => {
    const c = changes.pageCommand && changes.pageCommand.newValue;
    if (c && GB.foreground() && c.cmd === "translateSel") run();
  });

})();
