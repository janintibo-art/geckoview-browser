"use strict";

// ===========================================================================
//  background.js
//  Trois roles :
//   1. blocage des requetes publicitaires et de pistage (GeckoBlock)
//   2. blocage de la navigation par categories (portage de NetFilter)
//   3. identite du navigateur et remontee des compteurs vers l'app
// ===========================================================================

let enabled = true;
let blockedCount = 0;
let nativePort = null;

const adDomains = new Set(SEED_DOMAINS.map(d => d.toLowerCase()));
const allowDomains = new Set(ALLOWLIST.map(d => d.toLowerCase()));

let navSet = new Set();      // categories bloquant la navigation
let hideSet = new Set();     // categories masquees dans les pages/resultats
let catState = {};
let userExtra = [];
let userAllow = [];
let cookieCfg = {
  blockThirdParty: true,   // refuser les cookies tiers
  stripSent: true,         // ne pas renvoyer de cookie aux tiers
  clearOnExit: false       // purger les cookies a la fermeture
};
let consentRejected = 0;

// Sites ou les cookies tiers restent autorises (connexion, paiement, captcha)
const COOKIE_EXEMPT = new Set([
  "accounts.google.com", "recaptcha.net", "google.com", "gstatic.com",
  "hcaptcha.com", "cloudflare.com", "paypal.com", "paypalobjects.com",
  "stripe.com", "stripe.network", "checkout.stripe.com",
  "auth0.com", "okta.com", "microsoftonline.com", "live.com",
  "franceconnect.gouv.fr", "impots.gouv.fr"
]);

const bypass = new Set();    // sites debloques jusqu'au redemarrage
let lastOriginal = "";       // derniere adresse redirigee vers une facade
let pendingFrontend = null;   // derniere redirection, pour revenir en arriere
let bookmarksCache = null;
let bookmarksWaiting = null;

function requestBookmarks() {
  return new Promise(resolve => {
    if (!nativePort) { resolve(bookmarksCache || []); return; }
    bookmarksWaiting = resolve;
    try {
      nativePort.postMessage({ type: "getBookmarks" });
    } catch (e) {
      bookmarksWaiting = null;
      resolve(bookmarksCache || []);
      return;
    }
    setTimeout(() => {
      if (bookmarksWaiting) {
        bookmarksWaiting = null;
        resolve(bookmarksCache || []);
      }
    }, 1500);
  });
}

const REMOTE_LISTS = [
  "https://adaway.org/hosts.txt",
  "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=hosts&showintro=0&mimetype=plaintext"
];
const REFRESH_MS = 24 * 60 * 60 * 1000;
// Environ 120 000 domaines couvrent les listes AdAway et Peter Lowe reunies,
// pour a peu pres 12 Mo en memoire. Au-dela, le gain est marginal sur telephone.
const MAX_ENTRIES = 120000;

// ---------------------------------------------------------------------------
//  Utilitaires
// ---------------------------------------------------------------------------
function hostOf(url) {
  try { return new URL(url).hostname.toLowerCase().replace(/^www\./, ""); }
  catch (e) { return ""; }
}

function baseDomain(host) {
  const p = host.split(".");
  if (p.length <= 2) return host;
  const two = ["co.uk", "com.au", "co.jp", "com.br", "co.nz", "org.uk", "gov.uk", "ac.uk"];
  const last2 = p.slice(-2).join(".");
  return (two.includes(last2) && p.length >= 3) ? p.slice(-3).join(".") : last2;
}

function inSet(host, set) {
  if (!host || !set.size) return false;
  if (set.has(host)) return true;
  let i = host.indexOf(".");
  while (i !== -1) {
    if (set.has(host.slice(i + 1))) return true;
    i = host.indexOf(".", i + 1);
  }
  return false;
}

function matchesPattern(url) {
  for (const re of URL_PATTERNS) if (re.test(url)) return true;
  return false;
}

// ---------------------------------------------------------------------------
//  Categories
// ---------------------------------------------------------------------------
async function rebuildSets() {
  await loadFrontends();
  catState = await CAT_API.getCatState();
  try {
    const s = await browser.storage.local.get(
      ["pageExtra", "pageAllow", "cookieCfg"]);
    userExtra = (s && s.pageExtra) || [];
    userAllow = (s && s.pageAllow) || [];
    if (s && s.cookieCfg) cookieCfg = Object.assign(cookieCfg, s.cookieCfg);
  } catch (e) { }

  navSet = await CAT_API.buildSet("nav", catState, userExtra, userAllow);
  hideSet = await CAT_API.buildSet("search", catState, userExtra, userAllow);

  // Les domaines de la categorie "ads" alimentent aussi le filtre reseau.
  (CAT_API.CAT_DOMAINS.ads || []).forEach(d => adDomains.add(d));

  // Liste partagee avec les scripts de contenu et la page de recherche.
  try { await browser.storage.local.set({ hideList: Array.from(hideSet) }); }
  catch (e) { }

  pushState();
}

browser.storage.onChanged.addListener(changes => {
  if (changes.feCfg) loadFrontends();
  if (changes.catState || changes.pageExtra || changes.pageAllow ||
      changes.cookieCfg) {
    rebuildSets();
  }
});

// ---------------------------------------------------------------------------
//  Pont natif vers l'application
// ---------------------------------------------------------------------------
function connectNative() {
  try {
    nativePort = browser.runtime.connectNative("browser");
    nativePort.onMessage.addListener(msg => {
      if (!msg) return;
      if (msg.type === "setEnabled") { enabled = !!msg.value; pushState(); }
      else if (msg.type === "bookmarks") {
        bookmarksCache = msg.list || [];
        if (bookmarksWaiting) { bookmarksWaiting(bookmarksCache); bookmarksWaiting = null; }
      }
      else if (msg.type === "askOriginal") {
        if (nativePort) {
          try {
            nativePort.postMessage({
              type: "navigate",
              url: lastOriginal
                ? lastOriginal + (lastOriginal.indexOf("#") === -1 ? "#direct" : "")
                : "",
              notice: lastOriginal ? "" : "Aucune redirection recente"
            });
          } catch (e) { }
        }
      }
      else if (msg.type === "setSentinel") {
        try {
          browser.storage.local.get("alertCfg").then(s2 => {
            const c = Object.assign({ enabled: true, mute: [] },
                                    (s2 && s2.alertCfg) || {});
            c.enabled = !!msg.value;
            browser.storage.local.set({ alertCfg: c });
          });
        } catch (e) { }
      }
      else if (msg.type === "setEngine") {
        // Le moteur choisi dans le menu doit aussi s'appliquer au champ de
        // recherche de la page d'accueil, qui vit dans l'extension.
        try {
          browser.storage.local.set({ engineTemplate: msg.template || "internal" });
        } catch (e) { }
      }
      else if (msg.type === "setProfile") {
        // Le navigateur remplace deja l'agent lui-meme : on ne stocke le profil
        // que pour aligner les proprietes JavaScript secondaires.
        try {
          browser.storage.local.set({ deviceProfile: msg.profile || null });
        } catch (e) { }
      }
      else if (msg.type === "cmd" && msg.cmd) {
        // Le menu de l'application n'a pas d'acces direct aux scripts de
        // contenu : on passe par le stockage, qu'ils observent tous.
        try {
          browser.storage.local.set({
            pageCommand: { cmd: msg.cmd, ts: Date.now() }
          });
        } catch (e) { }
      }
      else if (msg.type === "inspect") {
        // Le menu de l'application n'a pas d'acces direct aux scripts de
        // contenu : on passe par le stockage, qu'ils observent.
        try { browser.storage.local.set({ inspectRequest: Date.now() }); } catch (e) { }
      }
      else if (msg.type === "resetCount") { blockedCount = 0; pushState(); }
      else if (msg.type === "getState") pushState();
    });
    nativePort.onDisconnect.addListener(() => { nativePort = null; });
    pushState();
  } catch (e) { nativePort = null; }
}

let pushTimer = null;
function pushState() {
  if (pushTimer) return;
  pushTimer = setTimeout(() => {
    pushTimer = null;
    if (!nativePort) return;
    try {
      nativePort.postMessage({
        type: "state", blocked: blockedCount, enabled: enabled,
        rules: adDomains.size + navSet.size
      });
    } catch (e) { nativePort = null; }
  }, 250);
}

// ---------------------------------------------------------------------------
//  Surveillance de pages
//
//  Les verifications ont lieu tant que le navigateur tourne. Une surveillance
//  reellement continue demanderait un service en arriere-plan permanent, avec
//  le cout en batterie que cela suppose : on s'en tient a un rattrapage au
//  demarrage et a un passage regulier pendant l'utilisation.
// ---------------------------------------------------------------------------
let watches = [];
const WATCH_TICK = 5 * 60 * 1000;

async function loadWatches() {
  try {
    const s = await browser.storage.local.get("watches");
    watches = (s && s.watches) || [];
  } catch (e) { watches = []; }
}

function watchValue(doc, w) {
  let el = null;
  try { el = doc.querySelector(w.selector); } catch (e) { return null; }

  if (w.mode === "presence") return el ? "present" : "absent";
  if (!el) return "";

  const text = (el.textContent || "").replace(/\s+/g, " ").trim();
  if (w.mode === "nombre") {
    const m = text.replace(/\u00A0/g, " ").match(/-?\d[\d\s.,]*/);
    return m ? m[0].replace(/\s/g, "").replace(",", ".") : "";
  }
  return text.slice(0, 400);
}

async function checkWatch(w) {
  let doc = null;
  try {
    const r = await fetch(w.url, { cache: "no-cache", credentials: "omit" });
    if (!r.ok) return { error: "HTTP " + r.status };
    doc = new DOMParser().parseFromString(await r.text(), "text/html");
  } catch (e) {
    return { error: "injoignable" };
  }

  const value = watchValue(doc, w);
  if (value === null) return { error: "selecteur invalide" };

  w.checkedAt = Date.now();
  if (value === w.value) return { changed: false };

  w.previous = w.value;
  w.value = value;
  w.changedAt = Date.now();
  w.history = (w.history || []).slice(-4);
  w.history.push({ at: w.changedAt, from: w.previous, to: value });
  return { changed: true };
}

async function checkAllWatches(force) {
  if (!watches.length) return { checked: 0, changed: 0 };
  const now = Date.now();
  let checked = 0, changed = 0;

  for (const w of watches) {
    if (!w.enabled) continue;
    const due = force || !w.checkedAt ||
                (now - w.checkedAt) >= (w.every || 120) * 60000;
    if (!due) continue;

    const res = await checkWatch(w);
    checked++;
    if (res.changed) {
      changed++;
      if (nativePort) {
        try {
          nativePort.postMessage({
            type: "notify",
            id: w.id,
            title: "Changement sur " + w.host,
            text: (w.previous || "(vide)") + "  \u2192  " + (w.value || "(vide)"),
            url: w.url
          });
        } catch (e) { }
      }
    }
  }

  try { await browser.storage.local.set({ watches: watches }); } catch (e) { }
  return { checked, changed };
}

// ---------------------------------------------------------------------------
//  Historique
//  Conserve localement, jamais transmis. Le texte des pages n'est indexe
//  que si l'utilisateur l'a demande explicitement.
// ---------------------------------------------------------------------------
let history = [];
const HIST_MAX = 800;

async function loadHistory() {
  try {
    const s = await browser.storage.local.get("history");
    history = (s && s.history) || [];
  } catch (e) { history = []; }
}

async function histAdd(entry) {
  if (!entry || !entry.url) return { ok: false };
  await loadHistory();

  // Une meme adresse revisitee met a jour l'entree plutot que d'en creer une
  const i = history.findIndex(h => h.url === entry.url);
  if (i !== -1) {
    const old = history[i];
    entry.visits = (old.visits || 1) + 1;
    if (!entry.text && old.text) entry.text = old.text;
    history.splice(i, 1);
  } else {
    entry.visits = 1;
  }

  history.unshift(entry);
  while (history.length > HIST_MAX) history.pop();

  try { await browser.storage.local.set({ history: history }); } catch (e) { }
  return { ok: true, size: history.length };
}

// ---------------------------------------------------------------------------
//  Flux maison : releve des nouvelles entrees
// ---------------------------------------------------------------------------
let feeds = [];
const FEED_TICK = 15 * 60 * 1000;

async function loadFeeds() {
  try {
    const s = await browser.storage.local.get("feeds");
    feeds = (s && s.feeds) || [];
  } catch (e) { feeds = []; }
}

function feedItems(doc, selector, base) {
  let nodes = [];
  try { nodes = Array.from(doc.querySelectorAll(selector)); } catch (e) { return []; }

  const out = [];
  const seen = new Set();

  for (const el of nodes) {
    const a = el.matches("a[href]") ? el : el.querySelector("a[href]");
    if (!a) continue;

    let link;
    try { link = new URL(a.getAttribute("href"), base).href; } catch (e) { continue; }
    if (!/^https?:/i.test(link) || seen.has(link)) continue;

    const h = el.querySelector("h1, h2, h3, h4, [class*='title'], [class*='titre']");
    let title = ((h || a).textContent || "").replace(/\s+/g, " ").trim();
    if (!title) title = (el.textContent || "").replace(/\s+/g, " ").trim();
    if (!title) continue;

    const time = el.querySelector("time[datetime], time");
    seen.add(link);
    out.push({
      title: title.slice(0, 200),
      link: link,
      date: time ? (time.getAttribute("datetime") || time.textContent || "").trim().slice(0, 40) : ""
    });
    if (out.length >= 60) break;
  }
  return out;
}

async function refreshFeed(f) {
  let doc = null;
  try {
    const r = await fetch(f.url, { cache: "no-cache", credentials: "omit" });
    if (!r.ok) return { error: "HTTP " + r.status };
    doc = new DOMParser().parseFromString(await r.text(), "text/html");
  } catch (e) {
    return { error: "injoignable" };
  }

  const fresh = feedItems(doc, f.selector, f.url);
  if (!fresh.length) return { error: "aucune entree" };

  const known = new Set((f.items || []).map(i => i.link));
  const added = fresh.filter(i => !known.has(i.link));

  f.checkedAt = Date.now();
  if (added.length) {
    const marked = added.map(i => Object.assign({ at: Date.now(), seen: false }, i));
    f.items = marked.concat(f.items || []).slice(0, 120);
  }
  return { added: added.length, titles: added.slice(0, 3).map(i => i.title) };
}

async function refreshFeeds(force) {
  if (!feeds.length) return { checked: 0, added: 0 };
  const now = Date.now();
  let checked = 0, added = 0;

  for (const f of feeds) {
    if (!f.enabled) continue;
    const due = force || !f.checkedAt ||
                (now - f.checkedAt) >= (f.every || 180) * 60000;
    if (!due) continue;

    const res = await refreshFeed(f);
    checked++;
    if (res.added) {
      added += res.added;
      // Notification seulement si le flux la demande : un releve de titres
      // deviendrait vite envahissant.
      if (f.notify && nativePort) {
        try {
          nativePort.postMessage({
            type: "notify",
            id: f.id,
            title: res.added + " nouveaute(s) sur " + f.host,
            text: res.titles.join("\n"),
            url: f.url
          });
        } catch (e) { }
      }
    }
  }

  try { await browser.storage.local.set({ feeds: feeds }); } catch (e) { }
  return { checked, added };
}

// ---------------------------------------------------------------------------
//  Journal reseau (tampon circulaire, consulte par l'analyseur de page)
// ---------------------------------------------------------------------------
const NET_MAX = 400;
const netLog = [];
const netIndex = new Map();   // requestId -> entree

function netPush(e) {
  netLog.push(e);
  netIndex.set(e.id, e);
  while (netLog.length > NET_MAX) {
    const old = netLog.shift();
    netIndex.delete(old.id);
  }
}

browser.webRequest.onBeforeRequest.addListener(
  d => {
    netPush({
      id: d.requestId,
      url: d.url,
      method: d.method,
      type: d.type,
      doc: d.documentUrl || d.originUrl || d.url,
      start: Date.now(),
      status: null,
      mime: "",
      size: null,
      ms: null,
      blocked: false,
      error: ""
    });
  },
  { urls: ["<all_urls>"] }
);

browser.webRequest.onHeadersReceived.addListener(
  d => {
    const e = netIndex.get(d.requestId);
    if (!e) return;
    e.status = d.statusCode;
    (d.responseHeaders || []).forEach(h => {
      const n = h.name.toLowerCase();
      if (n === "content-type") e.mime = (h.value || "").split(";")[0];
      if (n === "content-length") e.size = parseInt(h.value, 10);
    });
  },
  { urls: ["<all_urls>"] },
  ["responseHeaders"]
);

browser.webRequest.onCompleted.addListener(
  d => {
    const e = netIndex.get(d.requestId);
    if (!e) return;
    e.ms = Date.now() - e.start;
    if (e.status == null) e.status = d.statusCode;
    if (d.fromCache) e.mime = e.mime || "(cache)";
  },
  { urls: ["<all_urls>"] }
);

browser.webRequest.onErrorOccurred.addListener(
  d => {
    const e = netIndex.get(d.requestId);
    if (e) {
      e.ms = Date.now() - e.start;
      e.error = d.error || "erreur";
      if (/BLOCKED|ABORTED/i.test(e.error)) e.blocked = true;
    }

    // Une facade injoignable laisse une page vide : on revient a l'original
    // plutot que d'abandonner l'utilisateur devant un ecran noir.
    if (d.type === "main_frame" && pendingFrontend &&
        d.url === pendingFrontend.to &&
        Date.now() - pendingFrontend.at < 30000 &&
        !/ABORT/i.test(d.error || "")) {
      const back = pendingFrontend.from;
      pendingFrontend = null;
      if (nativePort) {
        try {
          nativePort.postMessage({
            type: "navigate",
            url: back + (back.indexOf("#") === -1 ? "#direct" : ""),
            notice: "Facade injoignable : retour au site d'origine"
          });
        } catch (e2) { }
      }
    }
  },
  { urls: ["<all_urls>"] }
);

// ---------------------------------------------------------------------------
//  Blocage des requetes
// ---------------------------------------------------------------------------
browser.webRequest.onBeforeRequest.addListener(
  details => {
    if (!enabled) return {};

    const url = details.url;
    if (url.startsWith("data:") || url.startsWith("blob:") ||
        url.startsWith("moz-extension:") || url.startsWith("about:")) return {};

    const host = hostOf(url);
    if (!host) return {};

    // --- Navigation principale : categories de sites ---
    if (details.type === "main_frame") {
      if (bypass.has(host)) return {};

      // Facade libre a la place du service d'origine
      const fe = resolveFrontend(url);
      if (fe) {
        lastOriginal = url;
        pendingFrontend = { from: url, to: fe, at: Date.now() };
        return { redirectUrl: fe };
      }

      const hit = CAT_API.hostMatches(host, navSet);
      if (hit) {
        blockedCount++;
        pushState();
        return {
          redirectUrl: browser.runtime.getURL("blocked.html") +
            "?host=" + encodeURIComponent(host) +
            "&via=" + encodeURIComponent(hit) +
            "&to=" + encodeURIComponent(url)
        };
      }
      return {};
    }

    if (inSet(host, allowDomains)) return {};

    const originHost = details.documentUrl ? hostOf(details.documentUrl) : "";
    const firstParty = originHost && baseDomain(originHost) === baseDomain(host);

    let block = false;
    if (inSet(host, adDomains)) block = true;
    else if (inSet(host, navSet)) block = true;      // ressources des sites filtres
    else if (!firstParty && matchesPattern(url)) block = true;

    if (!block) return {};

    blockedCount++;
    pushState();
    const logged = netIndex.get(details.requestId);
    if (logged) logged.blocked = true;

    if (details.type === "image") {
      return { redirectUrl:
        "data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7" };
    }
    if (details.type === "sub_frame") {
      return { redirectUrl: "data:text/html,<html><body></body></html>" };
    }
    return { cancel: true };
  },
  { urls: ["<all_urls>"] },
  ["blocking"]
);

// ---------------------------------------------------------------------------
//  En-tetes : pistage et identite du navigateur
// ---------------------------------------------------------------------------
browser.webRequest.onBeforeSendHeaders.addListener(
  details => {
    const headers = details.requestHeaders.filter(h => {
      const n = h.name.toLowerCase();
      return n !== "x-client-data";
    });

    if (enabled) {
      headers.push({ name: "DNT", value: "1" });
      headers.push({ name: "Sec-GPC", value: "1" });
    }

    // Cookies sortants vers un tiers : on ne les renvoie pas.
    if (cookieCfg.stripSent && details.type !== "main_frame") {
      const host = hostOf(details.url);
      const origin = details.documentUrl ? hostOf(details.documentUrl) : "";
      const thirdParty = origin && baseDomain(origin) !== baseDomain(host);
      if (thirdParty && !inSet(host, COOKIE_EXEMPT)) {
        for (let i = headers.length - 1; i >= 0; i--) {
          if (headers[i].name.toLowerCase() === "cookie") headers.splice(i, 1);
        }
      }
    }

    return { requestHeaders: headers };
  },
  { urls: ["<all_urls>"] },
  ["blocking", "requestHeaders"]
);

// ---------------------------------------------------------------------------
//  Cookies tiers : on refuse leur depot
// ---------------------------------------------------------------------------
browser.webRequest.onHeadersReceived.addListener(
  details => {
    if (!cookieCfg.blockThirdParty) return {};
    if (details.type === "main_frame") return {};

    const host = hostOf(details.url);
    const origin = details.documentUrl ? hostOf(details.documentUrl) : "";
    if (!origin) return {};
    if (baseDomain(origin) === baseDomain(host)) return {};   // premiere partie
    if (inSet(host, COOKIE_EXEMPT)) return {};

    const headers = details.responseHeaders.filter(
      h => h.name.toLowerCase() !== "set-cookie");
    if (headers.length === details.responseHeaders.length) return {};
    return { responseHeaders: headers };
  },
  { urls: ["<all_urls>"] },
  ["blocking", "responseHeaders"]
);

// ---------------------------------------------------------------------------
//  Purge des cookies a la fermeture (si l'API est disponible)
// ---------------------------------------------------------------------------
async function purgeCookies() {
  if (!cookieCfg.clearOnExit) return 0;
  if (typeof browser.cookies === "undefined" || !browser.cookies.getAll) return 0;
  try {
    const all = await browser.cookies.getAll({});
    let n = 0;
    for (const c of all) {
      const d = c.domain.replace(/^\./, "");
      if (inSet(d, COOKIE_EXEMPT)) continue;
      if (userAllow.some(a => d === a || d.endsWith("." + a))) continue;
      const url = (c.secure ? "https://" : "http://") + d + c.path;
      try { await browser.cookies.remove({ url, name: c.name }); n++; } catch (e) { }
    }
    return n;
  } catch (e) { return 0; }
}

// ---------------------------------------------------------------------------
//  Listes distantes de publicite
// ---------------------------------------------------------------------------
function parseHosts(text) {
  const out = [];
  for (let line of text.split("\n")) {
    const h = line.indexOf("#");
    if (h !== -1) line = line.slice(0, h);
    line = line.trim();
    if (!line) continue;
    const parts = line.split(/\s+/);
    let d = (parts.length >= 2 ? parts[1] : parts[0]).toLowerCase();
    if (!d || d.indexOf(".") === -1) continue;
    if (["localhost", "localhost.localdomain", "broadcasthost", "0.0.0.0", "::1"].includes(d)) continue;
    out.push(d);
  }
  return out;
}

function ingest(list) {
  for (const d of list) {
    if (adDomains.size >= MAX_ENTRIES) break;
    if (!allowDomains.has(d)) adDomains.add(d);
  }
}

async function refreshLists() {
  try {
    const store = await browser.storage.local.get(["ts", "domains"]);
    if (store.ts && Date.now() - store.ts < REFRESH_MS && Array.isArray(store.domains)) {
      ingest(store.domains);
      pushState();
      return;
    }
    const collected = [];
    for (const url of REMOTE_LISTS) {
      try {
        const r = await fetch(url, { cache: "no-cache" });
        if (r.ok) collected.push(...parseHosts(await r.text()));
      } catch (e) { }
    }
    if (collected.length) {
      ingest(collected);
      await browser.storage.local.set({ ts: Date.now(), domains: collected.slice(0, MAX_ENTRIES) });
    } else if (Array.isArray(store.domains)) {
      ingest(store.domains);
    }
    pushState();
  } catch (e) { pushState(); }
}

// ---------------------------------------------------------------------------
//  Messages des pages internes et scripts de contenu
// ---------------------------------------------------------------------------
browser.runtime.onMessage.addListener(msg => {
  if (!msg) return;
  if (msg.type === "cosmetic") {
    blockedCount += msg.count || 0;
    pushState();
  }
  if (msg.type === "getConfig") {
    return Promise.resolve({
      enabled: enabled,
      selectors: COSMETIC_SELECTORS,
      overlays: OVERLAY_SELECTORS
    });
  }
  if (msg.type === "bypass" && msg.host) {
    bypass.add(String(msg.host).toLowerCase().replace(/^www\./, ""));
    return Promise.resolve({ ok: true });
  }
  if (msg.type === "consentRejected") {
    consentRejected++;
    return Promise.resolve({ ok: true });
  }
  if (msg.type === "purgeCookies") {
    return purgeCookies().then(n => ({ removed: n }));
  }
  // -------------------------------------------------------------------------
  //  Synchronisation : instantane et restauration
  // -------------------------------------------------------------------------
  if (msg.type === "syncSnapshot") {
    return (async () => {
      const keys = [
        "userscripts", "userStyles", "catState", "pageCfg", "cookieCfg",
        "feCfg", "engineOn", "searxUrl", "pageExtra", "pageAllow",
        "deviceProfile", "autopagerOff", "showBadge"
      ];
      let data = {};
      try { data = await browser.storage.local.get(keys); } catch (e) { }

      // Les favoris vivent cote application : on les demande par le port.
      data.bookmarks = await requestBookmarks();

      return {
        version: 1,
        updated: new Date().toISOString(),
        data: data
      };
    })();
  }

  if (msg.type === "syncApply") {
    return (async () => {
      const d = (msg.snapshot && msg.snapshot.data) || {};
      const bookmarks = d.bookmarks;
      delete d.bookmarks;
      try { await browser.storage.local.set(d); } catch (e) {
        return { error: String(e) };
      }
      if (bookmarks && nativePort) {
        try {
          nativePort.postMessage({ type: "setBookmarks", list: bookmarks });
        } catch (e) { }
      }
      await rebuildSets();
      return { ok: true, keys: Object.keys(d).length };
    })();
  }

  if (msg.type === "feList") {
    return Promise.resolve({
      cfg: feCfg,
      services: FRONTENDS.map(f => ({
        id: f.id, name: f.name, target: f.target,
        instances: f.instances, def: f.def !== false
      }))
    });
  }
  if (msg.type === "getOriginal") {
    return Promise.resolve({ url: lastOriginal });
  }
  if (msg.type === "feBack") {
    // Revenir au service d'origine depuis une facade
    const f = serviceOfInstance(msg.host || "");
    return Promise.resolve({
      service: f ? { id: f.id, name: f.name } : null,
      original: lastOriginal
    });
  }
  if (msg.type === "feExcept") {
    if (msg.id && feCfg.except.indexOf(msg.id) === -1) {
      feCfg.except.push(msg.id);
      try { browser.storage.local.set({ feCfg: feCfg }); } catch (e) { }
    }
    return Promise.resolve({ ok: true, original: lastOriginal });
  }
  if (msg.type === "histAdd") {
    return histAdd(msg.entry);
  }
  if (msg.type === "histList") {
    return loadHistory().then(() => ({ history: history }));
  }
  if (msg.type === "histSave") {
    history = msg.history || [];
    return browser.storage.local.set({ history: history }).then(() => ({ ok: true }));
  }
  if (msg.type === "feedList") {
    return loadFeeds().then(() => ({ feeds: feeds }));
  }
  if (msg.type === "feedRefresh") {
    return refreshFeeds(true);
  }
  if (msg.type === "feedSave") {
    feeds = msg.feeds || [];
    return browser.storage.local.set({ feeds: feeds }).then(() => ({ ok: true }));
  }
  if (msg.type === "watchList") {
    return loadWatches().then(() => ({ watches: watches }));
  }
  if (msg.type === "watchCheck") {
    return checkAllWatches(true);
  }
  if (msg.type === "watchSave") {
    watches = msg.watches || [];
    return browser.storage.local.set({ watches: watches }).then(() => ({ ok: true }));
  }
  // -------------------------------------------------------------------------
  //  Rapport « qui parle a qui » : les tiers contactes par une page
  // -------------------------------------------------------------------------
  if (msg.type === "sameOwner") {
    const host = (msg.host || "").replace(/^www\./, "").toLowerCase();
    let owner = null;
    try { owner = ownerOf(host); } catch (e) { }
    if (!owner) return Promise.resolve({ owner: null, domains: [host] });

    // Tous les titres partageant ce proprietaire sont ecartes de la recherche
    const domains = Object.keys(OWNERSHIP).filter(d => OWNERSHIP[d] === owner);
    if (domains.indexOf(host) === -1) domains.push(host);
    return Promise.resolve({ owner: owner, domains: domains });
  }

  if (msg.type === "thirdParty") {
    const origin = msg.origin || "";
    let pageHost = "";
    try { pageHost = new URL(origin).hostname.replace(/^www\./, ""); } catch (e) { }
    const pageBase = pageHost ? baseDomain(pageHost) : "";

    const byDomain = new Map();
    let total = 0;

    for (const e of netLog) {
      const belongs = (e.doc && e.doc.indexOf(origin) === 0) ||
                      e.url.indexOf(origin) === 0;
      if (!belongs) continue;
      total++;

      const host = hostOf(e.url);
      if (!host) continue;
      const base = baseDomain(host);
      if (base === pageBase) continue;   // premiere partie

      let d = byDomain.get(base);
      if (!d) {
        d = {
          domain: base,
          hosts: [],
          count: 0,
          bytes: 0,
          blocked: 0,
          types: {},
          owner: null,
          category: null
        };
        byDomain.set(base, d);
      }

      d.count++;
      if (e.size) d.bytes += e.size;
      if (e.blocked) d.blocked++;
      if (d.hosts.indexOf(host) === -1 && d.hosts.length < 6) d.hosts.push(host);
      d.types[e.type] = (d.types[e.type] || 0) + 1;
    }

    // Qualification : proprietaire connu, categorie de filtrage, regie
    for (const d of byDomain.values()) {
      try { d.owner = ownerOf(d.domain); } catch (e) { d.owner = null; }

      if (inSet(d.domain, adDomains)) d.category = "publicite";

      for (const cat of CAT_API.CATEGORIES) {
        const list = CAT_API.CAT_DOMAINS[cat.id];
        if (!list || !list.length) continue;
        if (CAT_API.hostMatches(d.domain, new Set(list))) {
          d.category = cat.id === "ads" ? "publicite" : cat.name;
          break;
        }
      }
    }

    const list = Array.from(byDomain.values()).sort((a, b) => b.count - a.count);
    return Promise.resolve({
      page: pageHost,
      totalRequests: total,
      thirdParties: list,
      blockedTotal: list.reduce((n, d) => n + d.blocked, 0)
    });
  }

  if (msg.type === "netLog") {
    const origin = msg.origin || "";
    const list = netLog.filter(e =>
      !origin || (e.doc && e.doc.indexOf(origin) === 0) || e.url.indexOf(origin) === 0);
    return Promise.resolve({ entries: list.slice(-250) });
  }
  if (msg.type === "netClear") {
    netLog.length = 0;
    netIndex.clear();
    return Promise.resolve({ ok: true });
  }
  if (msg.type === "gmCommands") {
    if (nativePort) {
      try {
        nativePort.postMessage({ type: "gmCommands", list: msg.list || [] });
      } catch (e) { }
    }
    return Promise.resolve({ ok: true });
  }
  if (msg.type === "downloadUrls") {
    if (!nativePort) return Promise.resolve({ error: "app non connectee" });
    try {
      nativePort.postMessage({
        type: "download",
        urls: msg.urls || [],
        referer: msg.referer || ""
      });
      return Promise.resolve({ ok: true, count: (msg.urls || []).length });
    } catch (e) {
      return Promise.resolve({ error: String(e) });
    }
  }
  if (msg.type === "extractAudio") {
    if (!nativePort) return Promise.resolve({ error: "app non connectee" });
    try {
      nativePort.postMessage({
        type: "extractAudio",
        urls: msg.urls || [],
        referer: msg.referer || ""
      });
      return Promise.resolve({ ok: true, count: (msg.urls || []).length });
    } catch (e) {
      return Promise.resolve({ error: String(e) });
    }
  }
  if (msg.type === "downloadText") {
    if (!nativePort) return Promise.resolve({ error: "app non connectee" });
    try {
      nativePort.postMessage({
        type: "downloadText",
        name: msg.name || "liste.txt",
        text: msg.text || ""
      });
      return Promise.resolve({ ok: true });
    } catch (e) {
      return Promise.resolve({ error: String(e) });
    }
  }
  if (msg.type === "gmFetch") {
    return (async () => {
      try {
        const init = { method: msg.method || "GET", headers: msg.headers || {} };
        if (msg.data) init.body = msg.data;
        const r = await fetch(msg.url, init);

        // Mode binaire : renvoie le contenu encode, pour l'archivage de page
        if (msg.binary) {
          const buf = await r.arrayBuffer();
          const max = msg.maxBytes || 2 * 1024 * 1024;
          if (buf.byteLength > max) {
            return { error: "trop volumineux", bytes: buf.byteLength };
          }
          const bytes = new Uint8Array(buf);
          let bin = "";
          const step = 0x8000;
          for (let i = 0; i < bytes.length; i += step) {
            bin += String.fromCharCode.apply(null, bytes.subarray(i, i + step));
          }
          return {
            status: r.status,
            base64: btoa(bin),
            mime: (r.headers.get("content-type") || "").split(";")[0],
            bytes: buf.byteLength
          };
        }

        const body = await r.text();
        const headers = {};
        r.headers.forEach((v, k) => { headers[k] = v; });
        return { status: r.status, statusText: r.statusText, body, headers, finalUrl: r.url };
      } catch (e) {
        return { error: String(e) };
      }
    })();
  }
  if (msg.type === "stats") {
    return Promise.resolve({
      blocked: blockedCount,
      adRules: adDomains.size,
      navRules: navSet.size,
      consent: consentRejected,
      categories: catState
    });
  }
});

// ---------------------------------------------------------------------------
//  Demarrage
// ---------------------------------------------------------------------------
// Recuperation automatique au demarrage, si elle a ete demandee
async function autoPull() {
  try {
    const s = await browser.storage.local.get(["syncCfg", "lastPull"]);
    const c = s && s.syncCfg;
    if (!c || !c.autopull || !c.owner || !c.repo || !c.token) return;

    const url = "https://api.github.com/repos/" + encodeURIComponent(c.owner) +
      "/" + encodeURIComponent(c.repo) + "/contents/" +
      c.path.split("/").map(encodeURIComponent).join("/") +
      "?ref=" + encodeURIComponent(c.branch || "main");

    const r = await fetch(url, {
      headers: {
        "Accept": "application/vnd.github+json",
        "Authorization": "Bearer " + c.token,
        "X-GitHub-Api-Version": "2022-11-28"
      }
    });
    if (!r.ok) return;
    const j = await r.json();
    if (!j || !j.content) return;

    const bin = atob(j.content.replace(/\s/g, ""));
    const bytes = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
    const snap = JSON.parse(new TextDecoder().decode(bytes));

    // On n'ecrase que si le depot est plus recent que la derniere recuperation
    if (!snap || !snap.data || !snap.updated) return;
    if (s.lastPull && snap.updated <= s.lastPull) return;

    const bookmarks = snap.data.bookmarks;
    delete snap.data.bookmarks;
    await browser.storage.local.set(snap.data);
    await browser.storage.local.set({ lastPull: snap.updated });
    if (bookmarks && nativePort) {
      try { nativePort.postMessage({ type: "setBookmarks", list: bookmarks }); }
      catch (e) { }
    }
    await rebuildSets();
  } catch (e) { }
}

connectNative();
rebuildSets();
setTimeout(autoPull, 3000);
refreshLists();
setInterval(refreshLists, REFRESH_MS);

// Surveillances : rattrapage peu apres le demarrage, puis passage regulier.
loadWatches().then(() => setTimeout(() => checkAllWatches(false), 8000));
setInterval(() => checkAllWatches(false), WATCH_TICK);

loadFeeds().then(() => setTimeout(() => refreshFeeds(false), 20000));
setInterval(() => refreshFeeds(false), FEED_TICK);
