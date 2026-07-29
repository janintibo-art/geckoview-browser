"use strict";

// ===========================================================================
//  player.js -- ameliorations du lecteur video de la page.
//
//  N'impose pas un lecteur maison : agit sur l'element <video> existant.
//    - gestes plein ecran : double-tape gauche/droite pour reculer/avancer
//      de 10 s, glissement vertical pour le volume ;
//    - vitesse de lecture reglable, memorisee par site ;
//    - bascule des sous-titres.
//
//  Un reglage general (cle playerGestures) permet de tout desactiver pour
//  les gens que ces gestes genent.
// ===========================================================================

(function () {

  // Ce script agit sur l'element <video>, souvent place dans une iframe de
  // lecteur : il doit donc s'executer aussi dans les cadres. Seule contrainte,
  // le retour visuel se place dans le cadre courant.

  let enabled = true;
  let playerActed = false;
  let lastVideo = null;
  let overlay = null;
  let hideTimer = null;

  // ----- preferences -------------------------------------------------------
  try {
    browser.storage.local.get(["playerGestures", "playerSpeed", "playerResume"]).then(s => {
      if (s && s.playerGestures === false) enabled = false;
      const host = location.hostname.replace(/^www\./, "");
      const map = (s && s.playerSpeed) || {};
      pendingSpeed = map[host];
      resumeMap = (s && s.playerResume) || {};
    });
  } catch (e) { }
  let pendingSpeed = undefined;
  let resumeMap = {};

  // Cle de reprise : l'URL de la page suffit dans l'immense majorite des cas
  // (une video par page). On tronque pour ne pas gonfler le stockage.
  function resumeKey() {
    return (location.origin + location.pathname).slice(0, 180);
  }

  function saveResume(v) {
    if (!v || !isFinite(v.duration) || v.duration < 60) return; // pas les clips courts
    try {
      const key = resumeKey();
      const pos = v.currentTime;
      // Fin de video : on efface, la prochaine ouverture repart du debut.
      if (pos < 5 || pos > v.duration - 15) delete resumeMap[key];
      else resumeMap[key] = { t: Math.floor(pos), d: Math.floor(v.duration), at: Date.now() };
      pruneResume();
      browser.storage.local.set({ playerResume: resumeMap });
    } catch (e) { }
  }

  // Borne memoire : on garde les 60 positions les plus recentes.
  function pruneResume() {
    const keys = Object.keys(resumeMap);
    if (keys.length <= 60) return;
    keys.sort((a, b) => (resumeMap[a].at || 0) - (resumeMap[b].at || 0));
    for (let i = 0; i < keys.length - 60; i++) delete resumeMap[keys[i]];
  }

  function offerResume(v) {
    try {
      const r = resumeMap[resumeKey()];
      if (!r || !r.t) return;
      if (!isFinite(v.duration) || Math.abs(v.duration - r.d) > 5) return; // pas la meme video
      if (v.currentTime > 5) return; // deja en cours
      const mn = Math.floor(r.t / 60), sc = r.t % 60;
      flash("Reprise a " + mn + ":" + (sc < 10 ? "0" : "") + sc);
      try { v.currentTime = r.t; } catch (e) { }
    } catch (e) { }
  }

  function saveSpeed(rate) {
    try {
      browser.storage.local.get("playerSpeed").then(s => {
        const map = (s && s.playerSpeed) || {};
        const host = location.hostname.replace(/^www\./, "");
        if (rate === 1) delete map[host]; else map[host] = rate;
        browser.storage.local.set({ playerSpeed: map });
      });
    } catch (e) { }
  }

  // ----- reperage de la video active ---------------------------------------
  function remember(e) {
    const n = e && e.target;
    if (n && n.tagName === "VIDEO") {
      lastVideo = n;
      if (pendingSpeed && n.playbackRate === 1) {
        try { n.playbackRate = pendingSpeed; } catch (err) { }
      }
      // Reprise : proposee une seule fois par element video.
      if (!n.__gbResumeChecked) {
        n.__gbResumeChecked = true;
        offerResume(n);
        // Sauvegarde reguliere de la position pendant la lecture.
        n.addEventListener("timeupdate", () => {
          const now = Date.now();
          if (now - (n.__gbLastSave || 0) > 5000) {
            n.__gbLastSave = now;
            saveResume(n);
          }
        });
        n.addEventListener("pause", () => saveResume(n));
        n.addEventListener("ended", () => {
          saveResume(n);
          // Signaler la fin pour enchainer la file d'attente. Seul le cadre
          // principal l'envoie, et seulement pour une video de duree reelle
          // (evite les pubs/preroll de quelques secondes).
          if (window.top === window.self && isFinite(n.duration) && n.duration > 30) {
            try { browser.runtime.sendMessage({ type: "mediaEnded" }); } catch (e) { }
          }
        });
      }
    }
  }

  function activeVideo() {
    if (lastVideo && document.contains(lastVideo)) return lastVideo;
    const vids = Array.from(document.querySelectorAll("video"));
    const playing = vids.filter(v => !v.paused && !v.ended);
    const list = playing.length ? playing : vids;
    return list.sort((a, b) =>
      (b.clientWidth * b.clientHeight) - (a.clientWidth * a.clientHeight))[0] || null;
  }

  // ----- retour visuel bref ------------------------------------------------
  function flash(text) {
    const fs = document.fullscreenElement;
    const host = fs || document.body;
    if (!host) return;
    if (!overlay || overlay.parentNode !== host) {
      if (overlay) overlay.remove();
      overlay = document.createElement("div");
      overlay.style.cssText =
        "position:fixed;top:50%;left:50%;transform:translate(-50%,-50%);" +
        "z-index:2147483647;background:rgba(20,22,26,.85);color:#fff;" +
        "padding:12px 18px;border-radius:12px;font:600 15px/1 " +
        "-apple-system,Roboto,sans-serif;pointer-events:none;transition:opacity .2s";
      host.appendChild(overlay);
    }
    overlay.textContent = text;
    overlay.style.opacity = "1";
    clearTimeout(hideTimer);
    hideTimer = setTimeout(() => { if (overlay) overlay.style.opacity = "0"; }, 650);
  }

  // ----- gestes ------------------------------------------------------------
  let touchStartX = 0, touchStartY = 0, touchStartVol = 1, gesture = null;
  let lastTap = 0, lastTapX = 0;

  function onTouchStart(e) {
    if (!enabled || !document.fullscreenElement) return;
    const v = activeVideo();
    if (!v || e.touches.length !== 1) return;
    const t = e.touches[0];
    touchStartX = t.clientX;
    touchStartY = t.clientY;
    touchStartVol = v.volume;
    gesture = null;

    const now = Date.now();
    if (now - lastTap < 300 && Math.abs(t.clientX - lastTapX) < 80) {
      // Double-tape : saut de 10 s selon le cote touche.
      const half = window.innerWidth / 2;
      if (t.clientX < half) { v.currentTime = Math.max(0, v.currentTime - 10); flash("\u21BA 10 s"); }
      else { v.currentTime = v.currentTime + 10; flash("10 s \u21BB"); }
      lastTap = 0;
      e.preventDefault();
      return;
    }
    lastTap = now;
    lastTapX = t.clientX;
  }

  function onTouchMove(e) {
    if (!enabled || !document.fullscreenElement || e.touches.length !== 1) return;
    const v = activeVideo();
    if (!v) return;
    const t = e.touches[0];
    const dx = t.clientX - touchStartX;
    const dy = t.clientY - touchStartY;
    if (gesture === null) {
      if (Math.abs(dy) > 24 && Math.abs(dy) > Math.abs(dx)) gesture = "volume";
      else if (Math.abs(dx) > 40) gesture = "seek";
      else return;
    }
    if (gesture === "volume") {
      const range = window.innerHeight * 0.6;
      let vol = touchStartVol - dy / range;
      vol = Math.max(0, Math.min(1, vol));
      try { v.volume = vol; v.muted = vol === 0; } catch (err) { }
      flash("Volume " + Math.round(vol * 100) + " %");
      e.preventDefault();
    }
  }

  function onTouchEnd(e) {
    if (gesture === "seek") {
      const v = activeVideo();
      if (v) {
        const dx = (e.changedTouches[0] || {}).clientX - touchStartX;
        const delta = Math.round(dx / window.innerWidth * 60);
        if (delta) {
          v.currentTime = Math.max(0, v.currentTime + delta);
          flash((delta > 0 ? "+" : "") + delta + " s");
        }
      }
    }
    gesture = null;
  }

  // ----- commandes du menu -------------------------------------------------
  const SPEEDS = [0.5, 0.75, 1, 1.25, 1.5, 1.75, 2];

  function cycleSpeed() {
    const v = activeVideo();
    if (!v) { alert("Aucune video en cours."); return; }
    let i = SPEEDS.indexOf(v.playbackRate);
    if (i === -1) i = SPEEDS.indexOf(1);
    i = (i + 1) % SPEEDS.length;
    const rate = SPEEDS[i];
    try { v.playbackRate = rate; } catch (err) { }
    saveSpeed(rate);
    flash("Vitesse " + rate + "\u00d7");
  }

  // ----- telechargement des sous-titres ------------------------------------
  function subtitleTracks(v) {
    const out = [];
    if (!v) return out;
    // 1. Elements <track> du DOM : ils portent souvent une URL .vtt directe.
    v.querySelectorAll("track").forEach(tr => {
      if (tr.kind === "subtitles" || tr.kind === "captions" || !tr.kind) {
        out.push({
          label: tr.label || tr.srclang || "sous-titres",
          lang: tr.srclang || "",
          url: tr.src || "",
          track: tr.track || null
        });
      }
    });
    // 2. Pistes textTracks sans <track> associe (chargees par le lecteur) :
    //    pas d'URL, mais on pourra reconstruire depuis les cues.
    for (let i = 0; i < v.textTracks.length; i++) {
      const t = v.textTracks[i];
      if ((t.kind === "subtitles" || t.kind === "captions") &&
          !out.some(o => o.track === t)) {
        out.push({ label: t.label || t.language || "sous-titres",
                   lang: t.language || "", url: "", track: t });
      }
    }
    return out;
  }

  function timeVtt(sec) {
    const h = Math.floor(sec / 3600);
    const m = Math.floor((sec % 3600) / 60);
    const s = Math.floor(sec % 60);
    const ms = Math.floor((sec - Math.floor(sec)) * 1000);
    const p = (n, l) => String(n).padStart(l, "0");
    return p(h, 2) + ":" + p(m, 2) + ":" + p(s, 2) + "." + p(ms, 3);
  }

  // Reconstruit un fichier VTT a partir des cues deja charges en memoire.
  function cuesToVtt(track) {
    if (!track) return "";
    // Forcer le chargement des cues si la piste etait masquee.
    const prevMode = track.mode;
    if (track.mode === "disabled") track.mode = "hidden";
    const cues = track.cues;
    if (!cues || !cues.length) { track.mode = prevMode; return ""; }
    let out = "WEBVTT\n\n";
    for (let i = 0; i < cues.length; i++) {
      const c = cues[i];
      out += timeVtt(c.startTime) + " --> " + timeVtt(c.endTime) + "\n";
      out += (c.text || "") + "\n\n";
    }
    track.mode = prevMode;
    return out;
  }

  async function downloadSubtitles() {
    const v = activeVideo();
    const tracks = subtitleTracks(v);
    if (!tracks.length) {
      alert("Aucun sous-titre disponible sur cette video.");
      return;
    }
    // S'il y a plusieurs langues, demander laquelle (choix simple).
    let choice = tracks[0];
    if (tracks.length > 1) {
      const labels = tracks.map((t, i) => (i + 1) + ". " + t.label).join("\n");
      const pick = prompt("Quel sous-titre telecharger ?\n" + labels + "\n\nNumero :", "1");
      const idx = parseInt(pick, 10) - 1;
      if (isNaN(idx) || idx < 0 || idx >= tracks.length) return;
      choice = tracks[idx];
    }

    const base = (document.title || location.hostname).slice(0, 80)
                 + (choice.lang ? "." + choice.lang : "");

    // Cas 1 : URL directe -> l'app telecharge le fichier.
    if (choice.url && /^https?:/i.test(choice.url)) {
      try {
        await browser.runtime.sendMessage({
          type: "downloadUrls", urls: [choice.url], referer: location.href
        });
        flash("Sous-titres telecharges");
        return;
      } catch (e) { /* on tente la reconstruction */ }
    }

    // Cas 2 : reconstruction depuis les cues, puis enregistrement du texte.
    const vtt = cuesToVtt(choice.track);
    if (!vtt) {
      alert("Ces sous-titres ne sont pas encore charges. Affichez-les quelques "
            + "secondes puis reessayez.");
      return;
    }
    try {
      await browser.runtime.sendMessage({
        type: "downloadText", name: base + ".vtt", text: vtt
      });
      flash("Sous-titres enregistres");
    } catch (e) {
      alert("Echec de l'enregistrement des sous-titres.");
    }
  }

  function toggleCaptions() {
    const v = activeVideo();
    if (!v || !v.textTracks || !v.textTracks.length) {
      alert("Aucun sous-titre disponible sur cette video.");
      return;
    }
    const tracks = [];
    for (let i = 0; i < v.textTracks.length; i++) {
      const tr = v.textTracks[i];
      if (tr.kind === "subtitles" || tr.kind === "captions") tracks.push(tr);
    }
    if (!tracks.length) {
      alert("Aucun sous-titre disponible sur cette video.");
      return;
    }
    const anyShown = tracks.some(tr => tr.mode === "showing");
    if (anyShown) {
      tracks.forEach(tr => { tr.mode = "hidden"; });
      flash("Sous-titres masques");
    } else {
      tracks[0].mode = "showing";
      flash("Sous-titres affiches");
    }
  }

  document.addEventListener("play", remember, true);
  document.addEventListener("loadedmetadata", remember, true);
  document.addEventListener("touchstart", onTouchStart, { capture: true, passive: false });
  document.addEventListener("touchmove", onTouchMove, { capture: true, passive: false });
  document.addEventListener("touchend", onTouchEnd, true);

  // Routage des commandes.
  //
  // GB.foreground() est vrai dans TOUTES les iframes de l'onglet actif : si
  // chaque cadre reagissait a la commande, la vitesse changerait sur
  // plusieurs videos a la fois. Seul le cadre principal ecoute donc la
  // commande, puis la relaie a ses sous-cadres ; chaque cadre applique
  // l'action sur sa propre video, mais l'ordre ne part qu'une fois.
  function applyCommand(cmd) {
    if (cmd === "playerSpeed") cycleSpeed();
    if (cmd === "playerCaptions") toggleCaptions();
    if (cmd === "playerSubtitles") downloadSubtitles();
    if (cmd === "playerGestures") {
      enabled = !enabled;
      try { browser.storage.local.set({ playerGestures: enabled }); } catch (e) { }
      flash(enabled ? "Gestes actives" : "Gestes desactives");
    }
  }

  function hasActiveVideo() {
    const v = activeVideo();
    return !!(v && !v.paused);
  }

  if (window.top === window.self) {
    browser.storage.onChanged.addListener(changes => {
      if (changes.playerGestures) enabled = changes.playerGestures.newValue !== false;
      const c = changes.pageCommand && changes.pageCommand.newValue;
      if (!c || !GB.foreground()) return;
      if (c.cmd === "playerGestures") { applyCommand(c.cmd); return; }
      if (c.cmd !== "playerSpeed" && c.cmd !== "playerCaptions") return;
      // Le cadre principal a-t-il lui-meme une video active ? sinon, relayer.
      if (hasActiveVideo()) { applyCommand(c.cmd); return; }
      playerActed = false;
      const frames = window.frames;
      for (let i = 0; i < frames.length; i++) {
        try { frames[i].postMessage({ __gbPlayer: c.cmd }, "*"); } catch (e) { }
      }
      // Filet : si aucune iframe ne repond, agir quand meme localement.
      setTimeout(() => { if (!playerActed) applyCommand(c.cmd); }, 120);
    });
  } else {
    browser.storage.onChanged.addListener(changes => {
      if (changes.playerGestures) enabled = changes.playerGestures.newValue !== false;
    });
    window.addEventListener("message", e => {
      const cmd = e.data && e.data.__gbPlayer;
      if (!cmd) return;
      if (hasActiveVideo()) {
        applyCommand(cmd);
        try { e.source.postMessage({ __gbPlayerAck: true }, "*"); } catch (err) { }
      }
    });
  }

  window.addEventListener("message", e => {
    if (e.data && e.data.__gbPlayerAck) playerActed = true;
  });

})();
