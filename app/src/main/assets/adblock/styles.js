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
    if (!c) return;
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
  //  Pointeur : designer un element a masquer
  // -------------------------------------------------------------------------
  let picking = false;
  let overlay = null;
  let bar = null;
  let current = null;

  function selectorFor(el) {
    if (!el || el.nodeType !== 1) return "";
    if (el.id && /^[A-Za-z][\w-]*$/.test(el.id)) return "#" + el.id;

    const parts = [];
    let cur = el;
    let depth = 0;

    while (cur && cur.nodeType === 1 && cur !== document.documentElement && depth < 5) {
      let sel = cur.tagName.toLowerCase();

      if (cur.id && /^[A-Za-z][\w-]*$/.test(cur.id)) {
        parts.unshift("#" + cur.id);
        break;
      }

      // Classes stables : on ecarte celles qui ressemblent a du genere
      const good = (cur.className && cur.className.toString ? cur.className.toString() : "")
        .trim().split(/\s+/)
        .filter(c => c && c.length < 26 && !/^\d/.test(c) && !/^(css|sc|emotion)-/i.test(c));

      if (good.length) {
        sel += "." + good.slice(0, 2).map(c => CSS.escape(c)).join(".");
      } else {
        const parent = cur.parentElement;
        if (parent) {
          const same = Array.from(parent.children).filter(x => x.tagName === cur.tagName);
          if (same.length > 1) sel += ":nth-of-type(" + (same.indexOf(cur) + 1) + ")";
        }
      }

      parts.unshift(sel);

      // Un selecteur suffisamment precis : on s'arrete
      try {
        const test = parts.join(" > ");
        if (document.querySelectorAll(test).length === 1) break;
      } catch (e) { }

      cur = cur.parentElement;
      depth++;
    }
    return parts.join(" > ");
  }

  function highlight(el) {
    if (!overlay) return;
    if (!el) { overlay.style.display = "none"; return; }
    const r = el.getBoundingClientRect();
    Object.assign(overlay.style, {
      display: "block",
      left: r.left + "px",
      top: r.top + "px",
      width: r.width + "px",
      height: r.height + "px"
    });
  }

  function stopPicker() {
    picking = false;
    if (overlay) { overlay.remove(); overlay = null; }
    if (bar) { bar.remove(); bar = null; }
    current = null;
    document.removeEventListener("click", onPick, true);
    document.removeEventListener("touchstart", onTouch, true);
  }

  function onTouch(e) {
    if (!picking) return;
    const t = e.touches && e.touches[0];
    if (!t) return;
    const el = document.elementFromPoint(t.clientX, t.clientY);
    if (el && el !== overlay && !bar.contains(el)) {
      current = el;
      highlight(el);
      updateBar();
    }
  }

  function onPick(e) {
    if (!picking) return;
    if (bar && bar.contains(e.target)) return;
    e.preventDefault();
    e.stopPropagation();
    current = e.target;
    highlight(current);
    updateBar();
  }

  function updateBar() {
    if (!bar) return;
    const sel = current ? selectorFor(current) : "";
    bar.querySelector(".gb-sel").textContent = sel || "Touchez un element";
    const n = sel ? countOf(sel) : 0;
    bar.querySelector(".gb-cnt").textContent = sel ? n + " element(s) vise(s)" : "";
  }

  function countOf(sel) {
    try { return document.querySelectorAll(sel).length; } catch (e) { return 0; }
  }

  function startPicker() {
    if (picking) { stopPicker(); return; }
    picking = true;

    overlay = document.createElement("div");
    overlay.style.cssText =
      "position:fixed;z-index:2147483646;pointer-events:none;display:none;" +
      "background:rgba(111,174,95,.22);border:2px solid #6fae5f;border-radius:3px";
    document.documentElement.appendChild(overlay);

    bar = document.createElement("div");
    bar.style.cssText =
      "position:fixed;left:0;right:0;bottom:0;z-index:2147483647;background:#14161a;" +
      "border-top:1px solid #2b303a;padding:10px 12px 14px;" +
      "font:12px -apple-system,Roboto,sans-serif;color:#e8eaee";
    bar.innerHTML =
      '<div class="gb-sel" style="font-family:monospace;font-size:11px;color:#8ab4f8;' +
      'word-break:break-all;margin-bottom:3px">Touchez un element</div>' +
      '<div class="gb-cnt" style="color:#99a0ad;font-size:11px;margin-bottom:9px"></div>' +
      '<div style="display:flex;gap:6px;flex-wrap:wrap">' +
      '<button data-a="up" style="flex:1;padding:9px;border:1px solid #2b303a;' +
      'border-radius:7px;background:#1c1f26;color:#e8eaee;font-size:12px">Bloc parent</button>' +
      '<button data-a="hide" style="flex:1;padding:9px;border:1px solid #3d5c34;' +
      'border-radius:7px;background:#1c1f26;color:#8fce7c;font-size:12px">Masquer</button>' +
      '<button data-a="edit" style="flex:1;padding:9px;border:1px solid #2b303a;' +
      'border-radius:7px;background:#1c1f26;color:#8ab4f8;font-size:12px">Editer</button>' +
      '<button data-a="stop" style="flex:1;padding:9px;border:1px solid #2b303a;' +
      'border-radius:7px;background:transparent;color:#99a0ad;font-size:12px">Fermer</button>' +
      "</div>";
    document.documentElement.appendChild(bar);

    bar.addEventListener("click", e => {
      const a = e.target.getAttribute && e.target.getAttribute("data-a");
      if (!a) return;
      e.preventDefault();
      e.stopPropagation();

      if (a === "stop") { stopPicker(); return; }
      if (a === "up") {
        if (current && current.parentElement &&
            current.parentElement !== document.documentElement) {
          current = current.parentElement;
          highlight(current);
          updateBar();
        }
        return;
      }
      if (!current) return;
      const sel = selectorFor(current);
      if (!sel) return;
      if (a === "hide") { hideSelector(sel); stopPicker(); }
      if (a === "edit") { stopPicker(); openEditor(sel); }
    }, true);

    document.addEventListener("click", onPick, true);
    document.addEventListener("touchstart", onTouch, true);
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
