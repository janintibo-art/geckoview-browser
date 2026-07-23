"use strict";

// ===========================================================================
//  feeds.js -- fabrique un flux a partir d'une page qui n'en propose pas.
//  On designe la liste d'articles, le navigateur en deduit titres et liens,
//  puis revient reperer les nouvelles entrees.
// ===========================================================================

(function () {

  if (window.top !== window.self) return;

  const MAX_FEEDS = 30;

  function esc(v) {
    return String(v == null ? "" : v)
      .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
  }

  function abs(u) {
    try { return new URL(u, location.href).href; } catch (e) { return null; }
  }

  // -------------------------------------------------------------------------
  //  Lecture des entrees a partir d'un selecteur de liste
  // -------------------------------------------------------------------------
  function readItems(root, selector, base) {
    let nodes = [];
    try { nodes = Array.from(root.querySelectorAll(selector)); }
    catch (e) { return []; }

    const out = [];
    for (const el of nodes) {
      const a = el.matches("a[href]") ? el : el.querySelector("a[href]");
      if (!a) continue;

      let link = a.getAttribute("href");
      try { link = new URL(link, base).href; } catch (e) { continue; }
      if (!/^https?:/i.test(link)) continue;

      // Titre : un intitule structure d'abord, le texte du lien ensuite
      const h = el.querySelector("h1, h2, h3, h4, [class*='title'], [class*='titre']");
      let title = ((h || a).textContent || "").replace(/\s+/g, " ").trim();
      if (!title) title = (el.textContent || "").replace(/\s+/g, " ").trim();
      if (!title) continue;

      const time = el.querySelector("time[datetime], time");
      const date = time
        ? (time.getAttribute("datetime") || time.textContent || "").trim()
        : "";

      out.push({
        title: title.slice(0, 200),
        link: link,
        date: date.slice(0, 40)
      });

      if (out.length >= 60) break;
    }

    // Deux entrees pointant au meme endroit sont la meme entree
    const seen = new Set();
    return out.filter(it => {
      if (seen.has(it.link)) return false;
      seen.add(it.link);
      return true;
    });
  }

  // -------------------------------------------------------------------------
  //  Creation
  // -------------------------------------------------------------------------
  async function create() {
    const picked = await GB.pick({
      hint: "Touchez un article de la liste",
      color: "#8ab4f8",
      repeated: true
    });
    if (!picked) return;

    const items = readItems(document, picked.selector, location.href);
    if (!items.length) {
      alert("Aucun lien exploitable sous ce selecteur.\n\n" +
            "Essayez « Parent » pour viser le bloc entier de l'article.");
      return;
    }

    const preview = items.slice(0, 3).map(i => "• " + i.title).join("\n");
    const ok = confirm(
      picked.count + " entrees reconnues.\n\nApercu :\n" + preview +
      "\n\nCreer un flux pour cette page ?");
    if (!ok) return;

    const feed = {
      id: "f_" + Date.now().toString(36),
      url: location.href,
      host: location.hostname.replace(/^www\./, ""),
      title: (document.title || location.hostname).slice(0, 120),
      selector: picked.selector,
      every: 180,
      notify: false,
      enabled: true,
      checkedAt: Date.now(),
      items: items.map(i => Object.assign({ at: Date.now(), seen: true }, i))
    };

    try {
      const s = await browser.storage.local.get("feeds");
      const list = (s && s.feeds) || [];
      if (list.some(f => f.url === feed.url)) {
        alert("Un flux existe deja pour cette page.");
        return;
      }
      list.unshift(feed);
      while (list.length > MAX_FEEDS) list.pop();
      await browser.storage.local.set({ feeds: list });
      alert("Flux cree.\n\n" + items.length + " entrees enregistrees comme deja vues. " +
            "Les prochaines apparaitront comme nouvelles.");
    } catch (e) {
      alert("Enregistrement impossible.");
    }
  }

  browser.storage.onChanged.addListener(changes => {
    const c = changes.pageCommand && changes.pageCommand.newValue;
    if (c && c.cmd === "makeFeed") create();
  });
})();
