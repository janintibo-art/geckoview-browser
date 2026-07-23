"use strict";

// ===========================================================================
//  sentinel.js -- signale les mouchards a l'ouverture d'un site.
//
//  Le rapport complet existe deja ; il fallait qu'il vienne a l'utilisateur
//  plutot que d'attendre qu'on le demande. L'affichage doit rester bref :
//  une alerte permanente cesse d'etre lue au bout de trois jours.
// ===========================================================================

(function () {

  if (window.top !== window.self) return;
  if (!/^https?:/i.test(location.href)) return;

  let cfg = {
    enabled: true,
    delay: 3500,     // laisser la page charger ses requetes
    min: 1,          // nombre de mouchards a partir duquel on signale
    hide: 8000,      // effacement automatique
    mute: []         // sites ou l'on ne veut plus rien voir
  };

  let box = null;
  let timer = null;

  function esc(v) {
    return String(v == null ? "" : v)
      .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
  }

  const host = location.hostname.replace(/^www\./, "");

  // -------------------------------------------------------------------------
  function dismiss() {
    if (timer) { clearTimeout(timer); timer = null; }
    if (!box) return;
    box.style.transform = "translateY(120%)";
    setTimeout(() => { if (box) { box.remove(); box = null; } }, 260);
  }

  async function mute() {
    try {
      const s = await browser.storage.local.get("alertCfg");
      const c = Object.assign({}, cfg, (s && s.alertCfg) || {});
      c.mute = (c.mute || []).concat([host]);
      await browser.storage.local.set({ alertCfg: c });
    } catch (e) { }
    dismiss();
  }

  function openReport() {
    dismiss();
    try {
      browser.storage.local.set({
        pageCommand: { cmd: "thirdParty", ts: Date.now() }
      });
    } catch (e) { }
  }

  // -------------------------------------------------------------------------
  function show(list, blocked) {
    const names = list.slice(0, 5).map(d => {
      const owner = d.owner ? ' <i style="color:#8ab4f8;font-style:normal">' +
        esc(d.owner) + "</i>" : "";
      const mark = d.blocked ? ' <span style="color:#8fce7c">\u2713</span>' : "";
      return "<div>" + esc(d.domain) + owner + mark + "</div>";
    }).join("");

    const rest = list.length > 5
      ? '<div style="color:#5f6874">et ' + (list.length - 5) + " autre(s)</div>"
      : "";

    box = document.createElement("div");
    box.style.cssText =
      "position:fixed;left:10px;right:10px;bottom:14px;z-index:2147483646;" +
      "background:rgba(23,26,32,.97);border:1px solid #2b303a;border-radius:14px;" +
      "padding:11px 13px 12px;color:#e8eaee;box-shadow:0 8px 26px rgba(0,0,0,.5);" +
      "font:12px/1.55 -apple-system,Roboto,sans-serif;" +
      "transform:translateY(120%);transition:transform .26s ease";

    box.innerHTML =
      '<div style="display:flex;align-items:center;gap:8px;margin-bottom:6px">' +
      '<span style="color:#e08a72;font-size:14px">\u26A1</span>' +
      '<b style="flex:1;font-size:13px">' + list.length + " mouchard" +
      (list.length > 1 ? "s" : "") + " sur ce site" +
      (blocked ? ' <span style="color:#8fce7c;font-weight:400">\u00B7 ' +
        blocked + " bloque" + (blocked > 1 ? "s" : "") + "</span>" : "") +
      "</b>" +
      '<button data-a="x" style="background:none;border:0;color:#99a0ad;' +
      'font-size:18px;padding:0 4px;line-height:1">\u00D7</button></div>' +
      '<div style="color:#c3c8d1;font-size:11.5px;line-height:1.6">' +
      names + rest + "</div>" +
      '<div style="display:flex;gap:6px;margin-top:9px">' +
      '<button data-a="d" style="flex:1;padding:7px;border:1px solid #2b303a;' +
      'border-radius:7px;background:#1c1f26;color:#e8eaee;font-size:12px">Details</button>' +
      '<button data-a="m" style="flex:1;padding:7px;border:1px solid #2b303a;' +
      'border-radius:7px;background:transparent;color:#99a0ad;font-size:12px">' +
      "Plus sur ce site</button></div>";

    document.documentElement.appendChild(box);
    requestAnimationFrame(() => { box.style.transform = "translateY(0)"; });

    box.addEventListener("click", e => {
      const a = e.target.getAttribute && e.target.getAttribute("data-a");
      if (!a) return;
      e.preventDefault();
      e.stopPropagation();
      if (a === "x") dismiss();
      if (a === "d") openReport();
      if (a === "m") mute();
    }, true);

    // Toucher l'encart suspend l'effacement : on lit peut-etre encore
    box.addEventListener("pointerdown", () => {
      if (timer) { clearTimeout(timer); timer = null; }
    }, true);

    timer = setTimeout(dismiss, cfg.hide);
  }

  // -------------------------------------------------------------------------
  async function run() {
    try {
      const s = await browser.storage.local.get("alertCfg");
      if (s && s.alertCfg) cfg = Object.assign(cfg, s.alertCfg);
    } catch (e) { }

    if (!cfg.enabled) return;
    if ((cfg.mute || []).some(h => host === h || host.endsWith("." + h))) return;

    let rep = null;
    try {
      rep = await browser.runtime.sendMessage({
        type: "thirdParty", origin: location.origin
      });
    } catch (e) { return; }
    if (!rep || !rep.thirdParties) return;

    // Ne sont retenus que les tiers qualifies : un domaine technique de
    // diffusion de contenu n'est pas un mouchard.
    const list = rep.thirdParties.filter(d =>
      d.category === "publicite" || d.blocked > 0 || d.owner);

    if (list.length < (cfg.min || 1)) return;

    const blocked = list.reduce((n, d) => n + (d.blocked ? 1 : 0), 0);
    show(list, blocked);
  }

  setTimeout(run, cfg.delay);
})();
