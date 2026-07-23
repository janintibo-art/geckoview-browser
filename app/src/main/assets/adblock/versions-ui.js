"use strict";

(function () {

  const $ = s => document.querySelector(s);
  let store = {};

  function esc(v) {
    return String(v == null ? "" : v)
      .replace(/&/g, "&amp;").replace(/</g, "&lt;")
      .replace(/>/g, "&gt;").replace(/"/g, "&quot;");
  }

  function stamp(ts) {
    try { return new Date(ts).toLocaleString("fr-FR"); }
    catch (e) { return String(ts); }
  }

  function size(chars) {
    return chars < 1024 ? chars + " car." : Math.round(chars / 1024) + " Ko";
  }

  // -------------------------------------------------------------------------
  function render() {
    const urls = Object.keys(store);
    const box = $("#list");

    if (!urls.length) {
      $("#tot").textContent = "";
      box.innerHTML = '<div class="msg" style="padding:30px 8px;text-align:center;' +
        'color:var(--dim)">Aucune page archivee.</div>';
      return;
    }

    let versions = 0, chars = 0;
    urls.forEach(u => {
      versions += store[u].length;
      store[u].forEach(v => { chars += v.chars || 0; });
    });
    $("#tot").textContent = urls.length + " page(s), " + versions +
      " version(s), " + Math.round(chars / 1024) + " Ko conserves";

    box.innerHTML = urls.map((u, i) => {
      const list = store[u];
      const last = list[list.length - 1];
      const rows = list.slice().reverse().map(v => `
        <div class="v">
          <div class="d">${esc(stamp(v.at))}</div>
          <div class="s">${v.blocks} blocs &middot; ${esc(size(v.chars || 0))}</div>
        </div>`).join("");

      return `
        <div class="p">
          <div class="nm">${esc(last.title)}</div>
          <div class="u">${esc(u)}</div>
          <div class="vers">${rows}</div>
          <div class="acts">
            <a href="${esc(u)}">Ouvrir la page</a>
            <button data-x="${i}">Exporter le texte</button>
            <button data-d="${i}">Supprimer</button>
          </div>
        </div>`;
    }).join("");

    box.querySelectorAll("[data-d]").forEach(b => {
      b.onclick = async () => {
        const u = urls[+b.dataset.d];
        if (!confirm("Supprimer toutes les archives de cette page ?")) return;
        delete store[u];
        await save("Archives supprimees.");
      };
    });

    // Export : les versions successives dans un seul fichier lisible
    box.querySelectorAll("[data-x]").forEach(b => {
      b.onclick = async () => {
        const u = urls[+b.dataset.x];
        const list = store[u];
        let text = "# " + list[list.length - 1].title + "\n# " + u + "\n\n";
        list.forEach(v => {
          text += "===== Version du " + stamp(v.at) + " =====\n\n" + v.text + "\n\n";
        });
        let name = "archive";
        try { name = new URL(u).hostname.replace(/^www\./, ""); } catch (e) { }
        try {
          await browser.runtime.sendMessage({
            type: "downloadText", name: name + "-versions.txt", text: text
          });
          $("#msg").textContent = "Exporte dans Telechargements.";
        } catch (e) {
          $("#msg").textContent = "Export impossible.";
        }
      };
    });
  }

  // -------------------------------------------------------------------------
  async function load() {
    try {
      const s = await browser.storage.local.get("versions");
      store = (s && s.versions) || {};
    } catch (e) {
      $("#msg").textContent = "Stockage inaccessible.";
      return;
    }
    render();
  }

  async function save(message) {
    try {
      await browser.storage.local.set({ versions: store });
      if (message) $("#msg").textContent = message;
    } catch (e) {
      $("#msg").textContent = "Enregistrement impossible.";
    }
    render();
  }

  load();
})();
