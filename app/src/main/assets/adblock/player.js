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
    browser.storage.local.get(["playerGestures", "playerSpeed"]).then(s => {
      if (s && s.playerGestures === false) enabled = false;
      const host = location.hostname.replace(/^www\./, "");
      const map = (s && s.playerSpeed) || {};
      pendingSpeed = map[host];
    });
  } catch (e) { }
  let pendingSpeed = undefined;

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
