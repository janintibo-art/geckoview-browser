"use strict";

// ===========================================================================
//  inspector.js -- analyse de la page courante.
//  Trois onglets : ressources filtrables par type, code source, informations.
//  Ouvert depuis le panneau flottant ou depuis le menu de l'application.
// ===========================================================================

(function () {

  if (window.top !== window.self) return;   // uniquement le cadre principal

  let root = null;
  const consoleLog = [];      // historique de la console
  const history = [];         // commandes saisies
  let histPos = -1;
  let consoleMode = "page";   // page | sandbox
  let netEntries = [];
  let netFilter = "";
  let netOnly = "";
  let exRow = "";
  let exCols = [];
  let exPages = 1;
  let exData = [];
  let exBusy = false;
  let resources = [];
  let filters = new Set();
  let selected = new Set();
  let textFilter = "";
  let customExt = "";

  // -------------------------------------------------------------------------
  //  Capture des messages emis par la page
  // -------------------------------------------------------------------------
  function installLogHook() {
    const code = `(function(){
      if (window.__gbHooked) return;
      window.__gbHooked = true;
      function send(kind, args) {
        try {
          var parts = Array.prototype.map.call(args, function (x) {
            try {
              if (x instanceof Error) return x.name + ": " + x.message;
              if (typeof x === "object" && x !== null) return JSON.stringify(x);
              return String(x);
            } catch (e) { return String(x); }
          });
          document.dispatchEvent(new CustomEvent("__gbLog",
            { detail: JSON.stringify({ kind: kind, text: parts.join(" ") }) }));
        } catch (e) {}
      }
      ["log", "info", "warn", "error", "debug"].forEach(function (k) {
        var orig = console[k];
        console[k] = function () { send(k, arguments); return orig.apply(console, arguments); };
      });
      window.addEventListener("error", function (e) {
        send("error", [e.message + "  (" + (e.filename || "") + ":" + e.lineno + ")"]);
      });
      window.addEventListener("unhandledrejection", function (e) {
        send("error", ["Promesse rejetee : " + e.reason]);
      });
    })();`;
    try {
      const el = document.createElement("script");
      el.textContent = code;
      (document.head || document.documentElement).appendChild(el);
      el.remove();
    } catch (e) { }
  }

  document.addEventListener("__gbLog", ev => {
    try {
      const d = JSON.parse(ev.detail);
      pushLog(d.kind, d.text);
    } catch (e) { }
  });

  function pushLog(kind, text) {
    consoleLog.push({ kind, text, t: Date.now() });
    while (consoleLog.length > 400) consoleLog.shift();
    if (root && tab === "console") appendLogLine(consoleLog[consoleLog.length - 1]);
  }

  installLogHook();

  // -------------------------------------------------------------------------
  //  Classification
  // -------------------------------------------------------------------------
  const TYPES = [
    { id: "image",    label: "Images",     ext: "jpg jpeg png gif webp avif bmp ico svg apng tiff jfif" },
    { id: "video",    label: "Videos",     ext: "mp4 webm ogv mov m4v avi mkv m3u8 mpd ts" },
    { id: "audio",    label: "Audio",      ext: "mp3 ogg oga wav flac aac m4a opus weba" },
    { id: "script",   label: "Scripts",    ext: "js mjs cjs jsx ts" },
    { id: "style",    label: "Styles",     ext: "css scss less" },
    { id: "font",     label: "Polices",    ext: "woff woff2 ttf otf eot" },
    { id: "document", label: "Documents",  ext: "pdf doc docx xls xlsx ppt pptx odt ods odp rtf txt epub" },
    { id: "archive",  label: "Archives",   ext: "zip rar 7z tar gz bz2 xz iso dmg apk" },
    { id: "data",     label: "Donnees",    ext: "json xml csv tsv yaml yml rss atom geojson sql" },
    { id: "frame",    label: "Cadres",     ext: "" },
    { id: "link",     label: "Liens",      ext: "" },
    { id: "other",    label: "Autres",     ext: "" }
  ];

  const EXT_MAP = {};
  TYPES.forEach(t => t.ext.split(" ").filter(Boolean).forEach(e => { EXT_MAP[e] = t.id; }));

  const TEXTUAL = new Set(["script", "style", "data"]);

  function extOf(url) {
    try {
      const p = new URL(url, location.href).pathname;
      const m = p.match(/\.([a-z0-9]{1,6})$/i);
      return m ? m[1].toLowerCase() : "";
    } catch (e) { return ""; }
  }

  function typeOf(url, hint) {
    const e = extOf(url);
    if (EXT_MAP[e]) return EXT_MAP[e];
    if (hint) return hint;
    return "other";
  }

  function abs(url) {
    try { return new URL(url, location.href).href; } catch (e) { return url; }
  }

  // -------------------------------------------------------------------------
  //  Collecte
  // -------------------------------------------------------------------------
  function collect() {
    const map = new Map();

    const add = (url, type, origin, extra) => {
      if (!url) return;
      if (/^(data|blob|javascript|about):/i.test(url)) return;
      const full = abs(url);
      if (!/^https?:/i.test(full)) return;
      const key = full;
      if (map.has(key)) {
        const r = map.get(key);
        if (origin && !r.origins.includes(origin)) r.origins.push(origin);
        return;
      }
      map.set(key, {
        url: full,
        type: type || typeOf(full),
        ext: extOf(full),
        origins: origin ? [origin] : [],
        size: (extra && extra.size) || null,
        alt: (extra && extra.alt) || ""
      });
    };

    // 1) Chronologie des ressources reellement chargees (le plus fiable)
    try {
      const entries = performance.getEntriesByType("resource") || [];
      entries.forEach(e => {
        let t = null;
        switch (e.initiatorType) {
          case "img": case "image": t = "image"; break;
          case "script": t = "script"; break;
          case "css": case "link": t = null; break;
          case "video": t = "video"; break;
          case "audio": t = "audio"; break;
          case "iframe": case "frame": t = "frame"; break;
          case "fetch": case "xmlhttprequest": t = null; break;
        }
        add(e.name, t || typeOf(e.name), "reseau",
            { size: e.transferSize || e.encodedBodySize || null });
      });
    } catch (e) { /* chronologie desactivee par le niveau de confidentialite */ }

    // 2) Balises du document
    document.querySelectorAll("img[src]").forEach(el =>
      add(el.getAttribute("src"), "image", "img", { alt: el.alt || "" }));

    document.querySelectorAll("[srcset]").forEach(el => {
      (el.getAttribute("srcset") || "").split(",").forEach(part => {
        add(part.trim().split(/\s+/)[0], "image", "srcset");
      });
    });

    document.querySelectorAll("video[src], video source[src]").forEach(el =>
      add(el.getAttribute("src"), "video", "video"));
    document.querySelectorAll("audio[src], audio source[src]").forEach(el =>
      add(el.getAttribute("src"), "audio", "audio"));
    document.querySelectorAll("track[src]").forEach(el =>
      add(el.getAttribute("src"), "data", "sous-titres"));

    document.querySelectorAll("script[src]").forEach(el =>
      add(el.getAttribute("src"), "script", "script"));
    document.querySelectorAll("link[rel~='stylesheet'][href]").forEach(el =>
      add(el.getAttribute("href"), "style", "css"));
    document.querySelectorAll("link[rel~='icon'][href], link[rel~='apple-touch-icon'][href]")
      .forEach(el => add(el.getAttribute("href"), "image", "favicon"));
    document.querySelectorAll("link[rel='manifest'][href]").forEach(el =>
      add(el.getAttribute("href"), "data", "manifest"));
    document.querySelectorAll("link[rel='preload'][href], link[rel='prefetch'][href]")
      .forEach(el => add(el.getAttribute("href"), null, "preload"));

    document.querySelectorAll("iframe[src], embed[src], object[data]").forEach(el =>
      add(el.getAttribute("src") || el.getAttribute("data"), "frame", "cadre"));

    document.querySelectorAll("a[href]").forEach(el => {
      const href = el.getAttribute("href");
      const e = extOf(abs(href || ""));
      if (e && EXT_MAP[e]) add(href, EXT_MAP[e], "lien");
      else add(href, "link", "lien");
    });

    // 3) Images de fond declarees en style en ligne
    document.querySelectorAll("[style*='url(']").forEach(el => {
      const m = (el.getAttribute("style") || "").match(/url\(["']?([^"')]+)/gi) || [];
      m.forEach(u => add(u.replace(/^url\(["']?/i, ""), "image", "css inline"));
    });

    // 4) Images de fond des feuilles de style de meme origine
    try {
      for (const sheet of document.styleSheets) {
        let rules;
        try { rules = sheet.cssRules; } catch (e) { continue; }  // origine differente
        if (!rules) continue;
        for (const rule of rules) {
          const t = rule.cssText || "";
          const m = t.match(/url\(["']?([^"')]+)/gi) || [];
          m.forEach(u => {
            const clean = u.replace(/^url\(["']?/i, "");
            add(clean, typeOf(abs(clean), "image"), "feuille de style");
          });
        }
      }
    } catch (e) { }

    // 5) Polices reellement utilisees
    try {
      if (document.fonts) {
        document.fonts.forEach(f => {
          const m = (f.family || "") + "";
          if (m) { /* les URL ne sont pas exposees ici, deja couvertes par le CSS */ }
        });
      }
    } catch (e) { }

    resources = Array.from(map.values());
    resources.sort((a, b) => a.type.localeCompare(b.type) || a.url.localeCompare(b.url));
  }

  // -------------------------------------------------------------------------
  //  Rendu
  // -------------------------------------------------------------------------
  function esc(s) {
    return String(s == null ? "" : s)
      .replace(/&/g, "&amp;").replace(/</g, "&lt;")
      .replace(/>/g, "&gt;").replace(/"/g, "&quot;");
  }

  function human(n) {
    if (!n) return "";
    if (n < 1024) return n + " o";
    if (n < 1048576) return Math.round(n / 1024) + " Ko";
    return (n / 1048576).toFixed(1) + " Mo";
  }

  function counts() {
    const c = {};
    resources.forEach(r => { c[r.type] = (c[r.type] || 0) + 1; });
    return c;
  }

  function filtered() {
    const custom = customExt.split(/[,\s]+/).map(s => s.replace(/^\./, "").toLowerCase())
      .filter(Boolean);
    return resources.filter(r => {
      if (filters.size && !filters.has(r.type)) return false;
      if (custom.length && !custom.includes(r.ext)) return false;
      if (textFilter && r.url.toLowerCase().indexOf(textFilter.toLowerCase()) === -1) return false;
      return true;
    });
  }

  function rowHtml(r, i) {
    return `
      <div class="ins-row">
        <label class="ins-u">
          <input type="checkbox" class="ins-ck" data-k="${esc(r.url)}"
                 ${selected.has(r.url) ? "checked" : ""}>
          ${esc(r.url)}
        </label>
        <div class="ins-m">
          <span class="ins-tag">${esc(r.type)}</span>
          ${r.ext ? `<span>.${esc(r.ext)}</span>` : ""}
          ${r.size ? `<span>${human(r.size)}</span>` : ""}
          ${r.origins.length ? `<span>${esc(r.origins.join(", "))}</span>` : ""}
        </div>
        <div class="ins-act">
          <button data-dl="${i}" class="ins-dl">Telecharger</button>
          ${(r.type === "video" || r.type === "audio")
            ? `<button data-audio="${i}" class="ins-au">Extraire l'audio</button>` : ""}
          <button data-open="${i}">Ouvrir</button>
          <button data-copy="${i}">Copier</button>
          ${TEXTUAL.has(r.type) ? `<button data-src="${i}">Contenu</button>` : ""}
        </div>
      </div>`;
  }

  function renderResources() {
    const c = counts();
    const chips = TYPES.filter(t => c[t.id]).map(t =>
      `<button class="ins-chip${filters.has(t.id) ? " on" : ""}" data-t="${t.id}">
         ${t.label} <b>${c[t.id]}</b></button>`).join("");

    const list = filtered();
    const rows = list.map((r, i) => rowHtml(r, i)).join("");

    return `
      <div class="ins-chips">${chips}
        <button class="ins-chip" id="ins-clear">Tout</button>
      </div>
      <div class="ins-tools">
        <input id="ins-q" placeholder="Filtrer par texte dans l'URL" value="${esc(textFilter)}">
        <input id="ins-ext" placeholder="Extensions : webp, avif" value="${esc(customExt)}">
      </div>
      <div class="ins-tools">
        <button id="ins-sizes" class="ins-b">Mesurer les tailles</button>
        <button id="ins-copyall" class="ins-b">Copier les URL</button>
      </div>
      <div class="ins-tools">
        <button id="ins-dl-all" class="ins-b ins-dl">Telecharger ces ${list.length}</button>
        <button id="ins-dl-sel" class="ins-b">Telecharger la selection</button>
      </div>
      ${list.some(r => r.type === "video" || r.type === "audio")
        ? `<div class="ins-tools">
             <button id="ins-audio-all" class="ins-b ins-au">Extraire l'audio de ces medias</button>
           </div>` : ""}
      <div class="ins-tools">
        <button id="ins-sel-all" class="ins-b">Tout selectionner</button>
        <button id="ins-sel-none" class="ins-b">Aucun</button>
        <button id="ins-savelist" class="ins-b">Enregistrer la liste</button>
      </div>
      <div class="ins-list">${rows || '<div class="ins-empty">Aucune ressource pour ce filtre.</div>'}</div>`;
  }

  function renderCode() {
    return `
      <div class="ins-tools">
        <button id="ins-dom" class="ins-b">DOM actuel</button>
        <button id="ins-orig" class="ins-b">Source d'origine</button>
        <button id="ins-copycode" class="ins-b">Copier</button>
      </div>
      <div class="ins-tools">
        <input id="ins-find" placeholder="Rechercher dans le code">
        <span id="ins-hits" class="ins-hits"></span>
      </div>
      <pre id="ins-code" class="ins-code">Choisissez « DOM actuel » ou « Source d'origine ».</pre>`;
  }

  function renderConsole() {
    return `
      <div class="ins-tools">
        <button id="ins-cmode" class="ins-b">Contexte : ${consoleMode === "page" ? "page" : "isole"}</button>
        <button id="ins-cclear" class="ins-b">Effacer</button>
        <button id="ins-ccopy" class="ins-b">Copier</button>
      </div>
      <div id="ins-out" class="ins-out"></div>
      <div class="ins-tools ins-prompt">
        <button id="ins-prev" class="ins-b">&#8593;</button>
        <input id="ins-in" placeholder="Expression JavaScript" autocapitalize="off"
               autocorrect="off" spellcheck="false">
        <button id="ins-run" class="ins-b ins-dl">Executer</button>
      </div>
      <div class="ins-note">
        Contexte « page » : acces aux variables du site, soumis a sa politique de
        securite. Contexte « isole » : toujours disponible, variables du site
        accessibles via <code>unsafeWindow</code>. Raccourcis : <code>$</code> et
        <code>$$</code> pour les selecteurs.
      </div>`;
  }

  function renderNet() {
    const types = ["", "xmlhttprequest", "script", "image", "stylesheet",
                   "font", "media", "sub_frame", "main_frame"];
    const opts = types.map(t =>
      `<option value="${t}"${netOnly === t ? " selected" : ""}>${t || "tous types"}</option>`).join("");
    return `
      <div class="ins-tools">
        <button id="ins-nrefresh" class="ins-b">Actualiser</button>
        <button id="ins-nclear" class="ins-b">Vider</button>
        <button id="ins-ncopy" class="ins-b">Copier en CSV</button>
      </div>
      <div class="ins-tools">
        <input id="ins-nq" placeholder="Filtrer l'URL" value="${esc(netFilter)}">
        <select id="ins-ntype" class="ins-sel">${opts}</select>
      </div>
      <div id="ins-nlist" class="ins-list"><div class="ins-empty">Chargement…</div></div>`;
  }

  // -------------------------------------------------------------------------
  //  Extracteur structure
  // -------------------------------------------------------------------------
  const ATTRS = ["texte", "lien", "image", "html", "attribut"];

  function renderExtract() {
    const cols = exCols.map((c, i) => `
      <div class="ins-col">
        <input data-cn="${i}" placeholder="nom" value="${esc(c.name)}">
        <input data-cs="${i}" placeholder="selecteur (vide = la ligne)" value="${esc(c.sel)}">
        <select data-ca="${i}" class="ins-sel">
          ${ATTRS.map(a => `<option value="${a}"${c.attr === a ? " selected" : ""}>${a}</option>`).join("")}
        </select>
        <input data-cx="${i}" placeholder="nom de l'attribut" value="${esc(c.extra || "")}"
               style="${c.attr === "attribut" ? "" : "display:none"}">
        <button data-cd="${i}" class="ins-b">&times;</button>
      </div>`).join("");

    return `
      <div class="ins-tools">
        <input id="ex-row" placeholder="Selecteur des lignes" value="${esc(exRow)}">
      </div>
      <div class="ins-tools">
        <button id="ex-auto" class="ins-b">Deviner</button>
        <button id="ex-pick" class="ins-b">Pointer</button>
        <button id="ex-autocol" class="ins-b">Colonnes auto</button>
        <button id="ex-addcol" class="ins-b">+ colonne</button>
      </div>

      <div id="ex-cols">${cols}</div>

      <div class="ins-tools">
        <span class="ins-hits">Pages a suivre</span>
        <input id="ex-pages" type="number" min="1" max="50" value="${exPages}"
               style="max-width:80px">
        <button id="ex-run" class="ins-b ins-dl">Extraire</button>
      </div>

      <div id="ex-status" class="ins-note"></div>
      <div id="ex-prev" class="ins-prev"></div>

      <div class="ins-tools">
        <button id="ex-csv" class="ins-b">Enregistrer en CSV</button>
        <button id="ex-json" class="ins-b">Enregistrer en JSON</button>
        <button id="ex-copy" class="ins-b">Copier</button>
      </div>
      <div class="ins-note">
        Le selecteur des lignes designe un element repete : une carte de produit,
        un resultat, une ligne de tableau. Chaque colonne prend un selecteur relatif
        a cette ligne — laissez-le vide pour viser la ligne elle-meme.
      </div>`;
  }

  // Devine le conteneur repete et en deduit le selecteur des lignes
  function exAuto() {
    const box = findContainer(document);
    if (!box) return;
    const kids = Array.from(box.children);
    if (kids.length < 2) return;

    const sig = {};
    kids.forEach(k => { const g = signatureOf(k); sig[g] = (sig[g] || 0) + 1; });
    let best = "", n = 0;
    Object.keys(sig).forEach(k => { if (sig[k] > n) { n = sig[k]; best = k; } });

    const sample = kids.find(k => signatureOf(k) === best);
    if (!sample) return;

    const parent = cssPath(box);
    const cls = classOf(sample);
    exRow = parent + " > " + sample.tagName.toLowerCase() + (cls ? "." + cls : "");
    if (!exCols.length) exAutoCols();
  }

  function signatureOf(el) {
    return el.tagName + "." + classOf(el);
  }

  function classOf(el) {
    const raw = (el.className && el.className.toString ? el.className.toString() : "");
    const good = raw.trim().split(/\s+/)
      .filter(c => c && c.length < 26 && !/^\d/.test(c) && !/^(css|sc|emotion)-/i.test(c));
    return good.length ? good[0] : "";
  }

  function exRows() {
    if (!exRow) return [];
    try { return Array.from(document.querySelectorAll(exRow)); }
    catch (e) { return []; }
  }

  // Propose des colonnes a partir de la premiere ligne trouvee
  function exAutoCols() {
    const rows = exRows();
    if (!rows.length) return;
    const r = rows[0];
    const cols = [];

    const h = r.querySelector("h1, h2, h3, h4, [class*='title'], [class*='titre']");
    if (h) cols.push({ name: "titre", sel: tagSel(h, r), attr: "texte" });

    const a = r.querySelector("a[href]");
    if (a) {
      if (!h) cols.push({ name: "titre", sel: tagSel(a, r), attr: "texte" });
      cols.push({ name: "lien", sel: tagSel(a, r), attr: "lien" });
    }

    const img = r.querySelector("img[src], img[data-src]");
    if (img) cols.push({ name: "image", sel: tagSel(img, r), attr: "image" });

    const price = Array.from(r.querySelectorAll("*")).find(x =>
      x.children.length === 0 && /[\d\s]{1,9}[,.]?\d{0,2}\s?(€|EUR|\$|£)/.test(x.textContent || ""));
    if (price) cols.push({ name: "prix", sel: tagSel(price, r), attr: "texte" });

    if (!cols.length) cols.push({ name: "texte", sel: "", attr: "texte" });
    exCols = cols;
  }

  // Selecteur court d'un descendant, relatif a sa ligne
  function tagSel(el, root) {
    const cls = classOf(el);
    let sel = el.tagName.toLowerCase() + (cls ? "." + CSS.escape(cls) : "");
    try {
      if (root.querySelectorAll(sel).length >= 1) return sel;
    } catch (e) { }
    return el.tagName.toLowerCase();
  }

  // -------------------------------------------------------------------------
  function cellValue(row, col, base) {
    let el = row;
    if (col.sel) {
      try { el = row.querySelector(col.sel); } catch (e) { el = null; }
    }
    if (!el) return "";

    switch (col.attr) {
      case "lien": {
        const a = el.matches("a[href]") ? el : el.querySelector("a[href]");
        if (!a) return "";
        try { return new URL(a.getAttribute("href"), base).href; }
        catch (e) { return a.getAttribute("href") || ""; }
      }
      case "image": {
        const im = el.matches("img") ? el : el.querySelector("img");
        if (!im) return "";
        const v = im.getAttribute("src") || im.getAttribute("data-src") || "";
        try { return v ? new URL(v, base).href : ""; } catch (e) { return v; }
      }
      case "html":
        return (el.innerHTML || "").trim();
      case "attribut":
        return el.getAttribute(col.extra || "") || "";
      default:
        return (el.innerText || el.textContent || "").replace(/\s+/g, " ").trim();
    }
  }

  function extractFrom(root, base) {
    let rows = [];
    try { rows = Array.from(root.querySelectorAll(exRow)); } catch (e) { return []; }
    return rows.map(r => {
      const o = {};
      exCols.forEach(c => { o[c.name || "colonne"] = cellValue(r, c, base); });
      return o;
    });
  }

  function exPreview() {
    const box = root && root.querySelector("#ex-prev");
    if (!box) return;
    const rows = exRows();
    const st = root.querySelector("#ex-status");
    if (st) st.textContent = exRow
      ? rows.length + " ligne(s) reconnue(s) sur cette page"
      : "Indiquez un selecteur, ou appuyez sur Deviner.";
    if (!rows.length || !exCols.length) { box.innerHTML = ""; return; }

    const sample = rows.slice(0, 5).map(r => {
      const o = {};
      exCols.forEach(c => { o[c.name || "colonne"] = cellValue(r, c, location.href); });
      return o;
    });
    box.innerHTML = tableHtml(sample);
  }

  function tableHtml(list) {
    if (!list.length) return "";
    const keys = Object.keys(list[0]);
    return '<table class="ins-t"><thead><tr>' +
      keys.map(k => "<th>" + esc(k) + "</th>").join("") +
      "</tr></thead><tbody>" +
      list.map(o => "<tr>" + keys.map(k =>
        "<td>" + esc(String(o[k] || "").slice(0, 90)) + "</td>").join("") + "</tr>").join("") +
      "</tbody></table>";
  }

  // -------------------------------------------------------------------------
  //  Page suivante (detection compacte, meme principe que le defilement infini)
  // -------------------------------------------------------------------------
  // Detection deleguee a shared.js, commune avec le defilement infini.
  function nextLink(doc, base) {
    return GB.findNext(doc, base, Math.max(1, exRows().length));
  }

  async function exRun() {
    if (exBusy) return;
    if (!exRow || !exCols.length) return;
    exBusy = true;

    const st = root.querySelector("#ex-status");
    exData = extractFrom(document, location.href);
    if (st) st.textContent = "Page 1 : " + exData.length + " ligne(s)";

    let url = nextLink(document, location.href);
    const seenUrls = new Set([location.href]);

    for (let i = 1; i < exPages && url && !seenUrls.has(url); i++) {
      seenUrls.add(url);
      if (st) st.textContent = "Page " + (i + 1) + " en cours…";
      let doc = null;
      try {
        const res = await browser.runtime.sendMessage({
          type: "gmFetch", url: url, method: "GET"
        });
        if (res && res.body) doc = new DOMParser().parseFromString(res.body, "text/html");
      } catch (e) { }
      if (!doc) break;

      const part = extractFrom(doc, url);
      if (!part.length) break;
      exData = exData.concat(part);
      if (st) st.textContent = "Page " + (i + 1) + " : " + exData.length + " ligne(s) au total";
      url = nextLink(doc, url);
    }

    if (st) st.textContent = exData.length + " ligne(s) extraite(s)";
    const box = root.querySelector("#ex-prev");
    if (box) box.innerHTML = tableHtml(exData.slice(0, 30)) +
      (exData.length > 30 ? '<div class="ins-note">Apercu des 30 premieres lignes.</div>' : "");
    exBusy = false;
  }

  // -------------------------------------------------------------------------
  function toCsv(list) {
    if (!list.length) return "";
    const keys = Object.keys(list[0]);
    const q = v => '"' + String(v == null ? "" : v).replace(/"/g, '""') + '"';
    return [keys.map(q).join(",")]
      .concat(list.map(o => keys.map(k => q(o[k])).join(",")))
      .join("\n");
  }

  async function exSave(kind) {
    if (!exData.length) return;
    let host = "page";
    try { host = location.hostname.replace(/^www\./, ""); } catch (e) { }
    const name = host + "-extraction." + (kind === "csv" ? "csv" : "json");
    const text = kind === "csv" ? toCsv(exData) : JSON.stringify(exData, null, 2);
    try {
      await browser.runtime.sendMessage({ type: "downloadText", name: name, text: text });
    } catch (e) { }
    return name;
  }

  function renderInfo() {
    const metas = Array.from(document.querySelectorAll("meta")).map(m => {
      const k = m.getAttribute("name") || m.getAttribute("property")
             || m.getAttribute("http-equiv") || m.getAttribute("charset");
      const v = m.getAttribute("content") || m.getAttribute("charset") || "";
      return k ? `<tr><td>${esc(k)}</td><td>${esc(v).slice(0, 220)}</td></tr>` : "";
    }).join("");

    const c = counts();
    const totalSize = resources.reduce((a, r) => a + (r.size || 0), 0);

    const stats = [
      ["Adresse", location.href],
      ["Titre", document.title],
      ["Doctype", document.doctype ? document.doctype.name : "aucun"],
      ["Encodage", document.characterSet],
      ["Langue", document.documentElement.lang || "non declaree"],
      ["Elements DOM", document.getElementsByTagName("*").length],
      ["Scripts en ligne", document.querySelectorAll("script:not([src])").length],
      ["Styles en ligne", document.querySelectorAll("style").length],
      ["Formulaires", document.forms.length],
      ["Cadres", document.querySelectorAll("iframe").length],
      ["SVG en ligne", document.querySelectorAll("svg").length],
      ["Canvas", document.querySelectorAll("canvas").length],
      ["Ressources listees", resources.length],
      ["Poids mesure", totalSize ? human(totalSize) : "non mesure"],
      ["Domaines tiers", new Set(resources
          .map(r => { try { return new URL(r.url).hostname; } catch (e) { return ""; } })
          .filter(h => h && h !== location.hostname)).size],
      ["Cookies lisibles", document.cookie ? document.cookie.split(";").length : 0],
      ["Stockage local", (() => { try { return localStorage.length; } catch (e) { return "bloque"; } })()]
    ].map(r => `<tr><td>${esc(r[0])}</td><td>${esc(r[1])}</td></tr>`).join("");

    return `
      <table class="ins-t"><tbody>${stats}</tbody></table>
      <h4 class="ins-h">Balises meta</h4>
      <table class="ins-t"><tbody>${metas || '<tr><td colspan="2">aucune</td></tr>'}</tbody></table>`;
  }

  // -------------------------------------------------------------------------
  //  Interface
  // -------------------------------------------------------------------------
  const CSS = `
  #ins-root{position:fixed;inset:0;z-index:2147483647;background:#14161a;color:#e8eaee;
    font:13px/1.5 -apple-system,Roboto,"Segoe UI",sans-serif;display:flex;flex-direction:column}
  #ins-root *{box-sizing:border-box}
  .ins-head{display:flex;align-items:center;gap:8px;padding:10px 12px;border-bottom:1px solid #2b303a}
  .ins-head b{flex:1;font-size:14px}
  .ins-x{background:none;border:0;color:#99a0ad;font-size:22px;line-height:1;padding:0 6px}
  .ins-tabs{display:flex;gap:6px;padding:8px 12px;border-bottom:1px solid #2b303a}
  .ins-tab{flex:1;padding:8px;border:1px solid #2b303a;border-radius:8px;background:none;
    color:#99a0ad;font-size:13px}
  .ins-tab.on{background:#1c1f26;color:#e8eaee;border-color:#6fae5f}
  .ins-body{flex:1;overflow-y:auto;padding:10px 12px 30px}
  .ins-chips{display:flex;flex-wrap:wrap;gap:6px;margin-bottom:10px}
  .ins-chip{padding:5px 11px;border:1px solid #2b303a;border-radius:14px;background:none;
    color:#99a0ad;font-size:12px}
  .ins-chip.on{background:#1c1f26;color:#e8eaee;border-color:#6fae5f}
  .ins-chip b{color:#6fae5f;font-weight:600}
  .ins-tools{display:flex;gap:6px;margin-bottom:8px}
  .ins-tools input{flex:1;min-width:0;background:#1c1f26;border:1px solid #2b303a;
    border-radius:8px;color:#e8eaee;padding:8px;font-size:13px}
  .ins-b{padding:8px 12px;border:1px solid #2b303a;border-radius:8px;background:#1c1f26;
    color:#e8eaee;font-size:12px;white-space:nowrap}
  .ins-row{padding:9px 0;border-bottom:1px solid #22262e}
  .ins-u{word-break:break-all;color:#8ab4f8;font-size:12px}
  .ins-m{display:flex;flex-wrap:wrap;gap:8px;margin-top:3px;color:#99a0ad;font-size:11px}
  .ins-tag{color:#6fae5f}
  .ins-act{display:flex;gap:6px;margin-top:6px}
  .ins-act button{padding:4px 10px;border:1px solid #2b303a;border-radius:6px;
    background:none;color:#99a0ad;font-size:11px}
  .ins-code{white-space:pre-wrap;word-break:break-word;background:#101216;border:1px solid #2b303a;
    border-radius:8px;padding:10px;font:11px/1.5 monospace;color:#cfd4dc;max-height:none}
  .ins-code mark{background:#6fae5f;color:#10130f}
  .ins-t{width:100%;border-collapse:collapse;font-size:12px}
  .ins-t td{padding:5px 6px;border-bottom:1px solid #22262e;vertical-align:top;word-break:break-word}
  .ins-t td:first-child{color:#99a0ad;width:38%}
  .ins-h{margin:16px 0 6px;font-size:12px;color:#99a0ad;text-transform:uppercase}
  .ins-empty{color:#99a0ad;padding:24px 0;text-align:center}
  .ins-dl{border-color:#3d5c34!important;color:#8fce7c!important}
  .ins-au{border-color:#3a4d68!important;color:#8ab4f8!important}
  .ins-out{background:#101216;border:1px solid #2b303a;border-radius:8px;padding:8px;
    font:11px/1.5 monospace;max-height:44vh;overflow-y:auto;margin-bottom:8px}
  .ins-l{padding:3px 0;border-bottom:1px solid #191c22;white-space:pre-wrap;
    word-break:break-word}
  .ins-l-in{color:#8ab4f8}
  .ins-l-out{color:#8fce7c}
  .ins-l-error{color:#e08a72}
  .ins-l-warn{color:#d9c07c}
  .ins-l-log,.ins-l-info,.ins-l-debug{color:#cfd4dc}
  .ins-prompt input{font-family:monospace}
  .ins-note{font-size:11px;color:#99a0ad;line-height:1.5;margin-top:8px}
  .ins-note code{color:#e8eaee}
  .ins-sel{background:#1c1f26;border:1px solid #2b303a;border-radius:8px;color:#e8eaee;
    padding:8px;font-size:12px}
  .ins-s-ok{color:#6fae5f}
  .ins-s-red{color:#d9c07c}
  .ins-s-err{color:#e08a72}
  .ins-s-blk{color:#c06a8a}
  .ins-col{display:flex;gap:5px;margin-bottom:6px;flex-wrap:wrap;align-items:center}
  .ins-col input{flex:1 1 90px;min-width:0;background:#1c1f26;border:1px solid #2b303a;
    border-radius:7px;color:#e8eaee;padding:7px;font-size:12px}
  .ins-col .ins-b{flex:0 0 auto;padding:7px 11px}
  .ins-prev{overflow-x:auto;margin:10px 0}
  .ins-prev table{min-width:100%}
  .ins-prev th{text-align:left;color:#6fae5f;font-weight:600;font-size:11px;
    padding:5px 6px;border-bottom:1px solid #2b303a;white-space:nowrap}
  .ins-prev td{font-size:11px;max-width:190px}
  .ins-ck{margin-right:7px;vertical-align:middle}
  .ins-u{display:block;cursor:pointer}
  .ins-hits{color:#99a0ad;font-size:12px;align-self:center}`;

  let tab = "res";

  function paint() {
    const body = root.querySelector(".ins-body");
    body.innerHTML = tab === "res"     ? renderResources()
                   : tab === "code"    ? renderCode()
                   : tab === "console" ? renderConsole()
                   : tab === "net"     ? renderNet()
                   : tab === "extract" ? renderExtract()
                   : renderInfo();
    wire();
    if (tab === "console") paintConsole();
    if (tab === "net") loadNet();
    if (tab === "extract") { if (!exRow) exAuto(); exPreview(); }
  }

  function wire() {
    const body = root.querySelector(".ins-body");

    body.querySelectorAll(".ins-chip[data-t]").forEach(b => {
      b.onclick = () => {
        const t = b.dataset.t;
        if (filters.has(t)) filters.delete(t); else filters.add(t);
        paint();
      };
    });
    const clear = body.querySelector("#ins-clear");
    if (clear) clear.onclick = () => { filters.clear(); paint(); };

    const q = body.querySelector("#ins-q");
    if (q) q.oninput = () => { textFilter = q.value; refreshList(); };
    const ex = body.querySelector("#ins-ext");
    if (ex) ex.oninput = () => { customExt = ex.value; refreshList(); };

    const sizes = body.querySelector("#ins-sizes");
    if (sizes) sizes.onclick = () => measure(sizes);

    const copyAll = body.querySelector("#ins-copyall");
    if (copyAll) copyAll.onclick = () => {
      copy(filtered().map(r => r.url).join("\n"));
      copyAll.textContent = "Copie";
    };

    body.querySelectorAll(".ins-ck").forEach(cb => {
      cb.onchange = () => {
        if (cb.checked) selected.add(cb.dataset.k);
        else selected.delete(cb.dataset.k);
        updateSelCount();
      };
    });

    const selAll = body.querySelector("#ins-sel-all");
    if (selAll) selAll.onclick = () => {
      filtered().forEach(r => selected.add(r.url));
      refreshList();
    };
    const selNone = body.querySelector("#ins-sel-none");
    if (selNone) selNone.onclick = () => { selected.clear(); refreshList(); };

    const dlAll = body.querySelector("#ins-dl-all");
    if (dlAll) dlAll.onclick = () => download(filtered().map(r => r.url), dlAll);

    const dlSel = body.querySelector("#ins-dl-sel");
    if (dlSel) dlSel.onclick = () => download(Array.from(selected), dlSel);

    const saveList = body.querySelector("#ins-savelist");
    if (saveList) saveList.onclick = () => saveUrlList(saveList);

    const auAll = body.querySelector("#ins-audio-all");
    if (auAll) auAll.onclick = () => extractAudio(
      filtered().filter(r => r.type === "video" || r.type === "audio").map(r => r.url), auAll);

    body.querySelectorAll("[data-audio]").forEach(b => {
      b.onclick = () => extractAudio([filtered()[+b.dataset.audio].url], b);
    });

    body.querySelectorAll("[data-dl]").forEach(b => {
      b.onclick = () => download([filtered()[+b.dataset.dl].url], b);
    });

    body.querySelectorAll("[data-open]").forEach(b => {
      b.onclick = () => { location.href = filtered()[+b.dataset.open].url; };
    });
    body.querySelectorAll("[data-copy]").forEach(b => {
      b.onclick = () => { copy(filtered()[+b.dataset.copy].url); b.textContent = "Copie"; };
    });
    body.querySelectorAll("[data-src]").forEach(b => {
      b.onclick = () => showResource(filtered()[+b.dataset.src]);
    });

    const dom = body.querySelector("#ins-dom");
    if (dom) dom.onclick = () => setCode(document.documentElement.outerHTML);
    const orig = body.querySelector("#ins-orig");
    if (orig) orig.onclick = () => fetchOriginal();
    const cc = body.querySelector("#ins-copycode");
    if (cc) cc.onclick = () => {
      copy(body.querySelector("#ins-code").textContent);
      cc.textContent = "Copie";
    };
    const find = body.querySelector("#ins-find");
    if (find) find.oninput = () => highlight(find.value);

    // --- Console ---
    const runBtn = body.querySelector("#ins-run");
    const input = body.querySelector("#ins-in");
    if (runBtn && input) {
      const go = () => {
        const v = input.value;
        input.value = "";
        runCode(v);
      };
      runBtn.onclick = go;
      input.onkeydown = e => {
        if (e.key === "Enter") { e.preventDefault(); go(); }
        if (e.key === "ArrowUp" && history.length) {
          histPos = Math.max(0, histPos - 1);
          input.value = history[histPos] || "";
        }
        if (e.key === "ArrowDown" && history.length) {
          histPos = Math.min(history.length, histPos + 1);
          input.value = history[histPos] || "";
        }
      };
    }
    const prev = body.querySelector("#ins-prev");
    if (prev && input) prev.onclick = () => {
      if (!history.length) return;
      histPos = Math.max(0, histPos - 1);
      input.value = history[histPos] || "";
      input.focus();
    };
    const cmode = body.querySelector("#ins-cmode");
    if (cmode) cmode.onclick = () => {
      consoleMode = consoleMode === "page" ? "sandbox" : "page";
      cmode.textContent = "Contexte : " + (consoleMode === "page" ? "page" : "isole");
    };
    const cclear = body.querySelector("#ins-cclear");
    if (cclear) cclear.onclick = () => { consoleLog.length = 0; paintConsole(); };
    const ccopy = body.querySelector("#ins-ccopy");
    if (ccopy) ccopy.onclick = () => {
      copy(consoleLog.map(l => l.kind + "\t" + l.text).join("\n"));
      ccopy.textContent = "Copie";
    };

    // --- Reseau ---
    const nref = body.querySelector("#ins-nrefresh");
    if (nref) nref.onclick = () => loadNet();
    const nclr = body.querySelector("#ins-nclear");
    if (nclr) nclr.onclick = async () => {
      try { await browser.runtime.sendMessage({ type: "netClear" }); } catch (e) { }
      netEntries = [];
      paintNet();
    };
    const ncopy = body.querySelector("#ins-ncopy");
    if (ncopy) ncopy.onclick = () => { copy(netCsv()); ncopy.textContent = "Copie"; };
    const nq = body.querySelector("#ins-nq");
    if (nq) nq.oninput = () => { netFilter = nq.value; paintNet(); };
    const ntype = body.querySelector("#ins-ntype");
    if (ntype) ntype.onchange = () => { netOnly = ntype.value; paintNet(); };

    // --- Extraire ---
    const exr = body.querySelector("#ex-row");
    if (exr) exr.oninput = () => { exRow = exr.value.trim(); exPreview(); };

    const exa = body.querySelector("#ex-auto");
    if (exa) exa.onclick = () => { exAuto(); paint(); };

    const exp = body.querySelector("#ex-pick");
    if (exp) exp.onclick = () => exPick();

    const exac = body.querySelector("#ex-autocol");
    if (exac) exac.onclick = () => { exAutoCols(); paint(); };

    const exadd = body.querySelector("#ex-addcol");
    if (exadd) exadd.onclick = () => {
      exCols.push({ name: "colonne" + (exCols.length + 1), sel: "", attr: "texte" });
      paint();
    };

    body.querySelectorAll("[data-cn]").forEach(inp => {
      inp.oninput = () => { exCols[+inp.dataset.cn].name = inp.value; exPreview(); };
    });
    body.querySelectorAll("[data-cs]").forEach(inp => {
      inp.oninput = () => { exCols[+inp.dataset.cs].sel = inp.value.trim(); exPreview(); };
    });
    body.querySelectorAll("[data-ca]").forEach(sel => {
      sel.onchange = () => {
        const i = +sel.dataset.ca;
        exCols[i].attr = sel.value;
        const x = body.querySelector(`[data-cx="${i}"]`);
        if (x) x.style.display = sel.value === "attribut" ? "" : "none";
        exPreview();
      };
    });
    body.querySelectorAll("[data-cx]").forEach(inp => {
      inp.oninput = () => { exCols[+inp.dataset.cx].extra = inp.value.trim(); exPreview(); };
    });
    body.querySelectorAll("[data-cd]").forEach(b => {
      b.onclick = () => { exCols.splice(+b.dataset.cd, 1); paint(); };
    });

    const expg = body.querySelector("#ex-pages");
    if (expg) expg.oninput = () => {
      exPages = Math.max(1, Math.min(50, parseInt(expg.value, 10) || 1));
    };

    const exrun = body.querySelector("#ex-run");
    if (exrun) exrun.onclick = async () => {
      exrun.textContent = "Extraction…";
      await exRun();
      exrun.textContent = "Extraire";
    };

    const excsv = body.querySelector("#ex-csv");
    if (excsv) excsv.onclick = async () => {
      const n = await exSave("csv");
      excsv.textContent = n ? "Enregistre" : "Rien a enregistrer";
      setTimeout(() => { excsv.textContent = "Enregistrer en CSV"; }, 2500);
    };
    const exjson = body.querySelector("#ex-json");
    if (exjson) exjson.onclick = async () => {
      const n = await exSave("json");
      exjson.textContent = n ? "Enregistre" : "Rien a enregistrer";
      setTimeout(() => { exjson.textContent = "Enregistrer en JSON"; }, 2500);
    };
    const excopy = body.querySelector("#ex-copy");
    if (excopy) excopy.onclick = () => {
      if (!exData.length) { excopy.textContent = "Rien a copier"; return; }
      copy(toCsv(exData));
      excopy.textContent = "Copie";
      setTimeout(() => { excopy.textContent = "Copier"; }, 2500);
    };
  }

  // -------------------------------------------------------------------------
  //  Pointeur de ligne : designer l'element repete sur la page
  // -------------------------------------------------------------------------
  function exPick() {
    const saved = root.style.display;
    root.style.display = "none";

    const ov = document.createElement("div");
    ov.style.cssText = "position:fixed;z-index:2147483646;pointer-events:none;" +
      "display:none;background:rgba(138,180,248,.2);border:2px solid #8ab4f8";
    document.documentElement.appendChild(ov);

    const tip = document.createElement("div");
    tip.style.cssText = "position:fixed;left:0;right:0;bottom:0;z-index:2147483647;" +
      "background:#14161a;border-top:1px solid #2b303a;padding:10px 12px 14px;" +
      "font:12px -apple-system,Roboto,sans-serif;color:#e8eaee";
    tip.innerHTML = '<div id="xp-s" style="font-family:monospace;font-size:11px;' +
      'color:#8ab4f8;word-break:break-all;margin-bottom:8px">Touchez un element repete</div>' +
      '<div style="display:flex;gap:6px">' +
      '<button data-x="up" style="flex:1;padding:9px;border:1px solid #2b303a;' +
      'border-radius:7px;background:#1c1f26;color:#e8eaee">Parent</button>' +
      '<button data-x="ok" style="flex:1;padding:9px;border:1px solid #3d5c34;' +
      'border-radius:7px;background:#1c1f26;color:#8fce7c">Valider</button>' +
      '<button data-x="no" style="flex:1;padding:9px;border:1px solid #2b303a;' +
      'border-radius:7px;background:transparent;color:#99a0ad">Annuler</button></div>';
    document.documentElement.appendChild(tip);

    let cur = null;

    const selOf = el => {
      if (!el) return "";
      const cls = classOf(el);
      const tag = el.tagName.toLowerCase();
      // On vise la fratrie : un selecteur qui attrape plusieurs elements
      const parentSel = el.parentElement ? cssPath(el.parentElement) : "";
      const base = tag + (cls ? "." + CSS.escape(cls) : "");
      const full = parentSel ? parentSel + " > " + base : base;
      try {
        return document.querySelectorAll(full).length > 1 ? full : base;
      } catch (e) { return base; }
    };

    const show = () => {
      const s2 = cur ? selOf(cur) : "";
      let n = 0;
      try { n = s2 ? document.querySelectorAll(s2).length : 0; } catch (e) { }
      tip.querySelector("#xp-s").textContent = s2
        ? s2 + "   —   " + n + " element(s)" : "Touchez un element repete";
      if (cur) {
        const r = cur.getBoundingClientRect();
        Object.assign(ov.style, {
          display: "block", left: r.left + "px", top: r.top + "px",
          width: r.width + "px", height: r.height + "px"
        });
      }
    };

    const onTap = e => {
      if (tip.contains(e.target)) return;
      e.preventDefault(); e.stopPropagation();
      cur = e.target;
      show();
    };

    const finish = keep => {
      document.removeEventListener("click", onTap, true);
      ov.remove(); tip.remove();
      root.style.display = saved || "flex";
      if (keep && cur) {
        exRow = selOf(cur);
        exCols = [];
        exAutoCols();
        paint();
      }
    };

    tip.addEventListener("click", e => {
      const a = e.target.getAttribute && e.target.getAttribute("data-x");
      if (!a) return;
      e.preventDefault(); e.stopPropagation();
      if (a === "no") finish(false);
      if (a === "ok") finish(true);
      if (a === "up" && cur && cur.parentElement) { cur = cur.parentElement; show(); }
    }, true);

    document.addEventListener("click", onTap, true);
  }

  function refreshList() {
    const list = root.querySelector(".ins-list");
    if (!list) return paint();
    const items = filtered();
    list.innerHTML = items.length
      ? items.map((r, i) => rowHtml(r, i)).join("")
      : '<div class="ins-empty">Aucune ressource pour ce filtre.</div>';
    const all = root.querySelector("#ins-dl-all");
    if (all) all.textContent = "Telecharger ces " + items.length;
    wire();
  }

  function copy(text) {
    try { navigator.clipboard.writeText(text); } catch (e) { }
  }

  // -------------------------------------------------------------------------
  //  Console
  // -------------------------------------------------------------------------
  function fmt(v, depth) {
    depth = depth || 0;
    try {
      if (v === undefined) return "undefined";
      if (v === null) return "null";
      if (v instanceof Error) return v.name + ": " + v.message;
      if (typeof v === "function") return "\u0192 " + (v.name || "anonyme") + "()";
      if (typeof v === "string") return depth ? JSON.stringify(v) : v;
      if (typeof v !== "object") return String(v);
      if (v.nodeType === 1) {
        const html = v.outerHTML || "";
        return html.length > 400 ? html.slice(0, 400) + "…" : html;
      }
      if (v.nodeType) return String(v);
      if (depth > 2) return Array.isArray(v) ? "[…]" : "{…}";
      if (Array.isArray(v)) {
        const head = v.slice(0, 40).map(x => fmt(x, depth + 1));
        return "[" + head.join(", ") + (v.length > 40 ? ", …" + v.length : "") + "]";
      }
      if (v instanceof NodeList || v instanceof HTMLCollection) {
        return fmt(Array.from(v), depth);
      }
      const keys = Object.keys(v).slice(0, 40);
      return "{" + keys.map(k => k + ": " + fmt(v[k], depth + 1)).join(", ") + "}";
    } catch (e) {
      return "[non affichable]";
    }
  }

  function appendLogLine(entry) {
    const box = root && root.querySelector("#ins-out");
    if (!box) return;
    const div = document.createElement("div");
    div.className = "ins-l ins-l-" + entry.kind;
    div.textContent = (entry.kind === "in" ? "\u203A " :
                       entry.kind === "out" ? "\u2039 " : "") + entry.text;
    box.appendChild(div);
    box.scrollTop = box.scrollHeight;
  }

  function paintConsole() {
    const box = root.querySelector("#ins-out");
    if (!box) return;
    box.innerHTML = "";
    consoleLog.forEach(appendLogLine);
    box.scrollTop = box.scrollHeight;
  }

  // Execution dans le contexte de la page, via un evenement de retour.
  function runInPageCtx(src) {
    return new Promise(resolve => {
      const id = "gb" + Date.now() + Math.random().toString(36).slice(2);
      const onDone = ev => {
        try {
          const d = JSON.parse(ev.detail);
          if (d.id !== id) return;
          document.removeEventListener("__gbEval", onDone);
          resolve(d);
        } catch (e) { }
      };
      document.addEventListener("__gbEval", onDone);

      const wrapper =
        "(function(){var __id=" + JSON.stringify(id) + ";" +
        "var $=function(s){return document.querySelector(s)};" +
        "var $$=function(s){return Array.from(document.querySelectorAll(s))};" +
        "function reply(ok,val){try{document.dispatchEvent(new CustomEvent('__gbEval'," +
        "{detail:JSON.stringify({id:__id,ok:ok,val:String(val)})}));}catch(e){}}" +
        "try{var r;try{r=eval(" + JSON.stringify("(" + src + "\n)") + ");}" +
        "catch(e){r=eval(" + JSON.stringify(src) + ");}" +
        "if(r&&typeof r.then==='function'){r.then(function(v){reply(true,v)}," +
        "function(e){reply(false,e)});}else{" +
        "reply(true, (r&&r.nodeType===1)?r.outerHTML.slice(0,400):" +
        "(typeof r==='object'&&r!==null)?JSON.stringify(r):r);}}" +
        "catch(e){reply(false,e);}})();";

      try {
        const el = document.createElement("script");
        el.textContent = wrapper;
        (document.head || document.documentElement).appendChild(el);
        el.remove();
      } catch (e) {
        document.removeEventListener("__gbEval", onDone);
        resolve({ ok: false, val: "injection refusee : " + e });
      }

      setTimeout(() => {
        document.removeEventListener("__gbEval", onDone);
        resolve({ ok: false, val: "aucune reponse (politique de securite du site ?)" });
      }, 4000);
    });
  }

  async function runCode(src) {
    if (!src.trim()) return;
    pushLog("in", src);
    history.push(src);
    histPos = history.length;

    if (consoleMode === "page") {
      const r = await runInPageCtx(src);
      pushLog(r.ok ? "out" : "error", r.val);
      return;
    }

    try {
      const $ = sel => document.querySelector(sel);
      const $$ = sel => Array.from(document.querySelectorAll(sel));
      const unsafeWindow = window.wrappedJSObject || window;
      let fn;
      try {
        fn = new Function("$", "$$", "unsafeWindow", "return (" + src + "\n)");
      } catch (e) {
        fn = new Function("$", "$$", "unsafeWindow", src);
      }
      let r = fn($, $$, unsafeWindow);
      if (r && typeof r.then === "function") r = await r;
      pushLog("out", fmt(r));
    } catch (e) {
      pushLog("error", (e && e.name ? e.name + ": " : "") + (e && e.message ? e.message : e));
    }
  }

  // -------------------------------------------------------------------------
  //  Reseau
  // -------------------------------------------------------------------------
  async function loadNet() {
    try {
      const res = await browser.runtime.sendMessage({
        type: "netLog", origin: location.origin
      });
      netEntries = (res && res.entries) || [];
    } catch (e) {
      netEntries = [];
    }
    paintNet();
  }

  function netFiltered() {
    return netEntries.filter(e => {
      if (netOnly && e.type !== netOnly) return false;
      if (netFilter && e.url.toLowerCase().indexOf(netFilter.toLowerCase()) === -1) return false;
      return true;
    }).slice().reverse();
  }

  function statusClass(e) {
    if (e.blocked) return "ins-s-blk";
    if (e.error) return "ins-s-err";
    if (e.status >= 400) return "ins-s-err";
    if (e.status >= 300) return "ins-s-red";
    if (e.status) return "ins-s-ok";
    return "";
  }

  function paintNet() {
    const box = root.querySelector("#ins-nlist");
    if (!box) return;
    const list = netFiltered();
    if (!list.length) {
      box.innerHTML = '<div class="ins-empty">Aucune requete. Rechargez la page ' +
        'puis actualisez.</div>';
      return;
    }
    box.innerHTML = list.map((e, i) => `
      <div class="ins-row">
        <div class="ins-u">${esc(e.url)}</div>
        <div class="ins-m">
          <span class="${statusClass(e)}">${e.blocked ? "bloque"
            : e.error ? esc(e.error) : (e.status || "…")}</span>
          <span>${esc(e.method)}</span>
          <span class="ins-tag">${esc(e.type)}</span>
          ${e.mime ? `<span>${esc(e.mime)}</span>` : ""}
          ${e.size ? `<span>${human(e.size)}</span>` : ""}
          ${e.ms != null ? `<span>${e.ms} ms</span>` : ""}
        </div>
        <div class="ins-act">
          <button data-nopen="${i}">Ouvrir</button>
          <button data-ncopy="${i}">Copier</button>
          <button data-nbody="${i}">Reponse</button>
        </div>
      </div>`).join("");

    box.querySelectorAll("[data-nopen]").forEach(b => {
      b.onclick = () => { location.href = netFiltered()[+b.dataset.nopen].url; };
    });
    box.querySelectorAll("[data-ncopy]").forEach(b => {
      b.onclick = () => { copy(netFiltered()[+b.dataset.ncopy].url); b.textContent = "Copie"; };
    });
    box.querySelectorAll("[data-nbody]").forEach(b => {
      b.onclick = () => showResource({ url: netFiltered()[+b.dataset.nbody].url });
    });
  }

  function netCsv() {
    const rows = [["statut", "methode", "type", "mime", "octets", "ms", "url"]];
    netFiltered().forEach(e => rows.push([
      e.blocked ? "bloque" : (e.error || e.status || ""),
      e.method, e.type, e.mime, e.size || "", e.ms == null ? "" : e.ms, e.url
    ]));
    return rows.map(r => r.map(c => '"' + String(c).replace(/"/g, '""') + '"').join(",")).join("\n");
  }

  function updateSelCount() {
    const b = root.querySelector("#ins-dl-sel");
    if (b) b.textContent = selected.size
      ? "Telecharger la selection (" + selected.size + ")"
      : "Telecharger la selection";
  }

  // Le telechargement est confie a l'application : elle gere le binaire,
  // les gros fichiers, et passe par Tor si le mode est actif.
  async function download(urls, btn) {
    urls = (urls || []).filter(Boolean);
    if (!urls.length) {
      if (btn) btn.textContent = "Rien a telecharger";
      return;
    }
    if (urls.length > 25 &&
        !confirm(urls.length + " fichiers vont etre telecharges. Continuer ?")) {
      return;
    }

    const label = btn ? btn.textContent : "";
    if (btn) btn.textContent = "Envoi…";
    try {
      const res = await browser.runtime.sendMessage({
        type: "downloadUrls", urls: urls, referer: location.href
      });
      if (btn) {
        btn.textContent = (res && res.ok)
          ? urls.length + " en cours"
          : "Echec : " + ((res && res.error) || "inconnu");
        setTimeout(() => { btn.textContent = label; }, 3000);
      }
    } catch (e) {
      if (btn) btn.textContent = "Echec";
    }
  }

  // L'extraction est faite par l'application : recopie de la piste audio
  // sans reencodage, et via Tor si le mode est actif.
  async function extractAudio(urls, btn) {
    urls = (urls || []).filter(Boolean);
    const streams = urls.filter(u => /\.m3u8|\.mpd/i.test(u));
    urls = urls.filter(u => !/\.m3u8|\.mpd/i.test(u));

    if (!urls.length) {
      if (btn) btn.textContent = streams.length
        ? "Flux segmente non pris en charge"
        : "Aucun media";
      return;
    }
    if (urls.length > 10 &&
        !confirm(urls.length + " extractions vont etre lancees. Continuer ?")) {
      return;
    }

    const label = btn ? btn.textContent : "";
    if (btn) btn.textContent = "Envoi…";
    try {
      const res = await browser.runtime.sendMessage({
        type: "extractAudio", urls: urls, referer: location.href
      });
      if (btn) {
        btn.textContent = (res && res.ok)
          ? urls.length + " en cours"
          : "Echec : " + ((res && res.error) || "inconnu");
        setTimeout(() => { btn.textContent = label; }, 3000);
      }
    } catch (e) {
      if (btn) btn.textContent = "Echec";
    }
  }

  async function saveUrlList(btn) {
    const items = selected.size
      ? Array.from(selected)
      : filtered().map(r => r.url);
    if (!items.length) return;

    let host = "page";
    try { host = location.hostname.replace(/^www\./, ""); } catch (e) { }
    const name = host + "-ressources.txt";

    btn.textContent = "Envoi…";
    try {
      await browser.runtime.sendMessage({
        type: "downloadText", name: name,
        text: "# " + location.href + "\n# " + items.length + " ressources\n\n"
              + items.join("\n") + "\n"
      });
      btn.textContent = "Liste enregistree";
    } catch (e) {
      btn.textContent = "Echec";
    }
    setTimeout(() => { btn.textContent = "Enregistrer la liste"; }, 3000);
  }

  // -------------------------------------------------------------------------
  //  Mesure des tailles (requetes HEAD via l'extension, sans CORS)
  // -------------------------------------------------------------------------
  async function measure(btn) {
    const list = filtered().filter(r => !r.size);
    if (!list.length) { btn.textContent = "Deja mesure"; return; }
    btn.textContent = "Mesure…";

    let done = 0;
    const batch = 6;
    for (let i = 0; i < list.length; i += batch) {
      await Promise.all(list.slice(i, i + batch).map(async r => {
        try {
          const res = await browser.runtime.sendMessage({
            type: "gmFetch", url: r.url, method: "HEAD"
          });
          if (res && res.headers) {
            const len = res.headers["content-length"] || res.headers["Content-Length"];
            if (len) r.size = parseInt(len, 10);
          }
        } catch (e) { }
        done++;
      }));
      btn.textContent = "Mesure… " + done + "/" + list.length;
    }
    btn.textContent = "Mesure terminee";
    refreshList();
  }

  // -------------------------------------------------------------------------
  //  Code
  // -------------------------------------------------------------------------
  let codeRaw = "";

  function setCode(text) {
    codeRaw = text;
    const pre = root.querySelector("#ins-code");
    if (pre) pre.textContent = text;
    const find = root.querySelector("#ins-find");
    if (find && find.value) highlight(find.value);
  }

  async function fetchOriginal() {
    const pre = root.querySelector("#ins-code");
    pre.textContent = "Telechargement…";
    try {
      const res = await browser.runtime.sendMessage({
        type: "gmFetch", url: location.href, method: "GET"
      });
      setCode(res && res.body ? res.body : "Reponse vide.");
    } catch (e) {
      setCode("Telechargement impossible : " + e);
    }
  }

  async function showResource(r) {
    tab = "code";
    paint();
    const pre = root.querySelector("#ins-code");
    pre.textContent = "Telechargement…";
    try {
      const res = await browser.runtime.sendMessage({
        type: "gmFetch", url: r.url, method: "GET"
      });
      setCode(res && res.body ? res.body : "Reponse vide.");
    } catch (e) {
      setCode("Telechargement impossible : " + e);
    }
  }

  function highlight(term) {
    const pre = root.querySelector("#ins-code");
    const hits = root.querySelector("#ins-hits");
    if (!pre) return;
    if (!term) { pre.textContent = codeRaw; if (hits) hits.textContent = ""; return; }

    const re = new RegExp(term.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"), "gi");
    let n = 0;
    pre.innerHTML = esc(codeRaw).replace(re, m => { n++; return "<mark>" + esc(m) + "</mark>"; });
    if (hits) hits.textContent = n + " occurrence(s)";
  }

  // -------------------------------------------------------------------------
  //  Ouverture et fermeture
  // -------------------------------------------------------------------------
  function open() {
    if (root) { close(); return; }
    collect();

    root = document.createElement("div");
    root.id = "ins-root";
    root.innerHTML = `
      <div class="ins-head">
        <b>Analyse de la page</b>
        <button class="ins-x" id="ins-close">&times;</button>
      </div>
      <div class="ins-tabs">
        <button class="ins-tab on" data-tab="res">Ressources</button>
        <button class="ins-tab" data-tab="code">Code</button>
        <button class="ins-tab" data-tab="console">Console</button>
        <button class="ins-tab" data-tab="net">Reseau</button>
        <button class="ins-tab" data-tab="extract">Extraire</button>
        <button class="ins-tab" data-tab="info">Infos</button>
      </div>
      <div class="ins-body"></div>`;

    const style = document.createElement("style");
    style.textContent = CSS;
    root.appendChild(style);

    document.documentElement.appendChild(root);

    root.querySelector("#ins-close").onclick = close;
    root.querySelectorAll(".ins-tab").forEach(b => {
      b.onclick = () => {
        root.querySelectorAll(".ins-tab").forEach(x => x.classList.remove("on"));
        b.classList.add("on");
        tab = b.dataset.tab;
        paint();
      };
    });

    tab = "res";
    paint();
  }

  function close() {
    if (root) { root.remove(); root = null; }
  }

  // Declenchement depuis le panneau flottant ou le menu de l'application
  window.__inspectPage = open;

  browser.storage.onChanged.addListener(changes => {
    if (changes.inspectRequest) open();
    const c = changes.pageCommand && changes.pageCommand.newValue;
    if (c && c.cmd === "inspect") open();
  });
})();
