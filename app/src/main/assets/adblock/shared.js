"use strict";

// ===========================================================================
//  shared.js -- code commun aux scripts de contenu.
//
//  Les scripts de contenu d'une meme extension partagent un espace de noms
//  par page. On y depose un objet unique plutot que de dupliquer la meme
//  logique dans plusieurs fichiers, ou elle finissait par diverger.
//
//  Doit rester le premier script declare dans le manifeste.
// ===========================================================================

var GB = (function () {

  // -------------------------------------------------------------------------
  //  Utilitaires
  // -------------------------------------------------------------------------
  function abs(u, base) {
    try { return new URL(u, base || location.href).href; }
    catch (e) { return null; }
  }

  function hostOf(u) {
    try { return new URL(u, location.href).hostname.replace(/^www\./, ""); }
    catch (e) { return ""; }
  }

  function norm(s) {
    return (s || "")
      .toLowerCase()
      .normalize("NFD")
      .replace(/[\u0300-\u036f]/g, "")
      .replace(/\s+/g, " ")
      .trim();
  }

  // -------------------------------------------------------------------------
  //  Detection de la page suivante
  //  Utilisee par le defilement infini et par l'extracteur structure.
  // -------------------------------------------------------------------------
  const NEXT_TEXT = /^(suivant|suivante|page suivante|next|next page|older|plus ancien|plus anciens|charger plus|voir plus|load more|more|»|›|→|>>|>)$/i;

  /**
   * @param doc      document a examiner
   * @param base     adresse de ce document
   * @param stepHint pas d'increment pour les parametres de decalage
   */
  function findNext(doc, base, stepHint) {
    base = base || location.href;
    const resolve = u => abs(u, base);
    const sameOrigin = u => {
      try { return new URL(u, base).origin === new URL(base).origin; }
      catch (e) { return false; }
    };

    // 1. Declaration explicite : la plus fiable
    let el = doc.querySelector("link[rel~='next'][href], a[rel~='next'][href]");
    if (el) {
      const u = resolve(el.getAttribute("href"));
      if (u && sameOrigin(u) && u !== base) return u;
    }

    // 2. Element suivant la page courante dans la pagination
    const current = doc.querySelector(
      ".pagination .active, .pagination .current, .pager .current, " +
      "[aria-current='page'], .page-numbers.current");
    if (current) {
      let sib = current.nextElementSibling;
      while (sib) {
        const a = sib.matches("a[href]") ? sib : sib.querySelector("a[href]");
        if (a) {
          const u = resolve(a.getAttribute("href"));
          if (u && sameOrigin(u) && u !== base) return u;
        }
        sib = sib.nextElementSibling;
      }
    }

    // 3. Libelle du lien
    const scopes = doc.querySelectorAll(
      ".pagination a[href], .pager a[href], nav a[href], " +
      "[class*='pagination'] a[href], [class*='paging'] a[href], " +
      "[id*='pagination'] a[href], a[href]");
    for (const a of scopes) {
      const t = (a.textContent || a.getAttribute("aria-label") || a.title || "")
        .replace(/\s+/g, " ").trim();
      if (!t || t.length > 24) continue;
      if (NEXT_TEXT.test(t)) {
        const u = resolve(a.getAttribute("href"));
        if (u && sameOrigin(u) && u !== base) return u;
      }
    }

    // 4. Increment du parametre de page dans l'adresse
    try {
      const u = new URL(base);
      const offsetKeys = ["start", "offset", "from", "skip"];
      for (const k of ["page", "p", "pg"].concat(offsetKeys)) {
        const v = u.searchParams.get(k);
        if (v && /^\d+$/.test(v)) {
          const step = offsetKeys.includes(k) ? Math.max(1, stepHint || 20) : 1;
          u.searchParams.set(k, String(parseInt(v, 10) + step));
          return u.href;
        }
      }
      const m = u.pathname.match(/\/page\/(\d+)\/?$/i);
      if (m) {
        u.pathname = u.pathname.replace(/\/page\/\d+\/?$/i,
          "/page/" + (parseInt(m[1], 10) + 1) + "/");
        return u.href;
      }
    } catch (e) { }

    return null;
  }

  // -------------------------------------------------------------------------
  //  Bandeaux de consentement
  //  Conteneurs a masquer, boutons de refus, libelles de refus.
  //  Une seule liste, utilisee par le refus automatique et par le masquage.
  // -------------------------------------------------------------------------
  const CMP_CONTAINERS = [
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
    "#gdpr-cookie-message", ".gdpr-cookie-notice", "#cc-main", ".fc-consent-root"
  ];

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

  const REJECT_TEXTS = [
    "continuer sans accepter", "continuer sans accepter →",
    "tout refuser", "refuser tout", "refuser et fermer", "je refuse", "refuser",
    "uniquement les cookies essentiels", "cookies essentiels uniquement",
    "seulement les necessaires", "poursuivre sans accepter",
    "reject all", "decline all", "deny all", "necessary only",
    "only essential", "essential cookies only", "continue without accepting",
    "alle ablehnen", "rechazar todo", "rifiuta tutto"
  ];

  // Conteneurs ou chercher un bouton de refus par son libelle
  const CMP_SCOPES =
    "[class*='cookie' i], [id*='cookie' i], [class*='consent' i], [id*='consent' i]," +
    "[class*='cmp' i], [id*='cmp' i], [class*='gdpr' i], [id*='gdpr' i]," +
    "[class*='privacy' i], dialog, [role='dialog']";

  // -------------------------------------------------------------------------
  //  Extraction du texte principal d'un document
  //  Partagee par l'archivage et la comparaison de versions, qui doivent
  //  imperativement lire une page de la meme facon pour etre comparables.
  // -------------------------------------------------------------------------
  function mainContainer(doc) {
    const pool = doc.querySelectorAll(
      "article, main, [role='main'], .article, .post, .entry-content, " +
      "#content, .content, .story, .post-content");
    const cands = pool.length ? pool : doc.querySelectorAll("div, section");

    let best = null, bestScore = 0;
    for (const el of cands) {
      const paras = el.querySelectorAll("p").length;
      const len = (el.textContent || "").length;
      if (len < 200) continue;
      const score = len + paras * 250;
      if (score > bestScore) { bestScore = score; best = el; }
    }
    return best || doc.body;
  }

  /** Blocs de texte significatifs, dans l'ordre, prets a etre compares. */
  function mainBlocks(doc) {
    const root = mainContainer(doc);
    if (!root) return [];

    const out = [];
    const nodes = root.querySelectorAll(
      "p, h1, h2, h3, h4, li, blockquote, figcaption, td");

    const source = nodes.length ? nodes : [root];
    for (const el of source) {
      // Un bloc contenant lui-meme des blocs serait compte deux fois
      if (el.querySelector && el.querySelector("p, li")) continue;
      const t = (el.textContent || "").replace(/\s+/g, " ").trim();
      if (t.length < 3) continue;
      out.push(t);
      if (out.length >= 600) break;
    }
    return out;
  }

  function mainText(doc) {
    return mainBlocks(doc).join("\n");
  }

  // -------------------------------------------------------------------------
  //  Pointeur d'element
  //  Trois variantes de ce code coexistaient : styles, extracteur et
  //  surveillance. Celle-ci est la version commune, utilisee par les
  //  nouveaux outils ; les trois autres suivront.
  // -------------------------------------------------------------------------
  function selectorOf(el, opts) {
    if (!el || el.nodeType !== 1) return "";
    const wantMany = opts && opts.repeated;

    if (!wantMany && el.id && /^[A-Za-z][\w-]*$/.test(el.id)) {
      return "#" + CSS.escape(el.id);
    }

    const parts = [];
    let cur = el;
    let depth = 0;

    while (cur && cur.nodeType === 1 && cur !== document.documentElement && depth < 6) {
      if (!wantMany && cur.id && /^[A-Za-z][\w-]*$/.test(cur.id)) {
        parts.unshift("#" + CSS.escape(cur.id));
        break;
      }

      let sel = cur.tagName.toLowerCase();
      const raw = (cur.className && cur.className.toString
                   ? cur.className.toString() : "").trim();
      const good = raw.split(/\s+/).filter(c =>
        c && c.length < 26 && !/^\d/.test(c) && !/^(css|sc|emotion)-/i.test(c));

      if (good.length) {
        sel += "." + good.slice(0, 2).map(c => CSS.escape(c)).join(".");
      } else if (cur.parentElement && !wantMany) {
        const same = Array.from(cur.parentElement.children)
          .filter(x => x.tagName === cur.tagName);
        if (same.length > 1) sel += ":nth-of-type(" + (same.indexOf(cur) + 1) + ")";
      }

      parts.unshift(sel);

      const full = parts.join(" > ");
      let n = 0;
      try { n = document.querySelectorAll(full).length; } catch (e) { }
      // Un element unique suffit, sauf si l'on cherche une serie repetee
      if (n === 1 && !wantMany) break;
      if (wantMany && n > 1 && depth > 0) break;

      cur = cur.parentElement;
      depth++;
    }
    return parts.join(" > ");
  }

  /**
   * Ouvre le pointeur et resout avec { element, selector, count }, ou null.
   * opts : { hint, color, repeated }
   */
  function pick(opts) {
    opts = opts || {};
    const color = opts.color || "#6fae5f";

    return new Promise(resolve => {
      let current = null;

      const ov = document.createElement("div");
      ov.style.cssText = "position:fixed;z-index:2147483646;pointer-events:none;" +
        "display:none;border:2px solid " + color + ";border-radius:3px;" +
        "background:" + color + "22";
      document.documentElement.appendChild(ov);

      const bar = document.createElement("div");
      bar.style.cssText = "position:fixed;left:0;right:0;bottom:0;z-index:2147483647;" +
        "background:#14161a;border-top:1px solid #2b303a;padding:11px 12px 15px;" +
        "color:#e8eaee;font:12px/1.5 -apple-system,Roboto,sans-serif";
      bar.innerHTML =
        '<div style="font-weight:600;margin-bottom:5px">' +
        (opts.hint || "Touchez un element") + "</div>" +
        '<div id="gbp-sel" style="font-family:monospace;font-size:11px;color:#8ab4f8;' +
        'word-break:break-all;min-height:16px"></div>' +
        '<div id="gbp-n" style="color:#99a0ad;font-size:11px;margin:2px 0 9px"></div>' +
        '<div style="display:flex;gap:6px">' +
        '<button data-a="up" style="flex:1;padding:9px;border:1px solid #2b303a;' +
        'border-radius:7px;background:#1c1f26;color:#e8eaee">Parent</button>' +
        '<button data-a="ok" style="flex:1;padding:9px;border:1px solid #3d5c34;' +
        'border-radius:7px;background:#1c1f26;color:#8fce7c">Valider</button>' +
        '<button data-a="no" style="flex:1;padding:9px;border:1px solid #2b303a;' +
        'border-radius:7px;background:transparent;color:#99a0ad">Annuler</button></div>';
      document.documentElement.appendChild(bar);

      function show() {
        if (!current) return;
        const sel = selectorOf(current, opts);
        let n = 0;
        try { n = sel ? document.querySelectorAll(sel).length : 0; } catch (e) { }
        bar.querySelector("#gbp-sel").textContent = sel || "—";
        bar.querySelector("#gbp-n").textContent =
          n + " element" + (n > 1 ? "s vises" : " vise");
        const r = current.getBoundingClientRect();
        Object.assign(ov.style, {
          display: "block", left: r.left + "px", top: r.top + "px",
          width: r.width + "px", height: r.height + "px"
        });
      }

      function onTap(e) {
        if (bar.contains(e.target)) return;
        e.preventDefault();
        e.stopPropagation();
        current = e.target;
        show();
      }

      function finish(value) {
        document.removeEventListener("click", onTap, true);
        ov.remove();
        bar.remove();
        resolve(value);
      }

      bar.addEventListener("click", e => {
        const a = e.target.getAttribute && e.target.getAttribute("data-a");
        if (!a) return;
        e.preventDefault();
        e.stopPropagation();
        if (a === "no") return finish(null);
        if (a === "up") {
          if (current && current.parentElement &&
              current.parentElement !== document.documentElement) {
            current = current.parentElement;
            show();
          }
          return;
        }
        if (!current) return;
        const sel = selectorOf(current, opts);
        let n = 0;
        try { n = document.querySelectorAll(sel).length; } catch (e2) { }
        finish({ element: current, selector: sel, count: n });
      }, true);

      document.addEventListener("click", onTap, true);
    });
  }

  return {
    abs, hostOf, norm,
    selectorOf, pick,
    mainContainer, mainBlocks, mainText,
    NEXT_TEXT, findNext,
    CMP_CONTAINERS, REJECT_SELECTORS, REJECT_TEXTS, CMP_SCOPES
  };
})();
