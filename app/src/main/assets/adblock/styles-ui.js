"use strict";

(function () {

  const $ = s => document.querySelector(s);
  let styles = [];
  let editing = null;

  const TEMPLATES = {
    hide:
      "/* Masquer un element : remplacez le selecteur */\n" +
      ".pub, .bandeau-promo {\n  display: none !important;\n}\n",
    wide:
      "/* Elargir la colonne de lecture */\n" +
      "main, article, .content, .container {\n" +
      "  max-width: 100% !important;\n  width: auto !important;\n}\n",
    dark:
      "/* Fond sombre sommaire — a ajuster selon le site */\n" +
      "html, body {\n  background: #14161a !important;\n  color: #e0e3e8 !important;\n}\n" +
      "a { color: #8ab4f8 !important; }\n" +
      "img, video { filter: brightness(.88); }\n",
    font:
      "/* Confort de lecture */\n" +
      "body, p, li {\n  font-size: 17px !important;\n  line-height: 1.7 !important;\n}\n" +
      "p { margin-bottom: 1.1em !important; }\n",
    sticky:
      "/* Supprimer les bandeaux et pieds de page colles */\n" +
      "header, .header, .navbar, .sticky, .fixed, [class*='sticky'], [class*='fixed-'] {\n" +
      "  position: static !important;\n}\n"
  };

  function esc(s) {
    return String(s == null ? "" : s)
      .replace(/&/g, "&amp;").replace(/</g, "&lt;")
      .replace(/>/g, "&gt;").replace(/"/g, "&quot;");
  }

  // -------------------------------------------------------------------------
  async function load() {
    try {
      const s = await browser.storage.local.get("userStyles");
      styles = (s && s.userStyles) || [];
    } catch (e) { styles = []; }
    render();
    handleQuery();
  }

  async function persist() {
    try { await browser.storage.local.set({ userStyles: styles }); }
    catch (e) { $("#msg").textContent = "Echec de l'enregistrement."; }
  }

  function render() {
    const box = $("#list");
    if (!styles.length) {
      box.innerHTML = '<div class="msg" style="padding:30px 8px;text-align:center;' +
        'color:var(--dim)">Aucun style pour l\'instant.</div>';
      return;
    }
    box.innerHTML = "";
    styles.forEach((st, i) => {
      const row = document.createElement("div");
      row.className = "row";
      row.innerHTML =
        `<button class="sw ${st.enabled !== false ? "on" : "off"}" data-i="${i}">` +
        `${st.enabled !== false ? "\u25C9" : "\u25CB"}</button>` +
        `<div class="info"><div class="nm"></div><div class="mt"></div></div>` +
        `<button class="ed" data-e="${i}">Modifier</button>`;
      row.querySelector(".nm").textContent = st.name || "Sans titre";
      row.querySelector(".mt").textContent = (st.patterns || []).join("  ") || "aucun motif";
      box.appendChild(row);
    });

    box.querySelectorAll(".sw").forEach(b => {
      b.onclick = async () => {
        const i = +b.dataset.i;
        styles[i].enabled = styles[i].enabled === false;
        await persist();
        render();
      };
    });
    box.querySelectorAll("[data-e]").forEach(b => {
      b.onclick = () => openEditor(+b.dataset.e);
    });
  }

  // -------------------------------------------------------------------------
  function openEditor(index, preset) {
    editing = index;
    const st = index === null ? (preset || {}) : styles[index];
    $("#name").value = st.name || "";
    $("#pat").value = (st.patterns || []).join("\n");
    $("#css").value = st.css || "";
    $("#editor").style.display = "block";
    $("#list-view").style.display = "none";
    $("#del").style.display = index === null ? "none" : "block";
    $("#msg").textContent = "";
    window.scrollTo(0, 0);
  }

  function closeEditor() {
    editing = null;
    $("#editor").style.display = "none";
    $("#list-view").style.display = "block";
  }

  // Verification sommaire : accolades equilibrees
  function cssLooksValid(css) {
    let depth = 0;
    for (const c of css) {
      if (c === "{") depth++;
      if (c === "}") { depth--; if (depth < 0) return false; }
    }
    return depth === 0;
  }

  $("#new").onclick = () => openEditor(null);
  $("#cancel").onclick = closeEditor;

  document.querySelectorAll(".tpl button").forEach(b => {
    b.onclick = () => {
      const box = $("#css");
      const tpl = TEMPLATES[b.dataset.t] || "";
      box.value = box.value ? box.value.trimEnd() + "\n\n" + tpl : tpl;
      box.focus();
    };
  });

  $("#save").onclick = async () => {
    const name = $("#name").value.trim() || "Sans titre";
    const patterns = $("#pat").value.split("\n").map(s => s.trim()).filter(Boolean);
    const css = $("#css").value;

    if (!patterns.length) {
      $("#msg").textContent = "Indiquez au moins un motif de site.";
      return;
    }
    if (!css.trim()) {
      $("#msg").textContent = "La feuille est vide.";
      return;
    }
    if (!cssLooksValid(css)) {
      $("#msg").textContent = "Accolades desequilibrees : verifiez la feuille.";
      return;
    }

    if (editing === null) {
      styles.push({
        id: "st_" + Date.now().toString(36),
        name, patterns, css, enabled: true
      });
    } else {
      styles[editing].name = name;
      styles[editing].patterns = patterns;
      styles[editing].css = css;
    }
    await persist();
    closeEditor();
    render();
  };

  $("#del").onclick = async () => {
    if (editing === null) return;
    styles.splice(editing, 1);
    await persist();
    closeEditor();
    render();
  };

  // -------------------------------------------------------------------------
  //  Arrivee depuis le menu ou le pointeur d'element
  // -------------------------------------------------------------------------
  function handleQuery() {
    const p = new URLSearchParams(location.search);
    const host = p.get("host");
    const hide = p.get("hide");
    if (!host) return;

    const existing = styles.findIndex(st =>
      (st.patterns || []).some(x => x === host));

    if (existing !== -1) {
      openEditor(existing);
      if (hide) {
        const box = $("#css");
        box.value = box.value.trimEnd() + "\n" + hide + " { display: none !important; }\n";
      }
      return;
    }

    openEditor(null, {
      name: "Style pour " + host,
      patterns: [host],
      css: hide
        ? hide + " { display: none !important; }\n"
        : "/* CSS applique sur " + host + " */\n\n"
    });
  }

  load();
})();
