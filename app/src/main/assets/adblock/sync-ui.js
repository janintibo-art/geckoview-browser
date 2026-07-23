"use strict";

(function () {

  const $ = s => document.querySelector(s);
  const logBox = $("#log");

  let cfg = {
    owner: "", repo: "", branch: "main",
    path: "geckobrowser.json", token: "", autopull: false, raw: ""
  };

  // -------------------------------------------------------------------------
  function log(text, cls) {
    const line = document.createElement("div");
    if (cls) line.className = cls;
    line.textContent = text;
    logBox.appendChild(line);
    logBox.scrollTop = logBox.scrollHeight;
  }

  function clearLog() { logBox.innerHTML = ""; }

  // Encodage compatible accents : le base64 brut ne gere que l'octet
  function toBase64(str) {
    const bytes = new TextEncoder().encode(str);
    let bin = "";
    const step = 0x8000;
    for (let i = 0; i < bytes.length; i += step) {
      bin += String.fromCharCode.apply(null, bytes.subarray(i, i + step));
    }
    return btoa(bin);
  }

  function fromBase64(b64) {
    const bin = atob(b64.replace(/\s/g, ""));
    const bytes = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
    return new TextDecoder().decode(bytes);
  }

  // -------------------------------------------------------------------------
  //  Reglages
  // -------------------------------------------------------------------------
  async function loadCfg() {
    try {
      const s = await browser.storage.local.get("syncCfg");
      if (s && s.syncCfg) cfg = Object.assign(cfg, s.syncCfg);
    } catch (e) { }
    $("#owner").value = cfg.owner;
    $("#repo").value = cfg.repo;
    $("#branch").value = cfg.branch || "main";
    $("#path").value = cfg.path || "geckobrowser.json";
    $("#token").value = cfg.token;
    $("#autopull").checked = !!cfg.autopull;
    $("#raw").value = cfg.raw || "";
  }

  function readCfg() {
    cfg.owner = $("#owner").value.trim();
    cfg.repo = $("#repo").value.trim();
    cfg.branch = $("#branch").value.trim() || "main";
    cfg.path = $("#path").value.trim() || "geckobrowser.json";
    cfg.token = $("#token").value.trim();
    cfg.autopull = $("#autopull").checked;
    cfg.raw = $("#raw").value.trim();
  }

  async function saveCfg() {
    readCfg();
    try {
      await browser.storage.local.set({ syncCfg: cfg });
      log("Reglages enregistres sur cet appareil.", "ok");
    } catch (e) {
      log("Echec de l'enregistrement : " + e, "err");
    }
  }

  function ready() {
    readCfg();
    if (!cfg.owner || !cfg.repo || !cfg.token) {
      log("Compte, depot et jeton sont requis.", "err");
      return false;
    }
    return true;
  }

  // -------------------------------------------------------------------------
  //  Appels a l'API, relayes par l'extension pour eviter le CORS
  // -------------------------------------------------------------------------
  function apiUrl() {
    return "https://api.github.com/repos/" + encodeURIComponent(cfg.owner) +
           "/" + encodeURIComponent(cfg.repo) + "/contents/" +
           cfg.path.split("/").map(encodeURIComponent).join("/");
  }

  async function call(url, method, body) {
    const headers = {
      "Accept": "application/vnd.github+json",
      "X-GitHub-Api-Version": "2022-11-28"
    };
    if (cfg.token) headers["Authorization"] = "Bearer " + cfg.token;
    if (body) headers["Content-Type"] = "application/json";

    const res = await browser.runtime.sendMessage({
      type: "gmFetch", url: url, method: method || "GET",
      headers: headers, data: body ? JSON.stringify(body) : null
    });
    if (!res) return { error: "aucune reponse" };
    if (res.error) return { error: res.error };

    let parsed = null;
    try { parsed = JSON.parse(res.body); } catch (e) { }
    return { status: res.status, json: parsed, text: res.body };
  }

  async function remoteFile() {
    const r = await call(apiUrl() + "?ref=" + encodeURIComponent(cfg.branch));
    if (r.error) return { error: r.error };
    if (r.status === 404) return { absent: true };
    if (r.status === 401 || r.status === 403) {
      return { error: "acces refuse (jeton invalide ou droits insuffisants)" };
    }
    if (!r.json || !r.json.content) {
      return { error: "reponse inattendue (" + r.status + ")" };
    }
    let snapshot = null;
    try { snapshot = JSON.parse(fromBase64(r.json.content)); }
    catch (e) { return { error: "fichier distant illisible" }; }
    return { snapshot: snapshot, sha: r.json.sha };
  }

  // -------------------------------------------------------------------------
  //  Actions
  // -------------------------------------------------------------------------
  async function snapshot() {
    return await browser.runtime.sendMessage({ type: "syncSnapshot" });
  }

  function describe(snap) {
    const d = (snap && snap.data) || {};
    const n = k => Array.isArray(d[k]) ? d[k].length : (d[k] ? 1 : 0);
    return n("userscripts") + " script(s), " + n("userStyles") + " style(s), " +
           n("bookmarks") + " favori(s), " + n("pageExtra") + " domaine(s) ajoute(s)";
  }

  async function push() {
    clearLog();
    if (!ready()) return;
    await saveCfg();

    log("Preparation de l'instantane…");
    const snap = await snapshot();
    if (!snap) { log("Instantane impossible.", "err"); return; }
    log("Contenu : " + describe(snap));

    log("Lecture de la version distante…");
    const cur = await remoteFile();
    if (cur.error) { log(cur.error, "err"); return; }
    if (cur.snapshot && cur.snapshot.updated > snap.updated) {
      if (!confirm("La version distante est plus recente (" +
          cur.snapshot.updated + "). Ecraser quand meme ?")) {
        log("Envoi annule.", "err");
        return;
      }
    }

    const body = {
      message: "Reglages GeckoBrowser — " + new Date().toLocaleString("fr-FR"),
      content: toBase64(JSON.stringify(snap, null, 2)),
      branch: cfg.branch
    };
    if (cur.sha) body.sha = cur.sha;

    const r = await call(apiUrl(), "PUT", body);
    if (r.error) { log(r.error, "err"); return; }
    if (r.status === 200 || r.status === 201) {
      log("Envoye dans " + cfg.owner + "/" + cfg.repo + "/" + cfg.path, "ok");
    } else {
      log("Echec (" + r.status + ") : " +
          ((r.json && r.json.message) || r.text || "").slice(0, 160), "err");
    }
  }

  async function pull(fromRaw) {
    clearLog();
    readCfg();

    let snap = null;
    if (fromRaw) {
      if (!cfg.raw) { log("Indiquez une adresse.", "err"); return; }
      await saveCfg();
      log("Telechargement…");
      const res = await browser.runtime.sendMessage({
        type: "gmFetch", url: cfg.raw, method: "GET"
      });
      if (!res || res.error || !res.body) { log("Telechargement impossible.", "err"); return; }
      try { snap = JSON.parse(res.body); }
      catch (e) { log("Fichier illisible.", "err"); return; }
    } else {
      if (!ready()) return;
      await saveCfg();
      log("Lecture du depot…");
      const cur = await remoteFile();
      if (cur.error) { log(cur.error, "err"); return; }
      if (cur.absent) { log("Aucun fichier a cet emplacement.", "err"); return; }
      snap = cur.snapshot;
    }

    if (!snap || !snap.data) { log("Instantane invalide.", "err"); return; }
    log("Version distante du " + (snap.updated || "?"));
    log("Contenu : " + describe(snap));

    if (!confirm("Remplacer vos reglages locaux par cette version ?\n\n" +
                 describe(snap))) {
      log("Restauration annulee.", "err");
      return;
    }

    const r = await browser.runtime.sendMessage({ type: "syncApply", snapshot: snap });
    if (!r || r.error) { log("Echec : " + ((r && r.error) || "inconnu"), "err"); return; }
    log(r.keys + " ensemble(s) de reglages restaures.", "ok");
    log("Rechargez les pages ouvertes pour voir le resultat.");
  }

  async function check() {
    clearLog();
    if (!ready()) return;
    const snap = await snapshot();
    log("Local  : " + describe(snap));
    const cur = await remoteFile();
    if (cur.error) { log(cur.error, "err"); return; }
    if (cur.absent) { log("Distant : aucun fichier.", "err"); return; }
    log("Distant : " + describe(cur.snapshot));
    log("Date distante : " + (cur.snapshot.updated || "?"));
    const newer = cur.snapshot.updated > snap.updated;
    log(newer ? "La version distante est plus recente."
              : "Votre version locale est au moins aussi recente.",
        newer ? "err" : "ok");
  }

  // -------------------------------------------------------------------------
  $("#push").onclick = push;
  $("#pull").onclick = () => pull(false);
  $("#pullraw").onclick = () => pull(true);
  $("#check").onclick = check;
  $("#save").onclick = () => { clearLog(); saveCfg(); };

  loadCfg();
})();
