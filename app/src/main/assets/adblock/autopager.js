"use strict";

// ===========================================================================
//  autopager.js -- enchaine automatiquement les pages d'un site pagine.
//  Detecte le lien « suivant », telecharge la page, repere le conteneur de
//  contenu et lui ajoute les nouveaux elements. Repli manuel si la detection
//  echoue : un bouton « Charger la suite » apparait.
// ===========================================================================

(function () {

  if (window.top !== window.self) return;

  let cfg = { autopager: true, autopagerMode: "auto", autopagerMax: 20 };
  let disabledHere = false;

  let container = null;
  let containerPath = "";
  let nextUrl = null;
  let page = 1;
  let loading = false;
  let stopped = false;
  const seen = new Set([location.href]);

  let sentinel = null;
  let pill = null;

  // -------------------------------------------------------------------------
  //  Detection du lien vers la page suivante
  // -------------------------------------------------------------------------
  const NEXT_TEXT = /^(suivant|suivante|page suivante|next|next page|older|plus ancien|plus anciens|charger plus|voir plus|load more|more|»|›|→|>>|>)$/i;

  function textOf(a) {
    return (a.textContent || a.getAttribute("aria-label") || a.title || "")
      .replace(/\s+/g, " ").trim();
  }

  function sameOrigin(url) {
    try { return new URL(url, location.href).origin === location.origin; }
    catch (e) { return false; }
  }

  function findNext(doc, baseUrl) {
    const base = baseUrl || location.href;
    const resolve = u => { try { return new URL(u, base).href; } catch (e) { return null; } };

    // 1. Declaration explicite
    let el = doc.querySelector("link[rel~='next'][href], a[rel~='next'][href]");
    if (el) {
      const u = resolve(el.getAttribute("href"));
      if (u && sameOrigin(u)) return u;
    }

    // 2. Element de pagination marque comme courant
    const current = doc.querySelector(
      ".pagination .active, .pagination .current, .pager .current, " +
      "[aria-current='page'], .page-numbers.current");
    if (current) {
      let sib = current.nextElementSibling;
      while (sib) {
        const a = sib.matches("a[href]") ? sib : sib.querySelector("a[href]");
        if (a) {
          const u = resolve(a.getAttribute("href"));
          if (u && sameOrigin(u)) return u;
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
      const t = textOf(a);
      if (t.length > 24) continue;
      if (NEXT_TEXT.test(t)) {
        const u = resolve(a.getAttribute("href"));
        if (u && sameOrigin(u) && u !== base) return u;
      }
    }

    // 4. Increment du parametre de page dans l'URL
    try {
      const u = new URL(base);
      const keys = ["page", "p", "pg", "start", "offset", "from", "skip"];
      for (const k of keys) {
        const v = u.searchParams.get(k);
        if (v && /^\d+$/.test(v)) {
          const step = (k === "start" || k === "offset" || k === "skip" || k === "from")
            ? guessStep() : 1;
          u.searchParams.set(k, String(parseInt(v, 10) + step));
          return u.href;
        }
      }
      // Motif /page/2/ dans le chemin
      const m = u.pathname.match(/\/page\/(\d+)\/?$/i);
      if (m) {
        u.pathname = u.pathname.replace(/\/page\/\d+\/?$/i,
          "/page/" + (parseInt(m[1], 10) + 1) + "/");
        return u.href;
      }
    } catch (e) { }

    return null;
  }

  function guessStep() {
    return container ? Math.max(1, container.children.length) : 20;
  }

  // -------------------------------------------------------------------------
  //  Reperage du conteneur de contenu repete
  // -------------------------------------------------------------------------
  function signature(el) {
    const cls = (el.className || "").toString().trim().split(/\s+/)[0] || "";
    return el.tagName + "." + cls;
  }

  function findContainer(doc) {
    let best = null, bestScore = 0;
    const cands = doc.querySelectorAll(
      "main, [role='main'], article, section, ul, ol, tbody, " +
      "div[class*='list'], div[class*='result'], div[class*='post'], " +
      "div[class*='item'], div[class*='grid'], div[class*='feed'], div[id*='content']");

    for (const el of cands) {
      const kids = Array.from(el.children);
      if (kids.length < 3) continue;

      const sig = {};
      kids.forEach(k => { const s = signature(k); sig[s] = (sig[s] || 0) + 1; });
      const repeated = Math.max.apply(null, Object.values(sig));
      if (repeated < 3) continue;

      const text = (el.innerText || "").length;
      if (text < 200) continue;

      const score = repeated * Math.log(1 + text);
      if (score > bestScore) { bestScore = score; best = el; }
    }
    return best;
  }

  function cssPath(el) {
    if (!el) return "";
    const parts = [];
    let cur = el;
    while (cur && cur.nodeType === 1 && cur !== document.documentElement) {
      let sel = cur.tagName.toLowerCase();
      if (cur.id && /^[A-Za-z][\w-]*$/.test(cur.id)) {
        parts.unshift("#" + cur.id);
        break;
      }
      const parent = cur.parentElement;
      if (parent) {
        const same = Array.from(parent.children).filter(c => c.tagName === cur.tagName);
        if (same.length > 1) sel += ":nth-of-type(" + (same.indexOf(cur) + 1) + ")";
      }
      parts.unshift(sel);
      cur = cur.parentElement;
    }
    return parts.join(" > ");
  }

  // -------------------------------------------------------------------------
  //  Reecriture des adresses relatives de la page telechargee
  // -------------------------------------------------------------------------
  function absolutize(node, base) {
    const fix = (el, attr) => {
      const v = el.getAttribute(attr);
      if (!v || /^(https?:|data:|blob:|#|mailto:|tel:|javascript:)/i.test(v)) return;
      try { el.setAttribute(attr, new URL(v, base).href); } catch (e) { }
    };

    node.querySelectorAll("[href]").forEach(el => fix(el, "href"));
    node.querySelectorAll("[src]").forEach(el => fix(el, "src"));
    node.querySelectorAll("[poster]").forEach(el => fix(el, "poster"));

    node.querySelectorAll("[srcset]").forEach(el => {
      const out = (el.getAttribute("srcset") || "").split(",").map(part => {
        const p = part.trim().split(/\s+/);
        if (!p[0]) return part;
        try { p[0] = new URL(p[0], base).href; } catch (e) { }
        return p.join(" ");
      }).join(", ");
      el.setAttribute("srcset", out);
    });

    // Images differees : on force le chargement, l'observateur du site
    // ne surveille pas les elements qu'on vient d'ajouter.
    node.querySelectorAll("[data-src], [data-lazy-src], [data-original]").forEach(el => {
      const v = el.getAttribute("data-src") || el.getAttribute("data-lazy-src")
             || el.getAttribute("data-original");
      if (v && !el.getAttribute("src")) {
        try { el.setAttribute("src", new URL(v, base).href); } catch (e) { }
      }
    });
    node.querySelectorAll("img[loading='lazy']").forEach(el => {
      el.setAttribute("loading", "eager");
    });
  }

  // -------------------------------------------------------------------------
  //  Chargement
  // -------------------------------------------------------------------------
  async function loadNext(manual) {
    if (loading || stopped || !nextUrl) return;
    if (page >= cfg.autopagerMax) {
      setPill("Limite de " + cfg.autopagerMax + " pages atteinte", true);
      stopped = true;
      return;
    }

    loading = true;
    setPill("Chargement de la page " + (page + 1) + "…");

    const url = nextUrl;
    let doc = null;

    try {
      const res = await browser.runtime.sendMessage({
        type: "gmFetch", url: url, method: "GET"
      });
      if (res && res.body) {
        doc = new DOMParser().parseFromString(res.body, "text/html");
      }
    } catch (e) { }

    if (!doc) {
      setPill("Page suivante inaccessible", true);
      stopped = true;
      loading = false;
      return;
    }

    // Conteneur equivalent dans la page telechargee
    let src = containerPath ? doc.querySelector(containerPath) : null;
    if (!src || src.children.length === 0) src = findContainer(doc);

    if (!src || src.children.length === 0) {
      setPill("Contenu de la page suivante non reconnu", true);
      stopped = true;
      loading = false;
      return;
    }

    absolutize(src, url);

    const sep = document.createElement("div");
    sep.setAttribute("data-autopager", "1");
    sep.style.cssText =
      "margin:18px 0;padding:5px 0;border-top:1px dashed #8884;text-align:center;" +
      "font:11px -apple-system,Roboto,sans-serif;color:#8888;letter-spacing:.05em";
    sep.textContent = "— page " + (page + 1) + " —";

    const frag = document.createDocumentFragment();
    frag.appendChild(sep);
    Array.from(src.children).forEach(c => frag.appendChild(document.importNode(c, true)));

    container.insertBefore(frag, sentinel && sentinel.parentNode === container
      ? sentinel : null);

    page++;
    seen.add(url);

    // Lien suivant de la nouvelle page
    const following = findNext(doc, url);
    nextUrl = (following && !seen.has(following)) ? following : null;

    if (!nextUrl) {
      setPill("Fin des pages", true);
      stopped = true;
    } else {
      setPill("Page " + page + " chargee");
      setTimeout(() => hidePill(), 1600);
    }

    loading = false;

    // Si la page n'a pas assez grandi, on enchaine sans attendre le defilement.
    if (!stopped && cfg.autopagerMode === "auto" &&
        document.documentElement.scrollHeight <= window.innerHeight * 1.5) {
      loadNext();
    }
  }

  // -------------------------------------------------------------------------
  //  Indicateur et bouton manuel
  // -------------------------------------------------------------------------
  function ensurePill() {
    if (pill) return pill;
    pill = document.createElement("div");
    pill.style.cssText =
      "position:fixed;left:50%;transform:translateX(-50%);bottom:16px;z-index:2147483640;" +
      "background:rgba(28,31,38,.94);color:#e8eaee;border:1px solid #2b303a;" +
      "border-radius:16px;padding:7px 15px;font:12px -apple-system,Roboto,sans-serif;" +
      "box-shadow:0 4px 16px rgba(0,0,0,.4);display:none;max-width:80vw;text-align:center";
    document.body.appendChild(pill);
    return pill;
  }

  function setPill(text, sticky) {
    const p = ensurePill();
    p.textContent = text;
    p.style.display = "block";
    p.onclick = null;
    if (sticky && nextUrl) {
      p.textContent = text + " · toucher pour reessayer";
      p.onclick = () => { stopped = false; loadNext(true); };
    }
  }

  function hidePill() {
    if (pill) pill.style.display = "none";
  }

  function manualButton() {
    const b = document.createElement("button");
    b.textContent = "Charger la suite";
    b.style.cssText =
      "display:block;margin:20px auto;padding:11px 22px;border:1px solid #2b303a;" +
      "border-radius:10px;background:#1c1f26;color:#e8eaee;font-size:14px";
    b.onclick = () => {
      b.textContent = "Chargement…";
      loadNext(true).then(() => {
        if (stopped) b.remove();
        else b.textContent = "Charger la suite";
      });
    };
    container.appendChild(b);
  }

  // -------------------------------------------------------------------------
  //  Demarrage
  // -------------------------------------------------------------------------
  function start() {
    container = findContainer(document);
    if (!container) return;
    containerPath = cssPath(container);

    nextUrl = findNext(document, location.href);
    if (!nextUrl || seen.has(nextUrl)) return;

    if (cfg.autopagerMode === "manuel") {
      manualButton();
      return;
    }

    sentinel = document.createElement("div");
    sentinel.style.cssText = "height:1px;width:100%";
    container.appendChild(sentinel);

    const obs = new IntersectionObserver(entries => {
      entries.forEach(e => { if (e.isIntersecting) loadNext(); });
    }, { rootMargin: "1200px 0px" });

    obs.observe(sentinel);
  }

  async function init() {
    try {
      const s = await browser.storage.local.get(["pageCfg", "autopagerOff"]);
      if (s && s.pageCfg) cfg = Object.assign(cfg, s.pageCfg);
      const off = (s && s.autopagerOff) || [];
      disabledHere = off.includes(location.hostname.replace(/^www\./, ""));
    } catch (e) { }

    if (!cfg.autopager || disabledHere) return;

    // Le contenu differe est frequent : on laisse la page se stabiliser.
    setTimeout(start, 900);
  }

  // Bascule pour le site courant, appelee depuis le panneau flottant
  window.__toggleAutopagerHere = async function () {
    const host = location.hostname.replace(/^www\./, "");
    try {
      const s = await browser.storage.local.get("autopagerOff");
      const off = (s && s.autopagerOff) || [];
      const i = off.indexOf(host);
      if (i === -1) off.push(host); else off.splice(i, 1);
      await browser.storage.local.set({ autopagerOff: off });
      alert(i === -1
        ? "Defilement infini desactive sur " + host
        : "Defilement infini active sur " + host);
      location.reload();
    } catch (e) { }
  };

  browser.storage.onChanged.addListener(changes => {
    const c = changes.pageCommand && changes.pageCommand.newValue;
    if (c && c.cmd === "autopagerHere" && window.__toggleAutopagerHere) {
      window.__toggleAutopagerHere();
    }
  });

  init();
})();
