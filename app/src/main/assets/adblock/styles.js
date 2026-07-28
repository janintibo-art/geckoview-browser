"use strict";

// ===========================================================================
//  styles.js -- feuilles de style personnelles par site, facon Stylus.
//  Injecte au plus tot, mis a jour a chaud, avec un pointeur permettant de
//  designer un element a masquer directement sur la page.
// ===========================================================================

(function () {

  const HOST = location.hostname.replace(/^www\./, "");
  let styleEl = null;
  let styles = [];

  // -------------------------------------------------------------------------
  //  Correspondance des motifs
  // -------------------------------------------------------------------------
  //  exemple.fr         -> le domaine et ses sous-domaines
  //  *.exemple.fr       -> les sous-domaines uniquement
  //  exemple.fr/blog*   -> restreint au chemin
  //  /expression/       -> expression reguliere sur l'URL complete
  //  *                  -> partout
  function matches(pattern) {
    const p = (pattern || "").trim();
    if (!p) return false;
    if (p === "*") return true;

    if (p.length > 2 && p[0] === "/" && p.endsWith("/")) {
      try { return new RegExp(p.slice(1, -1)).test(location.href); }
      catch (e) { return false; }
    }

    const slash = p.indexOf("/");
    const hostPart = slash === -1 ? p : p.slice(0, slash);
    const pathPart = slash === -1 ? "" : p.slice(slash);

    let hostOk;
    if (hostPart.startsWith("*.")) {
      const bare = hostPart.slice(2).toLowerCase();
      hostOk = HOST !== bare && HOST.endsWith("." + bare);
    } else {
      const bare = hostPart.toLowerCase();
      hostOk = HOST === bare || HOST.endsWith("." + bare);
    }
    if (!hostOk) return false;
    if (!pathPart) return true;

    const re = new RegExp("^" + pathPart
      .replace(/[.+?^${}()|[\]\\]/g, "\\$&")
      .replace(/\*/g, ".*"));
    return re.test(location.pathname + location.search);
  }

  function applicable(list) {
    return (list || []).filter(s =>
      s.enabled !== false && (s.patterns || []).some(matches));
  }

  // -------------------------------------------------------------------------
  //  Injection
  // -------------------------------------------------------------------------
  function ensureElement() {
    if (styleEl && styleEl.isConnected) return styleEl;
    styleEl = document.createElement("style");
    styleEl.id = "gb-user-styles";
    // En tete de document : les regles du site passent apres, d'ou !important
    // dans les modeles proposes par l'editeur.
    (document.head || document.documentElement).appendChild(styleEl);
    return styleEl;
  }

  function apply() {
    const active = applicable(styles);
    const css = active.map(s =>
      "/* " + (s.name || "sans titre") + " */\n" + (s.css || "")).join("\n\n");
    ensureElement().textContent = css;
  }

  async function load() {
    try {
      const s = await browser.storage.local.get("userStyles");
      styles = (s && s.userStyles) || [];
    } catch (e) {
      styles = [];
    }
    apply();
  }

  browser.storage.onChanged.addListener(changes => {
    if (changes.userStyles) {
      styles = changes.userStyles.newValue || [];
      apply();
    }
    const c = changes.pageCommand && changes.pageCommand.newValue;
    if (!c || !GB.foreground()) return;
    if (c.cmd === "styleThis") openEditor();
    if (c.cmd === "pickElement") startPicker();
  });

  // -------------------------------------------------------------------------
  //  Editeur
  // -------------------------------------------------------------------------
  function openEditor(selector) {
    let url = browser.runtime.getURL("styles.html") + "?host=" + encodeURIComponent(HOST);
    if (selector) url += "&hide=" + encodeURIComponent(selector);
    location.href = url;
  }

  // -------------------------------------------------------------------------
  //  Pointeur : delegue a GB.pick, le pointeur commun (shared.js).
  //  L'ancien pointeur maison (~160 lignes dupliquees) a ete retire.
  // -------------------------------------------------------------------------
  async function startPicker() {
    const picked = await GB.pick({
      hint: "Touchez l'element a masquer ou a editer",
      actions: [
        { a: "hide", label: "Masquer", accent: true },
        { a: "edit", label: "Editer" }
      ]
    });
    if (!picked || !picked.selector) return;
    if (picked.action === "hide") hideSelector(picked.selector);
    else if (picked.action === "edit") openEditor(picked.selector);
  }

  // Ajoute une regle de masquage a la feuille du site, ou la cree
  async function hideSelector(sel) {
    const rule = sel + " { display: none !important; }";
    try {
      const s = await browser.storage.local.get("userStyles");
      const list = (s && s.userStyles) || [];
      let target = list.find(x => (x.patterns || []).length === 1 &&
                                  x.patterns[0] === HOST && x.auto);
      if (target) {
        target.css = (target.css || "").trimEnd() + "\n" + rule + "\n";
      } else {
        list.push({
          id: "st_" + Date.now().toString(36),
          name: "Masquages sur " + HOST,
          patterns: [HOST],
          css: rule + "\n",
          enabled: true,
          auto: true
        });
      }
      await browser.storage.local.set({ userStyles: list });
      alert("Regle ajoutee :\n" + sel);
    } catch (e) {
      alert("Echec de l'enregistrement.");
    }
  }

  // -------------------------------------------------------------------------
  ensureElement();   // reserve la place des le depart
  load();
})();
