"use strict";

(function () {

  const $ = s => document.querySelector(s);
  let entries = [];
  let cfg = { enabled: true, fullText: false, exclude: [] };
  let query = "";

  function esc(v) {
    return String(v == null ? "" : v)
      .replace(/&/g, "&amp;").replace(/</g, "&lt;")
      .replace(/>/g, "&gt;").replace(/"/g, "&quot;");
  }

  function norm(v) {
    return String(v || "").toLowerCase()
      .normalize("NFD").replace(/[\u0300-\u036f]/g, "");
  }

  function dayLabel(ts) {
    const d = new Date(ts);
    const today = new Date();
    const y = new Date(today.getTime() - 86400000);
    const same = (a, b) => a.toDateString() === b.toDateString();
    if (same(d, today)) return "Aujourd'hui";
    if (same(d, y)) return "Hier";
    try { return d.toLocaleDateString("fr-FR", { weekday: "long", day: "numeric",
                                                 month: "long" }); }
    catch (e) { return d.toLocaleDateString(); }
  }

  function hour(ts) {
    try { return new Date(ts).toLocaleTimeString("fr-FR",
      { hour: "2-digit", minute: "2-digit" }); }
    catch (e) { return ""; }
  }

  // Extrait du texte autour de la premiere occurrence trouvee
  function snippet(text, q) {
    const n = norm(text);
    const i = n.indexOf(q);
    if (i === -1) return "";
    const start = Math.max(0, i - 70);
    const end = Math.min(text.length, i + q.length + 90);
    const raw = (start ? "…" : "") + text.slice(start, end) + (end < text.length ? "…" : "");

    // Surlignage insensible aux accents : on repere par position, pas par motif
    const rn = norm(raw);
    const at = rn.indexOf(q);
    if (at === -1) return esc(raw);
    return esc(raw.slice(0, at)) + "<mark>" + esc(raw.slice(at, at + q.length)) +
           "</mark>" + esc(raw.slice(at + q.length));
  }

  // -------------------------------------------------------------------------
  function matches() {
    const q = norm(query.trim());
    if (!q) return entries.map(e => ({ e: e, snip: "" }));

    const out = [];
    for (const e of entries) {
      if (norm(e.title).indexOf(q) !== -1 || norm(e.url).indexOf(q) !== -1) {
        out.push({ e: e, snip: e.text ? snippet(e.text, q) : "" });
        continue;
      }
      if (e.text && norm(e.text).indexOf(q) !== -1) {
        out.push({ e: e, snip: snippet(e.text, q) });
      }
    }
    return out;
  }

  function render() {
    const indexed = entries.filter(e => e.text).length;
    $("#tot").textContent = entries.length
      ? entries.length + " page(s) conservee(s), " + indexed + " avec leur texte"
      : "";

    const list = matches();
    const box = $("#list");

    if (!list.length) {
      box.innerHTML = '<div style="padding:30px 8px;text-align:center;color:var(--dim)">' +
        (entries.length ? "Aucun resultat." :
         (cfg.enabled ? "Historique vide pour l'instant."
                      : "L'historique est desactive.")) + "</div>";
      return;
    }

    let html = "";
    let day = "";
    list.slice(0, 300).forEach(({ e, snip }) => {
      const d = dayLabel(e.at);
      if (d !== day) {
        day = d;
        html += '<div class="day">' + esc(d) + "</div>";
      }
      html += `
        <div class="e">
          <a href="${esc(e.url)}">${esc(e.title)}</a>
          <div class="mt">${esc(e.host)} &middot; ${esc(hour(e.at))}${
            e.visits > 1 ? " &middot; " + e.visits + " visites" : ""}</div>
          ${snip ? '<div class="snip">' + snip + "</div>" : ""}
          <div class="acts"><button data-d="${esc(e.url)}">Oublier</button></div>
        </div>`;
    });

    box.innerHTML = html;

    box.querySelectorAll("[data-d]").forEach(b => {
      b.onclick = () => {
        entries = entries.filter(x => x.url !== b.dataset.d);
        save("Entree supprimee.");
      };
    });
  }

  // -------------------------------------------------------------------------
  async function load() {
    try {
      const r = await browser.runtime.sendMessage({ type: "histList" });
      entries = (r && r.history) || [];
      const s = await browser.storage.local.get("histCfg");
      if (s && s.histCfg) cfg = Object.assign(cfg, s.histCfg);
    } catch (e) {
      $("#msg").textContent = "Extension non joignable.";
      return;
    }
    $("#c-on").checked = cfg.enabled !== false;
    $("#c-txt").checked = !!cfg.fullText;
    $("#c-ex").value = (cfg.exclude || []).join("\n");
    render();
  }

  async function save(message) {
    try {
      await browser.runtime.sendMessage({ type: "histSave", history: entries });
      if (message) $("#msg").textContent = message;
    } catch (e) {
      $("#msg").textContent = "Enregistrement impossible.";
    }
    render();
  }

  $("#q").oninput = () => { query = $("#q").value; render(); };

  $("#settings").onclick = () => {
    const c = $("#cfg");
    c.hidden = !c.hidden;
    $("#settings").classList.toggle("on", !c.hidden);
  };

  $("#c-save").onclick = async () => {
    cfg.enabled = $("#c-on").checked;
    cfg.fullText = $("#c-txt").checked;
    cfg.exclude = $("#c-ex").value.split("\n").map(x => x.trim()).filter(Boolean);
    try {
      await browser.storage.local.set({ histCfg: cfg });
      $("#msg").textContent = cfg.enabled
        ? "Reglages enregistres." + (cfg.fullText
            ? " Le texte sera indexe a partir des prochaines visites."
            : " Seuls adresse et titre sont conserves.")
        : "Historique desactive. Les entrees existantes sont conservees.";
    } catch (e) {
      $("#msg").textContent = "Enregistrement impossible.";
    }
  };

  $("#c-clear").onclick = async () => {
    if (!confirm("Effacer tout l'historique ? Cette action est definitive.")) return;
    entries = [];
    await save("Historique efface.");
  };

  load();
})();
