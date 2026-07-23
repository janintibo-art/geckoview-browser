"use strict";

// ===========================================================================
//  frontends.js -- redirection vers des facades libres.
//  Chaque service definit les domaines qu'il intercepte, des instances
//  publiques par defaut, et la traduction du chemin.
//  Les instances publiques vont et viennent : elles sont toutes modifiables
//  depuis la page de reglages.
// ===========================================================================

const FRONTENDS = [
  {
    id: "youtube",
    name: "YouTube",
    target: "Invidious",
    instances: [
      "https://yewtu.be",
      "https://inv.nadeko.net",
      "https://invidious.nerdvpn.de",
      "https://iv.melmac.space",
      "https://invidious.f5.si",
      "https://id.420129.xyz"
    ],
    fragile: true,
    hosts: [/^(www\.|m\.|music\.)?youtube\.com$/i, /^youtu\.be$/i,
            /^(www\.)?youtube-nocookie\.com$/i],
    map: (u, base) => {
      // youtu.be/ID  ->  /watch?v=ID
      if (/^youtu\.be$/i.test(u.hostname)) {
        const id = u.pathname.slice(1);
        if (!id) return base;
        const q = new URLSearchParams(u.search);
        q.set("v", id);
        return base + "/watch?" + q.toString();
      }

      // Recherche : YouTube utilise /results?search_query=, Invidious /search?q=
      // Sans cette traduction, l'instance renvoie sur sa page d'accueil.
      if (/^\/results\/?$/i.test(u.pathname)) {
        const q = new URLSearchParams(u.search);
        const term = q.get("search_query") || q.get("q") || "";
        return base + "/search?q=" + encodeURIComponent(term);
      }

      // /shorts/ID, /live/ID, /embed/ID  ->  /watch?v=ID
      let m = u.pathname.match(/^\/(shorts|live|embed)\/([^/?#]+)/i);
      if (m) return base + "/watch?v=" + encodeURIComponent(m[2]) + hashOf(u);

      // Chaines : /c/nom et /user/nom ne sont pas repris tels quels
      m = u.pathname.match(/^\/(c|user)\/([^/?#]+)/i);
      if (m) return base + "/search?q=" + encodeURIComponent(m[2]);

      // Rubriques propres a YouTube, sans equivalent
      if (/^\/(feed|shorts|gaming|premium|account|playlists)\b/i.test(u.pathname)) {
        return base;
      }

      return base + u.pathname + u.search + u.hash;
    }
  },

  {
    id: "twitter",
    name: "Twitter / X",
    target: "Nitter",
    instances: [
      "https://nitter.net",
      "https://nitter.poast.org",
      "https://xcancel.com"
    ],
    hosts: [/^(www\.|mobile\.|m\.)?twitter\.com$/i, /^(www\.|mobile\.)?x\.com$/i],
    map: (u, base) => base + u.pathname + u.search + u.hash
  },

  {
    id: "reddit",
    name: "Reddit",
    target: "Redlib",
    instances: [
      "https://redlib.catsarch.com",
      "https://safereddit.com",
      "https://redlib.perennialte.ch"
    ],
    hosts: [/^(www\.|old\.|new\.|np\.|amp\.)?reddit\.com$/i, /^redd\.it$/i],
    map: (u, base) => {
      if (/^redd\.it$/i.test(u.hostname)) return base + "/comments" + u.pathname;
      return base + u.pathname + u.search + u.hash;
    }
  },

  {
    id: "medium",
    name: "Medium",
    target: "Scribe",
    instances: ["https://scribe.rip", "https://scribe.citizen4.eu"],
    hosts: [/^(www\.)?medium\.com$/i, /\.medium\.com$/i],
    map: (u, base) => {
      // Les blogs personnels utilisent auteur.medium.com/titre
      const sub = u.hostname.replace(/\.medium\.com$/i, "");
      if (sub && sub !== "www" && sub !== "medium.com") {
        return base + "/@" + sub + u.pathname + u.search;
      }
      return base + u.pathname + u.search + u.hash;
    }
  },

  {
    id: "tiktok",
    name: "TikTok",
    target: "ProxiTok",
    instances: ["https://proxitok.pabloferreiro.es", "https://tok.artemislena.eu"],
    hosts: [/^(www\.|m\.|vm\.)?tiktok\.com$/i],
    map: (u, base) => base + u.pathname + u.search + u.hash
  },

  {
    id: "imgur",
    name: "Imgur",
    target: "rimgo",
    instances: ["https://rimgo.pussthecat.org", "https://rimgo.bloat.cat"],
    hosts: [/^(www\.|i\.|m\.)?imgur\.(com|io)$/i],
    map: (u, base) => base + u.pathname + u.search + u.hash
  },

  {
    id: "fandom",
    name: "Fandom",
    target: "BreezeWiki",
    instances: ["https://breezewiki.com", "https://antifandom.com"],
    hosts: [/\.fandom\.com$/i, /\.wikia\.com$/i],
    map: (u, base) => {
      const wiki = u.hostname.replace(/\.(fandom|wikia)\.com$/i, "");
      let path = u.pathname.replace(/^\/wiki\//i, "/wiki/");
      return base + "/" + wiki + path + u.search + u.hash;
    }
  },

  {
    id: "quora",
    name: "Quora",
    target: "Quetre",
    instances: ["https://quetre.iket.me", "https://quetre.pussthecat.org"],
    hosts: [/^([a-z]{2}\.|www\.)?quora\.com$/i],
    map: (u, base) => base + u.pathname + u.search + u.hash
  },

  {
    id: "stackoverflow",
    name: "Stack Overflow",
    target: "AnonymousOverflow",
    instances: ["https://code.whatever.social", "https://ao.vern.cc"],
    hosts: [/^(www\.)?stackoverflow\.com$/i],
    map: (u, base) => {
      const m = u.pathname.match(/^\/questions\/(\d+)(\/.*)?$/);
      if (m) return base + "/questions/" + m[1] + (m[2] || "");
      return base + u.pathname + u.search + u.hash;
    }
  },

  {
    id: "imdb",
    name: "IMDb",
    target: "libremdb",
    instances: ["https://libremdb.iket.me", "https://libremdb.pussthecat.org"],
    hosts: [/^(www\.|m\.)?imdb\.com$/i],
    map: (u, base) => base + u.pathname + u.search + u.hash
  },

  {
    id: "translate",
    name: "Google Traduction",
    target: "Lingva",
    instances: ["https://lingva.ml", "https://translate.plausibility.cloud"],
    hosts: [/^translate\.google\.[a-z.]+$/i],
    map: (u, base) => {
      const q = new URLSearchParams(u.search);
      const sl = q.get("sl") || "auto";
      const tl = q.get("tl") || "fr";
      const text = q.get("text") || q.get("q") || "";
      if (text) {
        return base + "/" + sl + "/" + tl + "/" + encodeURIComponent(text);
      }
      return base;
    }
  },

  {
    id: "wikipedia",
    name: "Wikipedia",
    target: "Wikiless",
    instances: ["https://wikiless.tiekoetter.com", "https://wikiless.esmailelbob.xyz"],
    hosts: [/^([a-z-]+)\.(m\.)?wikipedia\.org$/i],
    map: (u, base) => {
      const lang = u.hostname.split(".")[0];
      const q = new URLSearchParams(u.search);
      q.set("lang", lang);
      return base + u.pathname + "?" + q.toString() + u.hash;
    },
    def: false     // desactive par defaut : Wikipedia ne piste pas
  }
];

function hashOf(u) {
  return u.hash || "";
}

// ---------------------------------------------------------------------------
//  Pages accelerees Google : on remonte a l'original
// ---------------------------------------------------------------------------
function unAmp(u) {
  try {
    // google.com/amp/s/exemple.fr/article
    let m = u.pathname.match(/^\/amp\/s\/(.+)$/i);
    if (m && /(^|\.)google\./i.test(u.hostname)) {
      return "https://" + m[1] + u.search + u.hash;
    }
    // cdn.ampproject.org/v/s/exemple.fr/article
    if (/\.cdn\.ampproject\.org$/i.test(u.hostname)) {
      m = u.pathname.match(/^\/[cv]\/s\/(.+)$/i);
      if (m) return "https://" + m[1] + u.search + u.hash;
    }
  } catch (e) { }
  return null;
}

// ---------------------------------------------------------------------------
//  Etat et resolution
// ---------------------------------------------------------------------------
let feCfg = { enabled: true, amp: true, services: {}, instance: {}, except: [] };

async function loadFrontends() {
  try {
    const s = await browser.storage.local.get("feCfg");
    if (s && s.feCfg) feCfg = Object.assign(feCfg, s.feCfg);
  } catch (e) { }
}

function serviceEnabled(f) {
  const v = feCfg.services[f.id];
  if (v === undefined) return f.def !== false;
  return !!v;
}

function instanceOf(f) {
  const custom = feCfg.instance[f.id];
  if (custom) return custom.replace(/\/+$/, "");
  return f.instances[0].replace(/\/+$/, "");
}

function serviceForHost(host) {
  host = (host || "").toLowerCase();
  for (const f of FRONTENDS) {
    for (const re of f.hosts) if (re.test(host)) return f;
  }
  return null;
}

/** Reconnait une facade pour proposer de revenir au service d'origine. */
function serviceOfInstance(host) {
  host = (host || "").toLowerCase();
  for (const f of FRONTENDS) {
    const all = f.instances.concat([feCfg.instance[f.id] || ""]);
    for (const inst of all) {
      if (!inst) continue;
      try {
        if (new URL(inst).hostname.toLowerCase() === host) return f;
      } catch (e) { }
    }
  }
  return null;
}

/** Retourne l'URL de remplacement, ou null si rien a faire. */
function resolveFrontend(rawUrl) {
  if (!feCfg.enabled) return null;

  let u;
  try { u = new URL(rawUrl); } catch (e) { return null; }
  if (!/^https?:$/.test(u.protocol)) return null;

  // Derogation ponctuelle
  if (u.hash === "#direct" || u.searchParams.get("nofe") === "1") return null;

  if (feCfg.amp) {
    const amp = unAmp(u);
    if (amp) return amp;
  }

  const f = serviceForHost(u.hostname);
  if (!f) return null;
  if (!serviceEnabled(f)) return null;
  if (feCfg.except.indexOf(f.id) !== -1) return null;

  const base = instanceOf(f);
  if (!base) return null;

  let out;
  try { out = f.map(u, base); } catch (e) { return null; }
  if (!out) return null;

  // Garde-fou contre les boucles
  try {
    if (new URL(out).hostname === u.hostname) return null;
  } catch (e) { return null; }

  return out;
}
