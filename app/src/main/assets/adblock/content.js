"use strict";

(function () {
  let hidden = 0;
  let selectors = [];
  let overlays = [];
  let active = true;

  // -------------------------------------------------------------------------
  // Injection CSS immediate (avant le premier rendu, evite le clignotement)
  // -------------------------------------------------------------------------
  function injectCss(list) {
    if (!list.length) return;
    const css = list.join(",\n") +
      " { display: none !important; visibility: hidden !important;" +
      " height: 0 !important; min-height: 0 !important; max-height: 0 !important;" +
      " opacity: 0 !important; pointer-events: none !important; }";
    const style = document.createElement("style");
    style.setAttribute("data-geckoblock", "1");
    style.textContent = css;
    (document.head || document.documentElement).appendChild(style);
  }

  // -------------------------------------------------------------------------
  // Nettoyage des overlays et du blocage de defilement
  // -------------------------------------------------------------------------
  function cleanOverlays() {
    if (!active || !overlays.length) return;
    let removed = 0;
    for (const sel of overlays) {
      let nodes;
      try {
        nodes = document.querySelectorAll(sel);
      } catch (e) {
        continue;
      }
      for (const node of nodes) {
        node.remove();
        removed++;
      }
    }
    // Certains murs bloquent le defilement : on le retablit.
    if (removed) {
      for (const el of [document.documentElement, document.body]) {
        if (!el) continue;
        el.style.setProperty("overflow", "auto", "important");
        el.style.setProperty("position", "static", "important");
      }
      hidden += removed;
    }
  }

  // -------------------------------------------------------------------------
  // Elements plein ecran suspects (bandeaux fixes couvrant la page)
  // -------------------------------------------------------------------------
  function killStickyBanners() {
    if (!active) return;
    const vh = window.innerHeight;
    const nodes = document.querySelectorAll("div,section,aside");
    let n = 0;
    for (const el of nodes) {
      if (n > 40) break; // garde-fou de performance
      const cs = window.getComputedStyle(el);
      if (cs.position !== "fixed" && cs.position !== "sticky") continue;
      const r = el.getBoundingClientRect();
      if (r.height < 40 || r.height > vh * 0.45) continue;
      const txt = (el.id + " " + el.className).toLowerCase();
      if (/ad|sponsor|promo|banner|newsletter|subscribe/.test(txt)) {
        el.style.setProperty("display", "none", "important");
        n++;
      }
    }
    hidden += n;
  }

  // -------------------------------------------------------------------------
  // Neutralisation des detecteurs de bloqueur
  // -------------------------------------------------------------------------
  function defuseDetectors() {
    try {
      Object.defineProperty(window, "canRunAds", { value: true, writable: false });
      Object.defineProperty(window, "adsbygoogle", {
        value: { loaded: true, push: function () {} },
        writable: false
      });
    } catch (e) {
      // deja defini par la page
    }
  }

  // -------------------------------------------------------------------------
  // Observation des ajouts dynamiques
  // -------------------------------------------------------------------------
  function observe() {
    let pending = false;
    const obs = new MutationObserver(() => {
      if (pending) return;
      pending = true;
      setTimeout(() => {
        pending = false;
        cleanOverlays();
        killStickyBanners();
      }, 400);
    });
    obs.observe(document.documentElement, { childList: true, subtree: true });
  }

  // -------------------------------------------------------------------------
  // Compteur remonte au script d'arriere-plan
  // -------------------------------------------------------------------------
  function report() {
    if (!hidden) return;
    try {
      browser.runtime.sendMessage({ type: "cosmetic", count: hidden });
    } catch (e) {
      // canal indisponible
    }
    hidden = 0;
  }

  // -------------------------------------------------------------------------
  // Initialisation
  // -------------------------------------------------------------------------
  browser.runtime.sendMessage({ type: "getConfig" }).then(cfg => {
    if (!cfg) return;
    active = cfg.enabled !== false;
    selectors = cfg.selectors || [];
    overlays = cfg.overlays || [];
    if (!active) return;

    injectCss(selectors);
    defuseDetectors();

    const start = () => {
      cleanOverlays();
      killStickyBanners();
      observe();
      setTimeout(report, 2000);
      setInterval(report, 5000);
    };

    if (document.readyState === "loading") {
      document.addEventListener("DOMContentLoaded", start, { once: true });
    } else {
      start();
    }
  }).catch(() => {});
})();
