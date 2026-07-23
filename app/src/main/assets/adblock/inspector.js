"use strict";

// ===========================================================================
//  inspector.js -- analyse de la page courante.
//  Trois onglets : ressources filtrables par type, code source, informations.
//  Ouvert depuis le panneau flottant ou depuis le menu de l'application.
// ===========================================================================

(function () {

  if (window.top !== window.self) return;   // uniquement le cadre principal

  let root = null;
  let resources = [];
  let filters = new Set();
  let selected = new Set();
  let textFilter = "";
  let customExt = "";

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
  .ins-ck{margin-right:7px;vertical-align:middle}
  .ins-u{display:block;cursor:pointer}
  .ins-hits{color:#99a0ad;font-size:12px;align-self:center}`;

  let tab = "res";

  function paint() {
    const body = root.querySelector(".ins-body");
    body.innerHTML = tab === "res" ? renderResources()
                   : tab === "code" ? renderCode()
                   : renderInfo();
    wire();
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
