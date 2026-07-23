"use strict";

// ===========================================================================
//  saver.js -- archive la page courante dans un seul fichier HTML.
//  Les feuilles de style, images et polices sont incorporees en donnees
//  encodees, ce qui rend le fichier lisible hors ligne et deplacable.
// ===========================================================================

(function () {

  if (window.top !== window.self) return;

  let dialog = null;
  let cancelled = false;

  const MAX_ONE = 2 * 1024 * 1024;     // 2 Mo par ressource
  const MAX_TOTAL = 12 * 1024 * 1024;  // 12 Mo au total
  let used = 0;

  const cache = new Map();

  // -------------------------------------------------------------------------
  function esc(s) {
    return String(s == null ? "" : s)
      .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
  }

  function abs(u, base) {
    try { return new URL(u, base || location.href).href; } catch (e) { return null; }
  }

  function human(n) {
    if (n < 1024) return n + " o";
    if (n < 1048576) return Math.round(n / 1024) + " Ko";
    return (n / 1048576).toFixed(1) + " Mo";
  }

  // Recupere une ressource et la renvoie en adresse de donnees
  async function inline(url) {
    if (!url) return null;
    if (/^data:/i.test(url)) return url;
    if (cache.has(url)) return cache.get(url);
    if (used >= MAX_TOTAL) return null;

    try {
      const res = await browser.runtime.sendMessage({
        type: "gmFetch", url: url, method: "GET",
        binary: true, maxBytes: MAX_ONE
      });
      if (!res || res.error || !res.base64) {
        cache.set(url, null);
        return null;
      }
      used += res.bytes || 0;
      const mime = res.mime || guessMime(url);
      const out = "data:" + mime + ";base64," + res.base64;
      cache.set(url, out);
      return out;
    } catch (e) {
      cache.set(url, null);
      return null;
    }
  }

  function guessMime(url) {
    const m = (url.split("?")[0].match(/\.([a-z0-9]{1,5})$/i) || [])[1];
    const map = {
      png: "image/png", jpg: "image/jpeg", jpeg: "image/jpeg", gif: "image/gif",
      webp: "image/webp", avif: "image/avif", svg: "image/svg+xml",
      ico: "image/x-icon", woff: "font/woff", woff2: "font/woff2",
      ttf: "font/ttf", otf: "font/otf", css: "text/css"
    };
    return map[(m || "").toLowerCase()] || "application/octet-stream";
  }

  // Texte brut (feuilles de style)
  async function fetchText(url) {
    try {
      const res = await browser.runtime.sendMessage({
        type: "gmFetch", url: url, method: "GET"
      });
      return res && res.body ? res.body : null;
    } catch (e) { return null; }
  }

  // -------------------------------------------------------------------------
  //  Feuilles de style : incorporation des url() et resolution des @import
  // -------------------------------------------------------------------------
  async function processCss(css, base, depth) {
    if (!css || depth > 2) return css || "";

    // @import
    const imports = Array.from(css.matchAll(/@import\s+(?:url\()?["']?([^"')\s]+)["']?\)?[^;]*;/gi));
    for (const m of imports) {
      const u = abs(m[1], base);
      let sub = "";
      if (u) {
        const text = await fetchText(u);
        if (text) sub = await processCss(text, u, depth + 1);
      }
      css = css.replace(m[0], sub);
    }

    // url(...)
    const urls = Array.from(css.matchAll(/url\(\s*["']?([^"')]+)["']?\s*\)/gi));
    for (const m of urls) {
      const raw = m[1].trim();
      if (/^data:/i.test(raw)) continue;
      const u = abs(raw, base);
      if (!u) continue;
      const d = await inline(u);
      if (d) css = css.split(m[0]).join("url(" + d + ")");
    }
    return css;
  }

  // -------------------------------------------------------------------------
  //  Construction de l'archive
  // -------------------------------------------------------------------------
  async function build(opts, progress) {
    used = 0;
    cache.clear();

    const doc = document.cloneNode(true);

    // Elements de l'extension : ils n'ont rien a faire dans l'archive
    doc.querySelectorAll("#ins-root, #fp-root, #gb-user-styles, #gb-save," +
      " [data-autopager]").forEach(el => el.remove());

    if (!opts.scripts) {
      doc.querySelectorAll("script").forEach(el => el.remove());
      doc.querySelectorAll("noscript").forEach(el => el.remove());
    }
    doc.querySelectorAll("[integrity]").forEach(el => el.removeAttribute("integrity"));
    doc.querySelectorAll("link[rel~='preload'], link[rel~='prefetch'], " +
      "link[rel~='dns-prefetch'], link[rel~='preconnect']").forEach(el => el.remove());

    // --- Styles ---
    progress("Feuilles de style…");
    const links = Array.from(doc.querySelectorAll("link[rel~='stylesheet'][href]"));
    for (const link of links) {
      if (cancelled) return null;
      const u = abs(link.getAttribute("href"));
      let css = u ? await fetchText(u) : null;
      if (css) {
        css = await processCss(css, u, 0);
        const st = doc.createElement("style");
        st.textContent = css;
        if (link.media) st.media = link.media;
        link.replaceWith(st);
      } else {
        link.remove();
      }
    }

    const inlineStyles = Array.from(doc.querySelectorAll("style"));
    for (const st of inlineStyles) {
      if (cancelled) return null;
      st.textContent = await processCss(st.textContent, location.href, 0);
    }

    // La feuille personnelle du site, si elle existe, est appliquee telle quelle
    const own = document.getElementById("gb-user-styles");
    if (own && own.textContent.trim()) {
      const st = doc.createElement("style");
      st.textContent = own.textContent;
      (doc.head || doc.documentElement).appendChild(st);
    }

    // --- Images ---
    if (opts.images) {
      const imgs = Array.from(doc.querySelectorAll("img"));
      let i = 0;
      for (const img of imgs) {
        if (cancelled) return null;
        i++;
        if (i % 4 === 0) progress("Images " + i + " / " + imgs.length +
          "  (" + human(used) + ")");

        let src = img.getAttribute("src") || img.getAttribute("data-src");
        // srcset : on retient la premiere source et on abandonne le reste
        if (!src && img.getAttribute("srcset")) {
          src = img.getAttribute("srcset").split(",")[0].trim().split(/\s+/)[0];
        }
        img.removeAttribute("srcset");
        img.removeAttribute("loading");

        const u = abs(src);
        const d = u ? await inline(u) : null;
        if (d) img.setAttribute("src", d);
        else if (u) img.setAttribute("src", u);
      }

      doc.querySelectorAll("source[srcset]").forEach(el => el.remove());

      // Attributs style contenant des images de fond
      const styled = Array.from(doc.querySelectorAll("[style*='url(']"));
      for (const el of styled) {
        if (cancelled) return null;
        el.setAttribute("style",
          await processCss(el.getAttribute("style"), location.href, 1));
      }

      progress("Icones…");
      const icons = Array.from(doc.querySelectorAll("link[rel~='icon'][href]"));
      for (const ic of icons) {
        const u = abs(ic.getAttribute("href"));
        const d = u ? await inline(u) : null;
        if (d) ic.setAttribute("href", d); else ic.remove();
      }
    } else {
      doc.querySelectorAll("img, picture, source").forEach(el => el.remove());
    }

    // --- Ce qui ne peut pas etre archive ---
    doc.querySelectorAll("iframe, embed, object, video, audio").forEach(el => {
      const note = doc.createElement("div");
      note.setAttribute("style",
        "border:1px dashed #999;padding:10px;color:#777;font:13px sans-serif");
      const src = el.getAttribute("src") || el.getAttribute("data") || "";
      note.textContent = "[" + el.tagName.toLowerCase() + " non archive" +
        (src ? " : " + abs(src) : "") + "]";
      el.replaceWith(note);
    });

    // --- Adresses relatives restantes ---
    doc.querySelectorAll("a[href]").forEach(a => {
      const u = abs(a.getAttribute("href"));
      if (u) a.setAttribute("href", u);
      a.setAttribute("target", "_blank");
    });

    // --- En-tete d'archive ---
    const head = doc.head || doc.documentElement;
    const meta = doc.createElement("meta");
    meta.setAttribute("charset", "utf-8");
    head.insertBefore(meta, head.firstChild);

    const banner = doc.createElement("div");
    banner.setAttribute("style",
      "background:#1c1f26;color:#99a0ad;font:12px/1.5 sans-serif;" +
      "padding:8px 12px;border-bottom:1px solid #2b303a");
    banner.innerHTML = "Archive de <b style='color:#8ab4f8'>" + esc(location.href) +
      "</b><br>enregistree le " + esc(new Date().toLocaleString("fr-FR")) +
      " &middot; " + human(used) + " incorpores";
    if (doc.body) doc.body.insertBefore(banner, doc.body.firstChild);

    const html = "<!DOCTYPE html>\n<!-- Archive hors ligne — " +
      location.href + " — " + new Date().toISOString() + " -->\n" +
      doc.documentElement.outerHTML;

    return html;
  }

  // -------------------------------------------------------------------------
  //  Interface
  // -------------------------------------------------------------------------
  function close() {
    cancelled = true;
    if (dialog) { dialog.remove(); dialog = null; }
  }

  function open() {
    if (dialog) { close(); return; }
    cancelled = false;

    dialog = document.createElement("div");
    dialog.id = "gb-save";
    dialog.style.cssText =
      "position:fixed;inset:auto 0 0 0;z-index:2147483647;background:#14161a;" +
      "border-top:1px solid #2b303a;padding:14px 14px 20px;" +
      "font:13px/1.55 -apple-system,Roboto,sans-serif;color:#e8eaee;" +
      "box-shadow:0 -6px 24px rgba(0,0,0,.5)";
    dialog.innerHTML =
      '<div style="font-size:14px;font-weight:600;margin-bottom:10px">' +
      'Enregistrer la page en un fichier</div>' +
      '<label style="display:flex;gap:9px;margin:7px 0"><input type="checkbox" id="gs-img" checked>' +
      '<span>Incorporer les images et les polices</span></label>' +
      '<label style="display:flex;gap:9px;margin:7px 0"><input type="checkbox" id="gs-js">' +
      '<span>Conserver les scripts (deconseille)</span></label>' +
      '<div id="gs-st" style="color:#99a0ad;font-size:12px;margin:10px 0 12px;' +
      'min-height:18px">Les styles sont toujours incorpores.</div>' +
      '<div style="display:flex;gap:8px">' +
      '<button id="gs-go" style="flex:1;padding:11px;border:0;border-radius:9px;' +
      'background:#6fae5f;color:#10130f;font-weight:600;font-size:13px">Enregistrer</button>' +
      '<button id="gs-no" style="flex:1;padding:11px;border:1px solid #2b303a;' +
      'border-radius:9px;background:transparent;color:#99a0ad;font-size:13px">Annuler</button>' +
      "</div>";
    document.documentElement.appendChild(dialog);

    const st = dialog.querySelector("#gs-st");
    const progress = t => { if (st) st.textContent = t; };

    dialog.querySelector("#gs-no").onclick = close;
    dialog.querySelector("#gs-go").onclick = async () => {
      const go = dialog.querySelector("#gs-go");
      go.disabled = true;
      go.textContent = "En cours…";

      const opts = {
        images: dialog.querySelector("#gs-img").checked,
        scripts: dialog.querySelector("#gs-js").checked
      };

      let html = null;
      try {
        html = await build(opts, progress);
      } catch (e) {
        progress("Echec : " + e);
        go.disabled = false;
        go.textContent = "Enregistrer";
        return;
      }
      if (!html) return;

      progress("Ecriture du fichier… (" + human(html.length) + ")");

      let name = "page";
      try {
        name = (document.title || location.hostname)
          .replace(/[\\/:*?"<>|]/g, "_").trim().slice(0, 70) || "page";
      } catch (e) { }

      try {
        await browser.runtime.sendMessage({
          type: "downloadText", name: name + ".html", text: html
        });
        progress("Enregistre dans Telechargements : " + name + ".html");
        setTimeout(close, 2200);
      } catch (e) {
        progress("Enregistrement refuse : " + e);
        go.disabled = false;
        go.textContent = "Enregistrer";
      }
    };
  }

  browser.storage.onChanged.addListener(changes => {
    const c = changes.pageCommand && changes.pageCommand.newValue;
    if (c && c.cmd === "savePage") open();
  });
})();
