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
let identity = "auto";       // auto | desktop | mobile
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
const MAX_ENTRIES = 300000;

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
      ["pageExtra", "pageAllow", "identity", "cookieCfg"]);
    userExtra = (s && s.pageExtra) || [];
    userAllow = (s && s.pageAllow) || [];
    identity = (s && s.identity) || "auto";
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
      changes.identity || changes.cookieCfg) {
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
    if (!e) return;
    e.ms = Date.now() - e.start;
    e.error = d.error || "erreur";
    if (/BLOCKED|ABORTED/i.test(e.error)) e.blocked = true;
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
