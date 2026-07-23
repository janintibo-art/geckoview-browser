"use strict";

const { ENGINES, fetchDoc, fetchNews, instantAnswer, resolveBang, hostOf, clean } = window.ENGINE_API;
const P = window.PUBLISHERS;

const $ = sel => document.querySelector(sel);
const out = $("#out"), foot = $("#foot"), qBox = $("#q");

let scope = "web";
let prefs = {
  blockBollore: true,
  blockFarRight: true,
  showBadge: true,
  navBlock: false,
  extra: [],
  allow: []
};

// ---------------------------------------------------------------------------
//  Preferences
// ---------------------------------------------------------------------------
async function loadPrefs() {
  try {
    const s = await browser.storage.local.get("searchPrefs");
    if (s && s.searchPrefs) prefs = Object.assign(prefs, s.searchPrefs);
  } catch (e) { /* stockage indisponible */ }
  $("#opt-bollore").checked  = prefs.blockBollore;
  $("#opt-farright").checked = prefs.blockFarRight;
  $("#opt-badge").checked    = prefs.showBadge;
  $("#opt-navblock").checked = prefs.navBlock;
  $("#opt-extra").value      = (prefs.extra || []).join("\n");
  $("#opt-allow").value      = (prefs.allow || []).join("\n");
}

async function savePrefs() {
  prefs.blockBollore  = $("#opt-bollore").checked;
  prefs.blockFarRight = $("#opt-farright").checked;
  prefs.showBadge     = $("#opt-badge").checked;
  prefs.navBlock      = $("#opt-navblock").checked;
  prefs.extra = $("#opt-extra").value.split("\n").map(s => s.trim()).filter(Boolean);
  prefs.allow = $("#opt-allow").value.split("\n").map(s => s.trim()).filter(Boolean);
  try {
    await browser.storage.local.set({ searchPrefs: prefs });
    // Transmet la liste au bloqueur reseau si le blocage navigation est actif.
    await browser.storage.local.set({
      navBlockList: prefs.navBlock ? Array.from(P.buildBlockSet(prefs)) : []
    });
  } catch (e) { /* ignore */ }
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
    let s = x.toString();
    return s.replace(/\/$/, "").replace(/^https?:\/\/(www\.)?/, "");
  } catch (e) { return u; }
}

async function gather(query) {
  const jobs = ENGINES.map(async eng => {
    try {
      const doc = await fetchDoc(eng.url(query));
      return eng.parse(doc).slice(0, 15).map((r, i) => ({
        ...r, engine: eng.label, rank: i
      }));
    } catch (e) {
      return [];
    }
  });
  const packs = await Promise.all(jobs);
  return packs.flat();
}

function merge(raw) {
  const byKey = new Map();
  for (const r of raw) {
    if (!r.url || !r.title) continue;
    const key = normUrl(r.url);
    if (byKey.has(key)) {
      const ex = byKey.get(key);
      if (!ex.sources.includes(r.engine)) ex.sources.push(r.engine);
      ex.score += 1 / (r.rank + 2);
      if (r.snippet && r.snippet.length > (ex.snippet || "").length) ex.snippet = r.snippet;
    } else {
      byKey.set(key, {
        url: r.url,
        title: r.title,
        snippet: r.snippet || "",
        host: hostOf(r.url),
        sources: [r.engine],
        score: 1 / (r.rank + 1)
      });
    }
  }

  const list = Array.from(byKey.values());
  // Bonus d'accord inter-moteurs, malus de sur-representation d'un domaine.
  const seen = {};
  list.forEach(r => {
    r.score += (r.sources.length - 1) * 0.6;
    seen[r.host] = (seen[r.host] || 0) + 1;
    if (seen[r.host] > 2) r.score -= 0.35 * (seen[r.host] - 2);
  });
  return list.sort((a, b) => b.score - a.score);
}

function applyFilter(list) {
  const blockSet = P.buildBlockSet(prefs);
  const kept = [], removed = [];
  for (const r of list) {
    const hit = P.domainMatches(r.host, blockSet);
    if (hit) {
      r.blockedBy = hit;
      r.owner = P.OWNERSHIP[hit] || "classe extreme droite";
      removed.push(r);
    } else {
      r.owner = P.OWNERSHIP[P.domainMatches(r.host, new Set(P.BOLLORE_DOMAINS))] || null;
      kept.push(r);
    }
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
  const badge = (prefs.showBadge && r.owner)
    ? `<span class="owner">— ${esc(r.owner)}</span>` : "";
  return `<div class="res">
    <div class="host">${esc(r.host)} ${badge}</div>
    <a class="t" href="${esc(r.url)}">${esc(r.title)}</a>
    <p>${esc(r.snippet).slice(0, 240)}</p>
    <div class="srcs">${esc(r.sources.join(" · "))}</div>
  </div>`;
}

function renderFiltered(removed) {
  if (!removed.length) return "";
  const byOwner = {};
  removed.forEach(r => {
    byOwner[r.owner] = byOwner[r.owner] || new Set();
    byOwner[r.owner].add(r.host);
  });
  const lines = Object.entries(byOwner)
    .map(([owner, hosts]) => `<li>${esc(Array.from(hosts).join(", "))} <i>(${esc(owner)})</i></li>`)
    .join("");
  const alts = P.ALTERNATIVES.slice(0, 5)
    .map(a => `<a href="https://${a.domain}">${esc(a.name)}</a>`).join("");
  return `<div class="filtered">
    <b>${removed.length} resultat(s) masque(s)</b> par vos filtres :
    <ul>${lines}</ul>
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
  if (btn) {
    btn.onclick = () => {
      btn.closest(".filtered").insertAdjacentHTML(
        "afterend", removed.map(renderResult).join(""));
      btn.remove();
    };
  }

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
  if (v) { qBox.blur(); run(v); }
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
});
$("#prefs-save").addEventListener("click", savePrefs);

(async function init() {
  await loadPrefs();
  document.querySelector(".chip[data-scope='web']").classList.add("on");
  const params = new URLSearchParams(location.search);
  const q = params.get("q");
  if (params.get("s")) {
    scope = params.get("s");
    document.querySelectorAll(".chip[data-scope]").forEach(x =>
      x.classList.toggle("on", x.dataset.scope === scope));
  }
  if (q) { qBox.value = q; run(q); }
})();
