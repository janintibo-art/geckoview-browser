"use strict";

// ===========================================================================
//  watcher.js -- creation d'une surveillance de page.
//  On designe un element au doigt, on choisit ce qu'on surveille, et le
//  navigateur revient le verifier periodiquement.
// ===========================================================================

(function () {

  if (window.top !== window.self) return;

  let picking = false;
  let overlay = null;
  let bar = null;
  let current = null;

  // -------------------------------------------------------------------------
  //  Selecteur d'un element, priorite a ce qui est stable
  // -------------------------------------------------------------------------
  function selectorFor(el) {
    if (!el || el.nodeType !== 1) return "";
    if (el.id && /^[A-Za-z][\w-]*$/.test(el.id)) return "#" + CSS.escape(el.id);

    const parts = [];
    let cur = el;
    let depth = 0;

    while (cur && cur.nodeType === 1 && cur !== document.documentElement && depth < 6) {
      if (cur.id && /^[A-Za-z][\w-]*$/.test(cur.id)) {
        parts.unshift("#" + CSS.escape(cur.id));
        break;
      }

      let sel = cur.tagName.toLowerCase();
      const raw = (cur.className && cur.className.toString
                   ? cur.className.toString() : "").trim();
      const good = raw.split(/\s+/).filter(c =>
        c && c.length < 26 && !/^\d/.test(c) && !/^(css|sc|emotion)-/i.test(c));

      if (good.length) {
        sel += "." + good.slice(0, 2).map(c => CSS.escape(c)).join(".");
      } else if (cur.parentElement) {
        const same = Array.from(cur.parentElement.children)
          .filter(x => x.tagName === cur.tagName);
        if (same.length > 1) sel += ":nth-of-type(" + (same.indexOf(cur) + 1) + ")";
      }

      parts.unshift(sel);
      try {
        if (document.querySelectorAll(parts.join(" > ")).length === 1) break;
      } catch (e) { }

      cur = cur.parentElement;
      depth++;
    }
    return parts.join(" > ");
  }

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
  //  Pointeur
  // -------------------------------------------------------------------------
  function stop() {
    picking = false;
    if (overlay) { overlay.remove(); overlay = null; }
    if (bar) { bar.remove(); bar = null; }
    current = null;
    document.removeEventListener("click", onTap, true);
  }

  function onTap(e) {
    if (bar && bar.contains(e.target)) return;
    e.preventDefault();
    e.stopPropagation();
    current = e.target;
    refresh();
  }

  function refresh() {
    if (!bar || !current) return;
    const sel = selectorFor(current);
    const r = current.getBoundingClientRect();
    Object.assign(overlay.style, {
      display: "block", left: r.left + "px", top: r.top + "px",
      width: r.width + "px", height: r.height + "px"
    });
    bar.querySelector("#wt-sel").textContent = sel || "—";
    const mode = bar.querySelector("#wt-mode").value;
    bar.querySelector("#wt-val").textContent =
      "Valeur actuelle : " + (valueOf(current, mode) || "(vide)");
  }

  function start() {
    if (picking) { stop(); return; }
    picking = true;

    overlay = document.createElement("div");
    overlay.style.cssText =
      "position:fixed;z-index:2147483646;pointer-events:none;display:none;" +
      "background:rgba(217,119,87,.18);border:2px solid #d97757;border-radius:3px";
    document.documentElement.appendChild(overlay);

    bar = document.createElement("div");
    bar.style.cssText =
      "position:fixed;left:0;right:0;bottom:0;z-index:2147483647;background:#14161a;" +
      "border-top:1px solid #2b303a;padding:11px 12px 15px;color:#e8eaee;" +
      "font:12px/1.5 -apple-system,Roboto,sans-serif";
    bar.innerHTML =
      '<div style="font-weight:600;margin-bottom:6px">Surveiller un element</div>' +
      '<div id="wt-sel" style="font-family:monospace;font-size:11px;color:#8ab4f8;' +
      'word-break:break-all">Touchez l\'element a surveiller</div>' +
      '<div id="wt-val" style="color:#99a0ad;font-size:11px;margin:3px 0 9px"></div>' +
      '<div style="display:flex;gap:6px;margin-bottom:8px">' +
      '<select id="wt-mode" style="flex:1;background:#1c1f26;border:1px solid #2b303a;' +
      'border-radius:7px;color:#e8eaee;padding:8px;font-size:12px">' +
      '<option value="texte">Le texte change</option>' +
      '<option value="nombre">Le nombre change (prix, stock)</option>' +
      '<option value="presence">L\'element apparait ou disparait</option>' +
      "</select>" +
      '<select id="wt-freq" style="flex:1;background:#1c1f26;border:1px solid #2b303a;' +
      'border-radius:7px;color:#e8eaee;padding:8px;font-size:12px">' +
      '<option value="30">Toutes les 30 min</option>' +
      '<option value="120" selected>Toutes les 2 h</option>' +
      '<option value="360">Toutes les 6 h</option>' +
      '<option value="1440">Une fois par jour</option>' +
      "</select></div>" +
      '<div style="display:flex;gap:6px">' +
      '<button data-a="up" style="flex:1;padding:9px;border:1px solid #2b303a;' +
      'border-radius:7px;background:#1c1f26;color:#e8eaee">Parent</button>' +
      '<button data-a="ok" style="flex:1;padding:9px;border:1px solid #3d5c34;' +
      'border-radius:7px;background:#1c1f26;color:#8fce7c">Surveiller</button>' +
      '<button data-a="no" style="flex:1;padding:9px;border:1px solid #2b303a;' +
      'border-radius:7px;background:transparent;color:#99a0ad">Annuler</button></div>';
    document.documentElement.appendChild(bar);

    bar.querySelector("#wt-mode").addEventListener("change", refresh);

    bar.addEventListener("click", e => {
      const a = e.target.getAttribute && e.target.getAttribute("data-a");
      if (!a) return;
      e.preventDefault();
      e.stopPropagation();
      if (a === "no") { stop(); return; }
      if (a === "up") {
        if (current && current.parentElement &&
            current.parentElement !== document.documentElement) {
          current = current.parentElement;
          refresh();
        }
        return;
      }
      if (a === "ok") save();
    }, true);

    document.addEventListener("click", onTap, true);
  }

  // -------------------------------------------------------------------------
  async function save() {
    if (!current) { alert("Touchez d'abord un element."); return; }
    const sel = selectorFor(current);
    if (!sel) { alert("Selecteur introuvable pour cet element."); return; }

    const mode = bar.querySelector("#wt-mode").value;
    const freq = parseInt(bar.querySelector("#wt-freq").value, 10);
    const value = valueOf(current, mode);

    const watch = {
      id: "w_" + Date.now().toString(36),
      url: location.href,
      host: location.hostname.replace(/^www\./, ""),
      title: (document.title || location.hostname).slice(0, 90),
      selector: sel,
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
      stop();
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
    if (c && c.cmd === "watch") start();
  });
})();
