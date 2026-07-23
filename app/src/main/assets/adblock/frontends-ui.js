"use strict";

(function () {

  const $ = s => document.querySelector(s);
  let services = [];
  let cfg = { enabled: true, amp: true, services: {}, instance: {}, except: [] };

  function esc(s) {
    return String(s == null ? "" : s)
      .replace(/&/g, "&amp;").replace(/</g, "&lt;")
      .replace(/>/g, "&gt;").replace(/"/g, "&quot;");
  }

  function enabledOf(svc) {
    const v = cfg.services[svc.id];
    if (v === undefined) return svc.def;
    return !!v;
  }

  function render() {
    $("#fe-on").checked = cfg.enabled !== false;
    $("#fe-amp").checked = cfg.amp !== false;

    $("#list").innerHTML = services.map(svc => {
      const chosen = cfg.instance[svc.id] || "";
      const opts = svc.instances.map(i =>
        `<option value="${esc(i)}"${chosen === i ? " selected" : ""}>${esc(i)}</option>`).join("");
      const custom = chosen && svc.instances.indexOf(chosen) === -1;
      return `
        <div class="svc">
          <div class="top">
            <input type="checkbox" data-svc="${svc.id}" ${enabledOf(svc) ? "checked" : ""}>
            <div class="nm">
              <b>${esc(svc.name)}</b>
              <span>remplace par ${esc(svc.target)}</span>
              ${svc.fragile ? '<span class="fragile">instances souvent bloquees a la source : si la page reste vide, changez-en ou decochez</span>' : ""}
            </div>
          </div>
          <select data-inst="${svc.id}">
            ${opts}
            <option value="__custom"${custom ? " selected" : ""}>Instance personnalisee…</option>
          </select>
          <input data-custom="${svc.id}" type="url" placeholder="https://mon-instance.exemple"
                 value="${custom ? esc(chosen) : ""}"
                 style="${custom ? "" : "display:none"}">
        </div>`;
    }).join("");

    document.querySelectorAll("[data-inst]").forEach(sel => {
      sel.onchange = () => {
        const field = document.querySelector(`[data-custom="${sel.dataset.inst}"]`);
        field.style.display = sel.value === "__custom" ? "" : "none";
        if (sel.value === "__custom") field.focus();
      };
    });
  }

  async function load() {
    try {
      const res = await browser.runtime.sendMessage({ type: "feList" });
      if (res) {
        services = res.services || [];
        cfg = Object.assign(cfg, res.cfg || {});
      }
    } catch (e) {
      $("#msg").textContent = "Extension non joignable.";
      return;
    }
    render();
  }

  async function save() {
    cfg.enabled = $("#fe-on").checked;
    cfg.amp = $("#fe-amp").checked;
    cfg.services = {};
    cfg.instance = {};

    document.querySelectorAll("[data-svc]").forEach(cb => {
      cfg.services[cb.dataset.svc] = cb.checked;
    });

    document.querySelectorAll("[data-inst]").forEach(sel => {
      const id = sel.dataset.inst;
      if (sel.value === "__custom") {
        const v = document.querySelector(`[data-custom="${id}"]`).value.trim();
        if (v) cfg.instance[id] = v.replace(/\/+$/, "");
      } else if (sel.value) {
        cfg.instance[id] = sel.value;
      }
    });

    // Un service coche sort de la liste des exceptions
    cfg.except = (cfg.except || []).filter(id => !cfg.services[id]);

    try {
      await browser.storage.local.set({ feCfg: cfg });
      $("#msg").textContent = "Enregistre. Les prochaines navigations en tiennent compte.";
    } catch (e) {
      $("#msg").textContent = "Echec de l'enregistrement.";
    }
  }

  async function reset() {
    cfg = { enabled: true, amp: true, services: {}, instance: {}, except: [] };
    try { await browser.storage.local.set({ feCfg: cfg }); } catch (e) { }
    render();
    $("#msg").textContent = "Reglages par defaut retablis.";
  }

  $("#save").onclick = save;
  $("#reset").onclick = reset;

  load();
})();
