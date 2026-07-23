"use strict";

// ===========================================================================
//  help.js -- navigation du tutoriel : filtrage par onglet et recherche
//  plein texte dans les sections.
// ===========================================================================

(function () {

  const sections = Array.from(document.querySelectorAll("details[data-cat]"));
  const tabs = Array.from(document.querySelectorAll(".tab"));
  const input = document.getElementById("q");
  const count = document.getElementById("count");
  const none = document.getElementById("none");

  let cat = "tous";
  let query = "";

  // Texte de chaque section, retenu une fois pour toutes
  const index = sections.map(el => ({
    el: el,
    cat: el.getAttribute("data-cat"),
    title: (el.querySelector("summary") || {}).textContent || "",
    text: (el.textContent || "").toLowerCase()
  }));

  function norm(s) {
    return s.toLowerCase()
      .normalize("NFD")
      .replace(/[\u0300-\u036f]/g, "");
  }

  function apply() {
    const q = norm(query.trim());
    let shown = 0;

    index.forEach(item => {
      const okCat = cat === "tous" || item.cat === cat;
      const okText = !q || norm(item.text).indexOf(q) !== -1;
      const visible = okCat && okText;

      item.el.hidden = !visible;
      if (visible) {
        shown++;
        // Une recherche ouvre les sections concernees : on cherche une
        // reponse, pas une liste de titres a deplier un par un.
        if (q) item.el.open = true;
      }
    });

    none.hidden = shown > 0;
    count.textContent = q
      ? shown + " section" + (shown > 1 ? "s" : "")
      : (cat === "tous" ? sections.length + " sections" : shown + " sections");
  }

  tabs.forEach(b => {
    b.addEventListener("click", () => {
      tabs.forEach(x => x.classList.remove("on"));
      b.classList.add("on");
      cat = b.getAttribute("data-c");
      // Changer d'onglet replie tout : on repart d'une vue d'ensemble
      if (!query.trim()) sections.forEach(s => { s.open = false; });
      apply();
      window.scrollTo(0, 0);
    });
  });

  input.addEventListener("input", () => {
    query = input.value;
    apply();
  });

  // Une section ouverte se replie quand on ouvre une autre : sur telephone,
  // trois sections deployees rendent la page illisible.
  sections.forEach(s => {
    s.addEventListener("toggle", () => {
      if (!s.open || query.trim()) return;
      sections.forEach(o => { if (o !== s) o.open = false; });
    });
  });

  apply();
})();
