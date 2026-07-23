"use strict";

const $ = s => document.querySelector(s);
let scripts = [];
let editing = null;

const TEMPLATE = `// ==UserScript==
// @name         Mon script
// @namespace    perso
// @version      1.0
// @description  Ce que fait le script
// @match        *://*/*
// @run-at       document-end
// ==/UserScript==

(function () {
  'use strict';

  // Votre code ici.
  // Exemple : masquer un element sur toutes les pages.
  // document.querySelectorAll('.pub').forEach(e => e.remove());

  GM_registerMenuCommand('Dire bonjour', () => alert('Bonjour'));
})();
`;

function meta(code, key, def) {
  const re = new RegExp("^\\s*//\\s*@" + key + "\\s+(.*)$", "im");
  const m = code.match(re);
  return m ? m[1].trim() : def;
}

function allMatches(code) {
  const out = [];
  const re = /^\s*\/\/\s*@(?:match|include)\s+(.*)$/gim;
  let m;
  while ((m = re.exec(code))) out.push(m[1].trim());
  return out;
}

async function load() {
  try {
    const s = await browser.storage.local.get("userscripts");
    scripts = (s && s.userscripts) || [];
  } catch (e) { scripts = []; }
  render();
}

async function persist() {
  try { await browser.storage.local.set({ userscripts: scripts }); }
  catch (e) { $("#msg").textContent = "Echec de l'enregistrement."; }
}

function render() {
  const list = $("#list");
  if (!scripts.length) {
    list.innerHTML = `<div class="msg" style="padding:34px 16px;text-align:center;color:var(--dim)">
      Aucun script pour l'instant.<br>Appuyez sur « Nouveau script ».</div>`;
    return;
  }
  list.innerHTML = "";
  scripts.forEach((s, i) => {
    const name = meta(s.code, "name", "Sans titre");
    const ver = meta(s.code, "version", "");
    const targets = allMatches(s.code).join("  ") || "aucun motif";
    const row = document.createElement("div");
    row.className = "row";
    row.innerHTML = `
      <button class="sw ${s.enabled ? "on" : "off"}" data-i="${i}">${s.enabled ? "\u25C9" : "\u25CB"}</button>
      <div class="info">
        <div class="nm"></div>
        <div class="mt"></div>
      </div>
      <button data-edit="${i}">Modifier</button>`;
    row.querySelector(".nm").textContent = name + (ver ? "  v" + ver : "");
    row.querySelector(".mt").textContent = targets;
    list.appendChild(row);
  });

  list.querySelectorAll(".sw").forEach(b => {
    b.onclick = async () => {
      const i = +b.dataset.i;
      scripts[i].enabled = !scripts[i].enabled;
      await persist();
      render();
    };
  });
  list.querySelectorAll("[data-edit]").forEach(b => {
    b.onclick = () => openEditor(+b.dataset.edit);
  });
}

function openEditor(index) {
  editing = index;
  $("#code").value = index === null ? TEMPLATE : scripts[index].code;
  $("#editor").style.display = "block";
  $("#list").style.display = "none";
  $("#head").style.display = "none";
  $("#foot").style.display = "none";
  $("#delete").style.display = index === null ? "none" : "block";
  $("#msg").textContent = "";
  window.scrollTo(0, 0);
}

function closeEditor() {
  editing = null;
  $("#editor").style.display = "none";
  $("#list").style.display = "block";
  $("#head").style.display = "block";
  $("#foot").style.display = "block";
}

$("#new").onclick = () => openEditor(null);

$("#cancel").onclick = closeEditor;

$("#save").onclick = async () => {
  const code = $("#code").value;
  if (!code.trim()) { $("#msg").textContent = "Le script est vide."; return; }
  if (!/==UserScript==/.test(code)) {
    $("#msg").textContent = "En-tete ==UserScript== manquant : le script ne se declenchera nulle part.";
    return;
  }
  try {
    new Function(code.replace(/\/\/\s*==UserScript==[\s\S]*?==\/UserScript==/, ""));
  } catch (e) {
    $("#msg").textContent = "Erreur de syntaxe : " + e.message;
    return;
  }

  if (editing === null) {
    scripts.push({ id: "us_" + Date.now().toString(36), code, enabled: true });
  } else {
    scripts[editing].code = code;
  }
  await persist();
  closeEditor();
  render();
};

$("#delete").onclick = async () => {
  if (editing === null) return;
  const id = scripts[editing].id;
  scripts.splice(editing, 1);
  await persist();
  try { await browser.storage.local.remove("gmvalues:" + id); } catch (e) { }
  closeEditor();
  render();
};

$("#import").onclick = async () => {
  const url = prompt("URL du script (.user.js)");
  if (!url) return;
  try {
    const res = await browser.runtime.sendMessage({ type: "gmFetch", url, method: "GET" });
    if (!res || res.error || !res.body) { alert("Telechargement impossible."); return; }
    if (!/==UserScript==/.test(res.body)) {
      if (!confirm("Ce fichier n'a pas d'en-tete ==UserScript==. Ouvrir quand meme dans l'editeur ?")) return;
    }
    openEditor(null);
    $("#code").value = res.body;
  } catch (e) {
    alert("Telechargement impossible.");
  }
};

load();
