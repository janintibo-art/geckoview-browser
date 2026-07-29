"use strict";

// ===========================================================================
//  video.js -- « telecharger la video de cette page ».
//
//  Le journal reseau du background repere les flux video (playlists HLS,
//  fichiers directs) au fil de la lecture. Ce panneau les presente et
//  propose le telechargement, sans passer par l'analyseur de page.
//
//  Complement local : on repere aussi les <video> presentes dans le DOM,
//  utile pour un fichier direct que le journal aurait manque.
// ===========================================================================

(function () {

  if (window.top !== window.self) return;

  let panel = null;

  function esc(v) {
    return String(v == null ? "" : v)
      .replace(/&/g, "&amp;").replace(/</g, "&lt;")
      .replace(/>/g, "&gt;").replace(/"/g, "&quot;");
  }

  function shortUrl(u) {
    return String(u).replace(/^https?:\/\/(www\.)?/, "").slice(0, 60);
  }

  // <video>/<source> du DOM, en complement du journal reseau.
  function domVideos() {
    const out = [];
    document.querySelectorAll("video").forEach(v => {
      const src = v.currentSrc || v.src || "";
      if (src && /^https?:/i.test(src)) {
        out.push({ url: src, kind: /\.m3u8/i.test(src) ? "hls" : "file", dom: true });
      }
      v.querySelectorAll("source[src]").forEach(s => {
        if (/^https?:/i.test(s.src)) {
          out.push({ url: s.src, kind: /\.m3u8/i.test(s.src) ? "hls" : "file", dom: true });
        }
      });
    });
    return out;
  }

  function merge(net, dom) {
    const seen = new Set();
    const all = [];
    net.concat(dom).forEach(v => {
      if (!v.url || seen.has(v.url)) return;
      seen.add(v.url);
      all.push(v);
    });
    return all;
  }

  const KIND = {
    hls: { label: "Flux HLS", note: "telechargeable en MP4", ok: true },
    file: { label: "Fichier video", note: "telechargement direct", ok: true },
    dash: { label: "Flux DASH", note: "non pris en charge", ok: false }
  };

  async function run() {
    close();

    let net = [];
    try {
      const res = await browser.runtime.sendMessage({
        type: "videoStreams", origin: location.href
      });
      net = (res && res.streams) || [];
    } catch (e) { }

    const list = merge(net, domVideos());

    panel = document.createElement("div");
    panel.style.cssText =
      "position:fixed;left:0;right:0;bottom:0;z-index:2147483647;" +
      "background:#14161a;border-top:1px solid #2b303a;padding:12px 14px 16px;" +
      "color:#e8eaee;font:13px/1.5 -apple-system,Roboto,sans-serif;" +
      "max-height:60vh;overflow:auto";

    if (!list.length) {
      panel.innerHTML =
        '<div style="font-weight:600;margin-bottom:6px">Aucune video detectee</div>' +
        '<div style="color:#99a0ad;font-size:12px">Lancez la lecture quelques ' +
        'secondes puis reessayez : la video est reperee quand elle commence a ' +
        'se charger.</div>' +
        '<div style="margin-top:12px"><button data-a="close" style="width:100%;' +
        'padding:9px;border:1px solid #2b303a;border-radius:7px;background:#1c1f26;' +
        'color:#99a0ad">Fermer</button></div>';
    } else {
      const rows = list.map((v, i) => {
        const k = KIND[v.kind] || KIND.file;
        const btn = k.ok
          ? '<button data-dl="' + i + '" style="flex:1;padding:8px;border:1px solid ' +
            '#3d5c34;border-radius:7px;background:#1c1f26;color:#8fce7c;font-size:12px">' +
            'Telecharger</button>'
          : '<span style="flex:1;padding:8px;color:#99a0ad;font-size:12px;' +
            'text-align:center">' + esc(k.note) + '</span>';
        // « Ouvrir » envoie le flux a une app externe (VLC, MX Player...).
        // Utile notamment pour caster vers un Chromecast via VLC.
        const openBtn =
          '<button data-open="' + i + '" style="flex:1;padding:8px;border:1px solid ' +
          '#2b4a63;border-radius:7px;background:#1c1f26;color:#8ab4f8;font-size:12px">' +
          'Ouvrir (VLC\u2026)</button>';
        return '<div style="border:1px solid #2b303a;border-radius:8px;padding:9px;' +
          'margin-bottom:8px">' +
          '<div style="font-size:11px;color:#8ab4f8;word-break:break-all;' +
          'margin-bottom:4px">' + esc(shortUrl(v.url)) + '</div>' +
          '<div style="font-size:11px;color:#99a0ad;margin-bottom:6px">' +
          esc(k.label) + '</div>' +
          '<div style="display:flex;align-items:center;gap:8px">' +
          btn + openBtn + '</div></div>';
      }).join("");
      panel.innerHTML =
        '<div style="font-weight:600;margin-bottom:8px">Videos de cette page ' +
        '(' + list.length + ')</div>' + rows +
        '<div style="color:#99a0ad;font-size:11px;margin:2px 0 10px">Les flux ' +
        'chiffres ou proteges ne peuvent pas etre telecharges, mais « Ouvrir » ' +
        'les envoie a un lecteur externe (VLC) d\'ou vous pouvez caster.</div>' +
        '<button data-a="close" style="width:100%;padding:9px;border:1px solid ' +
        '#2b303a;border-radius:7px;background:transparent;color:#99a0ad">Fermer</button>';
    }

    panel.addEventListener("click", e => {
      const a = e.target.getAttribute && e.target.getAttribute("data-a");
      if (a === "close") { close(); return; }
      const dl = e.target.getAttribute && e.target.getAttribute("data-dl");
      if (dl != null) { startDownload(list[+dl], e.target); return; }
      const op = e.target.getAttribute && e.target.getAttribute("data-open");
      if (op != null) openExternal(list[+op], e.target);
    });

    document.documentElement.appendChild(panel);
  }

  async function openExternal(v, btn) {
    if (!v) return;
    btn.textContent = "\u2026";
    try {
      const res = await browser.runtime.sendMessage({
        type: "openVideoExternal", url: v.url
      });
      btn.textContent = (res && res.ok) ? "Ouvert" : "Echec";
    } catch (e) {
      btn.textContent = "Echec";
    }
  }

  async function startDownload(v, btn) {
    if (!v) return;
    const name = (document.title || location.hostname).slice(0, 80);
    btn.textContent = "\u2026";
    try {
      if (v.kind === "hls") {
        const res = await browser.runtime.sendMessage({
          type: "downloadHls", url: v.url, referer: location.href, name: name
        });
        btn.textContent = (res && res.ok) ? "Lance" : "Echec";
      } else {
        const res = await browser.runtime.sendMessage({
          type: "downloadUrls", urls: [v.url], referer: location.href
        });
        btn.textContent = (res && res.ok) ? "Lance" : "Echec";
      }
    } catch (e) {
      btn.textContent = "Echec";
    }
  }

  function close() {
    if (panel) { panel.remove(); panel = null; }
  }

  browser.storage.onChanged.addListener(changes => {
    const c = changes.pageCommand && changes.pageCommand.newValue;
    if (c && GB.foreground() && c.cmd === "videoDownload") run();
  });

})();
