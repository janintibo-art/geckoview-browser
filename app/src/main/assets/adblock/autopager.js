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
  // La detection de la page suivante vit dans shared.js, partagee avec
  // l'extracteur structure : une seule implementation a maintenir.
  function findNext(doc, baseUrl) {
    return GB.findNext(doc, baseUrl || location.href, guessStep());
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
