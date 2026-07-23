"use strict";

(function () {

  const $ = s => document.querySelector(s);
  let watches = [];

  function esc(v) {
    return String(v == null ? "" : v)
      .replace(/&/g, "&amp;").replace(/</g, "&lt;")
      .replace(/>/g, "&gt;").replace(/"/g, "&quot;");
  }

  function ago(ts) {
    if (!ts) return "jamais";
    const min = Math.round((Date.now() - ts) / 60000);
    if (min < 1) return "a l'instant";
    if (min < 60) return "il y a " + min + " min";
    const h = Math.round(min / 60);
    if (h < 24) return "il y a " + h + " h";
    return "il y a " + Math.round(h / 24) + " j";
  }

  function freq(m) {
    if (m >= 1440) return "une fois par jour";
    if (m >= 60) return "toutes les " + (m / 60) + " h";
    return "toutes les " + m + " min";
  }

  const MODES = {
    texte: "changement de texte",
    nombre: "changement de nombre",
    presence: "apparition ou disparition"
  };

  // -------------------------------------------------------------------------
  function render() {
    const box = $("#list");
    if (!watches.length) {
      box.innerHTML = '<div class="msg" style="padding:30px 8px;text-align:center;' +
        'color:var(--dim)">Aucune surveillance pour l\'instant.</div>';
      return;
    }

    box.innerHTML = watches.map((w, i) => {
      const changed = w.changedAt && (Date.now() - w.changedAt) < 7 * 86400000;
      return `
        <div class="w">
          <div class="top">
            <button class="sw ${w.enabled !== false ? "on" : "off"}" data-t="${i}">
              ${w.enabled !== false ? "\u25C9" : "\u25CB"}</button>
            <div class="info">
              <div class="nm">${esc(w.title)}</div>
              <div class="host">${esc(w.host)} &middot; ${esc(MODES[w.mode] || w.mode)}
                &middot; ${esc(freq(w.every || 120))}</div>
            </div>
          </div>

          <div class="val">
            <span class="lbl">Valeur</span>
            <div class="${changed ? "chg" : ""}">${esc(w.value || "(vide)")}</div>
            ${w.previous ? `<div class="old">${esc(w.previous)}</div>` : ""}
            <div class="sel">${esc(w.selector)}</div>
          </div>

          <div class="host" style="margin-top:6px">
            Verifie ${esc(ago(w.checkedAt))}${
              w.changedAt ? " &middot; change " + esc(ago(w.changedAt)) : ""}
          </div>

          <div class="acts">
            <a href="${esc(w.url)}">Ouvrir la page</a>
            <button data-r="${i}">Reference actuelle</button>
            <button data-d="${i}">Supprimer</button>
          </div>
        </div>`;
    }).join("");

    box.querySelectorAll("[data-t]").forEach(b => {
      b.onclick = () => {
        const i = +b.dataset.t;
        watches[i].enabled = watches[i].enabled === false;
        save();
      };
    });

    // Repartir de la valeur presente : utile apres un changement pris en compte
    box.querySelectorAll("[data-r]").forEach(b => {
      b.onclick = () => {
        const w = watches[+b.dataset.r];
        w.previous = "";
        w.changedAt = 0;
        save("Reference remise a la valeur actuelle.");
      };
    });

    box.querySelectorAll("[data-d]").forEach(b => {
      b.onclick = () => {
        const w = watches[+b.dataset.d];
        if (!confirm("Supprimer la surveillance de « " + w.title + " » ?")) return;
        watches.splice(+b.dataset.d, 1);
        save("Surveillance supprimee.");
      };
    });
  }

  // -------------------------------------------------------------------------
  async function load() {
    try {
      const r = await browser.runtime.sendMessage({ type: "watchList" });
      watches = (r && r.watches) || [];
    } catch (e) {
      $("#msg").textContent = "Extension non joignable.";
      return;
    }
    render();
  }

  async function save(message) {
    try {
      await browser.runtime.sendMessage({ type: "watchSave", watches: watches });
      if (message) $("#msg").textContent = message;
    } catch (e) {
      $("#msg").textContent = "Enregistrement impossible.";
    }
    render();
  }

  $("#check").onclick = async () => {
    const b = $("#check");
    b.textContent = "Verification…";
    try {
      const r = await browser.runtime.sendMessage({ type: "watchCheck" });
      $("#msg").textContent = r
        ? r.checked + " verifiee(s), " + r.changed + " changement(s)"
        : "Verification impossible.";
    } catch (e) {
      $("#msg").textContent = "Verification impossible.";
    }
    b.textContent = "Verifier maintenant";
    load();
  };

  $("#reload").onclick = load;

  load();
})();
