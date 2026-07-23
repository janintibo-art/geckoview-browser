"use strict";

// ===========================================================================
//  publishers.js -- sert uniquement a etiqueter les resultats.
//  Les listes de filtrage, elles, vivent dans lists/*.txt (voir categories.js).
// ===========================================================================

// Propriete du capital : fait verifiable, a relire de temps en temps
// (la scission de Vivendi fin 2024 a redistribue les entites, le controle
//  de Vincent Bollore s'exerce desormais via plusieurs societes cotees).
const OWNERSHIP = {
  "cnews.fr":              "Canal+ / Bollore",
  "canalplus.com":         "Canal+ / Bollore",
  "canalplus.fr":          "Canal+ / Bollore",
  "cstar.fr":              "Canal+ / Bollore",
  "c8.fr":                 "Canal+ / Bollore",
  "mycanal.fr":            "Canal+ / Bollore",
  "europe1.fr":            "Lagardere / Bollore",
  "lejdd.fr":              "Lagardere / Bollore",
  "parismatch.com":        "Lagardere / Bollore",
  "capital.fr":            "Prisma / Bollore",
  "gala.fr":               "Prisma / Bollore",
  "geo.fr":                "Prisma / Bollore",
  "voici.fr":              "Prisma / Bollore",
  "femmeactuelle.fr":      "Prisma / Bollore",
  "cuisineactuelle.fr":    "Prisma / Bollore",
  "caminteresse.fr":       "Prisma / Bollore",
  "programme-tv.net":      "Prisma / Bollore",
  "teleloisirs.fr":        "Prisma / Bollore",
  "neonmag.fr":            "Prisma / Bollore",
  "prima.fr":              "Prisma / Bollore",
  "harpersbazaar.fr":      "Prisma / Bollore",
  "dailymotion.com":       "Vivendi / Bollore",

  // Quelques autres concentrations, a titre indicatif
  "lefigaro.fr":           "Groupe Dassault",
  "lexpress.fr":           "Alain Weill",
  "bfmtv.com":             "CMA CGM (Saade)",
  "rmc.bfmtv.com":         "CMA CGM (Saade)",
  "latribune.fr":          "CMA CGM (Saade)",
  "lopinion.fr":           "Bernard Arnault",
  "lesechos.fr":           "LVMH (Arnault)",
  "leparisien.fr":         "LVMH (Arnault)",
  "liberation.fr":         "Fonds de dotation (Kretinsky)",
  "elle.fr":               "Czech Media Invest (Kretinsky)",
  "marianne.net":          "Czech Media Invest (Kretinsky)",
  "lemonde.fr":            "Groupe Le Monde (Niel, Pigasse, Kretinsky)",
  "nouvelobs.com":         "Groupe Le Monde",
  "mediapart.fr":          "Fonds pour une presse libre",
  "arretsurimages.net":    "Independant (abonnements)",
  "alternatives-economiques.fr": "Cooperative (Scop)",
  "lemondediplomatique.fr": "Association de lecteurs"
};

const ALTERNATIVES = [
  { name: "Le Monde",         domain: "lemonde.fr" },
  { name: "Mediapart",        domain: "mediapart.fr" },
  { name: "AFP Factuel",      domain: "factuel.afp.com" },
  { name: "France Info",      domain: "francetvinfo.fr" },
  { name: "Radio France",     domain: "radiofrance.fr" },
  { name: "Arret sur images", domain: "arretsurimages.net" },
  { name: "Alternatives Eco", domain: "alternatives-economiques.fr" },
  { name: "Le Diplo",         domain: "lemondediplomatique.fr" }
];

function ownerOf(host) {
  if (!host) return null;
  host = host.toLowerCase().replace(/^www\./, "");
  if (OWNERSHIP[host]) return OWNERSHIP[host];
  let i = host.indexOf(".");
  while (i !== -1) {
    const parent = host.slice(i + 1);
    if (OWNERSHIP[parent]) return OWNERSHIP[parent];
    i = host.indexOf(".", i + 1);
  }
  return null;
}

if (typeof window !== "undefined") {
  window.PUBLISHERS = { OWNERSHIP, ALTERNATIVES, ownerOf };
}
