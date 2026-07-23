"use strict";

// ===========================================================================
//  fingerprint.js -- ce qu'un site peut lire de votre navigateur.
//
//  Volontairement execute dans le contexte d'une page web ordinaire, et non
//  dans une page de l'extension : les protections anti-empreinte ne
//  s'appliquent pas de la meme facon aux pages privilegiees. Seul ce contexte
//  reflete ce qu'un site observe reellement.
// ===========================================================================

(function () {

  if (window.top !== window.self) return;

  let panel = null;

  // -------------------------------------------------------------------------
  //  Outils
  // -------------------------------------------------------------------------
  function hash(str) {
    let h = 0x811c9dc5;
    for (let i = 0; i < str.length; i++) {
      h ^= str.charCodeAt(i);
      h = (h * 0x01000193) >>> 0;
    }
    return h.toString(16).padStart(8, "0");
  }

  function esc(s) {
    return String(s == null ? "" : s)
      .replace(/&/g, "&amp;").replace(/</g, "&lt;")
      .replace(/>/g, "&gt;").replace(/"/g, "&quot;");
  }

  const safe = fn => { try { return fn(); } catch (e) { return "(refuse)"; } };

  // -------------------------------------------------------------------------
  //  Mesures
  // -------------------------------------------------------------------------
  function canvasPrint() {
    try {
      const c = document.createElement("canvas");
      c.width = 240; c.height = 60;
      const x = c.getContext("2d");
      if (!x) return { value: "indisponible", note: "contexte 2D refuse" };
      x.textBaseline = "top";
      x.font = "15px 'Arial'";
      x.fillStyle = "#f60";
      x.fillRect(10, 5, 90, 25);
      x.fillStyle = "#069";
      x.fillText("Empreinte 0123", 4, 8);
      x.fillStyle = "rgba(102,204,0,.7)";
      x.fillText("Empreinte 0123", 6, 20);
      const data = c.toDataURL();
      // Une image quasi vide signale un canvas neutralise
      const uniform = data.length < 2200;
      return {
        value: hash(data),
        note: uniform ? "rendu uniformise" : "rendu specifique a l'appareil",
        risky: !uniform
      };
    } catch (e) {
      return { value: "bloque", note: "lecture refusee" };
    }
  }

  function webglPrint() {
    try {
      const c = document.createElement("canvas");
      const gl = c.getContext("webgl") || c.getContext("experimental-webgl");
      if (!gl) return { value: "desactive", note: "WebGL indisponible" };

      const dbg = gl.getExtension("WEBGL_debug_renderer_info");
      const vendor = dbg ? gl.getParameter(dbg.UNMASKED_VENDOR_WEBGL) : gl.getParameter(gl.VENDOR);
      const renderer = dbg ? gl.getParameter(dbg.UNMASKED_RENDERER_WEBGL) : gl.getParameter(gl.RENDERER);

      const params = [
        gl.getParameter(gl.MAX_TEXTURE_SIZE),
        gl.getParameter(gl.MAX_RENDERBUFFER_SIZE),
        gl.getParameter(gl.MAX_VERTEX_ATTRIBS),
        (gl.getSupportedExtensions() || []).join(",")
      ].join("|");

      return {
        value: hash(vendor + renderer + params),
        note: renderer && String(renderer).length > 4
          ? "materiel expose : " + renderer
          : "materiel masque",
        risky: !!(renderer && String(renderer).length > 4 && !/generic|mask/i.test(renderer))
      };
    } catch (e) {
      return { value: "bloque", note: "acces refuse" };
    }
  }

  async function audioPrint() {
    try {
      const Ctx = window.OfflineAudioContext || window.webkitOfflineAudioContext;
      if (!Ctx) return { value: "desactive", note: "AudioContext indisponible" };
      const ctx = new Ctx(1, 44100, 44100);
      const osc = ctx.createOscillator();
      osc.type = "triangle";
      osc.frequency.value = 10000;
      const comp = ctx.createDynamicsCompressor();
      osc.connect(comp);
      comp.connect(ctx.destination);
      osc.start(0);
      const buf = await ctx.startRendering();
      const data = buf.getChannelData(0).slice(4500, 5000);
      let sum = 0;
      for (let i = 0; i < data.length; i++) sum += Math.abs(data[i]);
      return {
        value: hash(String(sum)),
        note: "signature du moteur audio",
        risky: true
      };
    } catch (e) {
      return { value: "desactive", note: "rendu audio indisponible" };
    }
  }

  function fontPrint() {
    const test = [
      "Arial", "Verdana", "Helvetica", "Times New Roman", "Courier New", "Georgia",
      "Palatino", "Garamond", "Bookman", "Trebuchet MS", "Comic Sans MS", "Impact",
      "Tahoma", "Lucida Console", "Monaco", "Roboto", "Noto Sans", "Droid Sans",
      "Ubuntu", "Cantarell", "DejaVu Sans", "Liberation Sans", "Segoe UI", "Calibri",
      "Cambria", "Consolas", "Candara", "Optima", "Futura", "Baskerville"
    ];
    const base = ["monospace", "sans-serif", "serif"];
    const span = document.createElement("span");
    span.style.cssText = "position:absolute;left:-9999px;font-size:72px;visibility:hidden";
    span.textContent = "mmmmmmmmmmlli";
    document.body.appendChild(span);

    const ref = {};
    base.forEach(b => {
      span.style.fontFamily = b;
      ref[b] = [span.offsetWidth, span.offsetHeight];
    });

    const found = [];
    test.forEach(f => {
      for (const b of base) {
        span.style.fontFamily = "'" + f + "'," + b;
        if (span.offsetWidth !== ref[b][0] || span.offsetHeight !== ref[b][1]) {
          found.push(f);
          break;
        }
      }
    });
    span.remove();

    return {
      value: found.length + " sur " + test.length,
      note: found.length ? found.slice(0, 8).join(", ") + (found.length > 8 ? "…" : "")
                         : "aucune police distinguee",
      risky: found.length > 12
    };
  }

  function webrtcTest() {
    return new Promise(resolve => {
      const PC = window.RTCPeerConnection || window.webkitRTCPeerConnection;
      if (!PC) {
        resolve({ value: "desactive", note: "WebRTC indisponible : aucune fuite possible" });
        return;
      }
      const found = new Set();
      let pc;
      try {
        pc = new PC({ iceServers: [] });
        pc.createDataChannel("x");
        pc.onicecandidate = e => {
          if (!e.candidate) return;
          const m = /([0-9]{1,3}(\.[0-9]{1,3}){3}|[a-f0-9]{1,4}(:[a-f0-9]{0,4}){2,})/i
            .exec(e.candidate.candidate);
          if (m) found.add(m[1]);
        };
        pc.createOffer().then(o => pc.setLocalDescription(o)).catch(() => { });
      } catch (e) {
        resolve({ value: "desactive", note: "initialisation refusee" });
        return;
      }
      setTimeout(() => {
        try { pc.close(); } catch (e) { }
        const list = Array.from(found);
        resolve(list.length
          ? { value: list.length + " adresse(s) exposee(s)",
              note: list.join(", "), risky: true }
          : { value: "aucune fuite", note: "aucune adresse locale revelee" });
      }, 1400);
    });
  }

  // -------------------------------------------------------------------------
  //  Assemblage du rapport
  // -------------------------------------------------------------------------
  async function collect() {
    const n = navigator;
    const s = screen;

    const tz = safe(() => Intl.DateTimeFormat().resolvedOptions().timeZone);
    const offset = safe(() => -new Date().getTimezoneOffset() / 60);
    const langs = safe(() => (n.languages || []).join(", "));

    const rfpLikely = (tz === "UTC" || tz === "Etc/UTC") &&
                      /^en-US/.test(n.language || "");

    const [audio, rtc] = await Promise.all([audioPrint(), webrtcTest()]);
    const canvas = canvasPrint();
    const webgl = webglPrint();
    const fonts = fontPrint();

    const uaMobile = /Android|Mobile/i.test(n.userAgent);
    const realMobile = (n.maxTouchPoints || 0) > 0;

    const sections = [
      {
        title: "Identite declaree",
        rows: [
          ["Agent utilisateur", n.userAgent, null],
          ["Plateforme", safe(() => n.platform), null],
          ["Langue principale", n.language,
            /^en-US/.test(n.language || "") ? null : "valeur peu commune"],
          ["Langues acceptees", langs, null],
          ["Marque", safe(() => n.vendor) || "(vide)", null]
        ]
      },
      {
        title: "Ecran et fenetre",
        rows: [
          ["Resolution", s.width + " x " + s.height, null],
          ["Zone utile", s.availWidth + " x " + s.availHeight, null],
          ["Profondeur de couleur", s.colorDepth + " bits", null],
          ["Densite de pixels", String(window.devicePixelRatio),
            window.devicePixelRatio !== 1 && window.devicePixelRatio !== 2
              ? "valeur distinctive" : null],
          ["Fenetre", window.innerWidth + " x " + window.innerHeight,
            (window.innerWidth % 50 === 0 && window.innerHeight % 50 === 0)
              ? null : "taille non arrondie"]
        ]
      },
      {
        title: "Temps et lieu",
        rows: [
          ["Fuseau horaire", tz, tz === "UTC" ? null : "fuseau reel expose"],
          ["Decalage", offset + " h", null],
          ["Format de date", safe(() => new Date().toLocaleDateString()), null]
        ]
      },
      {
        title: "Materiel",
        rows: [
          ["Coeurs annonces", String(n.hardwareConcurrency || "(masque)"),
            n.hardwareConcurrency > 8 ? "valeur distinctive" : null],
          ["Memoire annoncee", (n.deviceMemory ? n.deviceMemory + " Go" : "(masquee)"), null],
          ["Points tactiles", String(n.maxTouchPoints || 0), null],
          ["Type de reseau", safe(() => (n.connection && n.connection.effectiveType) || "(masque)"), null]
        ]
      },
      {
        title: "Rendu graphique",
        rows: [
          ["Empreinte canvas", canvas.value, canvas.risky ? canvas.note : null, canvas.note],
          ["Empreinte WebGL", webgl.value, webgl.risky ? webgl.note : null, webgl.note],
          ["Empreinte audio", audio.value, audio.risky ? audio.note : null, audio.note],
          ["Polices reconnues", fonts.value, fonts.risky ? "liste large et distinctive" : null, fonts.note]
        ]
      },
      {
        title: "Reseau et stockage",
        rows: [
          ["Fuite WebRTC", rtc.value, rtc.risky ? rtc.note : null, rtc.note],
          ["Cookies actives", n.cookieEnabled ? "oui" : "non", null],
          ["Signal Do Not Track", safe(() => n.doNotTrack) || "(non declare)", null],
          ["Stockage local", safe(() => { localStorage.length; return "accessible"; }), null],
          ["Service workers", ("serviceWorker" in n) ? "disponibles" : "desactives", null]
        ]
      }
    ];

    // Incoherences : elles sont elles-memes un signal distinctif
    const warnings = [];
    if (uaMobile && !realMobile) {
      warnings.push("L'agent annonce un mobile mais l'appareil ne declare aucun point " +
        "tactile. Cette contradiction est reperable et vous singularise.");
    }
    if (rfpLikely && canvas.risky) {
      warnings.push("La protection anti-empreinte semble active, pourtant le canvas " +
        "renvoie un rendu specifique a l'appareil.");
    }
    if (!rfpLikely && tz !== "UTC") {
      warnings.push("La protection anti-empreinte ne parait pas active : fuseau et " +
        "langue reels sont exposes. Niveau renforce recommande.");
    }

    const risky = sections.reduce((acc, sec) =>
      acc + sec.rows.filter(r => r[2]).length, 0);

    return { sections, warnings, risky, rfpLikely };
  }

  // -------------------------------------------------------------------------
  //  Affichage
  // -------------------------------------------------------------------------
  const CSS = `
  #fp-root{position:fixed;inset:0;z-index:2147483647;background:#14161a;color:#e8eaee;
    font:13px/1.55 -apple-system,Roboto,"Segoe UI",sans-serif;display:flex;
    flex-direction:column}
  #fp-root *{box-sizing:border-box}
  .fp-head{display:flex;align-items:center;gap:8px;padding:11px 12px;
    border-bottom:1px solid #2b303a}
  .fp-head b{flex:1;font-size:14px}
  .fp-x{background:none;border:0;color:#99a0ad;font-size:22px;padding:0 6px}
  .fp-body{flex:1;overflow-y:auto;padding:12px 14px 34px}
  .fp-sum{background:#1c1f26;border:1px solid #2b303a;border-radius:10px;
    padding:12px 14px;margin-bottom:14px}
  .fp-sum b{font-size:15px}
  .fp-sum p{margin:7px 0 0;color:#99a0ad;font-size:12px;line-height:1.6}
  .fp-warn{border-left:2px solid #d97757;padding-left:10px;margin:9px 0;
    color:#e0c0b4;font-size:12px;line-height:1.55}
  .fp-h{margin:18px 0 6px;font-size:11px;text-transform:uppercase;
    letter-spacing:.05em;color:#99a0ad}
  .fp-r{padding:8px 0;border-bottom:1px solid #22262e}
  .fp-k{color:#99a0ad;font-size:11px}
  .fp-v{word-break:break-word;font-size:13px;margin-top:1px}
  .fp-n{color:#6f7784;font-size:11px;margin-top:2px}
  .fp-bad{color:#e08a72}
  .fp-ok{color:#8fce7c}
  .fp-b{padding:9px 14px;border:1px solid #2b303a;border-radius:8px;
    background:#1c1f26;color:#e8eaee;font-size:12px;margin:4px 4px 0 0}`;

  function render(rep) {
    const sections = rep.sections.map(sec =>
      `<div class="fp-h">${esc(sec.title)}</div>` +
      sec.rows.map(r => `
        <div class="fp-r">
          <div class="fp-k">${esc(r[0])}</div>
          <div class="fp-v ${r[2] ? "fp-bad" : ""}">${esc(r[1])}</div>
          ${r[3] ? `<div class="fp-n">${esc(r[3])}</div>` : ""}
          ${r[2] ? `<div class="fp-n fp-bad">${esc(r[2])}</div>` : ""}
        </div>`).join("")
    ).join("");

    const warns = rep.warnings.map(w => `<div class="fp-warn">${esc(w)}</div>`).join("");

    return `
      <div class="fp-sum">
        <b class="${rep.risky > 3 ? "fp-bad" : "fp-ok"}">
          ${rep.risky} point(s) distinctif(s) releve(s)</b>
        <p>Protection anti-empreinte : <b>${rep.rfpLikely ? "probablement active" : "probablement inactive"}</b>
        (deduit du fuseau et de la langue annonces).</p>
        <p>Ce compte n'est pas un score d'anonymat. Il indique combien de valeurs
        lues sur cette page sortent de l'ordinaire. Peu de points distinctifs
        signifie que vous ressemblez a beaucoup de monde ; c'est cela qui protege.</p>
        ${warns}
      </div>
      ${sections}
      <div style="margin-top:18px">
        <button class="fp-b" id="fp-copy">Copier le rapport</button>
        <button class="fp-b" id="fp-again">Refaire la mesure</button>
      </div>
      <p class="fp-n" style="margin-top:16px;line-height:1.6">
        Mesure realisee dans le contexte de cette page, et non dans une page de
        l'extension : les protections ne s'appliquent pas de la meme facon aux deux,
        seul ce contexte reflete ce qu'un site observe.
      </p>`;
  }

  function reportText(rep) {
    let out = "Diagnostic d'empreinte — " + location.hostname + "\\n";
    out += rep.risky + " point(s) distinctif(s)\\n\\n";
    rep.sections.forEach(sec => {
      out += "== " + sec.title + " ==\\n";
      sec.rows.forEach(r => { out += r[0] + " : " + r[1] + "\\n"; });
      out += "\\n";
    });
    rep.warnings.forEach(w => { out += "! " + w + "\\n"; });
    return out;
  }

  async function open() {
    if (panel) { close(); return; }

    panel = document.createElement("div");
    panel.id = "fp-root";
    panel.innerHTML =
      '<div class="fp-head"><b>Diagnostic d&rsquo;empreinte</b>' +
      '<button class="fp-x" id="fp-close">&times;</button></div>' +
      '<div class="fp-body"><p style="color:#99a0ad">Mesure en cours…</p></div>';
    const st = document.createElement("style");
    st.textContent = CSS;
    panel.appendChild(st);
    document.documentElement.appendChild(panel);

    panel.querySelector("#fp-close").onclick = close;

    const rep = await collect();
    const body = panel.querySelector(".fp-body");
    body.innerHTML = render(rep);

    const cp = body.querySelector("#fp-copy");
    if (cp) cp.onclick = () => {
      try { navigator.clipboard.writeText(reportText(rep)); cp.textContent = "Copie"; }
      catch (e) { cp.textContent = "Copie impossible"; }
    };
    const ag = body.querySelector("#fp-again");
    if (ag) ag.onclick = () => { close(); open(); };
  }

  function close() {
    if (panel) { panel.remove(); panel = null; }
  }

  browser.storage.onChanged.addListener(changes => {
    const c = changes.pageCommand && changes.pageCommand.newValue;
    if (c && c.cmd === "fingerprint") open();
  });
})();
