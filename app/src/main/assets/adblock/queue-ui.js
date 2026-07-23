"use strict";

(function () {

  const $ = s => document.querySelector(s);
  let items = [];
  let filter = "";
  let unreadOnly = false;

  function esc(v) {
    return String(v == null ? "" : v)
      .replace(/&/g, "&amp;").replace(/</g, "&lt;")
      .replace(/>/g, "&gt;").replace(/"/g, "&quot;");
  }

  function stamp(ts) {
    try { return new Date(ts).toLocaleDateString("fr-FR"); }
    catch (e) { return ""; }
  }

  function size(n) {
    return n < 1024 ? n + " o" : Math.round(n / 1024) + " Ko";
  }

  // -------------------------------------------------------------------------
  //  Liste
  // -------------------------------------------------------------------------
  function visible() {
    const f = filter.toLowerCase();
    return items.filter(it => {
      if (unreadOnly && it.read) return false;
      if (!f) return true;
      return (it.title + " " + it.host).toLowerCase().indexOf(f) !== -1;
    });
  }

  function render() {
    const total = items.reduce((n, it) => n + (it.chars || 0), 0);
    const unread = items.filter(it => !it.read).length;
    $("#tot").textContent = items.length
      ? items.length + " article(s), " + unread + " non lu(s), " +
        Math.round(total / 1024) + " Ko conserves"
      : "";

    const list = visible();
    const box = $("#list");

    if (!list.length) {
      box.innerHTML = '<div style="padding:30px 8px;text-align:center;color:var(--dim)">' +
        (items.length ? "Aucun article ne correspond." : "Votre file est vide.") +
        "</div>";
      return;
    }

    box.innerHTML = list.map(it => `
      <div class="it ${it.read ? "lu" : ""}">
        <div class="nm">${esc(it.title)}</div>
        <div class="mt">
          ${esc(it.host)} &middot; ${esc(stamp(it.at))} &middot; ${esc(size(it.chars || 0))}
          ${it.images ? " &middot; avec images" : ""}
        </div>
        <div class="acts">
          <button class="go" data-r="${esc(it.id)}">Lire</button>
          <button data-m="${esc(it.id)}">${it.read ? "Marquer non lu" : "Marquer lu"}</button>
          <button data-d="${esc(it.id)}">Supprimer</button>
        </div>
      </div>`).join("");

    box.querySelectorAll("[data-r]").forEach(b => {
      b.onclick = () => read(b.dataset.r);
    });
    box.querySelectorAll("[data-m]").forEach(b => {
      b.onclick = () => {
        const it = items.find(x => x.id === b.dataset.m);
        if (it) { it.read = !it.read; save(); }
      };
    });
    box.querySelectorAll("[data-d]").forEach(b => {
      b.onclick = () => {
        const it = items.find(x => x.id === b.dataset.d);
        if (!it) return;
        if (!confirm("Supprimer « " + it.title + " » de la file ?")) return;
        items = items.filter(x => x.id !== it.id);
        save("Article supprime.");
      };
    });
  }

  // -------------------------------------------------------------------------
  //  Liseuse
  // -------------------------------------------------------------------------
  let current = null;

  function read(id) {
    const it = items.find(x => x.id === id);
    if (!it) return;
    current = it;

    $("#r-title").textContent = it.title;
    $("#r-src").textContent = it.host + " · enregistre le " + stamp(it.at);

    // Le contenu a ete nettoye a l'enregistrement : ni script ni cadre.
    $("#art").innerHTML = it.html || "";

    // Les liens de l'article partent vers le web, dans un nouvel onglet
    $("#art").querySelectorAll("a[href]").forEach(a => {
      a.setAttribute("target", "_blank");
    });

    $("#list-view").style.display = "none";
    $("#reader").style.display = "block";
    $("#mark").textContent = it.read ? "Marquer non lu" : "Marquer comme lu";
    window.scrollTo(0, 0);
  }

  $("#back").onclick = () => {
    $("#reader").style.display = "none";
    $("#list-view").style.display = "block";
    $("#art").innerHTML = "";
    current = null;
    render();
  };

  $("#mark").onclick = () => {
    if (!current) return;
    current.read = !current.read;
    $("#mark").textContent = current.read ? "Marquer non lu" : "Marquer comme lu";
    save();
  };

  $("#orig").onclick = () => {
    if (current && current.url) location.href = current.url;
  };

  // -------------------------------------------------------------------------
  $("#q").oninput = () => { filter = $("#q").value; render(); };

  $("#unread").onclick = () => {
    unreadOnly = !unreadOnly;
    $("#unread").classList.toggle("on", unreadOnly);
    render();
  };

  async function load() {
    try {
      const s = await browser.storage.local.get("queue");
      items = (s && s.queue) || [];
    } catch (e) {
      $("#msg").textContent = "Stockage inaccessible.";
      return;
    }
    render();
  }

  async function save(message) {
    try {
      await browser.storage.local.set({ queue: items });
      if (message) $("#msg").textContent = message;
    } catch (e) {
      $("#msg").textContent = "Enregistrement impossible.";
    }
    render();
  }

  load();
})();
