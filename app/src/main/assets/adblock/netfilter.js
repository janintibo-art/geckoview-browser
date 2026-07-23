"use strict";

// ===========================================================================
//  netfilter.js -- portage du userscript NetFilter Web en script de contenu.
//  Les fonctions qui gagnaient a passer au niveau reseau (blocage de site,
//  identite du navigateur) sont desormais traitees dans background.js.
// ===========================================================================

(function () {

  let cfg = {
    hideSearch: true,
    hideAll: false,
    cookies: true,
    clickbait: true,
    cleanurls: true,
    cookieReject: true,
    cookieClear: false
  };
  let blockedSet = new Set();
  let ready = false;

  // -------------------------------------------------------------------------
  //  Selecteurs
  // -------------------------------------------------------------------------
  const ENGINE_SELECTORS = {
    "google.":          "div.g, div.MjjYud, div.tF2Cxc, div[data-hveid]",
    "bing.":            "li.b_algo, li.b_ans",
    "duckduckgo.":      "article[data-testid='result'], li[data-layout], .result",
    "qwant.":           "[data-testid='webResult'], .result, article",
    "ecosia.":          "[data-test-id='mainline-result-web'], div.result, article.result",
    "yahoo.":           "div.algo, li.dd",
    "startpage.":       ".w-gl__result, .result",
    "search.brave.":    ".snippet, [data-type='web']",
    "mojeek.":          "ul.results-standard li, .results li",
    "lite.duckduckgo.": "tr"
  };

  const COOKIE_SELECTORS = [
    "#onetrust-consent-sdk", "#onetrust-banner-sdk",
    "#CybotCookiebotDialog", "#CybotCookiebotDialogBodyUnderlay",
    "#didomi-host", ".qc-cmp2-container", "#qc-cmp2-container",
    "#axeptio_overlay", "#tarteaucitronRoot", "#tarteaucitronAlertBig",
    "div[id^='sp_message_container']", "#usercentrics-root", "#uc-banner-modal",
    "#truste-consent-track", ".truste_overlay",
    "#cmplz-cookiebanner-container", ".cmplz-cookiebanner",
    ".cky-consent-container", ".cky-overlay", ".osano-cm-window",
    "#cookie-banner", "#cookie-consent", "#cookie-notice", "#cookieConsent",
    ".cookie-banner", ".cookie-consent", ".cookie-notice", ".cookie-law-info-bar",
    "#gdpr-cookie-message", ".gdpr-cookie-notice"
  ];

  const CLICKBAIT_SELECTORS = [
    "[id^='taboola']", ".taboola", ".trc_related_container", ".trc_rbox_container",
    "div[data-placement*='taboola' i]",
    "#taboola-below-article-thumbnails", "#taboola-right-rail-thumbnails",
    ".OUTBRAIN", ".ob-widget", "[id^='outbrain_widget']", "div[data-ob-template]",
    ".ob-smartfeed-wrapper", ".revcontent-inner", "[id^='rc-widget']"
  ];

  const TRACK_PARAMS = new Set([
    "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content", "utm_id",
    "fbclid", "gclid", "gclsrc", "dclid", "msclkid", "mc_eid", "mc_cid", "igshid",
    "twclid", "yclid", "_openstat", "vero_id", "wickedid", "oly_enc_id", "oly_anon_id",
    "ref_src", "ref_url", "spm", "cmpid", "icid"
  ]);

  // -------------------------------------------------------------------------
  //  Utilitaires
  // -------------------------------------------------------------------------
  function hostOf(href) {
    try { return new URL(href, location.href).hostname.replace(/^www\./, ""); }
    catch (e) { return ""; }
  }

  function matches(host) {
    if (!host || !blockedSet.size) return false;
    host = host.toLowerCase().replace(/^www\./, "");
    if (blockedSet.has(host)) return true;
    let i = host.indexOf(".");
    while (i !== -1) {
      if (blockedSet.has(host.slice(i + 1))) return true;
      i = host.indexOf(".", i + 1);
    }
    return false;
  }

  function hide(el) {
    if (!el || el.dataset.nfwHidden) return;
    el.dataset.nfwHidden = "1";
    el.style.setProperty("display", "none", "important");
  }

  function engineSelector() {
    const h = location.hostname.toLowerCase();
    for (const k in ENGINE_SELECTORS) if (h.indexOf(k) !== -1) return ENGINE_SELECTORS[k];
    return null;
  }

  function climb(el, levels) {
    let cur = el;
    for (let i = 0; i < levels && cur && cur.parentElement; i++) cur = cur.parentElement;
    return cur;
  }

  // -------------------------------------------------------------------------
  //  Masquage dans les resultats de recherche
  // -------------------------------------------------------------------------
  function runHiding() {
    const sel = engineSelector();
    const onEngine = !!sel;
    if (!onEngine && !cfg.hideAll) return;
    if (onEngine && !cfg.hideSearch) return;

    let count = 0;
    document.querySelectorAll("a[href]").forEach(a => {
      const host = hostOf(a.href);
      if (!host || !matches(host)) return;
      let container = sel ? a.closest(sel) : null;
      if (!container && sel) container = climb(a, 4);
      if (!container && !onEngine && cfg.hideAll) container = a;
      if (container) { hide(container); count++; }
    });
    return count;
  }

  function hideCookieBanners() {
    COOKIE_SELECTORS.forEach(sel => {
      document.querySelectorAll(sel).forEach(hide);
    });
    document.documentElement.style.setProperty("overflow", "auto", "important");
    if (document.body) document.body.style.setProperty("overflow", "auto", "important");
  }

  function hideClickbait() {
    CLICKBAIT_SELECTORS.forEach(sel => {
      document.querySelectorAll(sel).forEach(hide);
    });
  }

  // -------------------------------------------------------------------------
  //  Nettoyage d'URL
  // -------------------------------------------------------------------------
  function cleanOne(raw) {
    try {
      const url = new URL(raw, location.href);
      let changed = false;
      Array.from(url.searchParams.keys()).forEach(p => {
        if (TRACK_PARAMS.has(p.toLowerCase())) { url.searchParams.delete(p); changed = true; }
      });
      return changed ? url.href : null;
    } catch (e) { return null; }
  }

  function unwrap(raw) {
    try {
      const url = new URL(raw, location.href);
      if (/(^|\.)google\./.test(url.hostname) && url.pathname === "/url") {
        const t = url.searchParams.get("q") || url.searchParams.get("url");
        if (t && /^https?:/i.test(t)) return t;
      }
      if (/duckduckgo\./.test(url.hostname) && url.pathname.startsWith("/l/")) {
        const t = url.searchParams.get("uddg");
        if (t) return decodeURIComponent(t);
      }
      return null;
    } catch (e) { return null; }
  }

  function cleanLinks() {
    document.querySelectorAll("a[href]").forEach(a => {
      const u = unwrap(a.href);
      if (u) a.href = u;
      const c = cleanOne(a.href);
      if (c && c !== a.href) a.href = c;
    });
  }

  function cleanCurrentUrl() {
    const c = cleanOne(location.href);
    if (c && c !== location.href) {
      try { history.replaceState(history.state, "", c); } catch (e) { }
    }
  }

  // -------------------------------------------------------------------------
  //  Mode lecture
  // -------------------------------------------------------------------------
  function readingMode() {
    const pool = document.querySelectorAll(
      "article, main, [role='main'], .article, .post, .entry-content, #content, .content");
    const candidates = pool.length ? pool : document.querySelectorAll("div, section");
    let best = null, bestScore = 0;
    candidates.forEach(el => {
      const score = (el.innerText || "").length + el.querySelectorAll("p").length * 200;
      if (score > bestScore) { bestScore = score; best = el; }
    });
    if (!best) { alert("Contenu principal introuvable sur cette page."); return; }
    const html = best.innerHTML;
    const title = document.title || "";
    document.documentElement.innerHTML = "<head><meta charset='utf-8'></head><body></body>";
    document.title = title;
    const style = document.createElement("style");
    style.textContent =
      "body{max-width:720px;margin:24px auto;padding:0 20px;" +
      "font:18px/1.7 Georgia,'Times New Roman',serif;color:#222;background:#fafafa}" +
      "img{max-width:100%;height:auto}a{color:#1a6}h1{font-size:26px;line-height:1.3}";
    document.head.appendChild(style);
    const h1 = document.createElement("h1");
    h1.textContent = title;
    document.body.appendChild(h1);
    const box = document.createElement("div");
    box.innerHTML = html;
    document.body.appendChild(box);
  }

  // -------------------------------------------------------------------------
  //  Traitement de page
  // -------------------------------------------------------------------------
  function processPage() {
    if (!ready) return;
    try { runHiding(); } catch (e) { }
    try { if (cfg.cookies) hideCookieBanners(); } catch (e) { }
    try { if (cfg.clickbait) hideClickbait(); } catch (e) { }
    try { if (cfg.cleanurls) { cleanLinks(); cleanCurrentUrl(); } } catch (e) { }
  }

  let scheduled = false;
  function schedule() {
    if (scheduled) return;
    scheduled = true;
    setTimeout(() => { scheduled = false; processPage(); }, 250);
  }

  function unhideAll() {
    document.querySelectorAll("[data-nfw-hidden]").forEach(el => {
      el.style.removeProperty("display");
      el.removeAttribute("data-nfw-hidden");
    });
  }

  // -------------------------------------------------------------------------
  //  Panneau flottant deplacable
  // -------------------------------------------------------------------------
  function style(el, o) { for (const k in o) el.style[k] = o[k]; }

  function checkRow(label, key, onChange) {
    const row = document.createElement("label");
    style(row, {
      display: "flex", alignItems: "center", gap: "9px", padding: "7px 2px",
      fontSize: "13px", color: "#e8eaee", cursor: "pointer"
    });
    const cb = document.createElement("input");
    cb.type = "checkbox";
    cb.checked = !!cfg[key];
    cb.addEventListener("change", () => onChange(cb.checked));
    const span = document.createElement("span");
    span.textContent = label;
    row.appendChild(cb);
    row.appendChild(span);
    return row;
  }

  function actionBtn(label, fn) {
    const b = document.createElement("button");
    b.textContent = label;
    style(b, {
      display: "block", width: "100%", margin: "5px 0", padding: "9px",
      border: "1px solid #2b303a", borderRadius: "8px", background: "#14161a",
      color: "#e8eaee", fontSize: "13px", cursor: "pointer"
    });
    b.addEventListener("click", fn);
    return b;
  }

  function buildPanel() {
    if (window.top !== window.self) return;  // pas dans les cadres
    if (document.getElementById("nfw-fab")) return;

    const fab = document.createElement("div");
    fab.id = "nfw-fab";
    fab.textContent = "\u26E8";
    style(fab, {
      position: "fixed", right: "12px", bottom: "84px", zIndex: "2147483646",
      width: "34px", height: "34px", lineHeight: "34px", textAlign: "center",
      borderRadius: "17px", background: "rgba(20,22,26,.55)", color: "#6fae5f",
      fontSize: "17px", cursor: "pointer", userSelect: "none",
      backdropFilter: "blur(3px)", transition: "opacity .2s", opacity: ".55"
    });
    fab.addEventListener("mouseenter", () => fab.style.opacity = "1");

    const panel = document.createElement("div");
    style(panel, {
      position: "fixed", right: "12px", bottom: "126px", zIndex: "2147483647",
      width: "232px", maxHeight: "62vh", overflowY: "auto", display: "none",
      background: "#1c1f26", border: "1px solid #2b303a", borderRadius: "12px",
      padding: "12px 14px", boxShadow: "0 8px 28px rgba(0,0,0,.5)",
      font: "13px -apple-system,Roboto,sans-serif"
    });

    const title = document.createElement("div");
    title.textContent = "Filtres de page";
    style(title, { fontWeight: "700", color: "#e8eaee", marginBottom: "6px", fontSize: "13px" });
    panel.appendChild(title);

    const save = async () => {
      try { await browser.storage.local.set({ pageCfg: cfg }); } catch (e) { }
      unhideAll();
      processPage();
    };

    panel.appendChild(checkRow("Masquer dans les recherches", "hideSearch",
      v => { cfg.hideSearch = v; save(); }));
    panel.appendChild(checkRow("Masquer les liens partout", "hideAll",
      v => { cfg.hideAll = v; save(); }));
    panel.appendChild(checkRow("Bannieres cookies", "cookies",
      v => { cfg.cookies = v; save(); }));
    panel.appendChild(checkRow("Blocs de clickbait", "clickbait",
      v => { cfg.clickbait = v; save(); }));
    panel.appendChild(checkRow("Nettoyer les URL", "cleanurls",
      v => { cfg.cleanurls = v; save(); }));
    panel.appendChild(checkRow("Refuser le consentement", "cookieReject",
      v => { cfg.cookieReject = v; save(); }));

    const sep = document.createElement("div");
    style(sep, { height: "1px", background: "#2b303a", margin: "9px 0" });
    panel.appendChild(sep);

    panel.appendChild(actionBtn("\uD83D\uDCD6  Mode lecture", () => {
      panel.style.display = "none";
      try { readingMode(); } catch (e) { }
    }));

    panel.appendChild(actionBtn("\uD83D\uDEAB  Masquer ce site", async () => {
      const host = location.hostname.replace(/^www\./, "");
      try {
        const s = await browser.storage.local.get("pageExtra");
        const list = (s && s.pageExtra) || [];
        if (!list.includes(host)) list.push(host);
        await browser.storage.local.set({ pageExtra: list });
        blockedSet.add(host);
        alert(host + " ajoute a vos domaines masques.");
      } catch (e) { }
    }));

    panel.appendChild(actionBtn("\u2699  Categories et listes", () => {
      location.href = browser.runtime.getURL("search.html") + "#filtres";
    }));

    const note = document.createElement("div");
    note.textContent = "Le blocage complet des sites se regle dans les categories.";
    style(note, { fontSize: "11px", color: "#99a0ad", marginTop: "9px", lineHeight: "1.45" });
    panel.appendChild(note);

    // Deplacement du bouton
    let dragging = false, moved = false, sx = 0, sy = 0;
    fab.addEventListener("touchstart", e => {
      dragging = true; moved = false;
      sx = e.touches[0].clientX; sy = e.touches[0].clientY;
    }, { passive: true });

    fab.addEventListener("touchmove", e => {
      if (!dragging) return;
      const t = e.touches[0];
      if (Math.abs(t.clientX - sx) > 5 || Math.abs(t.clientY - sy) > 5) moved = true;
      style(fab, {
        left: (t.clientX - 17) + "px", top: (t.clientY - 17) + "px",
        right: "auto", bottom: "auto"
      });
    }, { passive: true });

    fab.addEventListener("touchend", () => {
      dragging = false;
      if (moved) {
        try {
          browser.storage.local.set({
            fabPos: { left: fab.style.left, top: fab.style.top }
          });
        } catch (e) { }
      }
    });

    fab.addEventListener("click", () => {
      if (moved) { moved = false; return; }
      const open = panel.style.display === "block";
      panel.style.display = open ? "none" : "block";
      if (!open) {
        const r = fab.getBoundingClientRect();
        style(panel, {
          left: "auto", right: "12px",
          top: r.top > window.innerHeight / 2 ? "auto" : (r.bottom + 8) + "px",
          bottom: r.top > window.innerHeight / 2 ? (window.innerHeight - r.top + 8) + "px" : "auto"
        });
      }
    });

    document.addEventListener("click", e => {
      if (!panel.contains(e.target) && e.target !== fab) panel.style.display = "none";
    });

    document.body.appendChild(fab);
    document.body.appendChild(panel);

    try {
      browser.storage.local.get("fabPos").then(s => {
        if (s && s.fabPos && s.fabPos.left) {
          style(fab, { left: s.fabPos.left, top: s.fabPos.top, right: "auto", bottom: "auto" });
        }
      });
    } catch (e) { }
  }

  // -------------------------------------------------------------------------
  //  Demarrage
  // -------------------------------------------------------------------------
  async function init() {
    try {
      const s = await browser.storage.local.get(["pageCfg", "hideList"]);
      if (s && s.pageCfg) cfg = Object.assign(cfg, s.pageCfg);
      if (s && s.hideList) blockedSet = new Set(s.hideList);
    } catch (e) { }

    ready = true;
    processPage();
    new MutationObserver(schedule).observe(document.documentElement,
      { childList: true, subtree: true });

    if (document.body) buildPanel();
    else document.addEventListener("DOMContentLoaded", buildPanel, { once: true });
  }

  browser.storage.onChanged.addListener(changes => {
    if (changes.hideList) {
      blockedSet = new Set(changes.hideList.newValue || []);
      unhideAll();
      processPage();
    }
    if (changes.pageCfg) {
      cfg = Object.assign(cfg, changes.pageCfg.newValue || {});
      unhideAll();
      processPage();
    }
  });

  init();
})();
