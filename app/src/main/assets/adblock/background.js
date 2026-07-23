"use strict";

// ---------------------------------------------------------------------------
// Etat
// ---------------------------------------------------------------------------
let enabled = true;
let blockedCount = 0;
let nativePort = null;

const blockedDomains = new Set(SEED_DOMAINS.map(d => d.toLowerCase()));
const allowDomains = new Set(ALLOWLIST.map(d => d.toLowerCase()));

// Sources distantes au format "hosts" (mises a jour toutes les 24 h)
const REMOTE_LISTS = [
  "https://adaway.org/hosts.txt",
  "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=hosts&showintro=0&mimetype=plaintext"
];
const REFRESH_MS = 24 * 60 * 60 * 1000;
const MAX_ENTRIES = 300000;

// ---------------------------------------------------------------------------
// Utilitaires
// ---------------------------------------------------------------------------
function hostOf(url) {
  try {
    return new URL(url).hostname.toLowerCase();
  } catch (e) {
    return "";
  }
}

// Retourne le domaine enregistrable approximatif (suffisant pour le 1st-party)
function baseDomain(host) {
  const parts = host.split(".");
  if (parts.length <= 2) return host;
  const twoLevel = ["co.uk", "com.au", "co.jp", "com.br", "co.nz", "org.uk", "gov.uk", "ac.uk"];
  const last2 = parts.slice(-2).join(".");
  if (twoLevel.includes(last2) && parts.length >= 3) {
    return parts.slice(-3).join(".");
  }
  return last2;
}

function inSet(host, set) {
  if (!host) return false;
  if (set.has(host)) return true;
  let idx = host.indexOf(".");
  while (idx !== -1) {
    const parent = host.slice(idx + 1);
    if (set.has(parent)) return true;
    idx = host.indexOf(".", idx + 1);
  }
  return false;
}

function matchesPattern(url) {
  for (const re of URL_PATTERNS) {
    if (re.test(url)) return true;
  }
  return false;
}

// ---------------------------------------------------------------------------
// Pont natif vers l'application Android
// ---------------------------------------------------------------------------
function connectNative() {
  try {
    nativePort = browser.runtime.connectNative("browser");
    nativePort.onMessage.addListener(msg => {
      if (!msg) return;
      if (msg.type === "setEnabled") {
        enabled = !!msg.value;
        pushState();
      } else if (msg.type === "resetCount") {
        blockedCount = 0;
        pushState();
      } else if (msg.type === "getState") {
        pushState();
      }
    });
    nativePort.onDisconnect.addListener(() => {
      nativePort = null;
    });
    pushState();
  } catch (e) {
    nativePort = null;
  }
}

let pushTimer = null;
function pushState() {
  if (pushTimer) return;
  pushTimer = setTimeout(() => {
    pushTimer = null;
    if (!nativePort) return;
    try {
      nativePort.postMessage({
        type: "state",
        blocked: blockedCount,
        enabled: enabled,
        rules: blockedDomains.size
      });
    } catch (e) {
      nativePort = null;
    }
  }, 250);
}

// ---------------------------------------------------------------------------
// Blocage reseau
// ---------------------------------------------------------------------------
browser.webRequest.onBeforeRequest.addListener(
  details => {
    if (!enabled) return {};

    const url = details.url;
    if (url.startsWith("data:") || url.startsWith("blob:") ||
        url.startsWith("moz-extension:") || url.startsWith("about:")) {
      return {};
    }

    const host = hostOf(url);
    if (!host || inSet(host, allowDomains)) return {};

    // Ne jamais bloquer la navigation principale : l'utilisateur l'a demandee.
    if (details.type === "main_frame") return {};

    // Requete de premiere partie : on ne bloque que sur motif d'URL explicite.
    const originHost = details.documentUrl ? hostOf(details.documentUrl) : "";
    const firstParty = originHost && baseDomain(originHost) === baseDomain(host);

    let block = false;
    if (inSet(host, blockedDomains)) {
      block = true;
    } else if (!firstParty && matchesPattern(url)) {
      block = true;
    }

    if (!block) return {};

    blockedCount++;
    pushState();

    // Image/frame : reponse vide pour eviter les trous de mise en page.
    if (details.type === "image") {
      return {
        redirectUrl:
          "data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7"
      };
    }
    if (details.type === "sub_frame") {
      return { redirectUrl: "data:text/html,<html><body></body></html>" };
    }
    return { cancel: true };
  },
  { urls: ["<all_urls>"] },
  ["blocking"]
);

// Suppression des en-tetes de pistage sortants
browser.webRequest.onBeforeSendHeaders.addListener(
  details => {
    if (!enabled) return {};
    const headers = details.requestHeaders.filter(h => {
      const n = h.name.toLowerCase();
      return n !== "x-client-data" && n !== "dnt-hash";
    });
    headers.push({ name: "DNT", value: "1" });
    headers.push({ name: "Sec-GPC", value: "1" });
    return { requestHeaders: headers };
  },
  { urls: ["<all_urls>"] },
  ["blocking", "requestHeaders"]
);

// ---------------------------------------------------------------------------
// Listes distantes
// ---------------------------------------------------------------------------
function parseHostsFile(text) {
  const out = [];
  const lines = text.split("\n");
  for (let i = 0; i < lines.length; i++) {
    let line = lines[i];
    const hash = line.indexOf("#");
    if (hash !== -1) line = line.slice(0, hash);
    line = line.trim();
    if (!line) continue;
    const parts = line.split(/\s+/);
    let domain = parts.length >= 2 ? parts[1] : parts[0];
    if (!domain) continue;
    domain = domain.toLowerCase();
    if (domain === "localhost" || domain === "localhost.localdomain" ||
        domain === "broadcasthost" || domain === "0.0.0.0" || domain === "::1") {
      continue;
    }
    if (domain.indexOf(".") === -1) continue;
    out.push(domain);
  }
  return out;
}

function ingest(domains) {
  for (const d of domains) {
    if (blockedDomains.size >= MAX_ENTRIES) break;
    if (!allowDomains.has(d)) blockedDomains.add(d);
  }
}

async function refreshLists(force) {
  try {
    const store = await browser.storage.local.get(["ts", "domains"]);
    const fresh = store.ts && Date.now() - store.ts < REFRESH_MS;

    if (fresh && Array.isArray(store.domains) && !force) {
      ingest(store.domains);
      pushState();
      return;
    }

    const collected = [];
    for (const url of REMOTE_LISTS) {
      try {
        const resp = await fetch(url, { cache: "no-cache" });
        if (!resp.ok) continue;
        const text = await resp.text();
        const parsed = parseHostsFile(text);
        collected.push(...parsed);
      } catch (e) {
        // source indisponible : on continue avec les autres
      }
    }

    if (collected.length) {
      ingest(collected);
      await browser.storage.local.set({
        ts: Date.now(),
        domains: collected.slice(0, MAX_ENTRIES)
      });
    } else if (Array.isArray(store.domains)) {
      ingest(store.domains);
    }
    pushState();
  } catch (e) {
    pushState();
  }
}

// ---------------------------------------------------------------------------
// Messages des scripts de contenu
// ---------------------------------------------------------------------------
browser.runtime.onMessage.addListener(msg => {
  if (msg && msg.type === "cosmetic") {
    blockedCount += msg.count || 0;
    pushState();
  }
  if (msg && msg.type === "getConfig") {
    return Promise.resolve({
      enabled: enabled,
      selectors: COSMETIC_SELECTORS,
      overlays: OVERLAY_SELECTORS
    });
  }
});

// ---------------------------------------------------------------------------
// Demarrage
// ---------------------------------------------------------------------------
connectNative();
refreshLists(false);
setInterval(() => refreshLists(false), REFRESH_MS);
