"use strict";

// ===========================================================================
//  watcher.js -- creation d'une surveillance de page.
//  On designe un element au doigt, on choisit ce qu'on surveille, et le
//  navigateur revient le verifier periodiquement.
// ===========================================================================

(function () {

  if (window.top !== window.self) return;

  // -------------------------------------------------------------------------
  //  Extraction de la valeur surveillee
  // -------------------------------------------------------------------------
  function valueOf(el, mode) {
    if (!el) return mode === "presence" ? "absent" : "";
    const text = (el.innerText || el.textContent || "").replace(/\s+/g, " ").trim();

    if (mode === "presence") return "present";
    if (mode === "nombre") {
      // Premier nombre rencontre : prix, compteur, stock restant
      const m = text.replace(/\u00A0/g, " ").match(/-?\d[\d\s.,]*/);
      if (!m) return "";
      return m[0].replace(/\s/g, "").replace(",", ".");
    }
    return text.slice(0, 400);
  }

  // -------------------------------------------------------------------------
  //  Pointeur : delegue a GB.pick, le pointeur commun (shared.js), avec
  //  l'apercu de la valeur courante et les reglages dans la barre.
  // -------------------------------------------------------------------------
  const FIELD = "flex:1;background:#1c1f26;border:1px solid #2b303a;" +
    "border-radius:7px;color:#e8eaee;padding:8px;font-size:12px";

  function preview(el) {
    const out = document.getElementById("wt-val");
    if (!out) return;
    if (!el) { out.textContent = ""; return; }
    const m = document.getElementById("wt-mode");
    const v = valueOf(el, m ? m.value : "texte");
    out.textContent = "Valeur actuelle : " +
      (v === "" ? "(vide)" : String(v).slice(0, 120));
  }

  async function start() {
    let hovered = null;

    // Changer de mode recalcule l'apercu sans avoir a re-toucher l'element
    setTimeout(() => {
      const m = document.getElementById("wt-mode");
      if (m) m.addEventListener("change", () => preview(hovered));
    }, 0);

    const picked = await GB.pick({
      hint: "Surveiller un element",
      color: "#d97757",
      actions: [{ a: "ok", label: "Surveiller", accent: true }],
      extra:
        '<div id="wt-val" style="color:#99a0ad;font-size:11px;margin:2px 0 9px"></div>' +
        '<div style="display:flex;gap:6px;margin-bottom:8px">' +
        '<select id="wt-mode" style="' + FIELD + '">' +
        '<option value="texte">Le texte change</option>' +
        '<option value="nombre">Le nombre change (prix, stock)</option>' +
        "<option value=\"presence\">L'element apparait ou disparait</option>" +
        "</select>" +
        '<select id="wt-freq" style="' + FIELD + '">' +
        '<option value="30">Toutes les 30 min</option>' +
        '<option value="120" selected>Toutes les 2 h</option>' +
        '<option value="360">Toutes les 6 h</option>' +
        '<option value="1440">Une fois par jour</option>' +
        "</select></div>",
      onHover: el => { hovered = el; preview(el); }
    });
    if (!picked || !picked.selector) return;

    const mode = (picked.fields && picked.fields["wt-mode"]) || "texte";
    const freq = parseInt(picked.fields && picked.fields["wt-freq"], 10) || 120;
    const value = valueOf(picked.element, mode);

    const watch = {
      id: "w_" + Date.now().toString(36),
      url: location.href,
      host: location.hostname.replace(/^www\./, ""),
      title: (document.title || location.hostname).slice(0, 90),
      selector: picked.selector,
      mode: mode,
      every: freq,
      value: value,
      previous: "",
      changedAt: 0,
      checkedAt: Date.now(),
      enabled: true,
      history: []
    };

    try {
      const s = await browser.storage.local.get("watches");
      const list = (s && s.watches) || [];
      list.push(watch);
      await browser.storage.local.set({ watches: list });
      alert("Surveillance ajoutee.\n\nValeur de reference : " +
            (value || "(vide)") + "\nVerification " +
            (freq >= 1440 ? "quotidienne" : "toutes les " +
             (freq >= 60 ? (freq / 60) + " h" : freq + " min")) + ".");
    } catch (e) {
      alert("Enregistrement impossible.");
    }
  }

  browser.storage.onChanged.addListener(changes => {
    const c = changes.pageCommand && changes.pageCommand.newValue;
    if (c && GB.foreground() && c.cmd === "watch") start();
  });
})();
