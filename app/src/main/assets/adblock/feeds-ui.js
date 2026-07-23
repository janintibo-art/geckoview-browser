"use strict";

(function () {

  const $ = s => document.querySelector(s);
  let feeds = [];
  let onlyNew = false;

  function esc(v) {
    return String(v == null ? "" : v)
      .replace(/&/g, "&amp;").replace(/</g, "&lt;")
      .replace(/>/g, "&gt;").replace(/"/g, "&quot;");
  }

  function ago(ts) {
    if (!ts) return "jamais";
    const min = Math.round((Date.now() - ts) / 60000);
    if (min < 1) return "a l'instant";
    if (min < 60) return "il y a " + min + " min";
    const h = Math.round(min / 60);
    if (h < 24) return "il y a " + h + " h";
    return "il y a " + Math.round(h / 24) + " j";
  }

  function freq(m) {
    if (m >= 1440) return "une fois par jour";
    if (m >= 60) return "toutes les " + Math.round(m / 60) + " h";
    return "toutes les " + m + " min";
  }

  function newCount(f) {
    return (f.items || []).filter(i => !i.seen).length;
  }

  // -------------------------------------------------------------------------
  function render() {
    const totalNew = feeds.reduce((n, f) => n + newCount(f), 0);
    $("#tot").textContent = feeds.length
      ? feeds.length + " flux, " + totalNew + " nouveaute(s)"
      : "";

    const box = $("#list");
    if (!feeds.length) {
      box.innerHTML = '<div style="padding:30px 8px;text-align:center;color:var(--dim)">' +
        "Aucun flux pour l'instant.</div>";
      return;
    }

    box.innerHTML = feeds.map((f, fi) => {
      let items = (f.items || []);
      if (onlyNew) items = items.filter(i => !i.seen);
      items = items.slice(0, 12);

      const rows = items.map(i => `
        <a class="it ${i.seen ? "" : "neuf"}" href="${esc(i.link)}">
          ${esc(i.title)}
          ${i.date ? '<div class="d">' + esc(i.date) + "</div>" : ""}
        </a>`).join("");

      const n = newCount(f);
      return `
        <div class="src">
          <div class="hd">
            <button class="sw ${f.enabled !== false ? "on" : "off"}" data-t="${fi}">
              ${f.enabled !== false ? "\u25C9" : "\u25CB"}</button>
            <div style="flex:1;min-width:0">
              <div class="nm">${esc(f.title)}</div>
              <div class="mt">
                ${esc(f.host)} &middot; ${esc(freq(f.every || 180))}
                &middot; releve ${esc(ago(f.checkedAt))}
                ${n ? " &middot; " + n + " nouveaute(s)" : ""}
              </div>
            </div>
          </div>

          <div class="items">${rows ||
            '<div style="color:var(--dim);font-size:12px;padding:6px 2px">' +
            (onlyNew ? "Rien de neuf." : "Aucune entree.") + "</div>"}</div>

          <div class="acts">
            <a href="${esc(f.url)}">Ouvrir le site</a>
            <button data-s="${fi}">Tout marquer comme vu</button>
            <button data-n="${fi}">${f.notify ? "Notifications : oui" : "Notifications : non"}</button>
            <button data-x="${fi}">Exporter en RSS</button>
            <button data-d="${fi}">Supprimer</button>
          </div>
        </div>`;
    }).join("");

    box.querySelectorAll("[data-t]").forEach(b => {
      b.onclick = () => {
        const f = feeds[+b.dataset.t];
        f.enabled = f.enabled === false;
        save();
      };
    });
    box.querySelectorAll("[data-s]").forEach(b => {
      b.onclick = () => {
        (feeds[+b.dataset.s].items || []).forEach(i => { i.seen = true; });
        save("Entrees marquees comme vues.");
      };
    });
    box.querySelectorAll("[data-n]").forEach(b => {
      b.onclick = () => {
        const f = feeds[+b.dataset.n];
        f.notify = !f.notify;
        save(f.notify
          ? "Notifications activees pour ce flux."
          : "Notifications desactivees.");
      };
    });
    box.querySelectorAll("[data-x]").forEach(b => {
      b.onclick = () => exportRss(feeds[+b.dataset.x]);
    });
    box.querySelectorAll("[data-d]").forEach(b => {
      b.onclick = () => {
        const f = feeds[+b.dataset.d];
        if (!confirm("Supprimer le flux « " + f.title + " » ?")) return;
        feeds.splice(+b.dataset.d, 1);
        save("Flux supprime.");
      };
    });
  }

  // -------------------------------------------------------------------------
  //  Export au format RSS, pour un lecteur exterieur
  // -------------------------------------------------------------------------
  function xmlEsc(v) {
    return String(v == null ? "" : v)
      .replace(/&/g, "&amp;").replace(/</g, "&lt;")
      .replace(/>/g, "&gt;").replace(/"/g, "&quot;");
  }

  async function exportRss(f) {
    const items = (f.items || []).slice(0, 60).map(i =>
      "    <item>\n" +
      "      <title>" + xmlEsc(i.title) + "</title>\n" +
      "      <link>" + xmlEsc(i.link) + "</link>\n" +
      "      <guid isPermaLink=\"true\">" + xmlEsc(i.link) + "</guid>\n" +
      (i.at ? "      <pubDate>" + new Date(i.at).toUTCString() + "</pubDate>\n" : "") +
      "    </item>").join("\n");

    const xml =
      '<?xml version="1.0" encoding="UTF-8"?>\n' +
      '<rss version="2.0">\n  <channel>\n' +
      "    <title>" + xmlEsc(f.title) + "</title>\n" +
      "    <link>" + xmlEsc(f.url) + "</link>\n" +
      "    <description>Flux constitue par GeckoBrowser</description>\n" +
      items + "\n  </channel>\n</rss>\n";

    try {
      await browser.runtime.sendMessage({
        type: "downloadText",
        name: f.host.replace(/[^\w.-]/g, "_") + ".rss.xml",
        text: xml
      });
      $("#msg").textContent = "Flux exporte dans Telechargements.";
    } catch (e) {
      $("#msg").textContent = "Export impossible.";
    }
  }

  // -------------------------------------------------------------------------
  async function load() {
    try {
      const r = await browser.runtime.sendMessage({ type: "feedList" });
      feeds = (r && r.feeds) || [];
    } catch (e) {
      $("#msg").textContent = "Extension non joignable.";
      return;
    }
    render();
  }

  async function save(message) {
    try {
      await browser.runtime.sendMessage({ type: "feedSave", feeds: feeds });
      if (message) $("#msg").textContent = message;
    } catch (e) {
      $("#msg").textContent = "Enregistrement impossible.";
    }
    render();
  }

  $("#refresh").onclick = async () => {
    const b = $("#refresh");
    b.textContent = "Relevé en cours…";
    try {
      const r = await browser.runtime.sendMessage({ type: "feedRefresh" });
      $("#msg").textContent = r
        ? r.checked + " flux releve(s), " + r.added + " nouveaute(s)"
        : "Relevé impossible.";
    } catch (e) {
      $("#msg").textContent = "Relevé impossible.";
    }
    b.textContent = "Relever maintenant";
    load();
  };

  $("#onlynew").onclick = () => {
    onlyNew = !onlyNew;
    $("#onlynew").classList.toggle("on", onlyNew);
    render();
  };

  load();
})();
