"use strict";

// ===========================================================================
//  userscripts.js -- moteur de scripts utilisateur (facon Tampermonkey).
//  Charge les scripts stockes, verifie les motifs @match, puis les execute
//  dans le bac a sable du script de contenu avec une API GM_*.
// ===========================================================================

(function () {

  const GM_VERSION = "1.0";

  // -------------------------------------------------------------------------
  //  Analyse de l'en-tete ==UserScript==
  // -------------------------------------------------------------------------
  function parseMeta(code) {
    const meta = {
      name: "Sans titre", namespace: "", version: "", description: "",
      match: [], include: [], exclude: [], require: [], grant: [],
      runAt: "document-end", noframes: false, world: "sandbox"
    };

    const block = code.match(/\/\/\s*==UserScript==([\s\S]*?)\/\/\s*==\/UserScript==/);
    if (!block) return meta;

    for (const line of block[1].split("\n")) {
      const m = line.match(/^\s*\/\/\s*@(\S+)\s*(.*)$/);
      if (!m) continue;
      const key = m[1].toLowerCase();
      const val = m[2].trim();
      switch (key) {
        case "name":        meta.name = val || meta.name; break;
        case "namespace":   meta.namespace = val; break;
        case "version":     meta.version = val; break;
        case "description": meta.description = val; break;
        case "match":       if (val) meta.match.push(val); break;
        case "include":     if (val) meta.include.push(val); break;
        case "exclude":
        case "exclude-match": if (val) meta.exclude.push(val); break;
        case "require":     if (val) meta.require.push(val); break;
        case "grant":       if (val) meta.grant.push(val); break;
        case "run-at":      meta.runAt = val || meta.runAt; break;
        case "noframes":    meta.noframes = true; break;
        case "world":       meta.world = val === "page" ? "page" : "sandbox"; break;
      }
    }
    if (!meta.match.length && !meta.include.length) meta.include.push("*");
    return meta;
  }

  // -------------------------------------------------------------------------
  //  Correspondance des motifs
  // -------------------------------------------------------------------------
  function escapeRe(s) {
    return s.replace(/[.+?^${}()|[\]\\]/g, "\\$&");
  }

  // Motif @match : scheme://host/path avec * comme joker
  function matchToRe(pattern) {
    if (pattern === "<all_urls>") return /^https?:\/\//;
    const m = pattern.match(/^(\*|https?|file|ftp):\/\/([^/]*)(\/.*)$/);
    if (!m) return null;
    const scheme = m[1] === "*" ? "https?" : m[1];
    let host = m[2];
    let path = m[3];

    let hostRe;
    if (host === "*") hostRe = "[^/]*";
    else if (host.startsWith("*.")) hostRe = "(?:[^/]*\\.)?" + escapeRe(host.slice(2));
    else hostRe = escapeRe(host);

    const pathRe = escapeRe(path).replace(/\*/g, ".*");
    return new RegExp("^" + scheme + "://" + hostRe + pathRe.replace(/\\\*/g, ".*") + "$");
  }

  // Motif @include : glob simple, ou expression reguliere si /.../
  function includeToRe(pattern) {
    if (pattern.length > 2 && pattern[0] === "/" && pattern.endsWith("/")) {
      try { return new RegExp(pattern.slice(1, -1)); } catch (e) { return null; }
    }
    if (pattern === "*") return /.*/;
    return new RegExp("^" + escapeRe(pattern).replace(/\\\*/g, ".*") + "$");
  }

  function urlMatches(meta, url) {
    for (const p of meta.exclude) {
      const re = includeToRe(p) || matchToRe(p);
      if (re && re.test(url)) return false;
    }
    for (const p of meta.match) {
      const re = matchToRe(p);
      if (re && re.test(url)) return true;
    }
    for (const p of meta.include) {
      const re = includeToRe(p);
      if (re && re.test(url)) return true;
    }
    return false;
  }

  // -------------------------------------------------------------------------
  //  API GM_*
  // -------------------------------------------------------------------------
  function buildApi(script, values, menuSink) {
    const prefix = "gmv:" + script.id + ":";

    const persist = () => {
      const payload = {};
      payload["gmvalues:" + script.id] = values;
      try { browser.storage.local.set(payload); } catch (e) { }
    };

    const api = {
      GM_info: {
        script: {
          name: script.meta.name,
          namespace: script.meta.namespace,
          version: script.meta.version,
          description: script.meta.description,
          matches: script.meta.match,
          includes: script.meta.include
        },
        scriptHandler: "GeckoBrowser UserScripts",
        version: GM_VERSION
      },

      GM_getValue: (k, d) => (k in values ? values[k] : d),
      GM_setValue: (k, v) => { values[k] = v; persist(); },
      GM_deleteValue: k => { delete values[k]; persist(); },
      GM_listValues: () => Object.keys(values),

      GM_addStyle: css => {
        const el = document.createElement("style");
        el.textContent = css;
        (document.head || document.documentElement).appendChild(el);
        return el;
      },

      GM_registerMenuCommand: (label, fn) => {
        menuSink.push({ label, fn, script: script.meta.name });
        return menuSink.length - 1;
      },
      GM_unregisterMenuCommand: i => { if (menuSink[i]) menuSink[i] = null; },

      GM_openInTab: (url) => { window.open(url, "_blank"); },

      GM_setClipboard: text => {
        try { navigator.clipboard.writeText(text); } catch (e) { }
      },

      GM_log: (...a) => console.log("[" + script.meta.name + "]", ...a),

      // Requete inter-domaines relayee par le script d'arriere-plan
      GM_xmlhttpRequest: opts => {
        const o = opts || {};
        browser.runtime.sendMessage({
          type: "gmFetch",
          url: o.url,
          method: o.method || "GET",
          headers: o.headers || {},
          data: o.data || null
        }).then(res => {
          if (!res) return o.onerror && o.onerror({ error: "no response" });
          if (res.error) return o.onerror && o.onerror(res);
          const r = {
            status: res.status, statusText: res.statusText,
            responseText: res.body, response: res.body,
            responseHeaders: res.headers, finalUrl: res.finalUrl
          };
          o.onload && o.onload(r);
        }).catch(e => { o.onerror && o.onerror({ error: String(e) }); });
      }
    };

    api.GM = {
      getValue: async (k, d) => api.GM_getValue(k, d),
      setValue: async (k, v) => api.GM_setValue(k, v),
      deleteValue: async k => api.GM_deleteValue(k),
      listValues: async () => api.GM_listValues(),
      addStyle: api.GM_addStyle,
      setClipboard: api.GM_setClipboard,
      openInTab: api.GM_openInTab,
      xmlHttpRequest: api.GM_xmlhttpRequest,
      registerMenuCommand: api.GM_registerMenuCommand,
      info: api.GM_info
    };

    return api;
  }

  // -------------------------------------------------------------------------
  //  Execution
  // -------------------------------------------------------------------------
  const menuCommands = [];

  function runSandboxed(script, code, api) {
    const names = Object.keys(api);
    const args = names.map(n => api[n]);
    names.push("unsafeWindow");
    args.push(typeof window.wrappedJSObject !== "undefined" ? window.wrappedJSObject : window);

    const fn = new Function(...names,
      '"use strict";\n' + code + "\n//# sourceURL=userscript/" +
      encodeURIComponent(script.meta.name) + ".user.js");
    fn.apply(window, args);
  }

  function runInPage(script, code) {
    const el = document.createElement("script");
    el.textContent = "(function(){try{\n" + code +
      "\n}catch(e){console.error('[userscript]',e);}})();";
    (document.head || document.documentElement).appendChild(el);
    el.remove();
  }

  async function loadRequires(list) {
    if (!list.length) return "";
    const parts = await Promise.all(list.map(async url => {
      try {
        const res = await browser.runtime.sendMessage({ type: "gmFetch", url, method: "GET" });
        return (res && res.body) ? res.body + "\n;\n" : "";
      } catch (e) { return ""; }
    }));
    return parts.join("");
  }

  async function execute(script, values) {
    const api = buildApi(script, values, menuCommands);
    const libs = await loadRequires(script.meta.require);
    const code = libs + script.code;

    if (script.meta.world === "page") { runInPage(script, code); return; }

    try {
      runSandboxed(script, code, api);
    } catch (e) {
      // Si l'evaluation dynamique est refusee, on retombe sur le contexte page.
      console.warn("[userscript] bac a sable indisponible :", e && e.message);
      try { runInPage(script, code); }
      catch (e2) { console.error("[userscript] " + script.meta.name, e2); }
    }
  }

  // -------------------------------------------------------------------------
  //  Menu des commandes enregistrees
  // -------------------------------------------------------------------------
  function buildMenuButton() {
    if (!menuCommands.filter(Boolean).length) return;
    if (window.top !== window.self) return;
    if (document.getElementById("gm-menu-fab")) return;

    const fab = document.createElement("div");
    fab.id = "gm-menu-fab";
    fab.textContent = "\u2318";
    Object.assign(fab.style, {
      position: "fixed", right: "12px", bottom: "128px", zIndex: "2147483645",
      width: "30px", height: "30px", lineHeight: "30px", textAlign: "center",
      borderRadius: "15px", background: "rgba(20,22,26,.55)", color: "#8ab4f8",
      fontSize: "15px", cursor: "pointer", opacity: ".55", userSelect: "none"
    });

    const list = document.createElement("div");
    Object.assign(list.style, {
      position: "fixed", right: "12px", bottom: "164px", zIndex: "2147483646",
      display: "none", background: "#1c1f26", border: "1px solid #2b303a",
      borderRadius: "10px", padding: "6px", minWidth: "190px",
      boxShadow: "0 8px 24px rgba(0,0,0,.5)", font: "13px -apple-system,Roboto,sans-serif"
    });

    menuCommands.filter(Boolean).forEach(cmd => {
      const item = document.createElement("div");
      item.textContent = cmd.label;
      item.title = cmd.script;
      Object.assign(item.style, {
        padding: "8px 10px", color: "#e8eaee", cursor: "pointer", borderRadius: "6px"
      });
      item.onclick = () => {
        list.style.display = "none";
        try { cmd.fn(); } catch (e) { console.error(e); }
      };
      list.appendChild(item);
    });

    fab.onclick = () => {
      list.style.display = list.style.display === "block" ? "none" : "block";
    };
    document.addEventListener("click", e => {
      if (e.target !== fab && !list.contains(e.target)) list.style.display = "none";
    });

    document.body.appendChild(fab);
    document.body.appendChild(list);
  }

  // -------------------------------------------------------------------------
  //  Demarrage
  // -------------------------------------------------------------------------
  async function init() {
    let scripts = [];
    let store = {};
    try {
      store = await browser.storage.local.get(null);
      scripts = store.userscripts || [];
    } catch (e) { return; }
    if (!scripts.length) return;

    const url = location.href;
    const inFrame = window.top !== window.self;

    const pending = [];
    for (const s of scripts) {
      if (!s.enabled) continue;
      const meta = parseMeta(s.code);
      if (meta.noframes && inFrame) continue;
      if (!urlMatches(meta, url)) continue;
      pending.push({ id: s.id, code: s.code, meta });
    }
    if (!pending.length) return;

    const runNow = [], runEnd = [], runIdle = [];
    pending.forEach(s => {
      const values = store["gmvalues:" + s.id] || {};
      const job = () => execute(s, values);
      if (s.meta.runAt === "document-start") runNow.push(job);
      else if (s.meta.runAt === "document-idle") runIdle.push(job);
      else runEnd.push(job);
    });

    runNow.forEach(j => j());

    const onEnd = () => {
      runEnd.forEach(j => j());
      setTimeout(() => {
        runIdle.forEach(j => j());
        setTimeout(buildMenuButton, 300);
      }, 0);
    };

    if (document.readyState === "loading") {
      document.addEventListener("DOMContentLoaded", onEnd, { once: true });
    } else {
      onEnd();
    }
  }

  init();
})();
