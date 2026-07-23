"use strict";

// ===========================================================================
//  publishers.js
//  Deux listes de nature differente. Modifiez-les librement.
// ===========================================================================

// ---------------------------------------------------------------------------
//  1) GROUPE BOLLORE  --  critere : propriete du capital (fait verifiable)
//
//  Perimetre : Vivendi, Canal+, Havas, Louis Hachette Group, Lagardere,
//  Prisma Media. Le groupe a ete scinde en quatre entites cotees fin 2024,
//  Vincent Bollore conservant le controle de l'ensemble.
//  Verifiez l'actualite du capital : les cessions sont frequentes.
// ---------------------------------------------------------------------------
const OWNERSHIP = {
  "cnews.fr":                 "Canal+ (Bollore)",
  "canalplus.com":            "Canal+ (Bollore)",
  "cstar.fr":                 "Canal+ (Bollore)",
  "mycanal.fr":               "Canal+ (Bollore)",
  "europe1.fr":               "Lagardere (Bollore)",
  "europe2.fr":               "Lagardere (Bollore)",
  "rfm.fr":                   "Lagardere (Bollore)",
  "virginradio.fr":           "Lagardere (Bollore)",
  "lejdd.fr":                 "Lagardere (Bollore)",
  "parismatch.com":           "Lagardere (Bollore)",
  "capital.fr":               "Prisma Media (Bollore)",
  "geo.fr":                   "Prisma Media (Bollore)",
  "gala.fr":                  "Prisma Media (Bollore)",
  "voici.fr":                 "Prisma Media (Bollore)",
  "femmeactuelle.fr":         "Prisma Media (Bollore)",
  "cuisineactuelle.fr":       "Prisma Media (Bollore)",
  "caminteresse.fr":          "Prisma Media (Bollore)",
  "programme-tv.net":         "Prisma Media (Bollore)",
  "nationalgeographic.fr":    "Prisma Media (Bollore)",
  "hachette.com":             "Louis Hachette Group (Bollore)",
  "dailymotion.com":          "Vivendi (Bollore)",
  "havas.com":                "Havas (Bollore)"
};

const BOLLORE_DOMAINS = Object.keys(OWNERSHIP);

// ---------------------------------------------------------------------------
//  2) MEDIAS NATIONALISTES / EXTREME DROITE
//
//  ATTENTION : contrairement a la liste ci-dessus, il s'agit d'un classement
//  editorial, pas d'un fait comptable. Criteres retenus ici :
//    (a) le media se revendique lui-meme nationaliste, identitaire ou
//        "dissident" ; ou
//    (b) il est classe a l'extreme droite de facon convergente par les
//        travaux universitaires et la presse de reference.
//  Plusieurs de ces titres contestent publiquement cette etiquette.
//  Ajoutez ou retirez ce que vous voulez : c'est votre filtre.
// ---------------------------------------------------------------------------
const FARRIGHT_DOMAINS = [
  // France
  "valeursactuelles.com",
  "bvoltaire.fr",
  "fdesouche.com",
  "ripostelaique.com",
  "tvlibertes.com",
  "lesalonbeige.fr",
  "egaliteetreconciliation.fr",
  "rivarol.com",
  "lincorrect.org",
  "revue-elements.com",
  "breizh-info.com",
  "radiocourtoisie.fr",
  "livrenoir.fr",
  "frontieres.eu",
  "ojim.fr",
  "medias-presse.info",
  "reinformation.tv",
  "contre-info.com",
  "nouvelle-droite.fr",
  "institut-iliade.com",
  // Europe
  "jungefreiheit.de",
  "pi-news.net",
  "nius.de",
  "tichyseinblick.de",
  "remix.news",
  "voiceofeurope.com",
  "gatesofvienna.net",
  "elmanifiesto.com",
  "ilprimatonazionale.it",
  // Amerique du Nord
  "breitbart.com",
  "thegatewaypundit.com",
  "infowars.com",
  "vdare.com",
  "amren.com",
  "takimag.com",
  "unz.com",
  "rebelnews.com",
  "thepostmillennial.com",
  "bigleaguepolitics.com",
  "newsmax.com",
  "oann.com"
];

// ---------------------------------------------------------------------------
//  3) VOS AJOUTS PERSONNELS
//  Le plus simple : ajoutez ici, cela survit aux mises a jour des listes.
// ---------------------------------------------------------------------------
const CUSTOM_BLOCKED = [
  // "exemple.fr",
];

// ---------------------------------------------------------------------------
//  Sources alternatives suggerees en remplacement d'un resultat filtre.
// ---------------------------------------------------------------------------
const ALTERNATIVES = [
  { name: "Le Monde",        domain: "lemonde.fr" },
  { name: "Mediapart",       domain: "mediapart.fr" },
  { name: "AFP Factuel",     domain: "factuel.afp.com" },
  { name: "France Info",     domain: "francetvinfo.fr" },
  { name: "Radio France",    domain: "radiofrance.fr" },
  { name: "Liberation",      domain: "liberation.fr" },
  { name: "Alternatives Eco", domain: "alternatives-economiques.fr" },
  { name: "Arret sur images", domain: "arretsurimages.net" }
];

// ---------------------------------------------------------------------------
//  Assemblage
// ---------------------------------------------------------------------------
function buildBlockSet(prefs) {
  const set = new Set();
  const p = prefs || {};
  if (p.blockBollore !== false) BOLLORE_DOMAINS.forEach(d => set.add(d));
  if (p.blockFarRight !== false) FARRIGHT_DOMAINS.forEach(d => set.add(d));
  CUSTOM_BLOCKED.forEach(d => set.add(d));
  (p.extra || []).forEach(d => set.add(d.toLowerCase().trim()));
  (p.allow || []).forEach(d => set.delete(d.toLowerCase().trim()));
  return set;
}

function domainMatches(host, set) {
  if (!host) return null;
  host = host.toLowerCase().replace(/^www\./, "");
  if (set.has(host)) return host;
  let i = host.indexOf(".");
  while (i !== -1) {
    const parent = host.slice(i + 1);
    if (set.has(parent)) return parent;
    i = host.indexOf(".", i + 1);
  }
  return null;
}

if (typeof window !== "undefined") {
  window.PUBLISHERS = {
    OWNERSHIP, BOLLORE_DOMAINS, FARRIGHT_DOMAINS,
    CUSTOM_BLOCKED, ALTERNATIVES, buildBlockSet, domainMatches
  };
}
