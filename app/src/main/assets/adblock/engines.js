"use strict";

// ===========================================================================
//  engines.js -- recuperation et analyse des moteurs sources.
//  Aucun serveur : l'extension a la permission de requeter ces domaines,
//  la page n'est donc pas soumise au CORS.
// ===========================================================================

const UA_TIMEOUT = 9000;

function withTimeout(promise, ms) {
  return Promise.race([
    promise,
    new Promise((_, rej) => setTimeout(() => rej(new Error("timeout")), ms))
  ]);
}

async function fetchDoc(url) {
  const resp = await withTimeout(fetch(url, {
    credentials: "omit",
    headers: { "Accept-Language": "fr-FR,fr;q=0.9,en;q=0.6" }
  }), UA_TIMEOUT);
  if (!resp.ok) throw new Error("HTTP " + resp.status);
  const text = await resp.text();
  return new DOMParser().parseFromString(text, "text/html");
}

function clean(s) {
  return (s || "").replace(/\s+/g, " ").trim();
}

function hostOf(u) {
  try { return new URL(u).hostname.replace(/^www\./, ""); }
  catch (e) { return ""; }
}

// DuckDuckGo enveloppe ses liens ; on extrait la cible reelle.
function unwrap(href) {
  if (!href) return "";
  try {
    if (href.startsWith("//")) href = "https:" + href;
    const u = new URL(href, "https://duckduckgo.com");
    const inner = u.searchParams.get("uddg") || u.searchParams.get("u");
    if (inner) return decodeURIComponent(inner);
    return u.href;
  } catch (e) {
    return href;
  }
}

// ---------------------------------------------------------------------------
//  Moteurs
// ---------------------------------------------------------------------------
const ENGINES = [

  {
    id: "ddg",
    label: "DuckDuckGo",
    url: q => "https://html.duckduckgo.com/html/?kl=fr-fr&q=" + encodeURIComponent(q),
    parse: doc => {
      const out = [];
      doc.querySelectorAll(".result, .web-result").forEach(el => {
        const a = el.querySelector("a.result__a, .result__title a");
        if (!a) return;
        const url = unwrap(a.getAttribute("href"));
        if (!url || !url.startsWith("http")) return;
        out.push({
          url,
          title: clean(a.textContent),
          snippet: clean((el.querySelector(".result__snippet") || {}).textContent)
        });
      });
      return out;
    }
  },

  {
    id: "mojeek",
    label: "Mojeek",
    url: q => "https://www.mojeek.com/search?q=" + encodeURIComponent(q),
    parse: doc => {
      const out = [];
      doc.querySelectorAll("ul.results-standard li, .results li, li.r").forEach(el => {
        const a = el.querySelector("h2 a, a.title, a.ob");
        if (!a) return;
        const url = a.getAttribute("href");
        if (!url || !url.startsWith("http")) return;
        out.push({
          url,
          title: clean(a.textContent),
          snippet: clean((el.querySelector("p.s, .s, p") || {}).textContent)
        });
      });
      return out;
    }
  },

  {
    id: "brave",
    label: "Brave",
    url: q => "https://search.brave.com/search?q=" + encodeURIComponent(q),
    parse: doc => {
      const out = [];
      doc.querySelectorAll("[data-type='web'], .snippet, #results .fdb").forEach(el => {
        const a = el.querySelector("a[href^='http']");
        if (!a) return;
        const title = el.querySelector(".title, .snippet-title, h3") || a;
        out.push({
          url: a.getAttribute("href"),
          title: clean(title.textContent),
          snippet: clean((el.querySelector(".snippet-description, .description") || {}).textContent)
        });
      });
      return out;
    }
  },

  {
    id: "marginalia",
    label: "Marginalia",
    url: q => "https://search.marginalia.nu/search?query=" + encodeURIComponent(q),
    parse: doc => {
      const out = [];
      doc.querySelectorAll(".search-result, .card").forEach(el => {
        const a = el.querySelector("a[href^='http']");
        if (!a) return;
        out.push({
          url: a.getAttribute("href"),
          title: clean(a.textContent),
          snippet: clean((el.querySelector(".description, p") || {}).textContent)
        });
      });
      return out;
    }
  }
];

// Actualites : flux RSS de sources non concernees par les filtres.
const NEWS_FEEDS = [
  { name: "Le Monde",     url: "https://www.lemonde.fr/rss/une.xml" },
  { name: "France Info",  url: "https://www.francetvinfo.fr/titres.rss" },
  { name: "Mediapart",    url: "https://www.mediapart.fr/articles/feed" },
  { name: "Liberation",   url: "https://www.liberation.fr/arc/outboundfeeds/rss/?outputType=xml" },
  { name: "Radio France", url: "https://www.radiofrance.fr/franceinter/rss" }
];

async function fetchNews(query) {
  const q = query.toLowerCase();
  const jobs = NEWS_FEEDS.map(async feed => {
    try {
      const resp = await withTimeout(fetch(feed.url, { credentials: "omit" }), UA_TIMEOUT);
      const xml = new DOMParser().parseFromString(await resp.text(), "text/xml");
      const items = [];
      xml.querySelectorAll("item, entry").forEach(it => {
        const title = clean((it.querySelector("title") || {}).textContent);
        let link = clean((it.querySelector("link") || {}).textContent);
        if (!link) {
          const l = it.querySelector("link[href]");
          if (l) link = l.getAttribute("href");
        }
        const desc = clean((it.querySelector("description, summary") || {}).textContent)
          .replace(/<[^>]+>/g, "");
        if (!link) return;
        const hay = (title + " " + desc).toLowerCase();
        if (q && !q.split(/\s+/).every(w => hay.includes(w))) return;
        items.push({ url: link, title, snippet: desc, engine: feed.name });
      });
      return items.slice(0, 8);
    } catch (e) {
      return [];
    }
  });
  const all = await Promise.all(jobs);
  return all.flat();
}

// Reponse instantanee Wikipedia
async function instantAnswer(query) {
  try {
    const searchUrl = "https://fr.wikipedia.org/w/api.php?action=query&list=search" +
      "&format=json&origin=*&srlimit=1&srsearch=" + encodeURIComponent(query);
    const r = await withTimeout(fetch(searchUrl, { credentials: "omit" }), 6000);
    const j = await r.json();
    const hit = j && j.query && j.query.search && j.query.search[0];
    if (!hit) return null;
    const sum = await withTimeout(fetch(
      "https://fr.wikipedia.org/api/rest_v1/page/summary/" +
      encodeURIComponent(hit.title.replace(/ /g, "_")),
      { credentials: "omit" }), 6000);
    const s = await sum.json();
    if (!s || !s.extract) return null;
    return {
      title: s.title,
      extract: s.extract,
      url: (s.content_urls && s.content_urls.desktop && s.content_urls.desktop.page) ||
           ("https://fr.wikipedia.org/wiki/" + encodeURIComponent(hit.title))
    };
  } catch (e) {
    return null;
  }
}

// Raccourcis facon "bangs"
const BANGS = {
  "!w":   q => "https://fr.wikipedia.org/w/index.php?search=" + encodeURIComponent(q),
  "!yt":  q => "https://www.youtube.com/results?search_query=" + encodeURIComponent(q),
  "!gh":  q => "https://github.com/search?q=" + encodeURIComponent(q),
  "!osm": q => "https://www.openstreetmap.org/search?query=" + encodeURIComponent(q),
  "!ia":  q => "https://archive.org/search?query=" + encodeURIComponent(q),
  "!mdn": q => "https://developer.mozilla.org/fr/search?q=" + encodeURIComponent(q),
  "!lm":  q => "https://www.lemonde.fr/recherche/?search_keywords=" + encodeURIComponent(q),
  "!mp":  q => "https://www.mediapart.fr/recherche?search_word=" + encodeURIComponent(q)
};

function resolveBang(input) {
  const parts = input.trim().split(/\s+/);
  const idx = parts.findIndex(p => BANGS[p.toLowerCase()]);
  if (idx === -1) return null;
  const bang = parts[idx].toLowerCase();
  const rest = parts.slice(0, idx).concat(parts.slice(idx + 1)).join(" ");
  return BANGS[bang](rest);
}

window.ENGINE_API = {
  ENGINES, fetchDoc, fetchNews, instantAnswer, resolveBang, hostOf, clean
};
