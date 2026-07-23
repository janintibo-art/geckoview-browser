// Listes embarquees (fonctionnent hors ligne, sans telechargement).
// Elles sont completees au demarrage par des listes distantes (voir background.js).

// --- Domaines de regie publicitaire / analytics / tracking ---
const SEED_DOMAINS = [
  // Google
  "doubleclick.net", "googlesyndication.com", "googleadservices.com",
  "googletagservices.com", "googletagmanager.com", "google-analytics.com",
  "adservice.google.com", "pagead2.googlesyndication.com", "2mdn.net",
  "admob.com", "app-measurement.com", "crashlytics.com",
  // Meta
  "connect.facebook.net", "an.facebook.com", "graph.facebook.com",
  // Amazon
  "amazon-adsystem.com", "assoc-amazon.com", "aax.amazon-adsystem.com",
  // Regies generalistes
  "adnxs.com", "adsrvr.org", "rubiconproject.com", "pubmatic.com",
  "openx.net", "criteo.com", "criteo.net", "taboola.com", "outbrain.com",
  "smartadserver.com", "casalemedia.com", "contextweb.com", "33across.com",
  "sharethrough.com", "teads.tv", "adform.net", "adotmob.com",
  "adroll.com", "bidswitch.net", "districtm.io", "gumgum.com",
  "improvedigital.com", "indexww.com", "lijit.com", "media.net",
  "onetag-sys.com", "pubnative.net", "revcontent.com", "richaudience.com",
  "sonobi.com", "spotxchange.com", "themoneytizer.com", "tremorhub.com",
  "triplelift.com", "yieldmo.com", "zemanta.com", "adsafeprotected.com",
  "moatads.com", "serving-sys.com", "flashtalking.com", "adcolony.com",
  "applovin.com", "unityads.unity3d.com", "vungle.com", "chartboost.com",
  "inmobi.com", "startapp.com", "mopub.com", "smaato.net", "tapjoy.com",
  // Analytics / mesure
  "scorecardresearch.com", "quantserve.com", "chartbeat.com", "chartbeat.net",
  "hotjar.com", "hotjar.io", "mixpanel.com", "segment.io", "segment.com",
  "amplitude.com", "fullstory.com", "mouseflow.com", "clarity.ms",
  "newrelic.com", "nr-data.net", "bugsnag.com", "sentry.io",
  "optimizely.com", "kissmetrics.com", "heapanalytics.com", "matomo.cloud",
  "statcounter.com", "histats.com", "clicky.com", "luckyorange.com",
  "crazyegg.com", "inspectlet.com", "yandex.ru/metrika", "mc.yandex.ru",
  // Trackers d'e-mail / marketing
  "branch.io", "adjust.com", "appsflyer.com", "kochava.com", "singular.net",
  "braze.com", "iterable.com", "klaviyo.com", "mailchimp.com/track",
  "list-manage.com", "sailthru.com", "exponea.com", "bluecore.com",
  // Consentement / murs publicitaires
  "cookielaw.org", "onetrust.com", "cookiebot.com", "quantcast.mgr.consensu.org",
  "consensu.org", "trustarc.com", "usercentrics.eu", "sourcepoint.mgr.consensu.org",
  // Divers
  "popads.net", "popcash.net", "propellerads.com", "adsterra.com",
  "exoclick.com", "juicyads.com", "trafficjunky.com", "hilltopads.net",
  "mgid.com", "adcash.com", "clickadu.com", "bidvertiser.com",
  "zedo.com", "adblade.com", "infolinks.com", "vidoomy.com", "ezoic.net",
  "pubguru.net", "freestar.io", "playwire.com", "primis.tech"
];

// --- Motifs d'URL bloques (chemins typiques d'emplacements publicitaires) ---
const URL_PATTERNS = [
  /\/ad(s|serv|server|frame|vert|vertis|vertising)?[\/._-]/i,
  /\/banner(s|ad|_ad)?[\/._-]/i,
  /\/pop(up|under)s?[\/._-]/i,
  /\/(pre|mid|post)roll[\/._-]/i,
  /\/sponsor(ed|ship)?[\/._-]/i,
  /\/track(er|ing|ing_?pixel)?[\/._-]/i,
  /\/(beacon|telemetry|analytics|metrics|collect|pixel)[\/._-]/i,
  /[?&](utm_|gclid|fbclid|dclid|msclkid)/i,
  /\/prebid[.-]/i,
  /\/gpt[\/.-]/i,
  /\/adsbygoogle/i,
  /\/vast[\/.?]/i,
  /\/openrtb/i
];

// --- Selecteurs CSS masques (filtrage cosmetique generique) ---
const COSMETIC_SELECTORS = [
  "[id^='ad-']", "[id^='ads-']", "[id^='ad_']", "[id$='-ad']", "[id$='_ad']",
  "[id*='banner-ad']", "[id*='ad-banner']", "[id*='adcontainer']",
  "[class^='ad-']", "[class^='ads-']", "[class*=' ad-']", "[class*='ad-slot']",
  "[class*='ad-unit']", "[class*='ad-wrapper']", "[class*='ad-container']",
  "[class*='adsbox']", "[class*='ad-placeholder']", "[class*='advert']",
  "[class*='sponsored']", "[class*='sponsor-']", "[class*='promoted']",
  "[class*='taboola']", "[class*='outbrain']", "[class*='revcontent']",
  "[data-ad-slot]", "[data-ad-client]", "[data-adunit]", "[data-google-query-id]",
  "ins.adsbygoogle", "iframe[src*='doubleclick']", "iframe[src*='googlesyndication']",
  "iframe[src*='amazon-adsystem']", "iframe[src*='adnxs']", "iframe[id^='google_ads']",
  "iframe[name^='google_ads']", "iframe[title='Advertisement']",
  "div[aria-label='Ad']", "div[aria-label='Advertisement']",
  ".ad-banner", ".ad-block", ".ad-box", ".ad-holder", ".ad-label",
  ".adsbygoogle", ".advertisement", ".advertising", ".google-ad",
  ".sponsored-content", ".partner-content", ".outbrain-widget",
  "#taboola-below-article", "#dfp-ad", "#google_image_div"
];

// --- Elements de "mur" a neutraliser (anti-adblock / overlays) ---
const OVERLAY_SELECTORS = [
  "[class*='adblock-detected']", "[class*='adblocker-']", "[id*='adblock-modal']",
  "[class*='paywall-overlay']", "[class*='newsletter-modal']"
];

// --- Domaines jamais bloques (evite de casser des sites courants) ---
const ALLOWLIST = [
  "googlevideo.com", "ytimg.com", "youtube.com", "gstatic.com",
  "googleapis.com", "googleusercontent.com", "cloudflare.com",
  "jsdelivr.net", "unpkg.com", "cdnjs.cloudflare.com",
  "paypal.com", "stripe.com", "recaptcha.net", "hcaptcha.com"
];
