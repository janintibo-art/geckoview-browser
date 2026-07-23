"use strict";

// ===========================================================================
//  categories.js  --  reprise des categories de NetFilter.
//  Les domaines vivent dans lists/*.txt : editez ces fichiers texte,
//  un domaine par ligne, "#" pour un commentaire.
// ===========================================================================

const CATEGORIES = [
  { id: "ads",            name: "Publicite et traqueurs",        file: "blocklist.txt",                 def: true,  nav: false },
  { id: "bollore",        name: "Medias du groupe Bollore",      file: "bollore-blocklist.txt",         def: true,  nav: true  },
  { id: "farright",       name: "Medias d'extreme droite",       file: "farright-blocklist.txt",        def: true,  nav: true,
    warn: "Classement editorial et conteste, pas un fait objectif." },
  { id: "multinationals", name: "Multinationales",               file: "multinationals-blocklist.txt",  def: false, nav: true  },
  { id: "socialmedia",    name: "Reseaux sociaux",               file: "socialmedia-blocklist.txt",     def: false, nav: true  },
  { id: "football",       name: "Football",                      file: "football-blocklist.txt",        def: false, nav: true  },
  { id: "sport",          name: "Sport (general)",               file: "sport-blocklist.txt",           def: false, nav: true  },
  { id: "doh",            name: "Resolveurs DNS chiffres",       file: "doh-blocklist.txt",             def: false, nav: false,
    warn: "Utile surtout avec un filtre DNS ; sans interet dans le navigateur seul." }
];

// Cache des domaines par categorie
const CAT_DOMAINS = {};

function parseList(text) {
  const out = [];
  for (let line of text.split("\n")) {
    const h = line.indexOf("#");
    if (h !== -1) line = line.slice(0, h);
    line = line.trim().toLowerCase();
    if (!line || line.indexOf(".") === -1) continue;
    const parts = line.split(/\s+/);
    out.push(parts[parts.length - 1].replace(/^www\./, ""));
  }
  return out;
}

async function loadCategory(cat) {
  if (CAT_DOMAINS[cat.id]) return CAT_DOMAINS[cat.id];
  try {
    const url = (typeof browser !== "undefined" && browser.runtime)
      ? browser.runtime.getURL("lists/" + cat.file)
      : "lists/" + cat.file;
    const resp = await fetch(url);
    CAT_DOMAINS[cat.id] = parseList(await resp.text());
  } catch (e) {
    CAT_DOMAINS[cat.id] = [];
  }
  return CAT_DOMAINS[cat.id];
}

async function loadAllCategories() {
  await Promise.all(CATEGORIES.map(loadCategory));
  return CAT_DOMAINS;
}

// Etat des categories (coche / decoche), persiste dans storage.local
async function getCatState() {
  let saved = {};
  try {
    const s = await browser.storage.local.get("catState");
    saved = (s && s.catState) || {};
  } catch (e) { /* ignore */ }
  const state = {};
  CATEGORIES.forEach(c => {
    state[c.id] = saved[c.id] === undefined ? c.def : !!saved[c.id];
  });
  return state;
}

async function setCatState(state) {
  try { await browser.storage.local.set({ catState: state }); } catch (e) { }
}

// Construit l'ensemble des domaines actifs pour un usage donne
//   usage = "nav"    -> blocage de la navigation (categories nav: true)
//   usage = "search" -> masquage dans les resultats
//   usage = "net"    -> blocage des requetes de ressources
async function buildSet(usage, state, extra, allow) {
  await loadAllCategories();
  const set = new Set();
  for (const cat of CATEGORIES) {
    if (!state[cat.id]) continue;
    if (usage === "nav" && !cat.nav) continue;
    (CAT_DOMAINS[cat.id] || []).forEach(d => set.add(d));
  }
  (extra || []).forEach(d => set.add(String(d).toLowerCase().trim()));
  (allow || []).forEach(d => set.delete(String(d).toLowerCase().trim()));
  return set;
}

function hostMatches(host, set) {
  if (!host || !set.size) return null;
  host = host.toLowerCase().replace(/^www\./, "");
  if (set.has(host)) return host;
  let i = host.indexOf(".");
  while (i !== -1) {
    const parent = host.slice(i + 1);
    if (set.has(parent)) return parent;
    i = host.indexOf(".", i + 1);
  }
  return null;
}

const CAT_API = {
  CATEGORIES, CAT_DOMAINS, loadCategory, loadAllCategories,
  getCatState, setCatState, buildSet, hostMatches, parseList
};

if (typeof window !== "undefined") window.CAT_API = CAT_API;
