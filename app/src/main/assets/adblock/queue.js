"use strict";

// ===========================================================================
//  queue.js -- « lire plus tard », mais vraiment hors ligne.
//  L'article est nettoye, ses images incorporees, et conserve dans le
//  navigateur : il reste lisible sans reseau, meme si la page disparait.
// ===========================================================================

(function () {

  if (window.top !== window.self) return;

  const MAX_ITEMS = 60;
  const MAX_HTML = 400 * 1024;        // par article
  const MAX_IMG = 180 * 1024;         // par image
  const MAX_IMG_TOTAL = 1200 * 1024;  // images d'un meme article

  // Elements sans interet une fois hors ligne, ou nuisibles a la lecture
  const STRIP = "script, noscript, style, iframe, form, input, button, " +
    "nav, aside, footer, [class*='share'], [class*='social'], [class*='comment'], " +
    "[class*='related'], [class*='newsletter'], [class*='promo'], [id*='comment']";

  function esc(v) {
    return String(v == null ? "" : v).replace(/&/g, "&amp;").replace(/</g, "&lt;");
  }

  function abs(u) {
    try { return new URL(u, location.href).href; } catch (e) { return null; }
  }

  // -------------------------------------------------------------------------
  //  Incorporation des images
  // -------------------------------------------------------------------------
  let used = 0;

  async function inline(url) {
    if (!url || /^data:/i.test(url)) return url || null;
    if (used >= MAX_IMG_TOTAL) return null;
    try {
      const res = await browser.runtime.sendMessage({
        type: "gmFetch", url: url, method: "GET", binary: true, maxBytes: MAX_IMG
      });
      if (!res || res.error || !res.base64) return null;
      used += res.bytes || 0;
      return "data:" + (res.mime || "image/jpeg") + ";base64," + res.base64;
    } catch (e) { return null; }
  }

  // -------------------------------------------------------------------------
  //  Extraction et nettoyage
  // -------------------------------------------------------------------------
  async function buildArticle(withImages, progress) {
    const root = GB.mainContainer(document);
    if (!root) return null;

    const copy = root.cloneNode(true);
    copy.querySelectorAll(STRIP).forEach(el => el.remove());
    copy.querySelectorAll("*").forEach(el => {
      // On ne garde que ce qui sert a la lecture
      Array.from(el.attributes).forEach(a => {
        if (!/^(href|src|alt|title|colspan|rowspan)$/i.test(a.name)) {
          el.removeAttribute(a.name);
        }
      });
    });

    copy.querySelectorAll("a[href]").forEach(a => {
      const u = abs(a.getAttribute("href"));
      if (u) a.setAttribute("href", u);
    });

    used = 0;
    const imgs = Array.from(copy.querySelectorAll("img"));
    if (withImages) {
      let done = 0;
      for (const img of imgs) {
        let src = img.getAttribute("src");
        if (!src && img.getAttribute("srcset")) {
          src = img.getAttribute("srcset").split(",")[0].trim().split(/\s+/)[0];
        }
        const u = src ? abs(src) : null;
        const data = u ? await inline(u) : null;
        if (data) img.setAttribute("src", data);
        else img.remove();
        done++;
        if (progress) progress("Images " + done + " / " + imgs.length);
      }
    } else {
      imgs.forEach(el => el.remove());
    }

    let html = copy.innerHTML;
    if (html.length > MAX_HTML) {
      html = html.slice(0, MAX_HTML) + "<p><i>[article tronque]</i></p>";
    }
    return html;
  }

  // -------------------------------------------------------------------------
  async function save() {
    const box = document.createElement("div");
    box.style.cssText =
      "position:fixed;left:0;right:0;bottom:0;z-index:2147483647;background:#14161a;" +
      "border-top:1px solid #2b303a;padding:12px 14px 16px;color:#e8eaee;" +
      "font:13px/1.5 -apple-system,Roboto,sans-serif";
    box.innerHTML =
      '<div style="font-weight:600;margin-bottom:8px">Lire plus tard</div>' +
      '<label style="display:flex;gap:9px;margin-bottom:10px">' +
      '<input type="checkbox" id="q-img" checked>' +
      '<span>Incorporer les images (lecture hors ligne complete)</span></label>' +
      '<div id="q-st" style="color:#99a0ad;font-size:12px;margin-bottom:10px;' +
      'min-height:17px"></div>' +
      '<div style="display:flex;gap:8px">' +
      '<button id="q-ok" style="flex:1;padding:10px;border:0;border-radius:8px;' +
      'background:#6fae5f;color:#10130f;font-weight:600">Enregistrer</button>' +
      '<button id="q-no" style="flex:1;padding:10px;border:1px solid #2b303a;' +
      'border-radius:8px;background:transparent;color:#99a0ad">Annuler</button></div>';
    document.documentElement.appendChild(box);

    const st = box.querySelector("#q-st");
    const done = () => box.remove();
    box.querySelector("#q-no").onclick = done;

    box.querySelector("#q-ok").onclick = async () => {
      const btn = box.querySelector("#q-ok");
      btn.disabled = true;
      btn.textContent = "En cours…";

      const withImages = box.querySelector("#q-img").checked;
      let html = null;
      try {
        html = await buildArticle(withImages, t => { st.textContent = t; });
      } catch (e) { }

      if (!html) {
        st.textContent = "Aucun contenu principal reconnu sur cette page.";
        btn.disabled = false;
        btn.textContent = "Enregistrer";
        return;
      }

      const item = {
        id: "q_" + Date.now().toString(36),
        url: location.href,
        host: location.hostname.replace(/^www\./, ""),
        title: (document.title || location.hostname).slice(0, 140),
        at: Date.now(),
        chars: html.length,
        images: withImages,
        read: false,
        html: html
      };

      try {
        const s = await browser.storage.local.get("queue");
        const list = (s && s.queue) || [];
        if (list.some(x => x.url === item.url)) {
          st.textContent = "Cette page est deja dans votre file.";
          btn.disabled = false;
          btn.textContent = "Enregistrer";
          return;
        }
        list.unshift(item);
        while (list.length > MAX_ITEMS) list.pop();
        await browser.storage.local.set({ queue: list });

        st.textContent = "Enregistre : " + Math.round(item.chars / 1024) +
          " Ko, " + list.length + " article(s) en file.";
        setTimeout(done, 1600);
      } catch (e) {
        st.textContent = "Espace de stockage insuffisant.";
        btn.disabled = false;
        btn.textContent = "Enregistrer";
      }
    };
  }

  browser.storage.onChanged.addListener(changes => {
    const c = changes.pageCommand && changes.pageCommand.newValue;
    if (c && c.cmd === "readLater") save();
  });
})();
