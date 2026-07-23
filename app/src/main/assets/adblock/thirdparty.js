"use strict";

// ===========================================================================
//  thirdparty.js -- « qui parle a qui ».
//  Le journal reseau sait deja tout ce qu'une page contacte. Ce panneau en
//  donne une lecture : quels tiers, pour quel volume, appartenant a qui, et
//  ce que le blocage a effectivement coupe.
// ===========================================================================

(function () {

  if (window.top !== window.self) return;

  let panel = null;

  function esc(v) {
    return String(v == null ? "" : v)
      .replace(/&/g, "&amp;").replace(/</g, "&lt;")
      .replace(/>/g, "&gt;").replace(/"/g, "&quot;");
  }

  function human(n) {
    if (!n) return "";
    if (n < 1024) return n + " o";
    if (n < 1048576) return Math.round(n / 1024) + " Ko";
    return (n / 1048576).toFixed(1) + " Mo";
  }

  function typeLabel(types) {
    const map = {
      script: "scripts", image: "images", stylesheet: "styles",
      xmlhttprequest: "appels d'API", font: "polices", media: "medias",
      sub_frame: "cadres", ping: "balises", beacon: "balises",
      websocket: "connexions", other: "divers"
    };
    return Object.keys(types)
      .sort((a, b) => types[b] - types[a])
      .slice(0, 3)
      .map(t => (map[t] || t) + " \u00D7 " + types[t])
      .join(", ");
  }

  // -------------------------------------------------------------------------
  //  Lecture en clair
  // -------------------------------------------------------------------------
  function summary(rep) {
    const n = rep.thirdParties.length;
    if (!n) {
      return "Cette page n'a contacte aucun domaine tiers. C'est rare, et plutot " +
             "bon signe.";
    }

    const ads = rep.thirdParties.filter(d => d.category === "publicite").length;
    const known = rep.thirdParties.filter(d => d.owner).length;

    let out = "Cette page a contacte <b>" + n + " domaine" + (n > 1 ? "s" : "") +
              " tiers</b> sur " + rep.totalRequests + " requetes.";
    if (ads) {
      out += " <b>" + ads + "</b> " + (ads > 1 ? "sont des regies ou traqueurs"
                                              : "est une regie ou un traqueur") + ".";
    }
    if (rep.blockedTotal) {
      out += " Le blocage en a coupe <b>" + rep.blockedTotal + "</b>.";
    }
    if (known) {
      out += " " + known + " appartien" + (known > 1 ? "nent" : "t") +
             " a un groupe identifie.";
    }
    return out;
  }

  function render(rep) {
    const rows = rep.thirdParties.map(d => {
      const tags = [];
      if (d.category === "publicite") {
        tags.push('<span class="tp-t tp-ad">regie ou traqueur</span>');
      } else if (d.category) {
        tags.push('<span class="tp-t tp-cat">' + esc(d.category) + "</span>");
      }
      if (d.blocked) {
        tags.push('<span class="tp-t tp-blk">' + d.blocked + " bloquee" +
                  (d.blocked > 1 ? "s" : "") + "</span>");
      }
      if (d.owner) {
        tags.push('<span class="tp-t tp-own">' + esc(d.owner) + "</span>");
      }

      return `
        <div class="tp-r">
          <div class="tp-d">${esc(d.domain)}</div>
          <div class="tp-m">
            ${d.count} requete${d.count > 1 ? "s" : ""}
            ${d.bytes ? " &middot; " + human(d.bytes) : ""}
            ${Object.keys(d.types).length ? " &middot; " + esc(typeLabel(d.types)) : ""}
          </div>
          ${tags.length ? '<div class="tp-tags">' + tags.join("") + "</div>" : ""}
          ${d.hosts.length > 1
            ? '<div class="tp-h">' + esc(d.hosts.join(", ")) + "</div>" : ""}
        </div>`;
    }).join("");

    return `
      <div class="tp-sum">${summary(rep)}</div>
      ${rows || '<div class="tp-empty">Aucun tiers releve. Rechargez la page ' +
                'avec le panneau ferme, puis rouvrez-le.</div>'}
      <div class="tp-acts">
        <button class="tp-b" id="tp-copy">Copier le rapport</button>
        <button class="tp-b" id="tp-again">Actualiser</button>
      </div>
      <p class="tp-note">
        Le journal se remplit pendant le chargement. Un tiers absent de cette liste
        peut simplement avoir ete contacte avant l'ouverture du navigateur sur cette
        page : rechargez pour un releve complet.
      </p>`;
  }

  function asText(rep) {
    let out = "Tiers contactes par " + rep.page + "\n";
    out += rep.thirdParties.length + " domaines tiers, " +
           rep.totalRequests + " requetes, " + rep.blockedTotal + " bloquees\n\n";
    rep.thirdParties.forEach(d => {
      out += d.domain + " — " + d.count + " requetes";
      if (d.category) out += " [" + d.category + "]";
      if (d.owner) out += " (" + d.owner + ")";
      if (d.blocked) out += " — " + d.blocked + " bloquees";
      out += "\n";
    });
    return out;
  }

  // -------------------------------------------------------------------------
  const CSS = `
  #tp-root{position:fixed;inset:0;z-index:2147483647;background:#14161a;color:#e8eaee;
    font:13px/1.55 -apple-system,Roboto,"Segoe UI",sans-serif;display:flex;
    flex-direction:column}
  #tp-root *{box-sizing:border-box}
  .tp-head{display:flex;align-items:center;gap:8px;padding:11px 12px;
    border-bottom:1px solid #2b303a}
  .tp-head b{flex:1;font-size:14px}
  .tp-x{background:none;border:0;color:#99a0ad;font-size:22px;padding:0 6px}
  .tp-body{flex:1;overflow-y:auto;padding:12px 14px 34px}
  .tp-sum{background:#1c1f26;border:1px solid #2b303a;border-radius:10px;
    padding:12px 14px;margin-bottom:14px;line-height:1.6}
  .tp-sum b{color:#e8eaee}
  .tp-r{padding:10px 0;border-bottom:1px solid #22262e}
  .tp-d{font-size:14px;word-break:break-all}
  .tp-m{color:#99a0ad;font-size:11px;margin-top:2px}
  .tp-h{color:#5f6874;font-size:11px;margin-top:3px;word-break:break-all}
  .tp-tags{display:flex;flex-wrap:wrap;gap:5px;margin-top:5px}
  .tp-t{font-size:11px;padding:2px 8px;border-radius:10px;border:1px solid #2b303a}
  .tp-ad{color:#e08a72;border-color:#5a3230}
  .tp-cat{color:#d9c07c;border-color:#5a5030}
  .tp-blk{color:#8fce7c;border-color:#3d5c34}
  .tp-own{color:#8ab4f8;border-color:#3a4d68}
  .tp-empty{color:#99a0ad;padding:26px 0;text-align:center}
  .tp-acts{margin-top:18px;display:flex;gap:8px}
  .tp-b{flex:1;padding:10px;border:1px solid #2b303a;border-radius:8px;
    background:#1c1f26;color:#e8eaee;font-size:12px}
  .tp-note{color:#5f6874;font-size:11px;line-height:1.6;margin-top:16px}`;

  async function open() {
    if (panel) { close(); return; }

    panel = document.createElement("div");
    panel.id = "tp-root";
    panel.innerHTML =
      '<div class="tp-head"><b>Qui parle a qui</b>' +
      '<button class="tp-x" id="tp-close">&times;</button></div>' +
      '<div class="tp-body"><p style="color:#99a0ad">Lecture du journal reseau…</p></div>';
    const st = document.createElement("style");
    st.textContent = CSS;
    panel.appendChild(st);
    document.documentElement.appendChild(panel);
    panel.querySelector("#tp-close").onclick = close;

    let rep = null;
    try {
      rep = await browser.runtime.sendMessage({
        type: "thirdParty", origin: location.origin
      });
    } catch (e) { }

    const body = panel.querySelector(".tp-body");
    if (!rep) {
      body.innerHTML = '<div class="tp-empty">Journal indisponible.</div>';
      return;
    }

    body.innerHTML = render(rep);

    const cp = body.querySelector("#tp-copy");
    if (cp) cp.onclick = () => {
      try { navigator.clipboard.writeText(asText(rep)); cp.textContent = "Copie"; }
      catch (e) { cp.textContent = "Copie impossible"; }
    };
    const ag = body.querySelector("#tp-again");
    if (ag) ag.onclick = () => { close(); open(); };
  }

  function close() {
    if (panel) { panel.remove(); panel = null; }
  }

  browser.storage.onChanged.addListener(changes => {
    const c = changes.pageCommand && changes.pageCommand.newValue;
    if (c && c.cmd === "thirdParty") open();
  });
})();
