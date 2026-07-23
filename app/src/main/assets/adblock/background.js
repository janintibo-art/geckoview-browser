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

const bypass = new Set();    // sites debloques jusqu'au redemarrage

const REMOTE_LISTS = [
  "https://adaway.org/hosts.txt",
  "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=hosts&showintro=0&mimetype=plaintext"
];
const REFRESH_MS = 24 * 60 * 60 * 1000;
const MAX_ENTRIES = 300000;

const UA_DESKTOP = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0";
const UA_MOBILE  = "Mozilla/5.0 (Android 14; Mobile; rv:128.0) Gecko/20100101 Firefox/128.0";

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
  catState = await CAT_API.getCatState();
  try {
    const s = await browser.storage.local.get(["pageExtra", "pageAllow", "identity"]);
    userExtra = (s && s.pageExtra) || [];
    userAllow = (s && s.pageAllow) || [];
    identity = (s && s.identity) || "auto";
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
  if (changes.catState || changes.pageExtra || changes.pageAllow || changes.identity) {
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

    if (identity === "desktop" || identity === "mobile") {
      const ua = identity === "desktop" ? UA_DESKTOP : UA_MOBILE;
      let found = false;
      for (const h of headers) {
        if (h.name.toLowerCase() === "user-agent") { h.value = ua; found = true; }
      }
      if (!found) headers.push({ name: "User-Agent", value: ua });
    }

    return { requestHeaders: headers };
  },
  { urls: ["<all_urls>"] },
  ["blocking", "requestHeaders"]
);

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
  if (msg.type === "stats") {
    return Promise.resolve({
      blocked: blockedCount,
      adRules: adDomains.size,
      navRules: navSet.size,
      categories: catState
    });
  }
});

// ---------------------------------------------------------------------------
//  Demarrage
// ---------------------------------------------------------------------------
connectNative();
rebuildSets();
refreshLists();
setInterval(refreshLists, REFRESH_MS);
