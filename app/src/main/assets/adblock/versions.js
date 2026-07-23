"use strict";

// ===========================================================================
//  versions.js -- archive le texte d'une page et le compare a une version
//  anterieure. Sert a reperer les modifications discretes : corrections non
//  signalees, passages retires, chiffres revus.
//
//  Seul le texte est conserve, pas la page entiere : c'est ce qui se compare,
//  et cela tient en quelques dizaines de kilo-octets par version.
// ===========================================================================

(function () {

  if (window.top !== window.self) return;

  const MAX_VERSIONS = 4;      // par adresse
  const MAX_CHARS = 200000;    // par version
  let panel = null;

  function esc(v) {
    return String(v == null ? "" : v)
      .replace(/&/g, "&amp;").replace(/</g, "&lt;")
      .replace(/>/g, "&gt;");
  }

  function key() {
    // L'ancre et les parametres de suivi ne changent pas le contenu
    try {
      const u = new URL(location.href);
      u.hash = "";
      ["utm_source", "utm_medium", "utm_campaign", "fbclid", "gclid"]
        .forEach(p => u.searchParams.delete(p));
      return u.href;
    } catch (e) { return location.href; }
  }

  function stamp(ts) {
    try { return new Date(ts).toLocaleString("fr-FR"); }
    catch (e) { return String(ts); }
  }

  // -------------------------------------------------------------------------
  //  Archivage
  // -------------------------------------------------------------------------
  async function archive(silent) {
    const blocks = GB.mainBlocks(document);
    if (!blocks.length) {
      if (!silent) alert("Aucun texte principal reconnu sur cette page.");
      return null;
    }

    let text = blocks.join("\n");
    if (text.length > MAX_CHARS) text = text.slice(0, MAX_CHARS);

    const entry = {
      at: Date.now(),
      title: (document.title || location.hostname).slice(0, 120),
      blocks: blocks.length,
      chars: text.length,
      text: text
    };

    try {
      const s = await browser.storage.local.get("versions");
      const all = (s && s.versions) || {};
      const k = key();
      const list = all[k] || [];

      const last = list[list.length - 1];
      if (last && last.text === text) {
        if (!silent) alert("Aucun changement depuis la derniere archive.");
        return null;
      }

      list.push(entry);
      while (list.length > MAX_VERSIONS) list.shift();
      all[k] = list;
      await browser.storage.local.set({ versions: all });

      if (!silent) {
        alert("Version archivee.\n\n" + entry.blocks + " blocs, " +
              Math.round(entry.chars / 1024) + " Ko de texte.\n" +
              list.length + " version(s) conservee(s) pour cette page.");
      }
      return entry;
    } catch (e) {
      if (!silent) alert("Archivage impossible.");
      return null;
    }
  }

  // -------------------------------------------------------------------------
  //  Comparaison : plus longue sous-sequence commune sur les blocs
  // -------------------------------------------------------------------------
  function diff(oldB, newB) {
    const n = oldB.length, m = newB.length;

    // Garde-fou : au-dela, la table devient trop lourde pour un telephone
    if (n * m > 400000) {
      return [{ kind: "info",
                text: "Document trop volumineux pour une comparaison detaillee." }];
    }

    // Table des longueurs de sous-sequence commune
    const L = [];
    for (let i = 0; i <= n; i++) L.push(new Uint16Array(m + 1));
    for (let i = n - 1; i >= 0; i--) {
      for (let j = m - 1; j >= 0; j--) {
        L[i][j] = oldB[i] === newB[j]
          ? L[i + 1][j + 1] + 1
          : Math.max(L[i + 1][j], L[i][j + 1]);
      }
    }

    const out = [];
    let i = 0, j = 0;
    while (i < n && j < m) {
      if (oldB[i] === newB[j]) {
        out.push({ kind: "same", text: oldB[i] });
        i++; j++;
      } else if (L[i + 1][j] >= L[i][j + 1]) {
        out.push({ kind: "del", text: oldB[i] });
        i++;
      } else {
        out.push({ kind: "add", text: newB[j] });
        j++;
      }
    }
    while (i < n) out.push({ kind: "del", text: oldB[i++] });
    while (j < m) out.push({ kind: "add", text: newB[j++] });
    return out;
  }

  // -------------------------------------------------------------------------
  //  Affichage
  // -------------------------------------------------------------------------
  const CSS = `
  #vr-root{position:fixed;inset:0;z-index:2147483647;background:#14161a;color:#e8eaee;
    font:13px/1.6 -apple-system,Roboto,"Segoe UI",sans-serif;display:flex;
    flex-direction:column}
  #vr-root *{box-sizing:border-box}
  .vr-head{display:flex;align-items:center;gap:8px;padding:11px 12px;
    border-bottom:1px solid #2b303a}
  .vr-head b{flex:1;font-size:14px}
  .vr-x{background:none;border:0;color:#99a0ad;font-size:22px;padding:0 6px}
  .vr-body{flex:1;overflow-y:auto;padding:12px 14px 34px}
  .vr-sum{background:#1c1f26;border:1px solid #2b303a;border-radius:10px;
    padding:12px 14px;margin-bottom:14px}
  .vr-sum b{color:#e8eaee}
  .vr-sum p{margin:6px 0 0;color:#99a0ad;font-size:12px}
  .vr-l{padding:6px 10px;border-left:3px solid transparent;margin:3px 0;
    word-break:break-word;border-radius:0 6px 6px 0}
  .vr-add{background:rgba(111,174,95,.12);border-left-color:#6fae5f;color:#cfe6c6}
  .vr-del{background:rgba(217,119,87,.12);border-left-color:#d97757;color:#e6cbc1;
    text-decoration:line-through}
  .vr-same{color:#6a727e;font-size:12px;padding:2px 10px}
  .vr-info{color:#d9c07c}
  .vr-acts{display:flex;gap:8px;margin-top:16px;flex-wrap:wrap}
  .vr-b{flex:1 1 130px;padding:10px;border:1px solid #2b303a;border-radius:8px;
    background:#1c1f26;color:#e8eaee;font-size:12px}
  .vr-note{color:#5f6874;font-size:11px;margin-top:14px;line-height:1.6}`;

  function open(html) {
    if (panel) panel.remove();
    panel = document.createElement("div");
    panel.id = "vr-root";
    panel.innerHTML =
      '<div class="vr-head"><b>Comparaison de versions</b>' +
      '<button class="vr-x" id="vr-close">&times;</button></div>' +
      '<div class="vr-body">' + html + "</div>";
    const st = document.createElement("style");
    st.textContent = CSS;
    panel.appendChild(st);
    document.documentElement.appendChild(panel);
    panel.querySelector("#vr-close").onclick = close;
  }

  function close() {
    if (panel) { panel.remove(); panel = null; }
  }

  async function compare() {
    let list = [];
    try {
      const s = await browser.storage.local.get("versions");
      list = ((s && s.versions) || {})[key()] || [];
    } catch (e) { }

    if (!list.length) {
      alert("Aucune archive pour cette page.\n\nUtilisez d'abord " +
            "« Archiver cette version ».");
      return;
    }

    const previous = list[list.length - 1];
    const current = GB.mainBlocks(document);
    const oldBlocks = previous.text.split("\n");

    const parts = diff(oldBlocks, current);
    const added = parts.filter(p => p.kind === "add").length;
    const removed = parts.filter(p => p.kind === "del").length;

    let summary;
    if (!added && !removed) {
      summary = '<div class="vr-sum"><b>Aucun changement</b>' +
        '<p>Le texte est identique a la version du ' + esc(stamp(previous.at)) +
        ".</p></div>";
    } else {
      summary = '<div class="vr-sum"><b>' + added + " ajout" + (added > 1 ? "s" : "") +
        ", " + removed + " suppression" + (removed > 1 ? "s" : "") + "</b>" +
        "<p>Compare a la version du " + esc(stamp(previous.at)) +
        " &middot; " + list.length + " version(s) conservee(s).</p></div>";
    }

    // Les passages inchanges sont replies : seuls les ecarts comptent
    const body = parts.map((p, idx) => {
      if (p.kind === "info") return '<div class="vr-l vr-info">' + esc(p.text) + "</div>";
      if (p.kind === "add") return '<div class="vr-l vr-add">' + esc(p.text) + "</div>";
      if (p.kind === "del") return '<div class="vr-l vr-del">' + esc(p.text) + "</div>";

      const near = parts[idx - 1] && parts[idx - 1].kind !== "same" ||
                   parts[idx + 1] && parts[idx + 1].kind !== "same";
      if (!near) return "";
      return '<div class="vr-same">' + esc(p.text.slice(0, 120)) +
             (p.text.length > 120 ? "…" : "") + "</div>";
    }).join("");

    open(summary + (body || '<div class="vr-same">Rien a afficher.</div>') +
      '<div class="vr-acts">' +
      '<button class="vr-b" id="vr-arch">Archiver l\'etat actuel</button>' +
      '<button class="vr-b" id="vr-list">Toutes les archives</button>' +
      "</div>" +
      '<p class="vr-note">Les passages inchanges eloignes d\'un ecart sont masques. ' +
      'Seul le texte principal est compare : menus, encarts et pieds de page sont ' +
      'ecartes, ce qui evite un bruit permanent.</p>');

    const a = panel.querySelector("#vr-arch");
    if (a) a.onclick = async () => {
      const e = await archive(true);
      a.textContent = e ? "Archive" : "Deja a jour";
    };
    const l = panel.querySelector("#vr-list");
    if (l) l.onclick = () => { location.href = browser.runtime.getURL("versions.html"); };
  }

  browser.storage.onChanged.addListener(changes => {
    const c = changes.pageCommand && changes.pageCommand.newValue;
    if (!c) return;
    if (c.cmd === "archive") archive(false);
    if (c.cmd === "compare") compare();
  });
})();
