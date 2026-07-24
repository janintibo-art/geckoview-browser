"use strict";

// Enveloppe indispensable : search.html charge engines.js, categories.js et
// publishers.js comme scripts classiques, qui partagent la meme portee
// globale. Sans cette fonction, "const { ENGINES, ... }" redeclarerait des
// noms deja definis par engines.js, ce qui invalide tout le fichier.
(function () {

const { ENGINES, fetchDoc, fetchNews, instantAnswer, resolveBang, hostOf,
        activeEngines } = window.ENGINE_API;
const P = window.PUBLISHERS;
const C = window.CAT_API;

const $ = s => document.querySelector(s);
const out = $("#out"), foot = $("#foot"), qBox = $("#q");

let scope = "web";
let catState = {};
let filterSet = new Set();
let pageCfg = { hideSearch: true, cookies: true, clickbait: true, cleanurls: true,
                hideAll: false, autopager: true, autopagerMode: "auto", autopagerMax: 20 };
let showBadge = true;
let extra = [], allow = [];
let cookieCfg = { blockThirdParty: true, stripSent: true, clearOnExit: false };
let engineOn = {};
let searxUrl = "";
let engineTemplate = "internal";
let excludeOnce = [];
let excludeOwner = "";

// ---------------------------------------------------------------------------
//  Raccourcis
//  Pastilles coloree + initiale plutot que favicons : aucune requete vers
//  les sites concernes, donc aucune trace laissee en ouvrant l'accueil.
// ---------------------------------------------------------------------------
let shortcuts = [];

const DIAL_COLORS = [
  "#6fae5f", "#8ab4f8", "#d9c07c", "#d97757", "#a78bd0",
  "#5fb0ae", "#c98fb0", "#8fb36f", "#7f9ede", "#d0a05f"
];

function dialColor(text) {
  let h = 0;
  for (let i = 0; i < text.length; i++) h = (h * 31 + text.charCodeAt(i)) >>> 0;
  return DIAL_COLORS[h % DIAL_COLORS.length];
}

function dialLabel(sc) {
  const t = (sc.title || sc.url || "?").trim();
  // Une emoji fait un meilleur reperage qu'une lettre : on la garde telle quelle
  const first = Array.from(t)[0] || "?";
  return /[A-Za-z0-9]/.test(first) ? first.toUpperCase() : first;
}

async function loadShortcuts() {
  try {
    const s = await browser.storage.local.get("shortcuts");
    shortcuts = (s && s.shortcuts) || [];
  } catch (e) { shortcuts = []; }
  renderDial();
}

async function saveShortcuts() {
  try { await browser.storage.local.set({ shortcuts: shortcuts }); } catch (e) { }
  renderDial();
}

function renderDial() {
  const box = document.getElementById("dial");
  if (!box) return;

  const tiles = shortcuts.map((sc, i) => `
    <a class="sc" href="${esc(sc.url)}" data-i="${i}">
      <span class="ic" style="background:${esc(sc.color || dialColor(sc.url || ""))}"
        >${esc(sc.icon || dialLabel(sc))}</span>
      <span class="lb">${esc(sc.title || "")}</span>
    </a>`).join("");

  box.innerHTML = tiles + `
    <a class="sc add" href="#" id="sc-add">
      <span class="ic">+</span>
      <span class="lb">Ajouter</span>
    </a>`;

  box.querySelectorAll(".sc[data-i]").forEach(a => {
    // Appui long : modifier ou retirer, sans encombrer l'affichage
    a.addEventListener("contextmenu", e => {
      e.preventDefault();
      editShortcut(+a.dataset.i);
    });
  });

  const add = document.getElementById("sc-add");
  if (add) add.onclick = e => { e.preventDefault(); addShortcut(); };
}

function normalizeUrl(u) {
  u = (u || "").trim();
  if (!u) return "";
  if (!/^https?:\/\//i.test(u)) u = "https://" + u;
  try { return new URL(u).href; } catch (e) { return ""; }
}

function addShortcut() {
  const url = normalizeUrl(prompt("Adresse du raccourci", "https://"));
  if (!url) return;

  let suggested = "";
  try { suggested = new URL(url).hostname.replace(/^www\./, "").split(".")[0]; }
  catch (e) { }

  const title = (prompt("Nom affiche", suggested) || suggested).trim().slice(0, 18);
  const icon = (prompt("Lettre ou emoji (laisser vide pour l'initiale)", "") || "")
    .trim().slice(0, 2);

  shortcuts.push({ url, title, icon, color: dialColor(url) });
  saveShortcuts();
}

function editShortcut(i) {
  const sc = shortcuts[i];
  if (!sc) return;

  const choice = prompt(
    "« " + (sc.title || sc.url) + " »\n\n" +
    "1 = renommer\n2 = changer l'icone\n3 = deplacer a gauche\n" +
    "4 = deplacer a droite\n5 = supprimer", "1");

  if (choice === "1") {
    const t = prompt("Nouveau nom", sc.title || "");
    if (t !== null) sc.title = t.trim().slice(0, 18);
  } else if (choice === "2") {
    const ic = prompt("Lettre ou emoji (vide = initiale)", sc.icon || "");
    if (ic !== null) sc.icon = ic.trim().slice(0, 2);
  } else if (choice === "3" && i > 0) {
    shortcuts.splice(i - 1, 0, shortcuts.splice(i, 1)[0]);
  } else if (choice === "4" && i < shortcuts.length - 1) {
    shortcuts.splice(i + 1, 0, shortcuts.splice(i, 1)[0]);
  } else if (choice === "5") {
    if (!confirm("Retirer ce raccourci ?")) return;
    shortcuts.splice(i, 1);
  } else {
    return;
  }
  saveShortcuts();
}

// Les raccourcis suivent la page de marque : ils n'ont pas de sens
// lorsqu'un moteur externe prend la main.
function dialVisible(show) {
  const box = document.getElementById("dial");
  if (box) box.style.display = show ? "" : "none";
}

browser.storage.onChanged.addListener(changes => {
  if (changes.shortcuts) {
    shortcuts = changes.shortcuts.newValue || [];
    renderDial();
  }
});

// ---------------------------------------------------------------------------
//  Preferences
// ---------------------------------------------------------------------------
function renderCategories() {
  const box = $("#cats");
  box.innerHTML = "";
  C.CATEGORIES.forEach(cat => {
    const n = (C.CAT_DOMAINS[cat.id] || []).length;
    const row = document.createElement("label");
    row.innerHTML =
      `<input type="checkbox" data-cat="${cat.id}" ${catState[cat.id] ? "checked" : ""}>
       <span>${cat.name} <i class="count">${n}</i>${
         cat.warn ? `<em class="warn">${cat.warn}</em>` : ""}</span>`;
    box.appendChild(row);
  });
}

async function loadPrefs() {
  catState = await C.getCatState();
  await C.loadAllCategories();

  try {
    const s = await browser.storage.local.get(
      ["pageCfg", "pageExtra", "pageAllow", "showBadge", "cookieCfg",
       "engineOn", "searxUrl", "engineTemplate"]);
    if (s.pageCfg) pageCfg = Object.assign(pageCfg, s.pageCfg);
    extra = s.pageExtra || [];
    allow = s.pageAllow || [];
    showBadge = s.showBadge !== false;
    if (s.cookieCfg) cookieCfg = Object.assign(cookieCfg, s.cookieCfg);
    engineOn = s.engineOn || {};
    searxUrl = s.searxUrl || "";
    engineTemplate = s.engineTemplate || "internal";
  } catch (e) { }

  renderCategories();
  renderSources();
  showEngineBadge();
  $("#opt-searx").value = searxUrl;
  $("#opt-badge").checked      = showBadge;
  $("#opt-cookies").checked    = pageCfg.cookies;
  $("#opt-clickbait").checked  = pageCfg.clickbait;
  $("#opt-cleanurls").checked  = pageCfg.cleanurls;
  $("#opt-hidesearch").checked = pageCfg.hideSearch;
  $("#opt-autopager").checked = pageCfg.autopager !== false;
  $("#opt-apmax").value = pageCfg.autopagerMax || 20;
  const apm = document.querySelector(`#apmode input[value="${pageCfg.autopagerMode || "auto"}"]`);
  if (apm) apm.checked = true;
  $("#opt-creject").checked = pageCfg.cookieReject !== false;
  $("#opt-c3p").checked     = cookieCfg.blockThirdParty;
  $("#opt-csend").checked   = cookieCfg.stripSent;
  $("#opt-cexit").checked   = cookieCfg.clearOnExit;
  $("#opt-extra").value = extra.join("\n");
  $("#opt-allow").value = allow.join("\n");

  filterSet = await C.buildSet("search", catState, extra, allow);
  showStats();
}

// Rappelle quel moteur traite les recherches, quand ce n'est pas le notre
function showEngineBadge() {
  const bar = document.getElementById("engine-badge");
  const brand = document.getElementById("brand");

  if (engineTemplate === "internal") {
    if (bar) bar.hidden = true;
    if (brand) brand.style.display = "";
    dialVisible(true);
    return;
  }

  // Un autre moteur est actif : la page de marque n'a plus lieu d'etre.
  if (brand) brand.style.display = "none";
  dialVisible(false);
  if (!bar) return;
  let host = engineTemplate;
  try { host = new URL(engineTemplate.replace("%s", "x")).hostname.replace(/^www\./, ""); }
  catch (e) { }
  bar.hidden = false;
  bar.textContent = "Les recherches partent vers " + host +
    "  \u00B7  changez de moteur dans le menu";
}

function renderSources() {
  const box = $("#srcs");
  box.innerHTML = "";
  const all = ENGINES.concat([{ id: "searx", label: "SearXNG (instance ci-dessous)" }]);
  all.forEach(e => {
    const row = document.createElement("label");
    row.innerHTML =
      `<input type="checkbox" data-eng="${e.id}" ${engineOn[e.id] !== false ? "checked" : ""}>
       <span>${e.label}</span>`;
    box.appendChild(row);
  });
}

async function showStats() {
  try {
    const st = await browser.runtime.sendMessage({ type: "stats" });
    if (st) {
      $("#stats").textContent =
        `${st.adRules} regles publicitaires · ${st.navRules} domaines filtres · ` +
        `${st.blocked} elements bloques · ${st.consent || 0} bandeaux refuses`;
    }
  } catch (e) { }
}

async function savePrefs() {
  document.querySelectorAll("#cats input[data-cat]").forEach(cb => {
    catState[cb.dataset.cat] = cb.checked;
  });
  pageCfg.cookies    = $("#opt-cookies").checked;
  pageCfg.clickbait  = $("#opt-clickbait").checked;
  pageCfg.cleanurls  = $("#opt-cleanurls").checked;
  pageCfg.hideSearch = $("#opt-hidesearch").checked;
  pageCfg.autopager = $("#opt-autopager").checked;
  pageCfg.autopagerMax = Math.max(2, Math.min(200, parseInt($("#opt-apmax").value, 10) || 20));
  pageCfg.autopagerMode =
    (document.querySelector("#apmode input:checked") || {}).value || "auto";
  pageCfg.cookieReject = $("#opt-creject").checked;
  pageCfg.cookieClear  = $("#opt-cexit").checked;
  cookieCfg.blockThirdParty = $("#opt-c3p").checked;
  cookieCfg.stripSent       = $("#opt-csend").checked;
  cookieCfg.clearOnExit     = $("#opt-cexit").checked;
  showBadge = $("#opt-badge").checked;
  document.querySelectorAll("#srcs input[data-eng]").forEach(cb => {
    engineOn[cb.dataset.eng] = cb.checked;
  });
  searxUrl = $("#opt-searx").value.trim();
  extra = $("#opt-extra").value.split("\n").map(s => s.trim()).filter(Boolean);
  allow = $("#opt-allow").value.split("\n").map(s => s.trim()).filter(Boolean);

  await C.setCatState(catState);
  try {
    await browser.storage.local.set({
      pageCfg, pageExtra: extra, pageAllow: allow, showBadge, cookieCfg,
      engineOn, searxUrl
    });
  } catch (e) { }

  filterSet = await C.buildSet("search", catState, extra, allow);
  $("#prefs").hidden = true;
  if (qBox.value.trim()) run(qBox.value.trim());
}

// ---------------------------------------------------------------------------
//  Agregation
// ---------------------------------------------------------------------------
function normUrl(u) {
  try {
    const x = new URL(u);
    x.hash = "";
    ["utm_source","utm_medium","utm_campaign","utm_term","utm_content",
     "gclid","fbclid","ref","spm"].forEach(p => x.searchParams.delete(p));
    return x.toString().replace(/\/$/, "").replace(/^https?:\/\/(www\.)?/, "");
  } catch (e) { return u; }
}

async function gather(query) {
  const list = activeEngines(engineOn, searxUrl);
  if (!list.length) return [];
  const packs = await Promise.all(list.map(async eng => {
    try {
      const doc = await fetchDoc(eng.url(query));
      return eng.parse(doc).slice(0, 15).map((r, i) => ({ ...r, engine: eng.label, rank: i }));
    } catch (e) { return []; }
  }));
  return packs.flat();
}

function merge(raw) {
  const map = new Map();
  for (const r of raw) {
    if (!r.url || !r.title) continue;
    const key = normUrl(r.url);
    if (map.has(key)) {
      const ex = map.get(key);
      if (!ex.sources.includes(r.engine)) ex.sources.push(r.engine);
      ex.score += 1 / (r.rank + 2);
      if (r.snippet && r.snippet.length > ex.snippet.length) ex.snippet = r.snippet;
    } else {
      map.set(key, {
        url: r.url, title: r.title, snippet: r.snippet || "",
        host: hostOf(r.url), sources: [r.engine], score: 1 / (r.rank + 1)
      });
    }
  }
  const list = Array.from(map.values());
  const seen = {};
  list.forEach(r => {
    r.score += (r.sources.length - 1) * 0.6;
    seen[r.host] = (seen[r.host] || 0) + 1;
    if (seen[r.host] > 2) r.score -= 0.35 * (seen[r.host] - 2);
  });
  return list.sort((a, b) => b.score - a.score);
}

function applyFilter(list) {
  const kept = [], removed = [];
  const once = new Set(excludeOnce);
  for (const r of list) {
    // Exclusion demandee pour cette recherche seulement
    if (once.size && C.hostMatches(r.host, once)) {
      r.rule = "meme groupe";
      r.owner = P.ownerOf(r.host);
      removed.push(r);
      continue;
    }
    const hit = C.hostMatches(r.host, filterSet);
    r.owner = P.ownerOf(r.host);
    if (hit) { r.rule = hit; removed.push(r); }
    else kept.push(r);
  }
  return { kept, removed };
}

// ---------------------------------------------------------------------------
//  Rendu
// ---------------------------------------------------------------------------
function esc(s) {
  return (s || "").replace(/[&<>"]/g, c =>
    ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));
}

function renderResult(r) {
  const badge = (showBadge && r.owner) ? `<span class="owner">— ${esc(r.owner)}</span>` : "";
  return `<div class="res">
    <div class="host">${esc(r.host)} ${badge}</div>
    <a class="t" href="${esc(r.url)}">${esc(r.title)}</a>
    <p>${esc(r.snippet).slice(0, 240)}</p>
    <div class="srcs">${esc(r.sources.join(" · "))}</div>
  </div>`;
}

function renderFiltered(removed) {
  if (!removed.length) return "";
  const hosts = Array.from(new Set(removed.map(r => r.host)));
  const alts = P.ALTERNATIVES.slice(0, 5)
    .map(a => `<a href="https://${a.domain}">${esc(a.name)}</a>`).join("");
  return `<div class="filtered">
    <b>${removed.length} resultat(s) masque(s)</b> :
    ${esc(hosts.slice(0, 8).join(", "))}${hosts.length > 8 ? "…" : ""}
    <div class="alts">${alts}</div>
    <button id="reveal">Afficher quand meme</button>
  </div>`;
}

function renderCard(ia) {
  if (!ia) return "";
  return `<div class="card">
    <h2>${esc(ia.title)}</h2>
    <p>${esc(ia.extract).slice(0, 420)}</p>
    <p style="margin-top:8px"><a href="${esc(ia.url)}">Wikipedia</a></p>
  </div>`;
}

// ---------------------------------------------------------------------------
//  Execution
// ---------------------------------------------------------------------------
async function run(query) {
  const bang = resolveBang(query);
  if (bang) { location.href = bang; return; }

  history.replaceState(null, "", "?q=" + encodeURIComponent(query) + "&s=" + scope);
  const brand = $("#brand");
  if (brand) brand.style.display = "none";
  dialVisible(false);
  out.innerHTML = `<div class="msg"><span class="spin">◐</span> Interrogation des moteurs…</div>`;
  foot.textContent = "";

  const t0 = Date.now();
  const [raw, ia] = await Promise.all([
    scope === "news"
      ? fetchNews(query).then(x => x.map((r, i) => ({ ...r, rank: i })))
      : gather(query),
    scope === "web" ? instantAnswer(query) : Promise.resolve(null)
  ]);

  if (!raw.length) {
    out.innerHTML = `<div class="msg">Aucun resultat. Les moteurs sources ont pu
      limiter la requete — reessayez dans un instant.</div>`;
    return;
  }

  const merged = merge(raw);
  const { kept, removed } = applyFilter(merged);

  out.innerHTML = renderCard(ia) + renderFiltered(removed) +
    (kept.length ? kept.slice(0, 40).map(renderResult).join("")
                 : `<div class="msg">Tous les resultats ont ete filtres.</div>`);

  const btn = $("#reveal");
  if (btn) btn.onclick = () => {
    btn.closest(".filtered").insertAdjacentHTML("afterend", removed.map(renderResult).join(""));
    btn.remove();
  };

  const engines = new Set();
  merged.forEach(r => r.sources.forEach(s => engines.add(s)));
  foot.textContent = `${kept.length} resultats · ${removed.length} filtres · ` +
    `${Array.from(engines).join(", ")} · ${((Date.now() - t0) / 1000).toFixed(1)} s`;
}

// ---------------------------------------------------------------------------
//  Interface
// ---------------------------------------------------------------------------
$("#form").addEventListener("submit", e => {
  e.preventDefault();
  const v = qBox.value.trim();
  if (!v) return;
  qBox.blur();

  // Les raccourcis restent prioritaires, quel que soit le moteur
  const bang = resolveBang(v);
  if (bang) { location.href = bang; return; }

  if (engineTemplate && engineTemplate !== "internal") {
    location.href = engineTemplate.replace("%s", encodeURIComponent(v));
    return;
  }
  run(v);
});

document.querySelectorAll(".chip[data-scope]").forEach(c => {
  c.addEventListener("click", () => {
    document.querySelectorAll(".chip[data-scope]").forEach(x => x.classList.remove("on"));
    c.classList.add("on");
    scope = c.dataset.scope;
    if (qBox.value.trim()) run(qBox.value.trim());
  });
});

$("#prefs-btn").addEventListener("click", () => {
  $("#prefs").hidden = !$("#prefs").hidden;
  if (!$("#prefs").hidden) showStats();
});
$("#prefs-save").addEventListener("click", savePrefs);

// La page d'accueil etant deja search.html, un simple changement de fragment
// ne provoque pas de rechargement : on l'ecoute explicitement.
window.addEventListener("hashchange", () => {
  if (location.hash === "#filtres") {
    $("#prefs").hidden = false;
    showStats();
    window.scrollTo(0, 0);
  }
});

$("#purge-now").addEventListener("click", async e => {
  e.preventDefault();
  const btn = e.target;
  btn.textContent = "Purge en cours…";
  try {
    const r = await browser.runtime.sendMessage({ type: "purgeCookies" });
    btn.textContent = (r && r.removed)
      ? r.removed + " cookies supprimes"
      : "API cookies indisponible";
  } catch (err) {
    btn.textContent = "Echec de la purge";
  }
  setTimeout(() => { btn.textContent = "Purger les cookies maintenant"; }, 2600);
});

async function firstRun() {
  try {
    const s = await browser.storage.local.get("seenWelcome");
    if (s && s.seenWelcome) return;
    await browser.storage.local.set({ seenWelcome: true });
    out.innerHTML = `
      <div class="card welcome">
        <h2>Bienvenue</h2>
        <p>Cette page est votre moteur : il interroge plusieurs sources a la fois,
        fusionne les resultats et applique vos filtres editoriaux.</p>
        <p style="margin-top:9px">Le bouton <b>Filtres</b> ci-dessus regle les
        categories masquees et les sources interrogees. Le menu du navigateur,
        en haut a droite, donne acces au reste — analyse de page, scripts, styles,
        confidentialite.</p>
        <p style="margin-top:11px"><a href="help.html">Ouvrir le tutoriel</a></p>
      </div>`;
  } catch (e) { }
}

(async function init() {
  try {
    await loadPrefs();
  await loadShortcuts();
  } catch (e) {
    // Sans cela, une erreur de chargement laisse une page vide et muette.
    out.innerHTML = '<div class="msg">Erreur d\'initialisation : ' +
      String(e && e.message || e) + '</div>';
    $("#prefs").hidden = false;
    return;
  }
  document.querySelector(".chip[data-scope='web']").classList.add("on");
  const wantPrefs = location.hash === "#filtres" ||
                    new URLSearchParams(location.search).get("prefs") === "1";
  if (wantPrefs) { $("#prefs").hidden = false; showStats(); }
  const params = new URLSearchParams(location.search);

  const not = params.get("not");
  if (not) {
    excludeOnce = not.split(",").map(x => x.trim()).filter(Boolean);
    excludeOwner = params.get("owner") || "";
    const bar = $("#engine-badge");
    if (bar) {
      bar.hidden = false;
      bar.textContent = excludeOwner
        ? "Recherche hors " + excludeOwner + " \u00B7 " +
          excludeOnce.length + " domaine(s) ecarte(s)"
        : excludeOnce.length + " domaine(s) ecarte(s) de cette recherche";
    }
  }

  if (params.get("s")) {
    scope = params.get("s");
    document.querySelectorAll(".chip[data-scope]").forEach(x =>
      x.classList.toggle("on", x.dataset.scope === scope));
  }
  const q = params.get("q");
  if (q) { qBox.value = q; run(q); }
  else await firstRun();
})();

})();
