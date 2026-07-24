"use strict";

// ===========================================================================
//  elsewhere.js -- « ce sujet vu ailleurs ».
//  Reprend les mots significatifs du titre et relance la recherche en
//  ecartant les sites du meme groupe que la page consultee.
// ===========================================================================

(function () {

  if (window.top !== window.self) return;

  // Mots trop courants pour distinguer un sujet
  const STOP = new Set((
    "le la les un une des du de la au aux et ou ni mais donc or car que qui quoi " +
    "dont ou a en dans sur sous par pour avec sans chez vers entre depuis " +
    "ce cet cette ces son sa ses leur leurs notre nos votre vos mon ma mes " +
    "est sont etait etaient sera seront ete etre avoir a ont avait avaient " +
    "il elle ils elles on nous vous je tu se me te lui y " +
    "plus moins tres bien tout tous toute toutes meme encore deja apres avant " +
    "comment pourquoi quand combien video photos direct live info actualite " +
    "the of and for with from this that what how why"
  ).split(" "));

  function keywords() {
    // Le titre porte le sujet ; on ecarte le nom du site apres un separateur
    let t = document.title || "";
    t = t.split(/\s[|\u2013\u2014\u00BB-]\s/)[0];

    const h1 = document.querySelector("h1");
    if (h1) {
      const alt = (h1.textContent || "").replace(/\s+/g, " ").trim();
      if (alt.length > 15 && alt.length < 160) t = alt;
    }

    const words = t
      .toLowerCase()
      .normalize("NFD").replace(/[\u0300-\u036f]/g, "")
      .replace(/[^a-z0-9\s'-]/g, " ")
      .split(/\s+/)
      .map(w => w.replace(/^['-]+|['-]+$/g, ""))
      .filter(w => w.length > 2 && !STOP.has(w));

    // Doublons ecartes, ordre d'apparition conserve
    const seen = new Set();
    const out = [];
    for (const w of words) {
      if (seen.has(w)) continue;
      seen.add(w);
      out.push(w);
      if (out.length >= 7) break;
    }
    return out;
  }

  async function run() {
    const words = keywords();
    if (words.length < 2) {
      alert("Le titre de cette page ne permet pas d'en deduire un sujet.");
      return;
    }

    let excluded = [];
    let owner = null;
    try {
      const r = await browser.runtime.sendMessage({
        type: "sameOwner", host: location.hostname
      });
      if (r) { excluded = r.domains || []; owner = r.owner || null; }
    } catch (e) { }

    // A defaut de groupe connu, on ecarte au moins le site lui-meme
    const host = location.hostname.replace(/^www\./, "");
    if (excluded.indexOf(host) === -1) excluded.push(host);

    const url = browser.runtime.getURL("search.html") +
      "?q=" + encodeURIComponent(words.join(" ")) +
      "&not=" + encodeURIComponent(excluded.join(",")) +
      (owner ? "&owner=" + encodeURIComponent(owner) : "");

    location.href = url;
  }

  browser.storage.onChanged.addListener(changes => {
    const c = changes.pageCommand && changes.pageCommand.newValue;
    if (c && c.cmd === "elsewhere") run();
  });
})();
