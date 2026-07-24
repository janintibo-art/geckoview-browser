"use strict";

// ===========================================================================
//  patterns.js -- reperage des procedes de conception trompeurs.
//
//  Ce que produit cet outil, ce sont des *signaux*, pas des verdicts. Un
//  compte a rebours peut etre authentique, une case cochee peut avoir ete
//  cochee par l'utilisateur. Les intitules et le texte de l'interface s'en
//  tiennent donc a ce qui est observable.
// ===========================================================================

(function () {

  if (window.top !== window.self) return;

  let panel = null;
  let found = [];

  function esc(v) {
    return String(v == null ? "" : v)
      .replace(/&/g, "&amp;").replace(/</g, "&lt;")
      .replace(/>/g, "&gt;").replace(/"/g, "&quot;");
  }

  function textOf(el) {
    return (el.innerText || el.textContent || "").replace(/\s+/g, " ").trim();
  }

  function visible(el) {
    if (!el || !el.getBoundingClientRect) return false;
    const r = el.getBoundingClientRect();
    if (r.width < 1 || r.height < 1) return false;
    const cs = window.getComputedStyle(el);
    return cs.display !== "none" && cs.visibility !== "hidden" && cs.opacity !== "0";
  }

  function add(kind, label, note, elements, fix) {
    const els = (elements || []).filter(visible).slice(0, 12);
    if (!els.length) return;
    found.push({ kind, label, note, els, fix });
  }

  // -------------------------------------------------------------------------
  //  Reperages
  // -------------------------------------------------------------------------
  function scanPrechecked() {
    const boxes = Array.from(document.querySelectorAll("input[type='checkbox']"))
      .filter(b => b.checked);
    if (!boxes.length) return;

    // Une case cochee n'a rien de suspect en soi : on ne retient que celles
    // dont l'intitule evoque un consentement ou une offre.
    const SUSPECT = /(newsletter|offre|promo|partenaire|tiers|publicit|marketing|abonn|infolettre|consent|accepte|recevoir)/i;
    const flagged = boxes.filter(b => {
      const lab = b.closest("label") ||
                  (b.id ? document.querySelector('label[for="' + CSS.escape(b.id) + '"]') : null) ||
                  b.parentElement;
      return lab && SUSPECT.test(textOf(lab));
    });

    add("precoche", "Cases deja cochees",
        flagged.length + " case(s) liee(s) a une offre ou a un consentement sont " +
        "cochees a l'ouverture de la page.",
        flagged,
        () => {
          flagged.forEach(b => {
            b.checked = false;
            b.dispatchEvent(new Event("change", { bubbles: true }));
          });
          return flagged.length + " case(s) decochee(s)";
        });
  }

  function scanCountdown() {
    const els = Array.from(document.querySelectorAll(
      "[class*='countdown' i], [class*='timer' i], [id*='countdown' i], " +
      "[class*='compte-a-rebours' i], [class*='urgen' i]"));

    // Repli : un texte du type 02:14:53 anime
    const clocks = Array.from(document.querySelectorAll("span, div, b, strong"))
      .filter(el => el.children.length === 0 &&
                    /^\d{1,2}\s*[:hj]\s*\d{2}([:m]\s*\d{2})?$/i.test(textOf(el)));

    const all = els.concat(clocks);
    add("rebours", "Compte a rebours",
        "Un decompte est affiche. Rechargez la page : s'il repart au meme point, " +
        "il ne mesure aucune echeance reelle.",
        all,
        () => {
          all.filter(visible).forEach(el => {
            el.style.setProperty("display", "none", "important");
          });
          return "Decompte masque";
        });
  }

  function scanScarcity() {
    const RE = /(plus que|il ne reste (plus )?que|derniers? (articles?|exemplaires?|places?)|stock (limite|presque epuise)|bientot epuise|only \d+ left)/i;
    const els = Array.from(document.querySelectorAll("span, div, p, b, strong, li"))
      .filter(el => el.children.length === 0 && RE.test(textOf(el)));

    add("rarete", "Mentions de rarete",
        "Des mentions de stock limite sont affichees. Elles ne correspondent pas " +
        "toujours a un inventaire reel.",
        els, null);
  }

  function scanSocialProof() {
    const RE = /(\d+\s*(personnes?|visiteurs?|clients?)\s*(regardent|consultent|viennent d'acheter|ont reserve)|\d+\s*(people|others)\s*(are viewing|viewed))/i;
    const els = Array.from(document.querySelectorAll("span, div, p, b, strong"))
      .filter(el => el.children.length === 0 && RE.test(textOf(el)));

    add("preuve", "Compteurs d'audience",
        "Des compteurs de personnes presentes ou d'achats recents sont affiches. " +
        "Ces valeurs sont frequemment simulees.",
        els, null);
  }

  function scanConfirmshaming() {
    const RE = /(non merci,? je (prefere|ne veux)|je ne veux pas (economiser|profiter|beneficier)|non,? je n'aime pas|je refuse de faire des economies|continuer sans mes avantages)/i;
    const els = Array.from(document.querySelectorAll(
      "button, a, span, label, [role='button']"))
      .filter(el => RE.test(textOf(el)));

    add("culpabilisation", "Refus culpabilisant",
        "L'option de refus est formulee de facon a mettre mal a l'aise, plutot " +
        "que de facon neutre.",
        els, null);
  }

  function scanHiddenRefusal() {
    // Un bandeau de consentement est-il present ?
    let banner = null;
    for (const sel of GB.CMP_CONTAINERS) {
      let el = null;
      try { el = document.querySelector(sel); } catch (e) { continue; }
      if (el && visible(el)) { banner = el; break; }
    }
    if (!banner) return;

    // Un controle de refus y est-il visible ?
    let refusal = null;
    for (const sel of GB.REJECT_SELECTORS) {
      let el = null;
      try { el = banner.querySelector(sel) || document.querySelector(sel); }
      catch (e) { continue; }
      if (el && visible(el)) { refusal = el; break; }
    }
    if (!refusal) {
      const buttons = banner.querySelectorAll("button, a, [role='button']");
      for (const b of buttons) {
        const t = GB.norm(textOf(b));
        if (GB.REJECT_TEXTS.some(x => t === x || t.startsWith(x))) {
          if (visible(b)) { refusal = b; break; }
        }
      }
    }

    if (!refusal) {
      add("refus", "Refus non propose",
          "Un bandeau de consentement est affiche, sans bouton de refus visible " +
          "au premier niveau. Le refus doit etre aussi accessible que l'acceptation.",
          [banner], null);
    }
  }

  function scanSponsored() {
    const RE = /(contenu (partenaire|sponsoris|de marque)|en partenariat avec|publi[- ]?r[ée]dactionnel|sponsoris[ée]|advertorial|brand content|pr[ée]sent[ée] par|article partenaire|offert par)/i;

    const els = Array.from(document.querySelectorAll(
      "span, div, p, small, em, i, b, strong, a, li"))
      .filter(el => el.children.length === 0 && RE.test(textOf(el)) &&
                    textOf(el).length < 160);

    add("publi", "Contenu commercial",
        "Une mention de partenariat ou de contenu sponsorise figure sur cette page. " +
        "Ces mentions sont souvent placees en petits caracteres, loin du titre.",
        els,
        () => {
          els.filter(visible).forEach(el => {
            el.style.setProperty("background", "#d97757", "important");
            el.style.setProperty("color", "#10130f", "important");
            el.style.setProperty("font-size", "13px", "important");
            el.style.setProperty("padding", "1px 5px", "important");
            el.style.setProperty("border-radius", "4px", "important");
          });
          return "Mentions mises en evidence";
        });
  }

  function scanTinyText() {
    const els = Array.from(document.querySelectorAll("a, span, p, small, label"))
      .filter(el => {
        if (el.children.length) return false;
        const t = textOf(el);
        if (t.length < 12 || t.length > 220) return false;
        const size = parseFloat(window.getComputedStyle(el).fontSize);
        return size > 0 && size <= 9.5;
      });

    add("minuscule", "Texte tres reduit",
        "Des mentions sont affichees en dessous de dix pixels, taille a laquelle " +
        "un texte devient difficilement lisible sur telephone.",
        els,
        () => {
          els.forEach(el => {
            el.style.setProperty("font-size", "14px", "important");
            el.style.setProperty("outline", "1px dashed #d97757", "important");
          });
          return "Textes agrandis";
        });
  }

  function scanTinyClose() {
    const dialogs = Array.from(document.querySelectorAll(
      "dialog, [role='dialog'], [class*='modal' i], [class*='popup' i]"))
      .filter(visible);

    const tiny = [];
    dialogs.forEach(d => {
      d.querySelectorAll("button, a, span, [role='button']").forEach(b => {
        const t = textOf(b);
        if (!/^(\u00D7|x|fermer|close|\u2715|\u2716)$/i.test(t)) return;
        const r = b.getBoundingClientRect();
        if (r.width && r.width < 22 && r.height < 22) tiny.push(b);
      });
    });

    add("fermeture", "Fermeture reduite",
        "Le bouton de fermeture d'une fenetre superposee mesure moins de vingt-deux " +
        "pixels, en dessous de la taille recommandee pour une cible tactile.",
        tiny,
        () => {
          tiny.forEach(b => {
            b.style.setProperty("min-width", "40px", "important");
            b.style.setProperty("min-height", "40px", "important");
            b.style.setProperty("outline", "1px dashed #d97757", "important");
          });
          return "Zones de fermeture agrandies";
        });
  }

  // -------------------------------------------------------------------------
  //  Affichage
  // -------------------------------------------------------------------------
  const CSS_TXT = `
  #dp-root{position:fixed;inset:0;z-index:2147483647;background:#14161a;color:#e8eaee;
    font:13px/1.6 -apple-system,Roboto,"Segoe UI",sans-serif;display:flex;
    flex-direction:column}
  #dp-root *{box-sizing:border-box}
  .dp-head{display:flex;align-items:center;gap:8px;padding:11px 12px;
    border-bottom:1px solid #2b303a}
  .dp-head b{flex:1;font-size:14px}
  .dp-x{background:none;border:0;color:#99a0ad;font-size:22px;padding:0 6px}
  .dp-body{flex:1;overflow-y:auto;padding:12px 14px 34px}
  .dp-sum{background:#1c1f26;border:1px solid #2b303a;border-radius:10px;
    padding:12px 14px;margin-bottom:14px}
  .dp-sum b{font-size:15px}
  .dp-sum p{margin:6px 0 0;color:#99a0ad;font-size:12px;line-height:1.6}
  .dp-i{border-bottom:1px solid #22262e;padding:11px 0}
  .dp-t{font-size:14px;color:#e08a72}
  .dp-n{color:#99a0ad;font-size:12px;margin-top:3px}
  .dp-c{color:#5f6874;font-size:11px;margin-top:3px}
  .dp-a{display:flex;gap:6px;margin-top:8px;flex-wrap:wrap}
  .dp-a button{padding:5px 11px;border:1px solid #2b303a;border-radius:7px;
    background:transparent;color:#99a0ad;font-size:12px}
  .dp-a .fix{color:#8fce7c;border-color:#3d5c34}
  .dp-ok{color:#8fce7c}
  .dp-note{color:#5f6874;font-size:11px;line-height:1.6;margin-top:16px}`;

  function highlight(el) {
    try {
      el.scrollIntoView({ block: "center", behavior: "smooth" });
      const old = el.style.outline;
      el.style.setProperty("outline", "3px solid #d97757", "important");
      setTimeout(() => { el.style.outline = old; }, 2500);
    } catch (e) { }
  }

  function render() {
    if (!found.length) {
      return '<div class="dp-sum"><b class="dp-ok">Aucun signal releve</b>' +
        "<p>Aucun des procedes recherches n'a ete repere sur cette page. " +
        "L'absence de signal ne garantit rien : seuls des motifs connus sont " +
        "examines.</p></div>";
    }

    const total = found.reduce((n, f) => n + f.els.length, 0);
    let html = '<div class="dp-sum"><b>' + found.length + " type(s) de signal, " +
      total + " element(s)</b><p>Ce sont des indices, non des preuves : un " +
      "decompte peut etre authentique, une case peut avoir ete cochee par vous. " +
      "A vous de juger sur piece.</p></div>";

    html += found.map((f, i) => `
      <div class="dp-i">
        <div class="dp-t">${esc(f.label)}</div>
        <div class="dp-n">${esc(f.note)}</div>
        <div class="dp-c">${f.els.length} element(s) concerne(s)</div>
        <div class="dp-a">
          <button data-s="${i}">Montrer</button>
          ${f.fix ? '<button class="fix" data-f="' + i + '">Neutraliser</button>' : ""}
        </div>
      </div>`).join("");

    html += '<p class="dp-note">Les motifs recherches : cases deja cochees, ' +
      "comptes a rebours, mentions de rarete, compteurs d'audience, refus " +
      "culpabilisant, contenu commercial, bandeau sans refus visible, texte tres " +
      "reduit, fermeture de taille insuffisante. La detection repose sur des formulations et des " +
      "mesures : elle peut se tromper dans les deux sens.</p>";

    return html;
  }

  function open() {
    if (panel) { close(); return; }

    found = [];
    try {
      scanPrechecked();
      scanCountdown();
      scanScarcity();
      scanSocialProof();
      scanConfirmshaming();
      scanSponsored();
      scanHiddenRefusal();
      scanTinyText();
      scanTinyClose();
    } catch (e) { }

    panel = document.createElement("div");
    panel.id = "dp-root";
    panel.innerHTML =
      '<div class="dp-head"><b>Procedes reperes</b>' +
      '<button class="dp-x" id="dp-close">&times;</button></div>' +
      '<div class="dp-body">' + render() + "</div>";
    const st = document.createElement("style");
    st.textContent = CSS_TXT;
    panel.appendChild(st);
    document.documentElement.appendChild(panel);

    panel.querySelector("#dp-close").onclick = close;

    panel.querySelectorAll("[data-s]").forEach(b => {
      b.onclick = () => {
        close();
        const f = found[+b.dataset.s];
        if (f && f.els[0]) highlight(f.els[0]);
      };
    });

    panel.querySelectorAll("[data-f]").forEach(b => {
      b.onclick = () => {
        const f = found[+b.dataset.f];
        if (!f || !f.fix) return;
        let msg = "";
        try { msg = f.fix() || "Applique"; } catch (e) { msg = "Echec"; }
        b.textContent = msg;
        b.disabled = true;
      };
    });
  }

  function close() {
    if (panel) { panel.remove(); panel = null; }
  }

  browser.storage.onChanged.addListener(changes => {
    const c = changes.pageCommand && changes.pageCommand.newValue;
    if (c && c.cmd === "patterns") open();
  });
})();
