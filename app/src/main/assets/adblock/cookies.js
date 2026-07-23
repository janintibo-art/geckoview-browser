"use strict";

// ===========================================================================
//  cookies.js -- refus automatique du consentement.
//  Masquer un bandeau ne refuse rien : sans action, beaucoup de sites
//  considerent l'absence de reponse comme un accord, ou reaffichent le
//  bandeau a chaque visite. On appelle donc directement les API de refus
//  des plateformes de consentement, puis on retombe sur le clic du bouton.
// ===========================================================================

(function () {

  let cfg = { cookieReject: true, cookieClear: false };
  let done = false;
  let attempts = 0;

  // -------------------------------------------------------------------------
  //  1) API des plateformes de consentement (CMP)
  // -------------------------------------------------------------------------
  //  Execute dans le contexte de la page : les objets Didomi, OneTrust, etc.
  //  ne sont pas visibles depuis le script de contenu isole.
  function injectRejector() {
    const code = `(function(){
      "use strict";
      var tries = 0;
      function reject() {
        var did = false;
        try {
          // Didomi
          if (window.Didomi && Didomi.setUserDisagreeToAll) {
            Didomi.setUserDisagreeToAll(); did = true;
          }
          // OneTrust / CookiePro
          if (window.OneTrust && OneTrust.RejectAll) {
            OneTrust.RejectAll(); did = true;
          }
          if (window.Optanon && Optanon.RejectAll) {
            Optanon.RejectAll(); did = true;
          }
          // Cookiebot
          if (window.Cookiebot && Cookiebot.decline) {
            Cookiebot.decline(); did = true;
          }
          if (window.CookieConsent && CookieConsent.decline) {
            CookieConsent.decline(); did = true;
          }
          // Usercentrics
          if (window.UC_UI && UC_UI.denyAllConsents) {
            UC_UI.denyAllConsents(); did = true;
          }
          // Axeptio
          if (window.axeptioSDK && axeptioSDK.setConsents) {
            axeptioSDK.setConsents({}); did = true;
          }
          if (window._axcb) {
            window._axcb.push(function(sdk){
              try { sdk.setConsents({}); } catch(e){}
            });
          }
          // tarteaucitron
          if (window.tarteaucitron && tarteaucitron.userInterface &&
              tarteaucitron.userInterface.denyAll) {
            tarteaucitron.userInterface.denyAll(); did = true;
          }
          // Klaro
          if (window.klaro && klaro.getManager) {
            var m = klaro.getManager();
            if (m && m.declineAll) { m.declineAll(); m.saveAndApplyConsents(); did = true; }
          }
          // Osano
          if (window.Osano && Osano.cm && Osano.cm.denyAll) {
            Osano.cm.denyAll(); did = true;
          }
          // Complianz
          if (typeof window.cmplz_deny_all === "function") {
            window.cmplz_deny_all(); did = true;
          }
          // CookieYes
          if (window.cookieyes && cookieyes.reject) {
            cookieyes.reject(); did = true;
          }
          // Iubenda
          if (window._iub && _iub.cs && _iub.cs.api && _iub.cs.api.reject) {
            _iub.cs.api.reject(); did = true;
          }
          // Borlabs
          if (window.BorlabsCookie && BorlabsCookie.Consents &&
              BorlabsCookie.Consents.refuseAll) {
            BorlabsCookie.Consents.refuseAll(); did = true;
          }
          // Quantcast / TCF v2 generique
          if (typeof window.__tcfapi === "function") {
            try {
              window.__tcfapi("rejectAll", 2, function(){});
              did = true;
            } catch(e){}
          }
          // Google Funding Choices / IAB
          if (window.googlefc && googlefc.callbackQueue) {
            googlefc.callbackQueue.push({
              CONSENT_DATA_READY: function(){
                try { googlefc.showRevocationMessage && 0; } catch(e){}
              }
            });
          }
        } catch (e) {}
        return did;
      }

      function loop() {
        tries++;
        var ok = reject();
        if (!ok && tries < 20) setTimeout(loop, 350);
      }
      loop();
    })();`;

    try {
      const s = document.createElement("script");
      s.textContent = code;
      (document.head || document.documentElement).appendChild(s);
      s.remove();
    } catch (e) { }
  }

  // -------------------------------------------------------------------------
  //  2) Repli : clic sur le bouton de refus
  // -------------------------------------------------------------------------
  const REJECT_SELECTORS = [
    "#onetrust-reject-all-handler",
    ".ot-pc-refuse-all-handler",
    "#CybotCookiebotDialogBodyButtonDecline",
    "#CybotCookiebotDialogBodyLevelButtonLevelOptinDeclineAll",
    "button[id*='denyAll']",
    ".didomi-continue-without-agreeing",
    "#didomi-notice-disagree-button",
    ".qc-cmp2-summary-buttons > button[mode='secondary']",
    "button.sp_choice_type_REJECT_ALL",
    "button[title='REJECT ALL']",
    "#tarteaucitronAllDenied2",
    "#tarteaucitronAllDenied",
    ".cmplz-deny",
    ".cky-btn-reject",
    ".osano-cm-denyAll",
    "#uc-btn-deny-banner",
    ".uc-deny-button",
    "button[data-role='none'][data-testid='uc-deny-all-button']",
    ".fc-cta-do-not-consent",
    "#axeptio_btn_dismiss",
    ".iubenda-cs-reject-btn",
    "._brlbs-btn-cookie-preference",
    "[data-testid='reject-all']",
    "[aria-label*='Refuser' i]",
    "[aria-label*='Reject' i]"
  ];

  // Libelles frequents, francais et anglais.
  const REJECT_TEXTS = [
    "continuer sans accepter",
    "continuer sans accepter →",
    "tout refuser",
    "refuser tout",
    "refuser et fermer",
    "je refuse",
    "refuser",
    "uniquement les cookies essentiels",
    "cookies essentiels uniquement",
    "seulement les necessaires",
    "reject all",
    "decline all",
    "deny all",
    "necessary only",
    "only essential",
    "essential cookies only",
    "continue without accepting",
    "alle ablehnen",
    "rechazar todo",
    "rifiuta tutto"
  ];

  function norm(s) {
    return (s || "")
      .toLowerCase()
      .normalize("NFD")
      .replace(/[\u0300-\u036f]/g, "")
      .replace(/\s+/g, " ")
      .trim();
  }

  function clickable(el) {
    if (!el || !el.offsetParent) return false;
    const r = el.getBoundingClientRect();
    return r.width > 0 && r.height > 0;
  }

  function tryClick() {
    // a) selecteurs connus
    for (const sel of REJECT_SELECTORS) {
      let el;
      try { el = document.querySelector(sel); } catch (e) { continue; }
      if (clickable(el)) {
        el.click();
        return true;
      }
    }

    // b) recherche par libelle, limitee aux conteneurs de consentement
    const scopes = document.querySelectorAll(
      "[class*='cookie' i], [id*='cookie' i], [class*='consent' i], [id*='consent' i]," +
      "[class*='cmp' i], [id*='cmp' i], [class*='gdpr' i], [id*='gdpr' i]," +
      "[class*='privacy' i], dialog, [role='dialog']");

    for (const scope of scopes) {
      const buttons = scope.querySelectorAll("button, a[role='button'], input[type='button'], [role='button']");
      for (const b of buttons) {
        const t = norm(b.innerText || b.value || b.getAttribute("aria-label"));
        if (!t || t.length > 60) continue;
        if (REJECT_TEXTS.some(x => t === x || t.startsWith(x))) {
          if (clickable(b)) { b.click(); return true; }
        }
      }
    }
    return false;
  }

  // -------------------------------------------------------------------------
  //  3) Purge du stockage local des sites non autorises
  // -------------------------------------------------------------------------
  function clearFirstPartyStorage() {
    try {
      // Cookies de premiere partie accessibles en JavaScript
      const host = location.hostname;
      const parts = host.split(".");
      const domains = [host];
      for (let i = 1; i < parts.length - 1; i++) {
        domains.push("." + parts.slice(i).join("."));
      }
      document.cookie.split(";").forEach(c => {
        const name = c.split("=")[0].trim();
        if (!name) return;
        domains.forEach(d => {
          document.cookie = name + "=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/; domain=" + d;
        });
        document.cookie = name + "=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/";
      });
    } catch (e) { }
  }

  // -------------------------------------------------------------------------
  //  Boucle principale
  // -------------------------------------------------------------------------
  function attempt() {
    if (done || attempts > 24) return;
    attempts++;
    if (tryClick()) {
      done = true;
      try {
        browser.runtime.sendMessage({ type: "consentRejected", host: location.hostname });
      } catch (e) { }
      return;
    }
    setTimeout(attempt, 400);
  }

  async function init() {
    try {
      const s = await browser.storage.local.get("pageCfg");
      if (s && s.pageCfg) cfg = Object.assign(cfg, s.pageCfg);
    } catch (e) { }

    if (!cfg.cookieReject) return;

    injectRejector();

    if (document.readyState === "loading") {
      document.addEventListener("DOMContentLoaded", attempt, { once: true });
    } else {
      attempt();
    }
    // Certains bandeaux arrivent tardivement (chargement differe).
    setTimeout(() => { done = false; attempts = 0; attempt(); }, 2500);

    if (cfg.cookieClear) {
      window.addEventListener("pagehide", clearFirstPartyStorage);
    }
  }

  init();
})();
